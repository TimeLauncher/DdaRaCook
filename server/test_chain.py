"""체인(폴백 + 헤지) 동작 검증.

실제 API 는 부르지 않습니다. 가짜 백엔드로 "느림 / 즉시실패 / 정상" 세 가지
상황을 만들고, 체인이 앱 예산 안에서 옳은 선택을 하는지만 봅니다.
"""
from __future__ import annotations

import sys
import threading
import time

from judge.base import JudgeTimeout, JudgeUpstreamError, Verdict
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


def main() -> int:
    cases = [
        ("정상일 때 백업을 부르지 않음", test_fast_primary_never_calls_backup),
        ("주 백엔드 즉시 실패 → 백업 폴백", test_primary_fast_failure_falls_back),
        ("주 백엔드 지연 → 헤지 발사", test_slow_primary_triggers_hedge),
        ("전부 실패 → 예외 (CANNOT_TELL 금지)", test_all_backends_fail_raises),
        ("예산 초과 → JudgeTimeout", test_budget_exhausted_raises_timeout),
        ("통계 집계", test_stats_track_wins_and_failures),
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
