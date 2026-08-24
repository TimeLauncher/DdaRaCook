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
    ]
    for test in tests:
        test()
    print(f"recipe_extractor_tests_passed={len(tests)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
