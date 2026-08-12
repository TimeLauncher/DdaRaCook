"""
T1-1 · 어댑터 규격 + 관대한 JSON 파서

벤더 종속 코드는 `judge/` 안에만 둡니다. 서버 본체·평가 스크립트·프롬프트는
이 파일이 정의한 규격(`Verdict`, `VlmJudge`)만 알면 되고 벤더를 몰라도 됩니다.

핵심 설계 원칙 하나만 기억하면 됩니다:

    ❗ 읽지 못한 응답은 **무조건 CANNOT_TELL**. 절대 DONE으로 떨어지지 않는다.

명세서 12절 목표가 "정확도 80%"와 **"잘못된 자동 진행 0회"** 두 개이기 때문입니다.
파싱 실패를 DONE으로 처리하면 후자가 즉시 무너집니다.
"""
from __future__ import annotations

import base64
import binascii
import json
import re
from dataclasses import dataclass
from typing import Iterator, Literal, Optional, Protocol, runtime_checkable

# ══════════════════════════════════════════════════════════
# 1. 타입 (CONTRACT.md §4 와 반드시 일치)
# ══════════════════════════════════════════════════════════
VerdictValue = Literal["DONE", "NOT_DONE", "CANNOT_TELL"]
ReasonCodeValue = Literal["VISIBLE_CHANGE", "NO_CHANGE",
                          "TARGET_NOT_VISIBLE", "BLURRY", "OTHER"]

VERDICTS = frozenset(("DONE", "NOT_DONE", "CANNOT_TELL"))
REASON_CODES = frozenset(("VISIBLE_CHANGE", "NO_CHANGE",
                          "TARGET_NOT_VISIBLE", "BLURRY", "OTHER"))

SAFE_FALLBACK: VerdictValue = "CANNOT_TELL"


@dataclass
class Verdict:
    """판정 1건.

    필드 이름이 파이썬답지 않게 camelCase 인 것(`reasonCode`)은 의도적입니다.
    CONTRACT.md 의 JSON 필드와 1:1 로 대응시켜, 옮겨 적는 과정에서
    이름이 어긋나는 사고를 막습니다.
    """
    verdict: VerdictValue
    reasonCode: ReasonCodeValue = "OTHER"
    latencyMs: int = 0
    # 아래 둘은 평가·디버깅 전용이며 앱 응답(JudgeResponse)에는 포함하지 않습니다.
    raw: str = ""        # 모델 원문 (T2-3 프롬프트 개선의 재료)
    parsed: bool = True  # verdict 를 실제로 읽어냈는가. False = 폴백으로 떨어짐


# ══════════════════════════════════════════════════════════
# 2. 예외 — "AI가 보지도 못한" 실패
# ══════════════════════════════════════════════════════════
# 🚨 CONTRACT.md §5 의 가장 중요한 규칙:
#    CANNOT_TELL = AI가 사진을 봤는데 판단 못 함
#    아래 예외   = AI가 사진을 보지도 못함  → Verdict 로 바꾸면 안 된다
# 섞으면 와이파이가 잠깐 끊겼을 뿐인데 F4-5(3회 → 수동 모드)에 걸립니다.
class JudgeError(RuntimeError):
    """모델 호출 자체가 실패. 서버는 이걸 Verdict 로 변환하지 않는다."""
    http_status = 503


class JudgeConfigError(JudgeError):
    """키 누락·모델 ID 오타 등 설정 문제. 재시도해도 소용없다."""
    http_status = 500


class JudgeTimeout(JudgeError):
    """제한 시간 안에 응답이 오지 않음."""
    http_status = 503


class JudgeRateLimit(JudgeError):
    """429. 무료 티어 한도. T2-6에서 백오프 추가 예정."""
    http_status = 429


class JudgeUpstreamError(JudgeError):
    """벤더 5xx, 연결 실패, 잘못된 요청 등."""
    http_status = 503


# ══════════════════════════════════════════════════════════
# 3. 어댑터 규격
# ══════════════════════════════════════════════════════════
@runtime_checkable
class VlmJudge(Protocol):
    """벤더 어댑터가 지켜야 할 유일한 규격.

    새 벤더 추가 = 이 Protocol 을 만족하는 파일 1개 추가 + get_judge() 등록.
    서버와 eval.py 는 한 줄도 바뀌지 않습니다.
    """
    name: str    # "nemotron" / "openai" — 응답의 backend 필드에 실림
    model: str   # 실제 모델 ID (평가 리포트 추적용)

    def judge(
        self,
        system: str,
        user_text: str,
        start_b64: Optional[str],
        current_b64: str,
    ) -> Verdict:
        """이미지를 보고 판정 1건을 돌려준다.

        start_b64 가 None 이면 현재 이미지 1장만 전송한다(CONTRACT.md §3.2).
        호출 자체가 실패하면 Verdict 가 아니라 JudgeError 를 raise 한다.
        """
        ...


# ══════════════════════════════════════════════════════════
# 4. 관대한 JSON 파서
# ══════════════════════════════════════════════════════════
# 오픈 모델은 JSON 형식을 보장하지 않습니다. 실측(notes/nemotron.md §4)에서도
# 12b 모델이 3회 중 1회는 JSON 앞에 설명 문장을 붙였습니다.
# 아래 순서로 관대하게 시도하되, 마지막은 항상 안전한 쪽으로 떨어집니다.
#   ① ```json ... ``` 코드블록 벗기기
#   ② 문자열 안의 중괄호를 무시하는 괄호 균형 스캐너로 JSON 객체 후보 추출
#   ③ 홑따옴표·후행 쉼표 정도는 고쳐서 재시도
#   ④ 전부 실패 → CANNOT_TELL

_FENCE_RE = re.compile(r"```[ \t]*(?:json|JSON)?[ \t]*\r?\n?(.*?)```", re.DOTALL)
_TRAILING_COMMA_RE = re.compile(r",\s*([}\]])")

# 모델이 키 이름을 조금씩 바꿔 부르는 것까지는 받아줍니다.
_VERDICT_KEYS = ("verdict", "result", "judgment", "judgement", "decision")
_REASON_KEYS = ("reasoncode", "reason", "reasoncodes", "why")

_MAX_RAW = 2000  # 원문 보관 상한 (로그·CSV 가 비대해지는 것 방지)


def _strip_fences(text: str) -> list[str]:
    """코드블록이 있으면 그 안쪽을 우선 후보로, 전체 텍스트도 후보로 남긴다."""
    inner = [m.group(1) for m in _FENCE_RE.finditer(text)]
    return inner + [text]


def _iter_json_objects(text: str) -> Iterator[str]:
    """`{...}` 로 균형이 맞는 부분 문자열을 바깥쪽부터 순서대로 뽑는다.

    문자열 리터럴 안의 중괄호는 세지 않는다.
    (예: {"reason": "팬 {안} 재료"} 같은 응답에서 깨지지 않도록)
    """
    depth = 0
    start = -1
    in_str = False
    escape = False
    for i, ch in enumerate(text):
        if in_str:
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == '"':
                in_str = False
            continue
        if ch == '"':
            in_str = True
        elif ch == "{":
            if depth == 0:
                start = i
            depth += 1
        elif ch == "}":
            if depth > 0:
                depth -= 1
                if depth == 0 and start >= 0:
                    yield text[start:i + 1]
                    start = -1


def _repair(s: str) -> str:
    """망가진 JSON 을 최소한만 고친다. 과하게 고치면 오히려 오독한다."""
    s = _TRAILING_COMMA_RE.sub(r"\1", s)          # {"a": 1,} → {"a": 1}
    if '"' not in s and "'" in s:                  # {'a': 1} → {"a": 1}
        s = s.replace("'", '"')
    return s


def _norm_key(k: str) -> str:
    return re.sub(r"[\s_\-]+", "", str(k)).lower()


def _norm_enum(v: object) -> str:
    """열거값 정규화. 모르는 값은 그대로 두고, 판정은 호출부가 한다."""
    return re.sub(r"[\s\-]+", "_", str(v).strip()).upper()


def _pick(data: dict, keys: tuple[str, ...]) -> Optional[str]:
    norm = {_norm_key(k): v for k, v in data.items()}
    for k in keys:
        v = norm.get(k)
        if isinstance(v, (str, int, float)):
            return _norm_enum(v)
    return None


def _extract(data: object, depth: int = 0) -> Optional[tuple[str, str]]:
    """dict 안에서 (verdict, reasonCode) 를 찾는다. 중첩도 한 단계씩 파고든다.

    모델이 `{"result": {"verdict": ...}}` 처럼 한 겹 감싸는 경우가 있어서입니다.
    깊이를 제한하는 이유는 이상한 응답에서 무한히 헤매지 않기 위해서입니다.
    """
    if depth > 3:
        return None
    if isinstance(data, dict):
        v = _pick(data, _VERDICT_KEYS)
        if v in VERDICTS:
            r = _pick(data, _REASON_KEYS)
            return v, (r if r in REASON_CODES else "OTHER")
        for value in data.values():
            found = _extract(value, depth + 1)
            if found:
                return found
    elif isinstance(data, list):
        for value in data:
            found = _extract(value, depth + 1)
            if found:
                return found
    return None


def coerce_verdict(text: Optional[str], latency_ms: int = 0) -> Verdict:
    """모델 원문 → Verdict. **어떤 입력이 와도 예외를 던지지 않는다.**

    읽어내지 못하면 CANNOT_TELL / OTHER / parsed=False 를 돌려준다.
    """
    raw = (text or "")[:_MAX_RAW]
    if not raw.strip():
        return Verdict(SAFE_FALLBACK, "OTHER", latency_ms, raw, parsed=False)

    for candidate in _strip_fences(raw):
        for blob in _iter_json_objects(candidate):
            for attempt in (blob, _repair(blob)):
                try:
                    data = json.loads(attempt)
                except (ValueError, TypeError):
                    continue
                # 모델이 템플릿을 그대로 따라 쓰는 사고를 여기서 막는다.
                #   {"verdict": "DONE | NOT_DONE | CANNOT_TELL"}
                # → 정규화해도 3종에 없으므로 다음 후보로 넘어가고, 끝내 못 찾으면
                #   CANNOT_TELL 로 떨어진다.
                found = _extract(data)
                if found:
                    v, reason = found
                    return Verdict(v, reason, latency_ms, raw, parsed=True)  # type: ignore[arg-type]

    # 여기까지 왔다 = 읽지 못했다 = 안전한 쪽으로.
    return Verdict(SAFE_FALLBACK, "OTHER", latency_ms, raw, parsed=False)


# ══════════════════════════════════════════════════════════
# 5. 이미지 유틸 (서버·eval 공용)
# ══════════════════════════════════════════════════════════
_DATA_URL_RE = re.compile(r"^data:image/[a-zA-Z.+-]+;base64,", re.IGNORECASE)


def normalize_b64_image(s: str) -> str:
    """`data:image/jpeg;base64,...` 접두사와 줄바꿈을 제거한다.

    2번이 클라이언트에서 data URL 형태로 보내도 서버가 받아주기 위한 관용 처리.
    """
    s = (s or "").strip()
    s = _DATA_URL_RE.sub("", s)
    return "".join(s.split())


def decode_b64_image(s: str) -> bytes:
    """base64 디코드. 실패하면 ValueError."""
    try:
        return base64.b64decode(normalize_b64_image(s), validate=True)
    except (binascii.Error, ValueError) as e:
        raise ValueError(f"base64 디코드 실패: {e}") from e


def looks_like_jpeg(data: bytes) -> bool:
    """JPEG 매직 바이트 확인 (FF D8 FF).

    EXIF 회전 함정과 함께, 초반에 가장 시간을 잡아먹는 게 '엉뚱한 포맷/깨진 인코딩'
    입니다. 모델에 보내기 전에 400 으로 잘라내면 2번이 5초 만에 원인을 압니다.
    """
    return len(data) >= 3 and data[:3] == b"\xff\xd8\xff"
