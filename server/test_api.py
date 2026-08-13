"""`/judge-step` 계약 오류 응답 테스트.

실행:
    cd server
    python test_api.py
"""
import sys

from fastapi.testclient import TestClient

from server import TEAM_TOKEN, app


client = TestClient(app)
AUTH = {"Authorization": f"Bearer {TEAM_TOKEN}"}


def assert_error(response, status: int) -> None:
    assert response.status_code == status, response.text
    body = response.json()
    assert isinstance(body.get("detail"), str), body


def test_missing_auth_wins_over_invalid_json() -> None:
    response = client.post("/judge-step", content=b"{")
    assert_error(response, 401)


def test_invalid_body_is_contract_400() -> None:
    response = client.post("/judge-step", headers=AUTH, json={"requestId": "only-one-field"})
    assert_error(response, 400)


def main() -> int:
    cases = [
        ("인증 없는 깨진 JSON은 401", test_missing_auth_wins_over_invalid_json),
        ("인증된 형식 오류는 문자열 detail의 400", test_invalid_body_is_contract_400),
    ]
    failed = 0
    for name, test in cases:
        try:
            test()
            print(f"✅ {name}")
        except AssertionError as exc:
            failed += 1
            print(f"❌ {name}: {exc}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
