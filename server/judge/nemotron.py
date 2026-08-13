"""
T1-2 · NVIDIA Nemotron 어댑터 (1순위 백엔드)

NVIDIA NIM 은 OpenAI 호환 규격이라 `openai` 패키지를 그대로 씁니다
(notes/nemotron.md §1 실측). 덕분에 T2-5 에서 OpenAI 로 갈아탈 때
이 파일을 복사해 base_url/model 만 바꾸면 됩니다.

이 파일이 아는 것: 이미지 블록 포장, 타임아웃, 재시도, 예외 분류.
이 파일이 모르는 것: 프롬프트 내용(prompts.py), 판정 규칙(base.py).
"""
from __future__ import annotations

import os
import time
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
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
    JudgeConfigError,
    JudgeRateLimit,
    JudgeTimeout,
    JudgeUpstreamError,
    Verdict,
    coerce_verdict,
    normalize_b64_image,
)

DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
DEFAULT_MODEL = "nvidia/nemotron-nano-12b-v2-vl"

# ── 시간 예산 ────────────────────────────────────────────
# CONTRACT.md 는 앱 타임아웃을 8초로 못박았습니다. 서버가 8초짜리 시도를
# 두 번 하면 앱은 이미 포기한 뒤입니다. 그래서 **총 예산 8초를 시도들이 나눠 씁니다.**
#   - 연결 실패·429·5xx 처럼 빨리 실패하는 오류 → 예산이 남으므로 1회 재시도
#   - 진짜 타임아웃                              → 예산 소진, 재시도 없이 503
# notes/nemotron.md §5 에서 12b 가 간헐적으로 18초까지 튄 것이 실측됐으므로
# 여기서 끊고 앱이 다음 검사 주기에 다시 묻게 하는 편이 낫습니다.
# 앱 타임아웃(8초)보다 살짝 짧게 잡습니다. 같게 두면 앱은 서버의 503 메시지 대신
# 자기 쪽 소켓 타임아웃을 보게 되어, 원인이 '모델 지연'인지 '서버 다운'인지
# 앱 로그만으로는 구분할 수 없습니다.
DEFAULT_TIMEOUT_S = 7.5
MIN_ATTEMPT_S = 1.5   # 이보다 적게 남았으면 재시도해봐야 헛수고
RETRY_SLEEP_S = 0.3


def _retry_after_seconds(error: RateLimitError) -> float:
    """429 응답의 `Retry-After`를 초 단위로 읽는다.

    NVIDIA가 대기 시간을 알려주면 그 값을 우선한다. 헤더가 없거나 형식이
    잘못됐을 때만 짧은 기본 백오프를 쓴다. HTTP-date 형식도 RFC 7231에 맞춰
    처리하지만, 이 값이 남은 서버 예산을 넘으면 호출부가 기다리지 않고 429를
    앱에 돌려준다.
    """
    response = getattr(error, "response", None)
    headers = getattr(response, "headers", None)
    value = headers.get("retry-after") if headers else None
    if value is None:
        return RETRY_SLEEP_S

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
        return RETRY_SLEEP_S


class NemotronJudge:
    """base.VlmJudge 규격 구현."""

    name = "nemotron"

    def __init__(
        self,
        api_key: Optional[str] = None,
        base_url: Optional[str] = None,
        model: Optional[str] = None,
        timeout_s: Optional[float] = None,
        max_tokens: int = 200,
        temperature: float = 0.0,
    ):
        key = api_key or os.getenv("NVIDIA_API_KEY", "")
        if not key or key.startswith("nvapi-xxxx"):
            raise JudgeConfigError(
                "NVIDIA_API_KEY 가 설정되지 않았습니다. "
                "로컬은 server/.env, 배포는 Render Environment 탭에 넣으세요."
            )

        self.model = model or os.getenv("NVIDIA_MODEL") or DEFAULT_MODEL
        self.base_url = base_url or os.getenv("NVIDIA_BASE_URL") or DEFAULT_BASE_URL
        self.timeout_s = float(timeout_s or os.getenv("VLM_TIMEOUT_S", DEFAULT_TIMEOUT_S))
        self.max_tokens = int(os.getenv("VLM_MAX_TOKENS", max_tokens))
        self.temperature = temperature

        # max_retries=0: SDK 내부 재시도를 끕니다.
        # 켜두면 8초 예산을 SDK가 몰래 3배로 늘려 씁니다.
        self._client = OpenAI(base_url=self.base_url, api_key=key, max_retries=0)

    # ──────────────────────────────────────────────────────
    def describe(self) -> dict:
        """/health 표시용. 키 값은 절대 넣지 않는다."""
        return {"backend": self.name, "model": self.model,
                "baseUrl": self.base_url, "timeoutS": self.timeout_s}

    @staticmethod
    def _image_block(b64: str) -> dict:
        return {
            "type": "image_url",
            "image_url": {"url": f"data:image/jpeg;base64,{normalize_b64_image(b64)}"},
        }

    def _build_content(self, user_text: str,
                       start_b64: Optional[str], current_b64: str) -> list:
        """이미지 블록 구성.

        start_b64 가 None 이면 1장만 보냅니다(CONTRACT.md §3.2).
        라벨을 붙이는 이유: 라벨이 없으면 모델이 어느 쪽이 '이전'인지 모릅니다.
        실제로 probe(T0-2)에서 라벨을 붙였을 때 두 장 비교가 안정적이었습니다.
        """
        blocks: list = []
        if start_b64:
            blocks.append({"type": "text", "text": LABEL_START})
            blocks.append(self._image_block(start_b64))
            blocks.append({"type": "text", "text": LABEL_CURRENT_WITH_START})
        else:
            blocks.append({"type": "text", "text": LABEL_CURRENT_ONLY})
        blocks.append(self._image_block(current_b64))
        # 질문은 이미지 뒤에 둡니다. 앞에 두면 모델이 이미지를 보기 전에
        # 답을 정해버리는 경향이 있습니다.
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
        content = self._build_content(user_text, start_b64, current_b64)
        messages = [
            {"role": "system", "content": system},
            {"role": "user", "content": content},
        ]

        t0 = time.monotonic()
        deadline = t0 + self.timeout_s
        attempted_plain = False   # 샘플링 파라미터를 뺀 재시도를 이미 했는가
        retried = False  # 429·연결·5xx를 합쳐 전체 요청에서 1회만 재시도

        while True:
            remaining = deadline - time.monotonic()
            if remaining < MIN_ATTEMPT_S:
                raise JudgeTimeout(
                    f"{self.timeout_s:.1f}초 예산을 모두 소진했습니다 (model={self.model})")

            kwargs = dict(model=self.model, messages=messages,
                          max_tokens=self.max_tokens, timeout=remaining)
            if not attempted_plain:
                kwargs["temperature"] = self.temperature

            try:
                resp = self._client.chat.completions.create(**kwargs)
                text = (resp.choices[0].message.content or "") if resp.choices else ""
                latency = int((time.monotonic() - t0) * 1000)
                # 모델이 '봤지만' 형식이 깨진 경우는 여기서 CANNOT_TELL 로 떨어진다.
                # 이건 예외가 아니라 정상 판정 경로다(base.py 주석 참조).
                return coerce_verdict(text, latency)

            except APITimeoutError as e:
                raise JudgeTimeout(
                    f"모델 응답이 {self.timeout_s:.1f}초 안에 오지 않았습니다 "
                    f"(model={self.model})") from e

            except (AuthenticationError, PermissionDeniedError) as e:
                raise JudgeConfigError(f"인증 실패 — API 키를 확인하세요: {e}") from e

            except NotFoundError as e:
                raise JudgeConfigError(
                    f"모델 ID '{self.model}' 를 찾을 수 없습니다. "
                    f"notes/nemotron.md §2 의 목록을 확인하세요.") from e

            except BadRequestError as e:
                # NIM 이 특정 샘플링 파라미터를 거부하는 경우가 있어 한 번만
                # 파라미터를 빼고 재시도합니다. 그래도 안 되면 진짜 요청 오류.
                if not attempted_plain:
                    attempted_plain = True
                    continue
                raise JudgeUpstreamError(
                    f"모델이 요청을 거부했습니다(400). 이미지 크기나 다중 이미지 "
                    f"지원을 의심하세요: {str(e)[:300]}") from e

            except RateLimitError as e:
                delay = _retry_after_seconds(e)
                # 앱 타임아웃(8초)보다 먼저 응답하려면, 백오프 뒤에도 모델 호출에
                # 최소 MIN_ATTEMPT_S 만큼은 남아야 한다. 이 조건을 못 맞추면
                # 기다리지 않고 429를 반환해 앱 쪽 백오프에 맡긴다.
                can_retry = (
                    not retried
                    and (deadline - time.monotonic() - delay) >= MIN_ATTEMPT_S
                )
                if can_retry:
                    retried = True
                    time.sleep(delay)
                    continue
                raise JudgeRateLimit(
                    f"레이트 리밋(429). 백오프 후 다시 시도하세요: {str(e)[:200]}") from e

            except (APIConnectionError, InternalServerError) as e:
                if not retried:
                    retried = True
                    time.sleep(RETRY_SLEEP_S)
                    continue
                raise JudgeUpstreamError(f"모델 서버 연결 실패: {type(e).__name__}") from e

            except APIStatusError as e:
                if e.status_code >= 500 and not retried:
                    retried = True
                    time.sleep(RETRY_SLEEP_S)
                    continue
                raise JudgeUpstreamError(
                    f"모델 서버 오류 {e.status_code}: {str(e)[:200]}") from e
