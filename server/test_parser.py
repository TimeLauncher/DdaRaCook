"""
T1-1 DoD · 관대한 JSON 파서 단위 테스트

실행:
    python test_parser.py          # 의존성 없이 그대로 실행됩니다
    pytest test_parser.py          # pytest 가 있으면 이것도 됩니다

계획서가 요구한 5케이스(정상 / 코드블록 / 설명 포함 / 깨진 JSON / 이상한 verdict)에
실전에서 실제로 겪게 되는 함정 몇 개를 더했습니다.

    ❗ 이 테스트의 진짜 목적은 "파싱이 잘 되는가"가 아니라
       **"어떤 쓰레기 입력에도 DONE 이 튀어나오지 않는가"** 입니다.
"""
from __future__ import annotations

import sys

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

from judge.base import coerce_verdict

# (이름, 입력, 기대 verdict, 기대 reasonCode 또는 None=검사 안 함)
CASES: list[tuple[str, str, str, str | None]] = [
    # ── 계획서가 요구한 5케이스 ──────────────────────────
    (
        "1. 정상 JSON",
        '{"verdict": "DONE", "reasonCode": "VISIBLE_CHANGE"}',
        "DONE", "VISIBLE_CHANGE",
    ),
    (
        "2. ```json 코드블록",
        '```json\n{"verdict": "NOT_DONE", "reasonCode": "NO_CHANGE"}\n```',
        "NOT_DONE", "NO_CHANGE",
    ),
    (
        "3. 앞뒤에 설명 문장",
        '사진을 확인한 결과 양파가 아직 흰색입니다.\n'
        '{"verdict": "NOT_DONE", "reasonCode": "NO_CHANGE"}\n'
        '조금 더 볶아주세요.',
        "NOT_DONE", "NO_CHANGE",
    ),
    (
        "4. 깨진 JSON",
        '{"verdict": "DONE", "reasonCode":',
        "CANNOT_TELL", "OTHER",          # ★ DONE 이 보여도 절대 DONE 이면 안 된다
    ),
    (
        "5. 3종 밖의 verdict 값",
        '{"verdict": "MAYBE", "reasonCode": "VISIBLE_CHANGE"}',
        "CANNOT_TELL", "OTHER",
    ),

    # ── 실전 함정 ────────────────────────────────────────
    (
        "6. 템플릿을 그대로 따라 씀",       # 실제로 자주 나온다
        '{"verdict": "DONE | NOT_DONE | CANNOT_TELL", "reasonCode": "VISIBLE_CHANGE"}',
        "CANNOT_TELL", "OTHER",
    ),
    (
        "7. 빈 응답 / None",
        "",
        "CANNOT_TELL", "OTHER",
    ),
    (
        "8. JSON 이 아예 없는 산문",
        "이 단계는 완료된 것 같습니다. done!",
        "CANNOT_TELL", "OTHER",           # ★ 산문 속 'done' 을 주워 먹으면 안 된다
    ),
    (
        "9. 소문자·공백·하이픈 표기",
        '{"verdict": " not-done ", "reasonCode": "no change"}',
        "NOT_DONE", "NO_CHANGE",
    ),
    (
        "10. 키 이름 변형 (reason_code / Verdict)",
        '{"Verdict": "DONE", "reason_code": "VISIBLE_CHANGE"}',
        "DONE", "VISIBLE_CHANGE",
    ),
    (
        "11. 모르는 reasonCode",
        '{"verdict": "DONE", "reasonCode": "ONION_IS_GOLDEN"}',
        "DONE", "OTHER",                  # verdict 는 살리고 reason 만 OTHER
    ),
    (
        "12. 값 안에 중괄호가 든 문자열",
        '{"verdict": "NOT_DONE", "note": "팬 {안} 재료", "reasonCode": "NO_CHANGE"}',
        "NOT_DONE", "NO_CHANGE",
    ),
    (
        "13. 후행 쉼표",
        '{"verdict": "DONE", "reasonCode": "VISIBLE_CHANGE",}',
        "DONE", "VISIBLE_CHANGE",
    ),
    (
        "14. 홑따옴표",
        "{'verdict': 'CANNOT_TELL', 'reasonCode': 'BLURRY'}",
        "CANNOT_TELL", "BLURRY",
    ),
    (
        "15. 감싼 객체 안에 판정이 든 경우",
        '{"result": {"verdict": "DONE", "reasonCode": "VISIBLE_CHANGE"}}',
        "DONE", "VISIBLE_CHANGE",
    ),
    (
        "16. 마크다운 볼드가 섞임",
        '**판정**\n```\n{"verdict": "CANNOT_TELL", "reasonCode": "TARGET_NOT_VISIBLE"}\n```',
        "CANNOT_TELL", "TARGET_NOT_VISIBLE",
    ),
]


def _run_case(name: str, raw: str, want_v: str, want_r: str | None) -> tuple[bool, str]:
    got = coerce_verdict(raw, latency_ms=123)
    ok = got.verdict == want_v and (want_r is None or got.reasonCode == want_r)
    detail = f"{got.verdict}/{got.reasonCode} parsed={got.parsed}"
    return ok, detail


# ── pytest 로도 돌아가도록 함수 하나 노출 ────────────────
def test_parser_cases():
    for name, raw, want_v, want_r in CASES:
        got = coerce_verdict(raw, latency_ms=123)
        assert got.verdict == want_v, f"{name}: {got.verdict} != {want_v}"
        if want_r is not None:
            assert got.reasonCode == want_r, f"{name}: {got.reasonCode} != {want_r}"


def test_never_returns_done_on_garbage():
    """가장 중요한 불변식: 못 읽은 응답에서 DONE 이 나오면 안 된다."""
    garbage = [
        "", "   ", "DONE", "done", "verdict: DONE", "{", "}", "{}",
        "[]", "null", '{"verdict": null}', '{"verdict": ["DONE"]}',
        '{"verdict": {"value": "DONE"}}', "완료되었습니다 DONE",
        '{"verdict": "DONE"', '{"verdict":"DO NE"}',
    ]
    for g in garbage:
        v = coerce_verdict(g)
        assert v.verdict != "DONE", f"쓰레기 입력에서 DONE 이 나옴: {g!r}"


def main() -> int:
    print("=" * 64)
    print("T1-1 · coerce_verdict 단위 테스트")
    print("=" * 64)

    failed = 0
    for name, raw, want_v, want_r in CASES:
        ok, detail = _run_case(name, raw, want_v, want_r)
        mark = "✅" if ok else "❌"
        print(f"  {mark} {name:<34} → {detail}")
        if not ok:
            failed += 1
            print(f"       기대: {want_v}/{want_r}")

    print("\n  ── 불변식: 쓰레기 입력에서 DONE 금지 ──")
    try:
        test_never_returns_done_on_garbage()
        print("  ✅ 통과 (16종 쓰레기 입력)")
    except AssertionError as e:
        failed += 1
        print(f"  ❌ {e}")

    print("=" * 64)
    if failed:
        print(f"❌ {failed}건 실패")
        return 1
    print(f"✅ 전체 {len(CASES)}케이스 + 불변식 통과 — T1-1 DoD 충족")
    return 0


if __name__ == "__main__":
    sys.exit(main())
