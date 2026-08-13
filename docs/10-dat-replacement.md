# DAT 카메라 통합 현황

상태: `CODE_COMPLETE_DEVICE_PENDING`
기준: Meta Wearables DAT Android SDK `0.9.0`

기존 `FakeWearableCameraGateway`를 유지하면서 실제 앱의 기본 경로를
`DatWearableCameraGateway`로 교체했다. `USE_FAKE_CAMERA=true`를 지정한 디버그 빌드만
Fake 경로를 사용한다.

## 구현된 범위

- S4 진입 시에만 Android Bluetooth 권한을 확인하고 DAT를 초기화한다.
- Meta 등록, 활성 안경 탐색, 기기 세션 시작, wearable 카메라 권한을 S4 상태에 연결한다.
- 각 검사 요청은 검증된 `MEDIUM · 24 FPS` 설정으로 짧은 스트림을 시작한다.
- 첫 프레임 수신 뒤 `capturePhoto()`를 호출하고 PNG 캐시 `content://` URI를 반환한다.
- 한 번에 한 촬영만 허용하며 시작·촬영·정리에 각각 타임아웃을 둔다.
- 성공, 실패, 취소 모두 `removeCamera()`와 foreground service 종료를 수행한다.
- 스트림/촬영/정리 lifecycle 타임아웃 뒤에는 같은 세션의 자동 재시도를 막고 수동 모드로 전환한다.
- 단계 시작 baseline 촬영과 예약된 inspection 촬영은 기존 요리 상태 머신에 그대로 연결된다.
- 세션 종료 또는 폐기 시 Gateway가 소유한 캐시 이미지를 삭제한다.

Meta SDK 타입은 `camera` 패키지 안에만 있다. UI와 요리 상태 머신은
`WearableCameraGateway`, `CaptureOutcome`, `WearableCameraState` 계약만 사용한다.

## 로컬 설정

DAT 의존성을 새 환경에서 내려받으려면 추적하지 않는 루트 `local.properties`에 다음 값을
설정하거나 동일한 이름의 환경 변수를 사용한다.

```properties
github_token=<GitHub Packages read token>
USE_FAKE_CAMERA=false
```

Developer Mode는 manifest의 공식 sentinel 값 `0`을 사용한다. 운영용 App ID와 Client Token은
별도 합의 전까지 소스에 넣지 않는다.

## 검증 기록

- `assembleDebug`: 성공
- `testDebugUnitTest`: 11개 성공, 실패 0
- `lintDebug`: 성공
- 연결 휴대폰 `SM-S938N`: 계측 3개 성공, 실패 0
- 앱 시작 시 DAT 초기화 전 예외가 발생하지 않으며 `WearablesException`이 없음을 로그로 확인

## 남은 실기기 체크포인트

1. 앱에서 요리 시작 후 S4로 진입한다.
2. Android Bluetooth 권한, Meta 등록, wearable 카메라 권한을 순서대로 완료한다.
3. 상태가 `준비 완료`가 되는지 확인한다.
4. 1단계를 시작해 baseline 촬영음이 한 번만 나는지 확인한다.
5. 예약 검사 시점에 자동 촬영이 한 번 실행되고 이후 안경 카메라 표시등이 꺼지는지 확인한다.
6. 실패 시 화면 오류 문구와 `DatCameraGateway` 로그만 공유한다. 이미지 원본과 토큰은 공유하지 않는다.

실기기 자동 baseline/inspection 성공을 확인하기 전에는 이 통합을 `DONE`으로 표시하지 않는다.
