"""CONTRACT.md의 인증·검증·mock 오류 응답 회귀 테스트."""
import base64
import io
import os
from types import SimpleNamespace

os.environ.setdefault("TEAM_TOKEN", "contract-test-token")
os.environ.setdefault("DEBUG_MODE", "true")
os.environ.setdefault("VLM_BACKEND", "mock")

from fastapi.testclient import TestClient
from PIL import Image

import server
from judge.mock import MockJudge


client = TestClient(server.app)
# 토큰을 문자열로 박아두면 안 된다. 같은 프로세스에서 먼저 돈 테스트가
# .env 를 읽어버리면 (judge/__init__.py·eval.py·test_api.py 모두 load_dotenv 를
# 부른다) 위 setdefault 가 무시되고 실제 토큰이 남아 인증이 401 로 어긋난다.
# test_api.py 와 같이 서버가 실제로 쓰는 값에서 만든다.
AUTH = {"Authorization": f"Bearer {server.TEAM_TOKEN}"}
VALID_BODY = {
    "requestId": "request-1",
    "recipeId": "recipe-1",
    "stepOrder": 1,
    "instruction": "재료를 넣는다",
    "checkType": "PRESENCE",
    "checkCondition": "재료가 보이는가",
    "elapsedSeconds": 10,
    "currentImage": "AAAA",
}


def test_schema_error_is_400_with_string_detail():
    response = client.post("/judge-step", headers=AUTH, json={"requestId": "missing-fields"})
    assert response.status_code == 400
    assert isinstance(response.json()["detail"], str)


def test_unauthenticated_schema_error_does_not_leak_schema():
    response = client.post("/judge-step", json={})
    assert response.status_code == 401
    assert isinstance(response.json()["detail"], str)


def test_mock_503_remains_server_failure_not_verdict():
    response = client.post(
        "/judge-step",
        headers={**AUTH, "X-Mock-Status": "503"},
        json=VALID_BODY,
    )
    assert response.status_code == 503
    assert "verdict" not in response.json()


def test_unknown_mock_status_is_400():
    response = client.post(
        "/judge-step",
        headers={**AUTH, "X-Mock-Status": "418"},
        json=VALID_BODY,
    )
    assert response.status_code == 400


def test_crop_target_is_optional_for_old_clients():
    response = client.post(
        "/judge-step",
        headers={**AUTH, "X-Mock-Verdict": "DONE"},
        json=VALID_BODY,
    )
    assert response.status_code == 200


def test_valid_crop_target_is_accepted():
    response = client.post(
        "/judge-step",
        headers={**AUTH, "X-Mock-Verdict": "DONE"},
        json={**VALID_BODY, "cropTarget": "PAN_COOKING_ROI"},
    )
    assert response.status_code == 200


def test_auto_crop_target_is_accepted():
    response = client.post(
        "/judge-step",
        headers={**AUTH, "X-Mock-Verdict": "DONE"},
        json={**VALID_BODY, "cropTarget": "AUTO_ROI"},
    )
    assert response.status_code == 200


def test_invalid_crop_target_is_400():
    response = client.post(
        "/judge-step",
        headers={**AUTH, "X-Mock-Verdict": "DONE"},
        json={**VALID_BODY, "cropTarget": "COUNTERTOP"},
    )
    assert response.status_code == 400


def test_real_route_forwards_crop_target_to_cropper():
    output = io.BytesIO()
    Image.new("RGB", (16, 16), "white").save(output, "JPEG")
    image_b64 = base64.b64encode(output.getvalue()).decode("ascii")
    seen_targets = []

    def fake_prepare(value, target):
        seen_targets.append(target)
        return value, SimpleNamespace(mode="TEST_CROP", detection_count=2)

    old_prepare = server.prepare_judge_image
    old_get_judge = server.get_judge
    try:
        server.prepare_judge_image = fake_prepare
        server.get_judge = lambda: MockJudge(script="DONE")
        response = client.post(
            "/judge-step",
            headers=AUTH,
            json={
                **VALID_BODY,
                "currentImage": image_b64,
                "cropTarget": "CUTTING_BOARD_ROI",
            },
        )
    finally:
        server.prepare_judge_image = old_prepare
        server.get_judge = old_get_judge

    assert response.status_code == 200
    assert seen_targets == ["CUTTING_BOARD_ROI"]
    timing = response.json()["timing"]
    assert timing["currentCropMode"] == "TEST_CROP"
    assert timing["currentDetectionCount"] == 2
    assert timing["serverHandlerMs"] >= timing["cropTotalMs"]


def test_crop_preview_returns_exact_server_crop_without_vlm():
    output = io.BytesIO()
    Image.new("RGB", (100, 200), "white").save(output, "JPEG")
    image_b64 = base64.b64encode(output.getvalue()).decode("ascii")

    response = client.post(
        "/debug/crop-preview",
        headers=AUTH,
        json={"image": image_b64, "cropTarget": "LEGACY_BOTTOM_60"},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["cropMode"] == "LEGACY_BOTTOM_60"
    assert body["cropTarget"] == "LEGACY_BOTTOM_60"
    assert body["width"] == 100
    assert body["height"] == 120
    assert body["timing"]["serverHandlerMs"] >= body["timing"]["cropMs"]


def test_crop_preview_requires_authentication():
    response = client.post(
        "/debug/crop-preview",
        json={"image": "AAAA", "cropTarget": "PAN_COOKING_ROI"},
    )
    assert response.status_code == 401


def main() -> int:
    tests = [
        test_schema_error_is_400_with_string_detail,
        test_unauthenticated_schema_error_does_not_leak_schema,
        test_mock_503_remains_server_failure_not_verdict,
        test_unknown_mock_status_is_400,
        test_crop_target_is_optional_for_old_clients,
        test_valid_crop_target_is_accepted,
        test_auto_crop_target_is_accepted,
        test_invalid_crop_target_is_400,
        test_real_route_forwards_crop_target_to_cropper,
        test_crop_preview_returns_exact_server_crop_without_vlm,
        test_crop_preview_requires_authentication,
    ]
    for test in tests:
        test()
    print(f"contract_tests_passed={len(tests)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
