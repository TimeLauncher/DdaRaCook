# YOLO crop 평가 메모

평가일: 2026-08-25

## 현재 최선 설정

- 평가 모델: `yoloe-26n-seg.pt`
- 운영 모델: `models/yoloe-26n-cook-roi.onnx` (아래 prompt를 고정해 export)
- text prompts
  - 도마: `cutting board`, `chopping board`
  - 팬: `frying pan`, `skillet`, `cooking pan`, `wok`
- 추론 후보 confidence: `0.05`
- 활성 대상: `(0.5, 0.65)`에 가장 가까운 기대 클래스 후보
- 안전 gate: 시선 기준점과의 정규화 거리 `0.30` 초과 시 검출을 버리고 bottom-60 fallback
- crop: bbox 바깥 추가 여백 없음(0%), 도마 4:3, 팬 1:1, 긴 변 1024, JPEG q80

2026-08-25 폰 크롭 실사용 확인 후 22% → 10%로 축소했으나 여전히 넓다는 사용자 확인에
따라 추가 여백을 0%로 제거했다. 출력 비율을 맞추기 위한 확장만 허용하며 검출 bbox보다
작게 잘라내지는 않는다.

## 32장 결과

| 지표 | 결과 |
|---|---:|
| bbox precision / recall, IoU 0.5 | 70.0% / 84.5% |
| 엄격한 활성 bbox 적중 | 25/32 (78.1%) |
| 활성 객체와 실질적으로 겹친 검출 | 28/32 (87.5%) |
| ROI crop 적용 | 28/32 |
| bottom-60 fallback | 4/32 (12.5%) |
| ROI crop의 활성 객체 95% 포함 | 28/28 (100%) |
| fallback 포함 최종 이미지의 활성 객체 95% 포함 | 32/32 (100%) |

도마는 9장 중 4장이 fallback이고, 팬 23장은 모두 ROI crop을 적용했다.
가장 작은 YOLOE-26n에서 동의어 prompt가 최선이었다. YOLOE-26s는 bbox recall은
올랐지만 활성 대상 선택률이 낮아졌고, 합성 visual prompt는 유효한 bbox를 만들지 못했다.

운영 ONNX의 로컬 CPU 추론은 32장 평균 73.2ms, 중앙값 73.1ms, 최대 93.0ms였다.

## 판단

사용자 결정에 따라 다른 주방의 독립 평가셋 수집은 뒤로 미루고 서버 ONNX 방식으로 통합한다.
앱은 전체 시야를 보내고 레시피 단계가 도마/팬 target을 지정한다. 서버는 대상 검출 실패,
모델 로드 실패, 거리 gate 탈락 시 기존 bottom-60 방식으로 안전하게 fallback한다.

기존 앱은 `cropTarget`을 보내지 않으므로 서버가 이미 정규화된 이미지를 그대로 통과시킨다.
새 앱의 수동 갤러리 판정은 `NO_CROP`을 보내며, 자동 판정만 서버 crop을 사용한다.

재현 명령과 원본 결과는 `test-images/crop-eval/`에 있으며 이 경로는 로컬 전용이다.
