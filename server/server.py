"""
요리 어시스턴트 · 판정 서버

T0-3 단계: Mock 응답만 동작합니다. 2번 담당자가 앱 상태 머신을
           실기기·실제 요리 없이 테스트할 수 있게 하는 것이 목적입니다.
T1-4 단계: 실제 VLM 호출을 연결합니다.

실행:
    uvicorn server:app --reload --port 8000 --host 0.0.0.0
"""
import os
import sys
import time
from typing import Literal, Optional

from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

load_dotenv()

import prompts
from judge import (
    JudgeConfigError,
    JudgeError,
    decode_b64_image,
    get_judge,
    looks_like_jpeg,
)

DEBUG_MODE = os.getenv("DEBUG_MODE", "true").lower() == "true"
TEAM_TOKEN = os.getenv("TEAM_TOKEN", "cookassist-dev-change-me")
BACKEND_NAME = os.getenv("VLM_BACKEND", "nemotron")
PROMPT_VERSION = prompts.PROMPT_VERSION

app = FastAPI(title="Cooking Assistant Judge API", version="1.0.0")


# ══════════════════════════════════════════════════════════
# 계약 (CONTRACT.md 와 반드시 일치할 것)
# ══════════════════════════════════════════════════════════
Verdict = Literal["DONE", "NOT_DONE", "CANNOT_TELL"]
ReasonCode = Literal["VISIBLE_CHANGE", "NO_CHANGE",
                     "TARGET_NOT_VISIBLE", "BLURRY", "OTHER"]
CheckType = Literal["PRESENCE", "COUNT", "IDENTIFY",
                    "COLOR_CHANGE", "STATE_CHANGE", "TIME_ONLY"]


class JudgeRequest(BaseModel):
    requestId: str = Field(..., description="재시도 중복 판별용 UUID")
    recipeId: str
    stepOrder: int
    instruction: str = Field(..., description="사용자에게 읽어줄 단계 내용")
    checkType: CheckType
    checkCondition: str = Field(..., description="LLM에 전달할 완료 조건")
    elapsedSeconds: int
    startImage: Optional[str] = Field(
        None, description="base64 JPEG. 비교가 필요한 유형에서만 전송")
    currentImage: str = Field(..., description="base64 JPEG")


class JudgeResponse(BaseModel):
    verdict: Verdict
    reasonCode: ReasonCode
    vlmLatencyMs: int = Field(..., description="서버가 잰 모델 호출 시간")
    promptVersion: str
    backend: str


# ══════════════════════════════════════════════════════════
def check_auth(authorization: Optional[str]):
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Authorization: Bearer <token> 헤더가 필요합니다")
    if authorization[7:] != TEAM_TOKEN:
        raise HTTPException(403, "토큰이 올바르지 않습니다")


# X-Mock-Status 로 강제할 수 있는 오류 목록 (CONTRACT.md §5 와 1:1).
# 문구를 실제 오류와 비슷하게 맞춰둔다. 2번이 오류 메시지를 화면에 띄우는
# 코드를 짤 때 진짜와 다른 문자열로 테스트하면 의미가 없기 때문이다.
MOCK_STATUS_MESSAGES = {
    400: "요청 형식 오류입니다 (mock)",
    401: "Authorization: Bearer <token> 헤더가 필요합니다 (mock)",
    403: "토큰이 올바르지 않습니다 (mock)",
    429: "레이트 리밋(429). 무료 한도를 확인하세요 (mock)",
    500: "NVIDIA_API_KEY 가 설정되지 않았습니다 (mock)",
    503: "모델 응답이 7.5초 안에 오지 않았습니다 (mock)",
}


def _validate_image(b64: Optional[str], field: str) -> str:
    """모델에 보내기 전에 이미지를 검증한다.

    깨진 base64·PNG·빈 문자열을 그대로 모델에 보내면 판정이 이상해지거나
    벤더가 400을 뱉는데, 원인 파악에 반나절이 걸립니다. 여기서 잘라내면
    2번은 400 메시지를 보고 5초 만에 압니다.
    """
    if not b64 or not b64.strip():
        raise HTTPException(400, f"{field} 가 비어 있습니다")
    try:
        data = decode_b64_image(b64)
    except ValueError as e:
        raise HTTPException(400, f"{field}: {e}")
    if not looks_like_jpeg(data):
        raise HTTPException(
            400, f"{field} 가 JPEG 가 아닙니다 (앞 3바이트 {data[:3].hex()}). "
                 f"CONTRACT.md §3.3 참조 — JPEG quality 80, EXIF 제거.")
    return b64


def _log(req: "JudgeRequest", v, judge) -> None:
    """한 줄 로그. T2-2 평가와 T3-2 로그 수집의 재료가 된다.

    이미지는 절대 찍지 않는다(로그가 메가바이트 단위로 불어난다).
    parsed=False 일 때만 모델 원문을 남긴다 — 프롬프트 개선(T2-3)의 출발점.
    """
    line = (f"[judge] req={req.requestId} step={req.stepOrder} "
            f"type={req.checkType} imgs={'2' if req.startImage else '1'} "
            f"-> {v.verdict}/{v.reasonCode} {v.latencyMs}ms "
            f"backend={judge.name} prompt={PROMPT_VERSION}")
    if not v.parsed:
        line += f" ⚠️PARSE_FAIL raw={v.raw[:200]!r}"
    print(line, flush=True)


@app.get("/")
def root():
    """브라우저로 루트에 들어왔을 때 길 안내.

    없으면 FastAPI가 {"detail":"Not Found"} 만 뱉어서
    '서버가 죽었나?' 하고 오해하기 쉽다.
    """
    return {
        "service": "Cooking Assistant Judge API",
        "status": "running",
        "endpoints": {
            "GET  /health": "상태 확인 · 환경변수 점검 · 워밍업",
            "POST /judge-step": "단계 완료 판정 (CONTRACT.md 참조)",
            "GET  /docs": "API 문서 (브라우저로 열어보세요)",
        },
        "note": "판정은 POST 전용입니다. 브라우저로 /judge-step 를 열면 405가 정상입니다.",
    }


@app.get("/health")
def health():
    """배포 확인 및 시연 전 워밍업용 (T3-5).

    환경변수 '설정 여부'만 보고한다. 값 자체는 절대 노출하지 않는다.
    배포 직후 키 누락을 실제 판정 요청이 아니라 여기서 발견하기 위한 것.
    """
    def is_set(name):
        v = os.getenv(name, "")
        return bool(v) and not v.startswith("nvapi-xxxx")

    env = {
        "NVIDIA_API_KEY": is_set("NVIDIA_API_KEY"),
        "NVIDIA_BASE_URL": is_set("NVIDIA_BASE_URL"),
        "NVIDIA_MODEL": is_set("NVIDIA_MODEL"),
        "TEAM_TOKEN": TEAM_TOKEN != "cookassist-dev-change-me",
    }

    # 어댑터를 실제로 만들어 본다. 키가 없으면 여기서 잡힌다.
    # (모델을 호출하지는 않으므로 무료 한도를 쓰지 않는다)
    ready, detail = False, None
    try:
        j = get_judge()
        ready = True
        detail = j.describe() if hasattr(j, "describe") else {"backend": j.name}
    except JudgeError as e:
        detail = {"error": str(e)[:200]}

    return {
        "status": "ok",
        "backend": BACKEND_NAME,
        "promptVersion": PROMPT_VERSION,
        "debugMode": DEBUG_MODE,
        "realJudgeReady": ready,
        "judge": detail,
        "envConfigured": env,
        "envMissing": [k for k, v in env.items() if not v],
    }


@app.post("/judge-step", response_model=JudgeResponse)
def judge_step(
    req: JudgeRequest,
    authorization: Optional[str] = Header(None),
    x_mock_verdict: Optional[str] = Header(None, alias="X-Mock-Verdict"),
    x_mock_delay_ms: Optional[int] = Header(None, alias="X-Mock-Delay-Ms"),
    x_mock_status: Optional[int] = Header(None, alias="X-Mock-Status"),
):
    check_auth(authorization)

    # ── Mock 경로 (2번의 분기 테스트용) ──────────────────
    # 헤더 하나로 원하는 응답을 강제로 받을 수 있다.
    #   X-Mock-Verdict: CANNOT_TELL   → 그 판정을 그대로 반환
    #   X-Mock-Status:  503           → 그 오류를 그대로 반환 (판정보다 우선)
    #   X-Mock-Delay-Ms: 2500         → 지연 흉내 (둘 다에 적용)

    # 오류 주입이 판정 주입보다 우선한다.
    # 둘을 같이 보내는 건 "8초 뒤 503" 같은 조합을 만들려는 경우이기 때문.
    if DEBUG_MODE and x_mock_status:
        if x_mock_status not in MOCK_STATUS_MESSAGES:
            raise HTTPException(
                400, f"X-Mock-Status 는 {sorted(MOCK_STATUS_MESSAGES)} 중 하나여야 합니다 "
                     f"(받은 값: {x_mock_status})")
        delay = min(x_mock_delay_ms or 0, 30000)
        if delay:
            time.sleep(delay / 1000)
        raise HTTPException(x_mock_status, MOCK_STATUS_MESSAGES[x_mock_status])

    if DEBUG_MODE and x_mock_verdict:
        v = x_mock_verdict.strip().upper()
        if v not in ("DONE", "NOT_DONE", "CANNOT_TELL"):
            raise HTTPException(
                400, f"X-Mock-Verdict 는 DONE/NOT_DONE/CANNOT_TELL 중 하나여야 합니다 (받은 값: {v})")

        delay = min(x_mock_delay_ms or 0, 30000)
        if delay:
            time.sleep(delay / 1000)

        reason = {"DONE": "VISIBLE_CHANGE",
                  "NOT_DONE": "NO_CHANGE",
                  "CANNOT_TELL": "TARGET_NOT_VISIBLE"}[v]
        return JudgeResponse(
            verdict=v, reasonCode=reason,
            vlmLatencyMs=delay, promptVersion="mock", backend="mock",
        )

    # ── 실제 판정 경로 (T1-4) ────────────────────────────
    # TIME_ONLY 는 앱이 로컬 타이머로 처리하기로 합의했다(CONTRACT.md §3.1).
    # 여기까지 왔다면 앱 쪽 버그이므로 조용히 판정하지 말고 알려준다.
    if req.checkType == "TIME_ONLY":
        raise HTTPException(
            400, "TIME_ONLY 단계는 서버를 호출하지 않습니다 (CONTRACT.md §3.1). "
                 "앱의 로컬 타이머로 처리하세요.")

    current_b64 = _validate_image(req.currentImage, "currentImage")
    start_b64 = _validate_image(req.startImage, "startImage") if req.startImage else None

    try:
        judge = get_judge()
    except JudgeConfigError as e:
        # 설정 문제는 재시도해도 소용없다. 500 으로 분명히 구분한다.
        raise HTTPException(500, str(e))

    user_text = prompts.build_user_text(
        instruction=req.instruction,
        check_type=req.checkType,
        check_condition=req.checkCondition,
        elapsed_seconds=req.elapsedSeconds,
        has_start=start_b64 is not None,
        step_order=req.stepOrder,
    )

    try:
        v = judge.judge(
            system=prompts.SYSTEM_PROMPT,
            user_text=user_text,
            start_b64=start_b64,
            current_b64=current_b64,
        )
    except JudgeError as e:
        # 🚨 여기서 절대 CANNOT_TELL 을 반환하지 않는다 (CONTRACT.md §5).
        #    "AI가 보지도 못한" 실패를 "AI가 봤는데 모르겠다"로 바꾸면
        #    와이파이가 끊겼을 뿐인데 F4-5(3회 → 수동 모드)에 걸린다.
        print(f"[judge] req={req.requestId} FAIL {type(e).__name__}: {e}", flush=True)
        raise HTTPException(getattr(e, "http_status", 503), str(e))

    _log(req, v, judge)
    return JudgeResponse(
        verdict=v.verdict,
        reasonCode=v.reasonCode,
        vlmLatencyMs=v.latencyMs,
        promptVersion=PROMPT_VERSION,
        backend=judge.name,
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", 8000)))
