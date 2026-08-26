"""
T2-5 · OpenAI 호환 백엔드 (Groq · Gemini · OpenAI · OpenRouter 공용)

`nemotron.py` 와 같은 규격이지만 **벤더를 환경변수로 갈아끼웁니다.**
무료 티어를 옮겨 다녀야 하는 사정 때문에, 벤더마다 파일을 만드는 대신
접두사 하나로 여러 벤더를 태웁니다.

    GROQ_API_KEY / GROQ_BASE_URL / GROQ_MODEL   → JudgeBackend(prefix="GROQ")

nemotron 과 다른 점은 두 가지뿐입니다.

1. **추론 모델 대응** — qwen3.6 같은 모델은 `<think>` 를 먼저 뱉습니다.
   그대로 두면 max_tokens 200 을 생각만 하다 다 쓰고 **정작 JSON 을 못 냅니다**
   (실측: 출력 200tok 전부 추론, coerce_verdict 는 parsed=False → CANNOT_TELL).
   그래서 `{prefix}_REASONING_EFFORT=none` 을 기본으로 보냅니다.

2. **분당 토큰(TPM) 한도** — Groq 무료 티어는 8,000 TPM 입니다. 우리 판정은
   1회에 입력 3,240tok(768 2장)이라 분당 2.3회밖에 못 씁니다. 한도는 여기서
   못 늘리므로, 해상도를 줄여 토큰을 줄이는 쪽으로 대응합니다(server.py).
   429 는 나머지 예산 안에서만 짧게 재시도하고, 안 되면 상위에 넘깁니다.
"""
from __future__ import annotations

import os
import time
from email.utils import parsedate_to_datetime
from datetime import datetime, timezone
from typing import Optional

from openai import (
    APIConnectionError,
    APIStatusError,
    APITimeoutError,
    AuthenticationError,
    BadRequestError,
    InternalServerError,
    NotFoundError,
    OpenAI,
    PermissionDeniedError,
    RateLimitError,
)

from prompts import LABEL_CURRENT_ONLY, LABEL_CURRENT_WITH_START, LABEL_START

from .base import (
    Verdict,
    JudgeConfigError,
    JudgeRateLimit,
    JudgeTimeout,
    JudgeUpstreamError,
    coerce_verdict,
    normalize_b64_image,
)

# 벤더별 기본값. 키만 넣으면 나머지는 이 표를 씁니다.
_VENDOR_DEFAULTS: dict[str, dict[str, str]] = {
    "GROQ": {
        "base_url": "https://api.groq.com/openai/v1",
        # 2026-08 기준 Groq 무료 티어에서 이미지를 받는 유일한 모델입니다.
        # 목록은 판단 근거와 함께 notes/backup-backend.md 에 적어 둡니다.
        "model": "qwen/qwen3.6-27b",
        "reasoning_effort": "none",
    },
    "GEMINI": {
        "base_url": "https://generativelanguage.googleapis.com/v1beta/openai",
        "model": "gemini-2.0-flash",
        "reasoning_effort": "",
    },
    "OPENAI": {
        "base_url": "https://api.openai.com/v1",
        "model": "gpt-4o-mini",
        "reasoning_effort": "",
    },
    "OPENROUTER": {
        "base_url": "https://openrouter.ai/api/v1",
        "model": "",
        "reasoning_effort": "",
    },
}

DEFAULT_TIMEOUT_S = 7.5
MIN_ATTEMPT_S = 1.5
RETRY_SLEEP_S = 0.3


def _retry_after_seconds(error: RateLimitError, fallback: float) -> float:
    """429 의 Retry-After 를 초로 읽는다 (nemotron._retry_after_seconds 와 동일 규칙)."""
    response = getattr(error, "response", None)
    headers = getattr(response, "headers", None)
    value = headers.get("retry-after") if headers else None
    if value is None:
        return fallback
    try:
        return max(0.0, float(value))
    except (TypeError, ValueError):
        pass
    try:
        retry_at = parsedate_to_datetime(str(value))
        if retry_at.tzinfo is None:
            retry_at = retry_at.replace(tzinfo=timezone.utc)
        return max(0.0, (retry_at - datetime.now(timezone.utc)).total_seconds())
    except (TypeError, ValueError, IndexError, OverflowError):
        return fallback


class OpenAICompatibleJudge:
    """base.VlmJudge 규격 구현. 벤더는 접두사로 정한다."""

    def __init__(
        self,
        prefix: str = "OPENAI",
        *,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        model: Optional[str] = None,
        timeout_s: Optional[float] = None,
        max_tokens: int = 200,
        temperature: float = 0.0,
    ):
        prefix = prefix.upper()
        defaults = _VENDOR_DEFAULTS.get(prefix, {})
        self.name = prefix.lower()

        key = api_key or os.getenv(f"{prefix}_API_KEY", "")
        if not key.strip():
            raise JudgeConfigError(
                f"{prefix}_API_KEY 가 설정되지 않았습니다. "
                f"로컬은 server/.env, 배포는 Render Environment 탭에 넣으세요."
            )

        self.model = model or os.getenv(f"{prefix}_MODEL") or defaults.get("model", "")
        if not self.model:
            raise JudgeConfigError(
                f"{prefix}_MODEL 이 비어 있습니다. 사용할 모델 ID 를 지정하세요."
            )
        self.base_url = (
            base_url or os.getenv(f"{prefix}_BASE_URL") or defaults.get("base_url", "")
        )
        self.timeout_s = float(
            timeout_s or os.getenv(f"{prefix}_TIMEOUT_S")
            or os.getenv("VLM_TIMEOUT_S", DEFAULT_TIMEOUT_S)
        )
        self.max_tokens = int(os.getenv(f"{prefix}_MAX_TOKENS")
                              or os.getenv("VLM_MAX_TOKENS", max_tokens))
        self.temperature = temperature
        # 빈 문자열이면 파라미터 자체를 보내지 않습니다. 받지 않는 모델에
        # 보내면 400 이 나기 때문입니다(실측: `reasoning_effort` 는 none|default 만 허용).
        self.reasoning_effort = (
            os.getenv(f"{prefix}_REASONING_EFFORT")
            if os.getenv(f"{prefix}_REASONING_EFFORT") is not None
            else defaults.get("reasoning_effort", "")
        ).strip()

        self._client = OpenAI(base_url=self.base_url or None, api_key=key, max_retries=0)

    # ──────────────────────────────────────────────────────
    def describe(self) -> dict:
        """/health 표시용. 키 값은 절대 넣지 않는다."""
        return {
            "backend": self.name,
            "model": self.model,
            "baseUrl": self.base_url,
            "timeoutS": self.timeout_s,
            "reasoningEffort": self.reasoning_effort or None,
        }

    @staticmethod
    def _image_block(b64: str) -> dict:
        return {
            "type": "image_url",
            "image_url": {"url": f"data:image/jpeg;base64,{normalize_b64_image(b64)}"},
        }

    def _build_content(self, user_text: str,
                       start_b64: Optional[str], current_b64: str) -> list:
        """nemotron._build_content 와 동일한 배치.

        라벨 위치·질문을 이미지 뒤에 두는 순서까지 맞춥니다. 백업이 주 모델과
        다른 프롬프트를 쓰면 정확도 비교가 무의미해지기 때문입니다.
        """
        blocks: list = []
        if start_b64:
            blocks.append({"type": "text", "text": LABEL_START})
            blocks.append(self._image_block(start_b64))
            blocks.append({"type": "text", "text": LABEL_CURRENT_WITH_START})
        else:
            blocks.append({"type": "text", "text": LABEL_CURRENT_ONLY})
        blocks.append(self._image_block(current_b64))
        blocks.append({"type": "text", "text": user_text})
        return blocks

    # ──────────────────────────────────────────────────────
    def judge(
        self,
        system: str,
        user_text: str,
        start_b64: Optional[str],
        current_b64: str,
    ) -> Verdict:
        messages = [
            {"role": "system", "content": system},
            {"role": "user",
             "content": self._build_content(user_text, start_b64, current_b64)},
        ]

        t0 = time.monotonic()
        deadline = t0 + self.timeout_s
        attempted_plain = False   # 샘플링 파라미터를 뺀 재시도를 했는가
        retried = False

        while True:
            remaining = deadline - time.monotonic()
            if remaining < MIN_ATTEMPT_S:
                raise JudgeTimeout(
                    f"{self.timeout_s:.1f}초 예산을 모두 소진했습니다 "
                    f"(backend={self.name}, model={self.model})")

            kwargs: dict = dict(model=self.model, messages=messages,
                                max_tokens=self.max_tokens, timeout=remaining)
            if not attempted_plain:
                kwargs["temperature"] = self.temperature
                if self.reasoning_effort:
                    kwargs["reasoning_effort"] = self.reasoning_effort

            try:
                resp = self._client.chat.completions.create(**kwargs)
                text = (resp.choices[0].message.content or "") if resp.choices else ""
                latency = int((time.monotonic() - t0) * 1000)
                return coerce_verdict(text, latency)

            except APITimeoutError as e:
                raise JudgeTimeout(
                    f"모델 응답이 {self.timeout_s:.1f}초 안에 오지 않았습니다 "
                    f"(backend={self.name}, model={self.model})") from e

            except (AuthenticationError, PermissionDeniedError) as e:
                raise JudgeConfigError(
                    f"{self.name} 인증 실패 — API 키를 확인하세요: {str(e)[:200]}") from e

            except NotFoundError as e:
                raise JudgeConfigError(
                    f"모델 ID '{self.model}' 를 {self.name} 에서 찾을 수 없습니다: "
                    f"{str(e)[:200]}") from e

            except BadRequestError as e:
                # 두 갈래를 구분합니다.
                #  ① 샘플링·추론 파라미터 거부 → 파라미터를 빼고 1회 재시도
                #  ② 이미지 장수 제한("At most 1 image") → 재시도해도 같으므로 즉시 실패.
                #     Llama 3.2 Vision 계열이 여기 걸립니다. 백업으로 쓰려면
                #     2장을 1장으로 합성해야 하며, 그건 호출부의 몫입니다.
                message = str(e)
                if "image" in message.lower() and "at most" in message.lower():
                    raise JudgeUpstreamError(
                        f"{self.name} 모델이 이미지 여러 장을 받지 않습니다: "
                        f"{message[:200]}") from e
                if not attempted_plain:
                    attempted_plain = True
                    continue
                raise JudgeUpstreamError(
                    f"모델이 요청을 거부했습니다(400): {message[:300]}") from e

            except RateLimitError as e:
                # 무료 티어의 TPM 한도는 여기서 자주 걸립니다. 남은 예산 안에
                # 들어오는 짧은 백오프만 허용하고, 아니면 상위에 넘겨
                # 주 백엔드나 앱 백오프가 처리하게 합니다.
                delay = _retry_after_seconds(e, RETRY_SLEEP_S)
                can_retry = (
                    not retried
                    and (deadline - time.monotonic() - delay) >= MIN_ATTEMPT_S
                )
                if can_retry:
                    retried = True
                    time.sleep(delay)
                    continue
                raise JudgeRateLimit(
                    f"{self.name} 레이트 리밋(429): {str(e)[:200]}") from e

            except (APIConnectionError, InternalServerError) as e:
                if not retried:
                    retried = True
                    time.sleep(RETRY_SLEEP_S)
                    continue
                raise JudgeUpstreamError(
                    f"{self.name} 모델 서버 연결 실패: {type(e).__name__}") from e

            except APIStatusError as e:
                if e.status_code >= 500 and not retried:
                    retried = True
                    time.sleep(RETRY_SLEEP_S)
                    continue
                raise JudgeUpstreamError(
                    f"{self.name} 모델 서버 오류 {e.status_code}: {str(e)[:200]}") from e


class OpenAIJudge(OpenAICompatibleJudge):
    """`VLM_BACKEND=openai` 진입점 (judge/__init__.py 가 이 이름을 찾습니다)."""

    def __init__(self, **kwargs):
        super().__init__("OPENAI", **kwargs)


class GroqJudge(OpenAICompatibleJudge):
    def __init__(self, **kwargs):
        super().__init__("GROQ", **kwargs)


class GeminiJudge(OpenAICompatibleJudge):
    def __init__(self, **kwargs):
        super().__init__("GEMINI", **kwargs)
