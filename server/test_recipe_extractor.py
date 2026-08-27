"""YouTube 레시피 추출 회귀 테스트 (외부 네트워크·모델 호출 없음)."""
import json
import os
from types import SimpleNamespace
from unittest.mock import patch

os.environ.setdefault("TEAM_TOKEN", "recipe-extractor-test-token")
os.environ.setdefault("DEBUG_MODE", "true")
os.environ.setdefault("VLM_BACKEND", "mock")

from fastapi.testclient import TestClient

import server
import recipe_extractor
from recipe_extractor import RecipeExtractionError, TranscriptSource, extract_recipe, parse_youtube_video_id


client = TestClient(server.app)
AUTH = {"Authorization": f"Bearer {server.TEAM_TOKEN}"}


def sample_source() -> TranscriptSource:
    return TranscriptSource(
        video_id="dQw4w9WgXcQ",
        source_url="https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        title="계란 볶음밥",
        language="ko",
        text="[00:01] 계란 두 개를 풀어 주세요.\n[00:20] 팬에서 익혀 주세요.",
    )


def sample_model_json() -> str:
    return json.dumps({
        "recipe": {
            "id": "model-must-not-own-id",
            "title": "계란 볶음밥",
            "ingredients": [{"name": "계란", "amount": "2개"}],
            "steps": [{
                "order": 7,
                "instruction": "계란을 풀어 팬에서 익힌다",
                "checkType": "TIME_ONLY",
                "checkCondition": "모델이 잘못 넣은 조건",
                "needsStartImage": True,
                "inspectionPolicy": {
                    "earliestCheckSeconds": 30,
                    "checkIntervalSeconds": 30,
                    "burstSeconds": 3,
                    "requiredConsecutiveDone": 1,
                    "maxExpectedSeconds": 120,
                },
                "targetIngredients": ["계란"],
                "voicePrompt": "계란을 풀어 팬에서 익혀 주세요.",
                "isAutoCheck": True,
                "parallelTimer": None,
                "waitsForParallelTimer": False,
                "baselineOnStepStart": False,
            }],
            "heroNote": "초안",
            "isMvpReady": True,
        },
        "warnings": ["불 세기는 자막에 없습니다."],
    }, ensure_ascii=False)


def test_parse_supported_youtube_urls():
    video_id = "dQw4w9WgXcQ"
    assert parse_youtube_video_id(video_id) == video_id
    assert parse_youtube_video_id(f"https://youtu.be/{video_id}?si=x") == video_id
    assert parse_youtube_video_id(f"https://www.youtube.com/watch?v={video_id}&t=3") == video_id
    assert parse_youtube_video_id(f"https://youtube.com/shorts/{video_id}") == video_id


def test_model_result_is_normalized_to_app_contract():
    result = extract_recipe(sample_source(), completion=lambda _: sample_model_json())
    recipe = result["recipe"]
    step = recipe["steps"][0]
    assert recipe["id"] == ""
    assert recipe["isMvpReady"] is False
    assert step["order"] == 1
    assert step["isAutoCheck"] is False
    assert step["checkCondition"] is None
    assert step["needsStartImage"] is False


def test_incomplete_auto_check_is_downgraded_to_manual_step():
    payload = json.loads(sample_model_json())
    step = payload["recipe"]["steps"][0]
    step["checkType"] = "COLOR_CHANGE"
    step["checkCondition"] = None
    step["inspectionPolicy"] = None
    step["isAutoCheck"] = True

    result = extract_recipe(
        sample_source(), completion=lambda _: json.dumps(payload, ensure_ascii=False)
    )

    normalized = result["recipe"]["steps"][0]
    assert normalized["checkType"] == "TIME_ONLY"
    assert normalized["isAutoCheck"] is False
    assert normalized["needsStartImage"] is False
    assert normalized["checkCondition"] is None
    assert any("수동 진행 단계" in warning for warning in result["warnings"])


def test_time_only_step_gets_timer_from_its_own_instruction():
    """앱은 `inspectionPolicy.maxExpectedSeconds` 하나로 시간 전용 단계의 타이머를 읽는다.

    실측(`SSbyRvzf1VQ`)에서 "30초 불린다" 단계가 정책 없이 나왔고, 그 결과 앱에서 타이머도
    자동 진행도 없이 진행바만 가득 찬 채 멈춰 있었다.
    """
    payload = json.loads(sample_model_json())
    step = payload["recipe"]["steps"][0]
    step["instruction"] = "냉동 스파게티면을 뜨거운 물에 30초 불린다"
    step["checkType"] = "TIME_ONLY"
    step["isAutoCheck"] = False
    step["inspectionPolicy"] = None

    result = extract_recipe(
        sample_source(), completion=lambda _: json.dumps(payload, ensure_ascii=False)
    )

    policy = result["recipe"]["steps"][0]["inspectionPolicy"]
    assert policy is not None, "시간이 적힌 시간 전용 단계는 타이머를 받아야 한다"
    assert policy["maxExpectedSeconds"] == 30
    assert any("30초로 채웠습니다" in warning for warning in result["warnings"])


def test_downgraded_step_also_gets_timer_from_instruction():
    """강등된 단계도 TIME_ONLY 다. 같은 타이머 보정을 받아야 한다."""
    payload = json.loads(sample_model_json())
    step = payload["recipe"]["steps"][0]
    step["instruction"] = "7~8분간 삶는다"
    step["checkType"] = "COLOR_CHANGE"
    step["checkCondition"] = None
    step["inspectionPolicy"] = None
    step["isAutoCheck"] = True

    result = extract_recipe(
        sample_source(), completion=lambda _: json.dumps(payload, ensure_ascii=False)
    )

    normalized = result["recipe"]["steps"][0]
    assert normalized["checkType"] == "TIME_ONLY"
    # "7~8분"은 범위이므로 큰 쪽만 센다.
    assert normalized["inspectionPolicy"]["maxExpectedSeconds"] == 480


def test_time_only_step_without_any_duration_is_reported_not_invented():
    payload = json.loads(sample_model_json())
    step = payload["recipe"]["steps"][0]
    step["instruction"] = "면을 그릇에 담는다"
    step["checkType"] = "TIME_ONLY"
    step["isAutoCheck"] = False
    step["inspectionPolicy"] = None

    result = extract_recipe(
        sample_source(), completion=lambda _: json.dumps(payload, ensure_ascii=False)
    )

    assert result["recipe"]["steps"][0]["inspectionPolicy"] is None
    assert any("시간이 없어 타이머를 걸지 못했습니다" in w for w in result["warnings"])


def test_missing_ingredient_amount_uses_unknown_amount():
    payload = json.loads(sample_model_json())
    del payload["recipe"]["ingredients"][0]["amount"]

    result = extract_recipe(
        sample_source(), completion=lambda _: json.dumps(payload, ensure_ascii=False)
    )

    assert result["recipe"]["ingredients"][0]["amount"] == "분량 미상"
    assert any("분량 미상" in warning for warning in result["warnings"])


def test_endpoint_requires_auth_before_validation():
    response = client.post("/extract-recipe", json={})
    assert response.status_code == 401


def test_endpoint_returns_recipe_draft():
    source = sample_source()
    result = extract_recipe(source, completion=lambda _: sample_model_json())
    with patch.object(server, "fetch_transcript", return_value=source), patch.object(
        server, "extract_recipe", return_value=result
    ):
        response = client.post(
            "/extract-recipe",
            headers=AUTH,
            json={"url": source.source_url},
        )
    assert response.status_code == 200, response.text
    assert response.json()["recipe"]["steps"][0]["checkType"] == "TIME_ONLY"


def test_model_timeout_is_retried_once_then_succeeds():
    class FakeTimeoutError(Exception):
        pass

    class FakeCompletions:
        calls = 0

        def create(self, **kwargs):
            self.calls += 1
            assert kwargs["timeout"] == 90.0
            assert kwargs["model"] == recipe_extractor.DEFAULT_RECIPE_EXTRACTION_MODEL
            if self.calls == 1:
                raise FakeTimeoutError()
            return SimpleNamespace(
                choices=[SimpleNamespace(message=SimpleNamespace(content=sample_model_json()))]
            )

    completions = FakeCompletions()
    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=completions))
    env = {
        "NVIDIA_API_KEY": "nvapi-test-valid",
        "RECIPE_EXTRACTION_MODEL": "",
        "RECIPE_EXTRACTION_TIMEOUT_S": "90",
        "RECIPE_EXTRACTION_TIMEOUT_RETRIES": "1",
    }
    with patch.dict(os.environ, env), patch.object(
        recipe_extractor, "OpenAI", return_value=fake_client
    ), patch.object(recipe_extractor, "APITimeoutError", FakeTimeoutError):
        result = extract_recipe(sample_source())

    assert completions.calls == 2
    assert result["recipe"]["title"] == "계란 볶음밥"


def test_model_timeout_stops_after_configured_retry():
    class FakeTimeoutError(Exception):
        pass

    class FakeCompletions:
        calls = 0

        def create(self, **_kwargs):
            self.calls += 1
            raise FakeTimeoutError()

    completions = FakeCompletions()
    fake_client = SimpleNamespace(chat=SimpleNamespace(completions=completions))
    env = {
        "NVIDIA_API_KEY": "nvapi-test-valid",
        "RECIPE_EXTRACTION_TIMEOUT_S": "90",
        "RECIPE_EXTRACTION_TIMEOUT_RETRIES": "1",
    }
    with patch.dict(os.environ, env), patch.object(
        recipe_extractor, "OpenAI", return_value=fake_client
    ), patch.object(recipe_extractor, "APITimeoutError", FakeTimeoutError):
        try:
            extract_recipe(sample_source())
        except RecipeExtractionError as error:
            assert error.http_status == 503
        else:
            raise AssertionError("두 번째 시간 초과 뒤에는 RecipeExtractionError가 필요합니다.")

    assert completions.calls == 2


class FakeHostedResponse:
    """youtubetranscript.dev 응답 흉내 (requests.Response 중 쓰는 부분만)."""

    def __init__(self, status_code: int, payload: dict | None = None):
        self.status_code = status_code
        self.ok = 200 <= status_code < 300
        self._payload = payload or {}

    def json(self) -> dict:
        return self._payload


def hosted_success_payload() -> dict:
    return {
        "request_id": "req_test",
        "status": "completed",
        "data": {
            "video_id": "dQw4w9WgXcQ",
            "video_title": "계란 볶음밥 만들기",
            "transcript": {
                "text": "계란 두 개를 풀어 주세요.",
                "language": "ko",
                "source": "auto",
                # 계약상 start/end 는 밀리초다.
                "segments": [
                    {"text": "계란 두 개를 풀어 주세요.", "start": 0, "end": 2500},
                    {"text": "팬에서 익혀 주세요.", "start": 20000, "end": 23000},
                ],
            },
        },
        "credits_used": 1,
    }


def test_hosted_transcript_matches_documented_contract():
    captured = {}

    def fake_post(url, headers=None, json=None, timeout=None):
        captured["url"] = url
        captured["headers"] = headers
        captured["body"] = json
        return FakeHostedResponse(200, hosted_success_payload())

    with patch.object(recipe_extractor.requests, "post", fake_post):
        source = recipe_extractor._hosted_transcript("dQw4w9WgXcQ", "test-key")

    assert captured["url"] == "https://www.youtubetranscript.dev/api/v2/transcribe"
    assert captured["headers"]["Authorization"] == "Bearer test-key"
    # format 은 문자열이 아니라 불리언 플래그 객체다.
    assert captured["body"] == {
        "video": "dQw4w9WgXcQ",
        "language": "ko",
        "format": {"timestamp": True},
    }
    assert source.title == "계란 볶음밥 만들기"
    assert source.language == "ko"
    # 밀리초를 초로 바꿔 [분:초] 로 찍어야 한다.
    assert source.text == "[00:00] 계란 두 개를 풀어 주세요.\n[00:20] 팬에서 익혀 주세요."


def test_hosted_transcript_reports_missing_captions():
    with patch.object(
        recipe_extractor.requests, "post", lambda *a, **k: FakeHostedResponse(404)
    ):
        try:
            recipe_extractor._hosted_transcript("dQw4w9WgXcQ", "test-key")
        except RecipeExtractionError as error:
            assert error.http_status == 422
        else:
            raise AssertionError("자막이 없으면 422가 필요합니다.")


def test_hosted_transcript_rejects_pending_asr_job():
    """202 는 2xx라 ok 검사를 통과한다. '자막 없음'으로 오해되지 않아야 한다."""
    with patch.object(
        recipe_extractor.requests, "post", lambda *a, **k: FakeHostedResponse(202)
    ):
        try:
            recipe_extractor._hosted_transcript("dQw4w9WgXcQ", "test-key")
        except RecipeExtractionError as error:
            assert error.http_status == 422
            assert "음성 변환" in str(error)
        else:
            raise AssertionError("ASR 대기 응답에는 명확한 오류가 필요합니다.")


def test_hosted_key_replaces_blocked_direct_fetch():
    """키가 있으면 YouTube 직접 조회(= Render에서 차단되는 경로)를 아예 타지 않아야 한다."""

    def blocked_direct_fetch(*_args, **_kwargs):
        raise AssertionError("키가 설정되면 직접 조회를 호출하면 안 됩니다.")

    with patch.dict(os.environ, {"YOUTUBE_TRANSCRIPT_API_KEY": "test-key"}), patch.object(
        recipe_extractor, "YouTubeTranscriptApi", blocked_direct_fetch
    ), patch.object(
        recipe_extractor.requests,
        "post",
        lambda *a, **k: FakeHostedResponse(200, hosted_success_payload()),
    ):
        source = recipe_extractor.fetch_transcript(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        )

    assert source.video_id == "dQw4w9WgXcQ"
    assert source.language == "ko"


def test_instruction_seconds_reads_korean_durations():
    seconds = recipe_extractor.instruction_seconds
    assert seconds("약불로 10분간 끓인 뒤 5분간 뜸을 들인다") == 900
    assert seconds("30초만 불려 준다") == 30
    # 범위는 큰 쪽만 센다. 7과 8을 각각 더하면 15분이 되어 버린다.
    assert seconds("7~8분간 삶는다") == 480
    assert seconds("먹기 좋게 썬다") == 0


def test_consistency_check_reports_ingredient_mismatch():
    recipe = {
        "ingredients": [
            {"name": "다진 마늘", "amount": "1큰술"},
            {"name": "다진마늘", "amount": "1큰술"},
            {"name": "소금", "amount": "약간"},
        ],
        "steps": [{
            "instruction": "밥과 다진마늘을 넣고 볶는다",
            "targetIngredients": ["다진 마늘", "밥"],
            "inspectionPolicy": None,
        }],
    }

    warnings = recipe_extractor.check_recipe_consistency(recipe)
    joined = " ".join(warnings)

    # 공백만 다른 이름은 같은 재료로 본다.
    assert "중복" in joined and "다진마늘" in joined
    # 단계에만 있고 재료 목록에 없다.
    assert "재료 목록에 없는" in joined and "밥" in joined
    # 재료 목록에만 있고 어느 단계도 쓰지 않는다.
    assert "쓰지 않는" in joined and "소금" in joined


def test_consistency_check_flags_too_short_expected_time():
    recipe = {
        "ingredients": [{"name": "물", "amount": "1컵"}],
        "steps": [{
            "instruction": "약불로 10분간 끓인다",
            "targetIngredients": ["물"],
            "inspectionPolicy": {
                "earliestCheckSeconds": 30,
                "checkIntervalSeconds": 30,
                "burstSeconds": 2,
                "requiredConsecutiveDone": 1,
                "maxExpectedSeconds": 300,
            },
        }],
    }

    warnings = recipe_extractor.check_recipe_consistency(recipe)

    assert any("1단계" in w and "600초" in w for w in warnings), warnings


def test_consistency_check_allows_generous_expected_time():
    """여유를 크게 잡은 것은 문제가 아니다. 짧게 잡힌 경우만 짚는다."""
    recipe = {
        "ingredients": [{"name": "물", "amount": "1컵"}],
        "steps": [{
            "instruction": "약불로 10분간 끓인다",
            "targetIngredients": ["물"],
            "inspectionPolicy": {
                "earliestCheckSeconds": 30,
                "checkIntervalSeconds": 30,
                "burstSeconds": 2,
                "requiredConsecutiveDone": 1,
                "maxExpectedSeconds": 900,
            },
        }],
    }

    assert recipe_extractor.check_recipe_consistency(recipe) == []


def main() -> int:
    tests = [
        test_parse_supported_youtube_urls,
        test_model_result_is_normalized_to_app_contract,
        test_incomplete_auto_check_is_downgraded_to_manual_step,
        test_missing_ingredient_amount_uses_unknown_amount,
        test_endpoint_requires_auth_before_validation,
        test_endpoint_returns_recipe_draft,
        test_model_timeout_is_retried_once_then_succeeds,
        test_model_timeout_stops_after_configured_retry,
        test_hosted_transcript_matches_documented_contract,
        test_hosted_transcript_reports_missing_captions,
        test_hosted_transcript_rejects_pending_asr_job,
        test_hosted_key_replaces_blocked_direct_fetch,
        test_instruction_seconds_reads_korean_durations,
        test_consistency_check_reports_ingredient_mismatch,
        test_consistency_check_flags_too_short_expected_time,
        test_consistency_check_allows_generous_expected_time,
    ]
    for test in tests:
        test()
    print(f"recipe_extractor_tests_passed={len(tests)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
