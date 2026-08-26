"""체인(폴백 + 헤지) 동작 검증.

실제 API 는 부르지 않습니다. 가짜 백엔드로 "느림 / 즉시실패 / 정상" 세 가지
상황을 만들고, 체인이 앱 예산 안에서 옳은 선택을 하는지만 봅니다.
"""
from __future__ import annotations

import sys
import threading
import time

# Windows 콘솔은 기본이 cp949 라 실패 메시지의 유니코드에서 죽는다.
# 테스트가 실패했을 때 원인 대신 UnicodeEncodeError 를 보게 되면 최악이다.
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

from judge.base import (
    JudgeRateLimit,
    JudgeTimeout,
    JudgeUpstreamError,
    Verdict,
)
from judge.chain import ChainJudge


class FakeJudge:
    """지연·실패를 마음대로 만드는 가짜 백엔드."""

    def __init__(self, name, *, delay=0.0, error=None, verdict="DONE"):
        self.name = name
        self.model = f"fake/{name}"
        self.delay = delay
        self.error = error
        self._verdict = verdict
        self.calls = 0
        self._lock = threading.Lock()

    def judge(self, system, user_text, start_b64, current_b64) -> Verdict:
        with self._lock:
            self.calls += 1
        if self.delay:
            time.sleep(self.delay)
        if self.error:
            raise self.error
        return Verdict(self._verdict, "VISIBLE_CHANGE", int(self.delay * 1000))


def run(chain: ChainJudge) -> Verdict:
    return chain.judge("sys", "user", None, "current-b64")


def test_fast_primary_never_calls_backup() -> None:
    """정상일 때는 백업을 안 부른다 — 무료 티어 할당량을 아껴야 한다."""
    primary = FakeJudge("primary", delay=0.05)
    backup = FakeJudge("backup", delay=0.05)
    chain = ChainJudge([primary, backup], hedge_after_s=1.0, budget_s=5.0)

    v = run(chain)

    assert v.verdict == "DONE", v.verdict
    assert v.backend == "primary", v.backend
    assert primary.calls == 1
    assert backup.calls == 0, f"백업이 불필요하게 호출됨 ({backup.calls}회)"


def test_primary_fast_failure_falls_back() -> None:
    """500이 1초 만에 오는 실측 상황 — 남은 예산으로 백업이 성공해야 한다."""
    primary = FakeJudge("primary", delay=0.05,
                        error=JudgeUpstreamError("EngineCore encountered an issue"))
    backup = FakeJudge("backup", delay=0.05, verdict="NOT_DONE")
    chain = ChainJudge([primary, backup], hedge_after_s=2.0, budget_s=5.0)

    v = run(chain)

    assert v.verdict == "NOT_DONE", v.verdict
    assert v.backend == "backup", v.backend
    assert backup.calls == 1


def test_slow_primary_triggers_hedge() -> None:
    """35초씩 걸리는 지연형 — 헤지 시점에 백업을 띄워 먼저 온 답을 쓴다."""
    primary = FakeJudge("primary", delay=3.0)
    backup = FakeJudge("backup", delay=0.1, verdict="CANNOT_TELL")
    chain = ChainJudge([primary, backup], hedge_after_s=0.2, budget_s=5.0)

    started = time.monotonic()
    v = run(chain)
    elapsed = time.monotonic() - started

    assert v.backend == "backup", v.backend
    assert backup.calls == 1
    assert elapsed < 1.5, f"헤지가 늦게 걸렸습니다 ({elapsed:.2f}초)"


def test_all_backends_fail_raises() -> None:
    """전부 실패하면 Verdict 로 바꾸지 않고 오류를 올린다 (CONTRACT §5)."""
    primary = FakeJudge("primary", delay=0.02, error=JudgeUpstreamError("500"))
    backup = FakeJudge("backup", delay=0.02, error=JudgeUpstreamError("429"))
    chain = ChainJudge([primary, backup], hedge_after_s=0.5, budget_s=3.0)

    try:
        run(chain)
    except JudgeUpstreamError as e:
        assert "primary" in str(e) and "backup" in str(e), str(e)
    else:
        raise AssertionError("모든 백엔드 실패는 예외여야 합니다 (CANNOT_TELL 금지)")


def test_budget_exhausted_raises_timeout() -> None:
    """예산을 넘기면 늦더라도 기다리지 않는다."""
    primary = FakeJudge("primary", delay=5.0)
    backup = FakeJudge("backup", delay=5.0)
    chain = ChainJudge([primary, backup], hedge_after_s=0.2, budget_s=0.6)

    started = time.monotonic()
    try:
        run(chain)
    except JudgeTimeout:
        pass
    else:
        raise AssertionError("예산 초과는 JudgeTimeout 이어야 합니다")
    elapsed = time.monotonic() - started
    assert elapsed < 2.0, f"예산({0.6}초)을 넘겨 기다렸습니다 ({elapsed:.2f}초)"


def test_stats_track_wins_and_failures() -> None:
    """/health 에 실을 통계가 쌓이는지."""
    primary = FakeJudge("primary", delay=0.02, error=JudgeUpstreamError("500"))
    backup = FakeJudge("backup", delay=0.02)
    chain = ChainJudge([primary, backup], hedge_after_s=0.5, budget_s=3.0)

    run(chain)

    described = chain.describe()
    assert described["stats"]["primary"]["fail"] == 1, described["stats"]
    assert described["stats"]["backup"]["win"] == 1, described["stats"]
    assert described["primary"] == "primary"
    assert described["backups"] == ["backup"]



def test_slow_primary_with_failing_backup_reports_timeout_not_backup_error() -> None:
    """실측 회귀 — 주 백엔드가 아직 응답 중인데 백업이 429 를 내면?

    예산이 끝났을 때 백업의 429 를 그대로 올리면 앱은 "레이트 리밋이니 백오프"로
    읽는다. 실제로는 주 백엔드가 시간 안에 못 끝낸 것이므로 타임아웃(503)이어야
    한다. CONTRACT §5 의 오류 구분이 여기서 깨진다.
    (2026-08-26 배포본에서 gemini fail=0 인데 groq 429 가 앱에 나갔다)
    """
    primary = FakeJudge("primary", delay=5.0)          # 예산을 넘겨 계속 대기
    backup = FakeJudge("backup", delay=0.02,
                       error=JudgeRateLimit("Rate limit reached"))
    chain = ChainJudge([primary, backup], hedge_after_s=0.2, budget_s=1.0)

    try:
        run(chain)
    except JudgeTimeout as e:
        assert "primary" in str(e), f"대기 중이던 주 백엔드를 알려야 합니다: {e}"
    except JudgeRateLimit as e:
        raise AssertionError(
            f"백업의 429 가 앱에 그대로 나갔습니다 — 타임아웃이어야 합니다: {e}")
    else:
        raise AssertionError("예산 초과는 예외여야 합니다")

    described = chain.describe()
    assert described["stats"]["primary"]["fail"] == 0, "주 백엔드는 실패한 적이 없다"


def test_hedge_launches_one_backup_at_a_time() -> None:
    """헤지가 백업을 한꺼번에 다 띄우면 안 된다.

    Render 무료 티어는 0.1 CPU 다. 271KB base64 를 실은 동시 호출이 늘면
    서로 CPU 를 뺏어 주 백엔드까지 느려진다.
    """
    primary = FakeJudge("primary", delay=5.0)
    backup1 = FakeJudge("backup1", delay=5.0)
    backup2 = FakeJudge("backup2", delay=5.0)
    chain = ChainJudge([primary, backup1, backup2],
                       hedge_after_s=0.2, budget_s=0.6)

    try:
        run(chain)
    except JudgeTimeout:
        pass

    assert backup1.calls == 1, f"첫 백업은 떠야 합니다 ({backup1.calls})"
    assert backup2.calls == 0, (
        f"두 번째 백업까지 한꺼번에 뜨면 안 됩니다 ({backup2.calls})")


def test_failing_backup_escalates_to_next_backup() -> None:
    """백업이 실패하면 그 다음 백업으로 넘어가야 한다."""
    primary = FakeJudge("primary", delay=0.02, error=JudgeUpstreamError("500"))
    backup1 = FakeJudge("backup1", delay=0.02, error=JudgeRateLimit("429"))
    backup2 = FakeJudge("backup2", delay=0.02, verdict="NOT_DONE")
    chain = ChainJudge([primary, backup1, backup2],
                       hedge_after_s=1.0, budget_s=3.0)

    v = run(chain)

    assert v.verdict == "NOT_DONE", v.verdict
    assert v.backend == "backup2", v.backend
    assert backup1.calls == 1 and backup2.calls == 1


def main() -> int:
    cases = [
        ("정상일 때 백업을 부르지 않음", test_fast_primary_never_calls_backup),
        ("주 백엔드 즉시 실패 → 백업 폴백", test_primary_fast_failure_falls_back),
        ("주 백엔드 지연 → 헤지 발사", test_slow_primary_triggers_hedge),
        ("전부 실패 → 예외 (CANNOT_TELL 금지)", test_all_backends_fail_raises),
        ("예산 초과 → JudgeTimeout", test_budget_exhausted_raises_timeout),
        ("통계 집계", test_stats_track_wins_and_failures),
        ("느린 주 + 429 백업 → 타임아웃(백업 오류 아님)",
         test_slow_primary_with_failing_backup_reports_timeout_not_backup_error),
        ("헤지는 백업을 하나씩만 띄움", test_hedge_launches_one_backup_at_a_time),
        ("백업 실패 시 다음 백업으로 승계", test_failing_backup_escalates_to_next_backup),
    ]
    failed = 0
    for name, test in cases:
        try:
            test()
            print(f"[OK] {name}")
        except AssertionError as exc:
            failed += 1
            print(f"[FAIL] {name}: {exc}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
