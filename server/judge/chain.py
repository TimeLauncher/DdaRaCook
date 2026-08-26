"""
백엔드 체인 — 폴백 + 헤지 요청.

## 왜 필요한가

측정(2026-08-26)에서 NVIDIA NIM 은 두 가지로 고장났습니다.

  ① 즉시거절 — HTTP 500(`EngineCore encountered an issue`)이 **1초 만에** 온다.
     21쌍 중 20쌍이 이렇게 실패한 날이 있었다.
  ② 지연     — 같은 입력에 4.2초 / 35.5초 / 85초. 편차가 20배.

①은 예산 7.5초 중 6.5초가 남으므로 **다른 백엔드로 갈아타면 성공**합니다.
②는 실패 판정이 안 나므로 폴백으로 못 잡습니다. 그래서 일정 시간이 지나면
**백업을 동시에 발사하고 먼저 온 답을 씁니다**(헤지).

정상일 때는 헤지 시점 전에 주 백엔드가 답하므로 백업 호출이 나가지 않습니다.
즉 **무료 티어 할당량은 주 백엔드가 아플 때만 소모**됩니다.

## 설정

    VLM_BACKEND=chain
    JUDGE_CHAIN=nemotron,groq       # 앞이 주, 뒤가 백업(여러 개 가능)
    JUDGE_HEDGE_AFTER_S=4.5         # 이만큼 지나면 백업 동시 발사

기본 4.5초인 이유는 아래 DEFAULT_HEDGE_AFTER_S 주석 참고 — 백업의 무료
한도가 임계값을 결정합니다.
"""
from __future__ import annotations

import os
import threading
import time
from concurrent.futures import FIRST_COMPLETED, Future, ThreadPoolExecutor, wait
from typing import Optional, Sequence

from .base import (
    JudgeConfigError,
    JudgeError,
    JudgeTimeout,
    Verdict,
    VlmJudge,
)

# 헤지 임계값. **백업 무료 한도가 이 값을 결정합니다.**
# Groq 무료 티어는 8,000 TPM = 판정 분당 2.5회가 상한인데(notes/backup-backend.md),
# 2.5초로 두면 NVIDIA 정상 응답(실측 2.6~3.8초)에도 매번 헤지가 나가 할당량을
# 평상시에 다 써버린다. 실측에서 정상 최대가 5.2초, 고장은 11초 이상으로 갈리므로
# 그 사이인 4.5초에 둔다. 즉 "느린 정상"은 그냥 기다리고 "고장"만 헤지한다.
# 백업 한도가 넉넉한 벤더(Gemini 등)로 바꾸면 더 낮춰도 된다.
DEFAULT_HEDGE_AFTER_S = 4.5
DEFAULT_BUDGET_S = 7.5


class ChainJudge:
    """주 백엔드 + 백업들을 폴백·헤지로 묶는다.

    `VlmJudge` 규격을 그대로 만족하므로 server.py 는 한 줄도 바뀌지 않습니다.
    """

    def __init__(
        self,
        members: Sequence[VlmJudge],
        *,
        hedge_after_s: Optional[float] = None,
        budget_s: Optional[float] = None,
    ):
        if not members:
            raise JudgeConfigError("JUDGE_CHAIN 이 비어 있습니다.")
        self.members = list(members)
        self.primary = self.members[0]
        self.backups = self.members[1:]
        self.hedge_after_s = float(
            hedge_after_s
            if hedge_after_s is not None
            else os.getenv("JUDGE_HEDGE_AFTER_S", DEFAULT_HEDGE_AFTER_S)
        )
        self.budget_s = float(
            budget_s if budget_s is not None
            else os.getenv("VLM_TIMEOUT_S", DEFAULT_BUDGET_S)
        )
        self.name = "chain(" + "+".join(m.name for m in self.members) + ")"
        self.model = self.primary.model
        # 스레드 풀은 요청마다 만들지 않고 재사용합니다. 요청마다 만들면
        # 스레드 생성 비용이 헤지로 아낀 시간을 도로 까먹습니다.
        self._pool = ThreadPoolExecutor(
            max_workers=max(2, len(self.members) * 2),
            thread_name_prefix="judge-chain",
        )
        self._lock = threading.Lock()
        self._stats: dict[str, dict[str, int]] = {
            m.name: {"win": 0, "fail": 0, "calls": 0} for m in self.members
        }
        # 헤지가 몇 번 발사됐는지. 요청 수 대비 이 값이 크면 임계값이 낮아
        # 백업 할당량을 낭비하고 있다는 뜻이다.
        self._hedge_count = 0
        self._request_count = 0

    # ──────────────────────────────────────────────────────
    def describe(self) -> dict:
        with self._lock:
            stats = {k: dict(v) for k, v in self._stats.items()}
        return {
            "backend": self.name,
            "requests": self._request_count,
            "hedgeFired": self._hedge_count,
            "primary": self.primary.name,
            "backups": [m.name for m in self.backups],
            "hedgeAfterS": self.hedge_after_s,
            "budgetS": self.budget_s,
            "members": [
                m.describe() if hasattr(m, "describe") else {"backend": m.name}
                for m in self.members
            ],
            "stats": stats,
        }

    def _record(self, name: str, key: str) -> None:
        with self._lock:
            self._stats.setdefault(name, {"win": 0, "fail": 0, "calls": 0})[key] += 1

    # ──────────────────────────────────────────────────────
    def judge(
        self,
        system: str,
        user_text: str,
        start_b64: Optional[str],
        current_b64: str,
    ) -> Verdict:
        started = time.monotonic()
        deadline = started + self.budget_s
        with self._lock:
            self._request_count += 1

        def run(member: VlmJudge) -> Verdict:
            self._record(member.name, "calls")
            v = member.judge(system, user_text, start_b64, current_b64)
            # 어느 백엔드가 답했는지 응답·로그에 남기기 위해 표시해 둔다.
            v.backend = member.name
            return v

        pending: dict[Future, VlmJudge] = {}
        errors: dict[str, BaseException] = {}
        launched: set[str] = set()

        def launch(member: VlmJudge) -> None:
            if member.name in launched:
                return
            launched.add(member.name)
            pending[self._pool.submit(run, member)] = member

        launch(self.primary)
        hedged = False

        try:
            while pending:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break

                # 아직 헤지를 안 했으면 헤지 시점까지만 기다린다.
                if not hedged:
                    until_hedge = (started + self.hedge_after_s) - time.monotonic()
                    timeout = max(0.0, min(remaining, until_hedge))
                else:
                    timeout = remaining

                done, _ = wait(set(pending), timeout=timeout,
                               return_when=FIRST_COMPLETED)

                for future in done:
                    member = pending.pop(future)
                    try:
                        verdict = future.result()
                    except JudgeError as e:
                        errors[member.name] = e
                        self._record(member.name, "fail")
                        # 주 백엔드가 빨리 죽었다 → 예산이 남았으니 즉시 백업.
                        if not hedged:
                            hedged = True
                            with self._lock:
                                self._hedge_count += 1
                        for backup in self.backups:
                            launch(backup)
                        continue
                    except Exception as e:  # noqa: BLE001
                        errors[member.name] = e
                        self._record(member.name, "fail")
                        continue

                    self._record(member.name, "win")
                    return verdict

                if not done and not hedged:
                    # 헤지 시점 도달 — 주 백엔드는 아직 응답이 없다. 동시 발사.
                    hedged = True
                    with self._lock:
                        self._hedge_count += 1
                    for backup in self.backups:
                        launch(backup)
        finally:
            # 남은 호출은 버린다. 취소되지 않은 것은 백그라운드에서 끝나고
            # 결과는 버려지지만, 스레드가 새지 않도록 풀이 회수한다.
            for future in pending:
                future.cancel()

        # 전부 실패했다. 가장 설명력 있는 오류를 고른다.
        if errors:
            primary_error = errors.get(self.primary.name)
            chosen = primary_error or next(iter(errors.values()))
            detail = "; ".join(f"{k}: {str(v)[:120]}" for k, v in errors.items())
            if isinstance(chosen, JudgeError):
                raise type(chosen)(f"모든 백엔드 실패 — {detail}") from chosen
            raise JudgeTimeout(f"모든 백엔드 실패 — {detail}") from chosen
        raise JudgeTimeout(
            f"{self.budget_s:.1f}초 예산 안에 응답한 백엔드가 없습니다 "
            f"(체인: {', '.join(m.name for m in self.members)})")


def build_chain_from_env() -> ChainJudge:
    """`JUDGE_CHAIN=nemotron,groq` 를 읽어 체인을 만든다."""
    from . import get_judge  # 순환 import 회피: 호출 시점에 가져온다

    raw = os.getenv("JUDGE_CHAIN", "nemotron,groq")
    names = [n.strip().lower() for n in raw.split(",") if n.strip()]
    if not names:
        raise JudgeConfigError("JUDGE_CHAIN 이 비어 있습니다.")

    members: list[VlmJudge] = []
    problems: list[str] = []
    for name in names:
        if name == "chain":
            continue  # 자기 자신을 넣는 실수 방지
        try:
            members.append(get_judge(name))
        except JudgeError as e:
            # 백업 키가 아직 없더라도 주 백엔드만으로 뜨는 편이 낫습니다.
            # 서버가 아예 안 뜨면 시연이 통째로 막히기 때문입니다.
            problems.append(f"{name}: {str(e)[:120]}")
    if not members:
        raise JudgeConfigError(
            "체인에 사용할 수 있는 백엔드가 없습니다 — " + "; ".join(problems))
    if problems:
        print(f"[chain] 일부 백엔드를 건너뜁니다 — {'; '.join(problems)}", flush=True)
    return ChainJudge(members)
