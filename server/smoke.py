"""
T1-2 / T1-5 · 판정 경로 확인 도구

두 가지 모드가 있습니다.

  ① 어댑터 직접 호출 (서버 없이) — T1-2 DoD
        python smoke.py --direct

  ② HTTP 왕복 (배포·로컬 서버 대상) — T1-5 DoD
        python smoke.py --url http://127.0.0.1:8000
        python smoke.py --url https://ddaracook-server.onrender.com --repeat 5

②는 **vlmLatencyMs(서버가 잰 모델 시간)와 roundTripMs(내가 잰 왕복)를 함께**
찍습니다. 둘의 차이가 네트워크 구간이며, 명세서 12절 #4(3초)를 못 맞출 때
'모델이 느린 건지 업로드가 느린 건지'를 가르는 유일한 방법입니다.
"""
from __future__ import annotations

import argparse
import base64
import os
import statistics
import sys
import time

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

from dotenv import load_dotenv

load_dotenv()

DEFAULT_START = "probe_images/start.jpg"
DEFAULT_CURRENT = "probe_images/current.jpg"

# 합성 이미지(흰 원 → 호박색 원)에 맞춘 기본 시나리오.
# 실제 요리 사진으로 바꿔 돌릴 때는 --instruction/--condition 을 함께 바꾸세요.
DEFAULT_INSTRUCTION = "양파를 투명해질 때까지 볶으세요"
DEFAULT_CONDITION = "팬 안의 재료가 흰색/불투명에서 반투명하거나 노릇하게 변했는가"


def b64_of(path: str) -> str:
    with open(path, "rb") as f:
        return base64.standard_b64encode(f.read()).decode()


def kb(s: str) -> float:
    return len(s) / 1024


# ══════════════════════════════════════════════════════════
def run_direct(args) -> int:
    """서버를 거치지 않고 어댑터를 직접 호출 (T1-2 DoD)."""
    import prompts
    from judge import JudgeError, get_judge

    try:
        judge = get_judge(args.backend)
    except JudgeError as e:
        print(f"❌ 어댑터 생성 실패: {e}")
        return 1

    print(f"  backend : {judge.name}")
    print(f"  model   : {judge.model}")
    print(f"  prompt  : {prompts.PROMPT_VERSION}")

    start_b64 = None if args.no_start else b64_of(args.start)
    current_b64 = b64_of(args.current)
    print(f"  images  : {'2장' if start_b64 else '1장'} "
          f"(base64 합계 {kb(current_b64) + (kb(start_b64) if start_b64 else 0):.0f}KB)")

    user_text = prompts.build_user_text(
        instruction=args.instruction,
        check_type=args.type,
        check_condition=args.condition,
        elapsed_seconds=args.elapsed,
        has_start=start_b64 is not None,
        step_order=3,
    )
    if args.show_prompt:
        print("\n─ user prompt ─────────────────────────────────")
        print(user_text)
        print("───────────────────────────────────────────────")

    lat = []
    for i in range(args.repeat):
        try:
            v = judge.judge(prompts.SYSTEM_PROMPT, user_text, start_b64, current_b64)
        except JudgeError as e:
            print(f"  [{i+1}] ❌ {type(e).__name__}: {e}")
            continue
        lat.append(v.latencyMs)
        flag = "" if v.parsed else "  ⚠️ 파싱 실패 → CANNOT_TELL 폴백"
        print(f"  [{i+1}] {v.verdict:<12} {v.reasonCode:<20} {v.latencyMs:>6}ms{flag}")
        if args.show_raw or not v.parsed:
            print(f"       raw: {v.raw[:300]}")

    _summary(lat, [])
    return 0 if lat else 1


# ══════════════════════════════════════════════════════════
def run_http(args) -> int:
    """실제 서버에 HTTP 로 요청 (T1-5 DoD)."""
    import requests

    token = args.token or os.getenv("TEAM_TOKEN", "")
    if not token:
        print("❌ TEAM_TOKEN 이 없습니다. --token 또는 .env 로 주세요.")
        return 1

    url = args.url.rstrip("/") + "/judge-step"
    headers = {"Content-Type": "application/json",
               "Authorization": f"Bearer {token}"}
    if args.mock:
        headers["X-Mock-Verdict"] = args.mock

    payload = {
        "requestId": "smoke-0",
        "recipeId": "kimchi-fried-rice",
        "stepOrder": 3,
        "instruction": args.instruction,
        "checkType": args.type,
        "checkCondition": args.condition,
        "elapsedSeconds": args.elapsed,
        "startImage": None if args.no_start else b64_of(args.start),
        "currentImage": b64_of(args.current),
    }
    size_kb = (len(payload["currentImage"])
               + len(payload["startImage"] or "")) / 1024
    print(f"  url     : {url}")
    print(f"  payload : {size_kb:.0f}KB (base64 기준)")
    if args.mock:
        print(f"  mock    : {args.mock}  (AI 호출 없음)")

    vlm_ms, rt_ms = [], []
    for i in range(args.repeat):
        payload["requestId"] = f"smoke-{i+1}"
        t0 = time.monotonic()
        try:
            r = requests.post(url, json=payload, headers=headers,
                              timeout=args.timeout)
        except requests.RequestException as e:
            print(f"  [{i+1}] ❌ 요청 실패: {type(e).__name__}: {str(e)[:160]}")
            continue
        rt = int((time.monotonic() - t0) * 1000)

        if r.status_code != 200:
            body = r.text[:250].replace("\n", " ")
            print(f"  [{i+1}] ❌ HTTP {r.status_code} ({rt}ms) {body}")
            # 🚨 이건 CANNOT_TELL 이 아닙니다. 앱은 네트워크 실패 카운터로
            #    따로 세야 합니다 (CONTRACT.md §5).
            continue

        d = r.json()
        rt_ms.append(rt)
        vlm_ms.append(d.get("vlmLatencyMs", 0))
        net = rt - d.get("vlmLatencyMs", 0)
        print(f"  [{i+1}] {d['verdict']:<12} {d['reasonCode']:<20} "
              f"vlm {d['vlmLatencyMs']:>6}ms / 왕복 {rt:>6}ms / 네트워크 {net:>6}ms "
              f"[{d.get('backend')}·{d.get('promptVersion')}]")

    _summary(vlm_ms, rt_ms)
    return 0 if rt_ms else 1


# ══════════════════════════════════════════════════════════
def _summary(vlm_ms: list[int], rt_ms: list[int]) -> None:
    if not vlm_ms and not rt_ms:
        print("\n  성공한 호출이 없습니다.")
        return
    print("\n  ── 요약 ──")
    if vlm_ms:
        print(f"  모델 시간  중앙 {statistics.median(vlm_ms):.0f}ms / "
              f"최대 {max(vlm_ms)}ms  (n={len(vlm_ms)})")
    if rt_ms:
        med = statistics.median(rt_ms)
        print(f"  왕복 시간  중앙 {med:.0f}ms / 최대 {max(rt_ms)}ms")
        print(f"  네트워크   중앙 {med - statistics.median(vlm_ms):.0f}ms")
        verdict = "✅ 목표 달성" if med <= 3000 else "⚠️ 목표 초과 — 원인 분리 필요"
        print(f"  명세서 12절 #4 (왕복 3초 이내): {verdict}")


def main() -> int:
    p = argparse.ArgumentParser(description="판정 경로 확인 (T1-2 / T1-5)")
    p.add_argument("--direct", action="store_true",
                   help="서버 없이 어댑터를 직접 호출 (T1-2)")
    p.add_argument("--url", default="http://127.0.0.1:8000")
    p.add_argument("--token", default=None)
    p.add_argument("--backend", default=None, help="nemotron | openai")
    p.add_argument("--start", default=DEFAULT_START)
    p.add_argument("--current", default=DEFAULT_CURRENT)
    p.add_argument("--no-start", action="store_true",
                   help="현재 이미지 1장만 전송 (T2-4 비교 실험의 1장 모드)")
    p.add_argument("--type", default="COLOR_CHANGE")
    p.add_argument("--instruction", default=DEFAULT_INSTRUCTION)
    p.add_argument("--condition", default=DEFAULT_CONDITION)
    p.add_argument("--elapsed", type=int, default=260)
    p.add_argument("--repeat", type=int, default=1)
    p.add_argument("--timeout", type=float, default=30.0,
                   help="클라이언트 타임아웃(초). 앱은 8초를 씁니다")
    p.add_argument("--mock", default=None,
                   help="X-Mock-Verdict 헤더 값 (AI 호출 없이 배관만 확인)")
    p.add_argument("--show-prompt", action="store_true")
    p.add_argument("--show-raw", action="store_true")
    args = p.parse_args()

    for path in ([] if args.no_start else [args.start]) + [args.current]:
        if not os.path.exists(path):
            print(f"❌ 이미지가 없습니다: {path}\n"
                  f"   python probe.py 를 한 번 돌리면 합성 이미지가 생성됩니다.")
            return 1

    print("=" * 66)
    print("판정 경로 확인 — " + ("어댑터 직접 호출 (T1-2)" if args.direct
                              else "HTTP 왕복 (T1-5)"))
    print("=" * 66)
    return run_direct(args) if args.direct else run_http(args)


if __name__ == "__main__":
    sys.exit(main())
