"""
평가 하네스 검증용 Mock 어댑터 (API 호출 없음)

`eval.py` 의 지표 계산이 맞는지는 **실제 사진으로는 검증할 수 없습니다.**
실제 사진으로 "정확도 61%" 가 나와도 그 61% 가 맞게 계산된 값인지 알 방법이
없기 때문입니다. 정답과 응답을 둘 다 아는 상태로 돌려야 검산이 됩니다.

    MOCK_SCRIPT="DONE,NOT_DONE,CANNOT_TELL" python eval.py --backend mock

스크립트는 순환합니다. 라벨 순서가 고정이므로 혼동 행렬이 결정론적으로 나오고,
손으로 계산한 값과 대조할 수 있습니다.

특수 토큰 `GARBAGE` 는 파싱 실패 경로(→ CANNOT_TELL 폴백)를 태웁니다.
"""
from __future__ import annotations

import os
import time
from typing import Optional

from .base import Verdict, coerce_verdict

DEFAULT_SCRIPT = "DONE,NOT_DONE,CANNOT_TELL"

# 파싱 실패를 흉내 낼 때 모델이 뱉었다고 가정할 원문.
GARBAGE_RAW = "죄송합니다. 이미지를 확인해 보겠습니다..."


class MockJudge:
    """스크립트대로 판정을 돌려주는 가짜 어댑터."""

    name = "mock"

    def __init__(self, script: Optional[str] = None, delay_ms: int = 0):
        raw = script or os.getenv("MOCK_SCRIPT") or DEFAULT_SCRIPT
        self.script = [s.strip().upper() for s in raw.split(",") if s.strip()]
        if not self.script:
            self.script = [DEFAULT_SCRIPT]
        self.delay_ms = int(os.getenv("MOCK_DELAY_MS", delay_ms))
        self.model = f"mock[{','.join(self.script)}]"
        self._i = 0

    def reset(self) -> None:
        """순환 위치를 처음으로. eval.py 가 실행마다 부릅니다.

        --mode both 는 같은 어댑터로 두 번 도는데, 초기화하지 않으면
        2장 실행과 1장 실행이 서로 다른 스크립트 구간을 쓰게 되어
        비교 자체가 성립하지 않습니다.
        """
        self._i = 0

    def judge(
        self,
        system: str,
        user_text: str,
        start_b64: Optional[str],
        current_b64: str,
    ) -> Verdict:
        token = self.script[self._i % len(self.script)]
        self._i += 1

        if self.delay_ms:
            time.sleep(self.delay_ms / 1000)

        if token == "GARBAGE":
            # 실제 파서를 태운다. 폴백이 CANNOT_TELL 인지까지 함께 검증된다.
            return coerce_verdict(GARBAGE_RAW, self.delay_ms)

        reason = {"DONE": "VISIBLE_CHANGE",
                  "NOT_DONE": "NO_CHANGE"}.get(token, "TARGET_NOT_VISIBLE")
        return coerce_verdict(
            '{"verdict": "%s", "reasonCode": "%s"}' % (token, reason),
            self.delay_ms)
