# DAT 교체 지점

현재 앱은 `FakeWearableCameraGateway`를 사용한다.

교체 위치:
- `app/src/main/java/com/example/myapplication/camera/CameraContracts.kt`
- `app/src/main/java/com/example/myapplication/camera/FakeWearableCameraGateway.kt`
- `app/src/main/java/com/example/myapplication/CookingSessionViewModel.kt`

교체 방법:
1. 실기기 검증이 끝난 DAT 구현이 `WearableCameraGateway`를 구현한다.
2. `CookingSessionViewModel`의 `cameraGateway` 생성부를 Fake에서 실제 구현으로 교체한다.
3. 앱 상태 머신은 그대로 둔다.
4. Meta DAT 타입 import는 `camera` 패키지 내부에만 둔다.
5. 앱 화면, 자동 검사 예약, TTS/STT, JudgmentGateway는 변경하지 않는다.

교체 시 지켜야 할 점:
- 평소 카메라는 OFF 상태를 유지한다.
- 검사 시점에만 `capture(request)`를 호출한다.
- 한 번에 하나의 촬영만 허용한다.
- BUSY, 취소, 연결 끊김, 실패는 `CaptureOutcome.Failure`로만 전달한다.
- 앱 레이어에서 Meta SDK 타입을 직접 다루지 않는다.
