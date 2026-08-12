"""
T0-2 · 다중 이미지 지원 확인 (게이트 태스크)

실행:
    python probe.py

이 스크립트가 확인하는 것:
  1. 인증이 되는가            (텍스트 한 줄)
  2. 이미지를 볼 수 있는가     (1장)
  3. 이미지 2장을 동시에 받는가 (★ 게이트)
  4. 실제 판정 프롬프트가 동작하는가
  5. 응답 시간이 3초 이내인가

결과에 따라 프로젝트 계획이 갈립니다. 다른 코드보다 먼저 돌리세요.
"""
import base64
import io
import os
import sys
import time

# Windows 콘솔(cp949)에서 이모지 출력이 깨지지 않도록
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

from dotenv import load_dotenv
from openai import OpenAI
from PIL import Image, ImageDraw

load_dotenv()

BASE_URL = os.getenv("NVIDIA_BASE_URL", "https://integrate.api.nvidia.com/v1")
API_KEY = os.getenv("NVIDIA_API_KEY", "")
MODEL = os.getenv("NVIDIA_MODEL", "")

OUT_DIR = "probe_images"


# ──────────────────────────────────────────────────────────
# 테스트용 이미지 생성
#   실제 요리 사진 없이도 검증할 수 있게 합성 이미지를 만든다.
#   - 가운데 원   : 흰색 → 호박색  (양파가 투명해지는 상황 흉내)
#   - 좌상단 사각형: 파랑 → 빨강    (두 장을 다 봤는지 판별하는 결정적 표식)
# ──────────────────────────────────────────────────────────
def make_image(circle_rgb, marker_rgb, path):
    img = Image.new("RGB", (512, 512), (60, 58, 55))          # 팬 바닥
    d = ImageDraw.Draw(img)
    d.ellipse([120, 120, 392, 392], fill=circle_rgb)          # 재료
    d.rectangle([20, 20, 110, 110], fill=marker_rgb)          # 판별용 표식
    img.save(path, "JPEG", quality=80)
    return path


def generate_images():
    os.makedirs(OUT_DIR, exist_ok=True)
    a = make_image((240, 237, 229), (30, 95, 204), f"{OUT_DIR}/start.jpg")    # 흰 원 + 파란 표식
    b = make_image((217, 160, 91), (204, 32, 32), f"{OUT_DIR}/current.jpg")   # 호박 원 + 빨간 표식
    return a, b


def b64(path):
    with open(path, "rb") as f:
        return base64.standard_b64encode(f.read()).decode()


def image_block(path):
    """OpenAI 호환 이미지 블록. Anthropic과 포장지만 다르다."""
    return {"type": "image_url",
            "image_url": {"url": f"data:image/jpeg;base64,{b64(path)}"}}


# ──────────────────────────────────────────────────────────
def ask(client, content, label, max_tokens=300):
    print(f"\n{'─' * 60}\n▶ {label}")
    t0 = time.monotonic()
    try:
        resp = client.chat.completions.create(
            model=MODEL,
            max_tokens=max_tokens,
            messages=[{"role": "user", "content": content}],
        )
        ms = int((time.monotonic() - t0) * 1000)
        text = (resp.choices[0].message.content or "").strip()
        print(f"  응답 ({ms}ms):\n  {text}")
        return text, ms
    except Exception as e:
        ms = int((time.monotonic() - t0) * 1000)
        print(f"  ❌ 실패 ({ms}ms): {type(e).__name__}")
        print(f"     {str(e)[:400]}")
        return None, ms


def contains_any(text, words):
    low = text.lower()
    return any(w.lower() in low for w in words)


# ──────────────────────────────────────────────────────────
def main():
    print("=" * 60)
    print("T0-2 · 다중 이미지 지원 확인")
    print("=" * 60)

    if not API_KEY or API_KEY.startswith("nvapi-xxxx"):
        sys.exit("\n❌ NVIDIA_API_KEY 가 없습니다.\n"
                 "   .env.example 을 .env 로 복사하고 T0-1에서 발급한 키를 넣으세요.")
    if not MODEL:
        sys.exit("\n❌ NVIDIA_MODEL 이 비어 있습니다.\n"
                 "   T0-1에서 조사한 '이미지 입력 가능' 모델 ID를 .env 에 넣으세요.\n"
                 "   (notes/nemotron.md 3번 항목)")

    print(f"  Base URL : {BASE_URL}")
    print(f"  Model    : {MODEL}")

    client = OpenAI(base_url=BASE_URL, api_key=API_KEY)
    start, current = generate_images()
    print(f"  테스트 이미지 생성: {start}, {current}")

    results = {}
    timings = []

    # ── 1. 인증 ──
    text, ms = ask(client, "'준비 완료'라고만 답하세요.", "1. 인증 확인 (텍스트)", 20)
    results["auth"] = text is not None
    if not results["auth"]:
        print("\n❌ 인증 실패. 키 또는 Base URL을 확인하세요. 이후 테스트를 중단합니다.")
        sys.exit(1)

    # ── 2. 이미지 1장 ──
    text, ms = ask(client, [
        {"type": "text", "text": "이 이미지 좌측 상단 사각형은 무슨 색입니까? 색만 한 단어로 답하세요."},
        image_block(start),
    ], "2. 이미지 1장 인식")
    results["vision"] = bool(text) and contains_any(text, ["파랑", "파란", "blue", "청"])
    timings.append(ms)

    # ── 3. ★ 이미지 2장 (게이트) ──
    text, ms = ask(client, [
        {"type": "text", "text": "이미지 1:"},
        image_block(start),
        {"type": "text", "text": "이미지 2:"},
        image_block(current),
        {"type": "text", "text":
            "이미지가 총 몇 장 보입니까? "
            "그리고 각 이미지의 좌측 상단 사각형 색을 순서대로 말하세요."},
    ], "3. ★ 이미지 2장 동시 입력 (게이트)")
    timings.append(ms)

    # 게이트 판정은 두 신호를 분리해서 본다.
    #   (a) 2장을 받았는가  ← 이것이 게이트. API/모델의 다중 이미지 지원 여부
    #   (b) 색을 정확히 봤는가 ← 지각 정확도. 게이트가 아니라 품질 지표
    # 색 이름은 모델마다 표현이 달라("하늘색","남색") 게이트 근거로 부적합하다.
    saw_two = bool(text) and contains_any(
        text, ["2장", "두 장", "2 장", "두장", "2개", "두 개", "two image", "2 image"])
    saw_blue = bool(text) and contains_any(
        text, ["파랑", "파란", "blue", "청색", "하늘색", "남색", "코발트"])
    saw_red = bool(text) and contains_any(
        text, ["빨강", "빨간", "red", "적색", "붉은", "주황", "다홍"])

    results["multi_image"] = saw_two or (saw_blue and saw_red)
    results["color_ok"] = saw_blue and saw_red

    # ── 4. 실제 판정 프롬프트 ──
    text, ms = ask(client, [
        {"type": "text", "text": "이미지 1: 단계 시작 시점"},
        image_block(start),
        {"type": "text", "text": "이미지 2: 현재"},
        image_block(current),
        {"type": "text", "text":
            "완료 조건: 팬 안의 재료가 흰색/불투명에서 반투명하게 변했는가\n\n"
            "이 단계가 완료되었는가? 반드시 아래 JSON 형식으로만 답하라.\n"
            '{"verdict": "DONE | NOT_DONE | CANNOT_TELL", '
            '"reasonCode": "VISIBLE_CHANGE | NO_CHANGE | TARGET_NOT_VISIBLE | BLURRY"}'},
    ], "4. 실제 판정 프롬프트")
    timings.append(ms)
    results["judge"] = bool(text) and contains_any(text, ["DONE", "NOT_DONE", "CANNOT_TELL"])
    results["json_clean"] = bool(text) and text.lstrip().startswith("{")

    # ── 결과 ──
    print("\n" + "=" * 60)
    print("결과")
    print("=" * 60)
    rows = [
        ("인증",                     results["auth"],        "필수"),
        ("이미지 1장 인식",           results["vision"],      "필수"),
        ("★ 이미지 2장 동시 입력",     results["multi_image"], "게이트"),
        ("  └ 색 판별 정확도",         results.get("color_ok", False), "품질"),
        ("판정 프롬프트 응답",         results["judge"],       "필수"),
        ("JSON만 깔끔히 반환",         results["json_clean"],  "선택"),
    ]
    for name, ok, kind in rows:
        print(f"  {'✅' if ok else '❌'}  {name:<24} ({kind})")

    if timings:
        print(f"\n  응답 시간: 최소 {min(timings)}ms / 최대 {max(timings)}ms  (목표 3000ms 이내)")
        if max(timings) > 3000:
            print("  ⚠️  3초 목표 초과 — 명세서 12절 #4 재검토 필요")

    print("\n" + "=" * 60)
    if results["multi_image"]:
        print("✅ 게이트 통과 — 2장을 받습니다. 계획대로 진행하세요.")
        if not results.get("color_ok"):
            print("   ⚠️ 다만 색 판별이 부정확했습니다(예: 파랑→'하늘색').")
            print("      다중 이미지 지원과는 별개 문제이며, T2-3 프롬프트 개선과")
            print("      T2-1 실제 사진 평가에서 정확도를 확인해야 합니다.")
    elif results["vision"]:
        print("⚠️  이미지는 보지만 2장 동시 입력에 실패했습니다.")
        print("   위 3번 응답을 눈으로 확인하세요. 실제로 두 장을 다 봤나요?")
        print("   실패라면 팀 회의 필요:")
        print("     A안) OpenAI로 1순위 교체")
        print("     B안) COLOR_CHANGE 단계를 A등급으로 교체 (F4-7 검증 포기)")
    else:
        print("❌ 이미지 인식 자체가 안 됩니다.")
        print("   → 텍스트 전용 모델을 고른 것 같습니다.")
        print("   → notes/nemotron.md 3번으로 돌아가 vision 모델을 다시 고르세요.")
    print("=" * 60)
    print("\n결과를 notes/nemotron.md 5번 항목에 기록하고 팀에 공유하세요.")

    if not results["json_clean"] and results["judge"]:
        print("\n💡 JSON 앞뒤에 설명이 붙었다면 정상입니다.")
        print("   judge/base.py 의 coerce_verdict() 가 처리하도록 설계돼 있습니다.")


if __name__ == "__main__":
    main()
