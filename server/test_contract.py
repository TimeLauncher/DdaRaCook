"""CONTRACT.md의 인증·검증·mock 오류 응답 회귀 테스트."""
import os

os.environ.setdefault("TEAM_TOKEN", "contract-test-token")
os.environ.setdefault("DEBUG_MODE", "true")
os.environ.setdefault("VLM_BACKEND", "mock")

from fastapi.testclient import TestClient

import server


client = TestClient(server.app)
AUTH = {"Authorization": "Bearer contract-test-token"}
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


def main() -> int:
    tests = [
        test_schema_error_is_400_with_string_detail,
        test_unauthenticated_schema_error_does_not_leak_schema,
        test_mock_503_remains_server_failure_not_verdict,
        test_unknown_mock_status_is_400,
    ]
    for test in tests:
        test()
    print(f"contract_tests_passed={len(tests)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
