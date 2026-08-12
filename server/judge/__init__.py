"""
어댑터 팩토리.

서버·eval.py 는 `get_judge()` 하나만 알면 됩니다. 벤더 전환은 환경변수 한 줄:

    VLM_BACKEND=nemotron python -m uvicorn server:app     # 기본
    VLM_BACKEND=openai   python -m uvicorn server:app     # T2-5 이후

새 백엔드를 추가할 때 고칠 곳은 아래 _BACKENDS 딕셔너리 한 줄뿐입니다.
"""
from __future__ import annotations

import os
from typing import Callable, Optional

from dotenv import load_dotenv

# 어댑터는 환경변수로 설정됩니다. 진입점(server.py, smoke.py, eval.py)이 이미
# 부르지만, `python -c "from judge import get_judge"` 처럼 바로 쓸 때도
# 키를 찾도록 여기서 한 번 더 부릅니다. (이미 로드됐으면 무시됩니다)
load_dotenv()

from .base import (
    REASON_CODES,
    VERDICTS,
    JudgeConfigError,
    JudgeError,
    JudgeRateLimit,
    JudgeTimeout,
    JudgeUpstreamError,
    ReasonCodeValue,
    Verdict,
    VerdictValue,
    VlmJudge,
    coerce_verdict,
    decode_b64_image,
    looks_like_jpeg,
    normalize_b64_image,
)

__all__ = [
    "Verdict", "VlmJudge", "VerdictValue", "ReasonCodeValue",
    "VERDICTS", "REASON_CODES", "coerce_verdict",
    "normalize_b64_image", "decode_b64_image", "looks_like_jpeg",
    "JudgeError", "JudgeConfigError", "JudgeTimeout",
    "JudgeRateLimit", "JudgeUpstreamError",
    "get_judge", "available_backends", "DEFAULT_BACKEND",
]

DEFAULT_BACKEND = "nemotron"


def _make_nemotron() -> VlmJudge:
    from .nemotron import NemotronJudge
    return NemotronJudge()


def _make_mock() -> VlmJudge:
    # API 호출 없이 eval.py 의 지표 계산을 검산할 때 씁니다 (mock.py 참조).
    from .mock import MockJudge
    return MockJudge()


def _make_openai() -> VlmJudge:
    # T2-5 조건부 태스크. 파일이 없으면 친절하게 알려준다.
    try:
        from .openai_backend import OpenAIJudge  # type: ignore
    except ImportError as e:
        raise JudgeConfigError(
            "openai 백엔드는 아직 구현되지 않았습니다 (T2-5 조건부 태스크). "
            "VLM_BACKEND=nemotron 을 쓰세요."
        ) from e
    return OpenAIJudge()


_BACKENDS: dict[str, Callable[[], VlmJudge]] = {
    "nemotron": _make_nemotron,
    "openai": _make_openai,
    "mock": _make_mock,
}

# 어댑터 1개 생성 = OpenAI 클라이언트 1개 생성입니다.
# 요청마다 만들면 커넥션 풀이 매번 새로 뜨므로 백엔드별로 재사용합니다.
_cache: dict[str, VlmJudge] = {}


def available_backends() -> list[str]:
    return sorted(_BACKENDS)


def get_judge(backend: Optional[str] = None) -> VlmJudge:
    """백엔드 어댑터를 돌려준다 (실패 시 JudgeConfigError).

    키가 없는 상태에서도 서버는 떠야 하므로(Mock 경로는 키 없이 동작),
    생성은 앱 시작 시점이 아니라 **첫 실제 판정 요청 때** 일어납니다.
    """
    name = (backend or os.getenv("VLM_BACKEND") or DEFAULT_BACKEND).strip().lower()
    if name not in _BACKENDS:
        raise JudgeConfigError(
            f"알 수 없는 VLM_BACKEND='{name}'. 가능한 값: {', '.join(available_backends())}")
    if name not in _cache:
        _cache[name] = _BACKENDS[name]()
    return _cache[name]
