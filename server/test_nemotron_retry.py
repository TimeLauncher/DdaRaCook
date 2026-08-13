"""Nemotron 429 백오프 단위 테스트 (API 호출 없음).

실행:
    cd server
    python test_nemotron_retry.py
"""
from types import SimpleNamespace
import sys

import judge.nemotron as nemotron
from judge.base import JudgeRateLimit


class FakeRateLimitError(Exception):
    def __init__(self, retry_after=None):
        self.response = SimpleNamespace(headers={
            "retry-after": retry_after,
        } if retry_after is not None else {})
        super().__init__("too many requests")


def successful_response():
    return SimpleNamespace(choices=[SimpleNamespace(
        message=SimpleNamespace(
            content='{"verdict":"DONE","reasonCode":"VISIBLE_CHANGE"}',
        ),
    )])


class ScriptedCompletions:
    def __init__(self, outcomes):
        self.outcomes = list(outcomes)
        self.calls = 0

    def create(self, **_kwargs):
        self.calls += 1
        outcome = self.outcomes.pop(0)
        if isinstance(outcome, Exception):
            raise outcome
        return outcome


def run_with(outcomes, *, timeout_s=7.5):
    judge = nemotron.NemotronJudge(api_key="test-key", timeout_s=timeout_s)
    completions = ScriptedCompletions(outcomes)
    judge._client = SimpleNamespace(
        chat=SimpleNamespace(completions=completions),
    )
    return judge, completions


def test_retry_after_is_honored_once() -> None:
    judge, completions = run_with([FakeRateLimitError("0.4"), successful_response()])
    sleeps = []
    original_error, original_sleep = nemotron.RateLimitError, nemotron.time.sleep
    try:
        nemotron.RateLimitError = FakeRateLimitError
        nemotron.time.sleep = sleeps.append
        verdict = judge.judge("system", "user", None, "current")
    finally:
        nemotron.RateLimitError, nemotron.time.sleep = original_error, original_sleep

    assert verdict.verdict == "DONE"
    assert completions.calls == 2
    assert sleeps == [0.4]


def test_second_429_is_returned_to_app() -> None:
    judge, completions = run_with([FakeRateLimitError("0.1"), FakeRateLimitError("0.1")])
    sleeps = []
    original_error, original_sleep = nemotron.RateLimitError, nemotron.time.sleep
    try:
        nemotron.RateLimitError = FakeRateLimitError
        nemotron.time.sleep = sleeps.append
        try:
            judge.judge("system", "user", None, "current")
        except JudgeRateLimit:
            pass
        else:
            raise AssertionError("두 번째 429는 JudgeRateLimit 이어야 합니다")
    finally:
        nemotron.RateLimitError, nemotron.time.sleep = original_error, original_sleep

    assert completions.calls == 2
    assert sleeps == [0.1]


def test_long_retry_after_does_not_spend_app_timeout() -> None:
    judge, completions = run_with([FakeRateLimitError("10")], timeout_s=2.0)
    sleeps = []
    original_error, original_sleep = nemotron.RateLimitError, nemotron.time.sleep
    try:
        nemotron.RateLimitError = FakeRateLimitError
        nemotron.time.sleep = sleeps.append
        try:
            judge.judge("system", "user", None, "current")
        except JudgeRateLimit:
            pass
        else:
            raise AssertionError("예산 밖 Retry-After는 즉시 JudgeRateLimit 이어야 합니다")
    finally:
        nemotron.RateLimitError, nemotron.time.sleep = original_error, original_sleep

    assert completions.calls == 1
    assert sleeps == []


def test_missing_or_invalid_retry_after_uses_default() -> None:
    assert nemotron._retry_after_seconds(FakeRateLimitError()) == nemotron.RETRY_SLEEP_S
    assert nemotron._retry_after_seconds(FakeRateLimitError("not-a-delay")) == nemotron.RETRY_SLEEP_S


def main() -> int:
    cases = [
        ("Retry-After만큼 대기 후 1회 재시도", test_retry_after_is_honored_once),
        ("두 번째 429는 앱에 반환", test_second_429_is_returned_to_app),
        ("긴 Retry-After는 앱 타임아웃을 쓰지 않음", test_long_retry_after_does_not_spend_app_timeout),
        ("없는/잘못된 Retry-After는 기본값 사용", test_missing_or_invalid_retry_after_uses_default),
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
