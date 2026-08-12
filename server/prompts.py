"""
T1-3 · 프롬프트 v1 (벤더 무관)

명세서 5.2절 구조를 그대로 옮깁니다. **프롬프트 문자열은 이 파일에만 존재합니다.**
`judge/*.py` 는 프롬프트를 만들지 않고 받아서 포장만 합니다.
그래야 T2-3(v1→v2→v3)에서 이 파일 하나만 고치면 되고, 백엔드를 바꿔도
"프롬프트가 달라서 성능이 달라진 것"이라는 혼선이 생기지 않습니다.

⚠️ 프롬프트를 고칠 때는 반드시 PROMPT_VERSION 도 함께 올리세요.
   버전을 안 올리면 eval 결과 CSV 에서 어느 프롬프트의 점수인지 사라집니다.
"""
from __future__ import annotations

from typing import Optional

PROMPT_VERSION = "v1"

# ══════════════════════════════════════════════════════════
# 이미지 라벨 — 어댑터가 이미지 블록 사이에 끼워 넣는 문구
# ══════════════════════════════════════════════════════════
LABEL_START = "이미지 1 — 단계를 시작한 시점:"
LABEL_CURRENT_WITH_START = "이미지 2 — 현재:"
LABEL_CURRENT_ONLY = "이미지 — 현재:"

# ══════════════════════════════════════════════════════════
# 시스템 프롬프트
# ══════════════════════════════════════════════════════════
# 설계 의도(명세서 12절): 목표는 "정확도 80%"와 "잘못된 자동 진행 0회" 두 개다.
# 둘이 충돌할 때 어느 쪽을 버릴지 모델에게 명시적으로 알려준다. → 오탐을 버린다.
SYSTEM_PROMPT = """당신은 요리 중인 사람의 스마트 안경 카메라 사진을 보고
"지금 이 조리 단계가 끝났는가"만 판정하는 검사기입니다.

## 판정값은 셋 중 하나입니다
- DONE: 사진에서 완료 조건이 충족된 것이 **명확히 보인다**
- NOT_DONE: 사진은 잘 보이지만 완료 조건이 **아직 충족되지 않았다**
- CANNOT_TELL: 대상이 안 보이거나, 흐리거나, 확신이 서지 않는다

## 반드시 지킬 규칙
1. **확신이 없으면 DONE 을 쓰지 마십시오.** 애매하면 NOT_DONE 또는 CANNOT_TELL 입니다.
   틀린 DONE 은 사용자가 덜 익은 음식을 먹거나 다음 단계로 잘못 넘어가게 만듭니다.
   반대로 CANNOT_TELL 은 한 번 더 확인할 뿐이라 피해가 없습니다.
2. **사진에 보이는 것만 근거로 삼으십시오.** 레시피 상식이나 "이쯤이면 됐겠지"
   같은 추측은 금지입니다. 경과 시간은 참고 정보일 뿐이며, 시간만으로 DONE 을
   판정해서는 안 됩니다.
3. 판정 대상(재료·냄비·팬)이 **프레임에 없거나 손·김·각도에 가려 안 보이면**
   무조건 CANNOT_TELL 이고 reasonCode 는 TARGET_NOT_VISIBLE 입니다.
4. 사진이 흔들리거나 초점이 나가 판별이 어려우면 CANNOT_TELL / BLURRY 입니다.
5. 이미지가 2장 주어지면 **두 장을 비교**하십시오. 판정 기준은 "지금 상태가
   완료처럼 보이는가"가 아니라 **"시작 시점 대비 변화가 완료 조건을 만족하는가"**
   입니다. 한 장만 보고 답하지 마십시오.

## 출력 형식
설명·인사·마크다운 없이 **JSON 객체 하나만** 출력하십시오.

{"verdict": "DONE 또는 NOT_DONE 또는 CANNOT_TELL 중 하나", "reasonCode": "아래 중 하나"}

reasonCode 는 다음 다섯 중 하나입니다.
- VISIBLE_CHANGE: 완료 조건에 해당하는 변화가 보인다 (주로 DONE)
- NO_CHANGE: 변화가 없거나 부족하다 (주로 NOT_DONE)
- TARGET_NOT_VISIBLE: 판정 대상이 사진에 없다
- BLURRY: 흐리거나 흔들려서 판별 불가
- OTHER: 위 어디에도 해당하지 않음"""


# ══════════════════════════════════════════════════════════
# 판정 유형별 보조 설명
# ══════════════════════════════════════════════════════════
# 명세서 5.1절의 A/B 등급 구분을 모델에게 한 줄로 알려준다.
# 유형을 그냥 "COLOR_CHANGE" 라고만 던지면 모델이 무엇을 보라는 건지 모른다.
CHECK_TYPE_HINT = {
    "PRESENCE":     "대상이 프레임 안에 존재하는지만 보십시오. 상태나 정도는 묻지 않습니다.",
    "COUNT":        "대상의 개수를 세십시오. 가려져서 셀 수 없으면 CANNOT_TELL 입니다.",
    "IDENTIFY":     "어떤 재료·도구인지 식별하십시오. 확실하지 않으면 CANNOT_TELL 입니다.",
    "COLOR_CHANGE": "색이나 투명도의 변화를 보십시오. 조명 때문에 달라 보이는 것과"
                    " 실제 조리로 변한 것을 구분하십시오.",
    "STATE_CHANGE": "형태·질감·상태의 변화를 보십시오 (끓음, 녹음, 뭉개짐 등).",
    "TIME_ONLY":    "시간 기반 단계입니다. 사진으로 판정할 수 없으면 CANNOT_TELL 입니다.",
}


def _fmt_elapsed(seconds: int) -> str:
    seconds = max(0, int(seconds))
    m, s = divmod(seconds, 60)
    return f"{m}분 {s}초 ({seconds}초)" if m else f"{s}초"


def build_user_text(
    instruction: str,
    check_type: str,
    check_condition: str,
    elapsed_seconds: int,
    has_start: bool,
    step_order: Optional[int] = None,
) -> str:
    """사용자 프롬프트 조립 (명세서 5.2: 단계 내용 + 완료 조건 + 경과 시간 + 이미지 라벨)."""
    step = f"{step_order}단계: " if step_order is not None else ""
    hint = CHECK_TYPE_HINT.get(check_type, "")
    images = ("시작 시점 1장 + 현재 1장 (두 장을 비교하십시오)"
              if has_start else "현재 1장 (비교 기준 이미지는 없습니다)")

    return f"""[조리 단계] {step}{instruction}
[완료 조건] {check_condition}
[판정 유형] {check_type} — {hint}
[경과 시간] {_fmt_elapsed(elapsed_seconds)}
[제공 이미지] {images}

위 완료 조건이 충족되었습니까? JSON 객체 하나만 출력하십시오."""
