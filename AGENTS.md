# AGENTS.md — 따라쿡 협업 규약

기능 요구사항은 [`요리어시스턴트_기능명세서.md`](요리어시스턴트_기능명세서.md)가 정본입니다.
이 문서는 **누가 어디를 건드리는가**만 다룹니다.

> **문서는 정본이 아니라 지침입니다. 정본은 코드입니다.**
> 기능명세서·`CONTRACT.md`는 **참고용**입니다. 문서를 먼저 고치고 코드를 고치는 순서를 지킬 필요 없고,
> 문서와 코드가 어긋났다고 코드를 되돌릴 이유도 없습니다. 바꿨으면 **한 줄로 기록만** 남기세요.
> 문서 업데이트가 개발의 병목이 되면 안 됩니다.
>
> 소유 표는 **누구 허락을 받는가가 아니라 누가 그 코드를 아는가**를 나타냅니다.
> 허가가 필요하면 3번 한 곳에서 받고, 바꿨으면 알리세요.
>
> 다만 [규칙](#규칙)의 기술적 덫(픽스처 버전 · `CheckType` 이름 · 비밀값 · 계약 동시 수정)은 그대로입니다.
> 문서 문제가 아니라 어기면 런타임에 조용히 깨지는 것들이기 때문입니다.

## 소유 영역

| 담당 | 경로 |
|---|---|
| **1번** 기기·카메라 | `app/` — **2번과 공동 소유** |
| **2번** 앱·상태·음성 | `app/` — **1번과 공동 소유** |
| **3번** AI·서버·평가 | `server/` · **레시피 정의**(`app/`의 `RecipeFixtures.kt` — 아래 경계 표 참조) |

⚠️ **`app/`은 경로로 나누지 않습니다.** 그래서 **무엇을 건드렸는지 알립니다** — 사전 승인까지는 필요 없습니다. 아래는 소유가 아니라 **주로 누가 보는가**입니다.

| 주로 | 무엇 |
|---|---|
| **1번** | `camera/` · DAT 연동 · **이미지 정규화**(`judgment/ImageNormalizer.kt` — 위치는 `camera/` 밖이지만 담당은 1번, 아래 경계 표 참조) |
| **2번** | 화면 · 상태 머신 · 검사 스케줄러 · 음성(STT/TTS) · Room |
| **3번** | 레시피 정의(`RecipeFixtures.kt`) · 판정 계약 매핑(`judgment/JudgeApiService.kt`의 `toServerType()` · `shouldSendStartImage()`) |

**공용 파일** — `CONTRACT.md` · `요리어시스턴트_기능명세서.md` · `AGENTS.md` · `.gitattributes` · `.gitignore` · `*.gradle.kts`

자유롭게 고치되 **무엇을 왜 바꿨는지 PR에 한 줄** 남깁니다. `CONTRACT.md`의 요청·응답 필드는 문서가 아니라 **배선**이라, 바꾸면 앱과 서버 코드를 같이 고칩니다.

## 경계

| 경계 | 규정 문서 | 규칙 |
|---|---|---|
| 촬영 **1↔2** | `camera/CameraContracts.kt` | Meta DAT 타입은 `camera/` 밖으로 나가지 않음. 모든 실패는 `CaptureOutcome.Failure`로만 전달 |
| 판정 **2↔3** | [`CONTRACT.md`](CONTRACT.md) | 요청·응답 필드를 바꾸면 **앱과 서버를 같은 PR에서** 고칩니다 — 한쪽만 배포하면 깨집니다. `CheckType` 값 **추가**는 4곳을 함께(앱 enum · `toServerType()` · 서버 `Literal` · `CHECK_TYPE_HINT`), 값 **이름 변경·삭제는 금지** — 저장된 사용자 레시피가 파싱에 실패해 조용히 사라집니다 |
| 레시피 **2↔3** | `RecipeFixtures.kt` | **레시피의 정본은 코드입니다.** 문장·`checkType`·단계 수·`InspectionPolicy` 모두 3번이 정하고, 기능명세서 §5.1.1 표는 참고용이라 나중에 따라오면 됩니다. 단계 수나 인덱스를 바꾸면 `FakeJudgmentGatewayTest`의 단언과 저장된 세션이 함께 어긋나니 그 둘을 같이 고칩니다. 편집기 UI(`MainActivity`)·저장(`AppPersistence`)·네트워크 배관은 2번 |
| 이미지 **1↔3** | [`CONTRACT.md`](CONTRACT.md) §3.3 | JPEG q80 · **위 40% 제거 후 긴 변 1024px** · 회전을 픽셀에 반영 후 EXIF 제거. 정규화는 1번에서 1회만(`judgment/ImageNormalizer.kt`). 규격 감사는 `server/inspect_image.py` |

내 담당 밖을 건드려야 하면 **3번 허가를 받고 진행**합니다. 담당자 동의를 기다리지 않고, 바꾼 뒤 알립니다. `app/`↔`server/`를 넘을 때는 브랜치 + PR로 처리합니다. 권한이 애매하면 멈추지 말고 3번에게 물어본 뒤 진행합니다.

## 규칙

여기 있는 것만 지키면 됩니다. 문서 절차가 아니라, **어기면 빌드도 테스트도 통과하면서 런타임에 조용히 깨지는** 것들입니다.

- **비밀값을 소스에 쓰지 않습니다.** 앱은 `local.properties` → `BuildConfig`, 서버는 `.env` / Render 환경변수
- push 전 `bash server/check_secrets.sh`
- **`RecipeFixtures.kt`를 고치면 `AppPersistence.CURRENT_FIXTURE_VERSION`을 함께 +1** 합니다. 안 올리면 기존 설치본은 저장된 옛 레시피를 계속 씁니다 — 빌드도 테스트도 통과해 **증상이 없습니다**
- 단계 **수**를 바꾼 레시피 변경은 저장된 세션이 `order` 기준으로 어긋납니다. 앱 데이터를 지우고 확인합니다
- `InspectionPolicy`의 `earliestCheckSeconds`·`checkIntervalSeconds`는 런타임에 30초로 덮입니다(`withAutomaticInspectionInterval`). fixture에 적은 값이 그대로 쓰이지 않으니, 실제로 조절되는 `requiredConsecutiveDone`·`burstSeconds`·`maxExpectedSeconds`로 판단하세요
- 단계 안내를 **손으로 쓴 문자열로 화면에 내보내지 않습니다.** 조리 화면의 "판정 기준" 카드가 `checkCondition`·`checkType`·`InspectionPolicy` 런타임 값에서 문구를 만듭니다 — 그래야 실제 동작과 어긋날 수 없습니다
- Fake 구현(`FakeWearableCameraGateway` · `FakeJudgmentGateway`)을 실제 구현으로 착각하지 않습니다. 앱은 기본값이 실제 서버 호출입니다(`useMockJudgment = false`)
- 기능 ID(`F4-3` 등)는 기능명세서에서 확인한 뒤 인용합니다

## Git

각자 **상시 개인 브랜치**에서 작업하고 `main`에 머지합니다. 기능 단위가 아니라 사람 단위입니다.

| 담당 | 브랜치 |
|---|---|
| **1번** 건호 | `dev/keonho` |
| **2번** 요한 | `dev/yohan` |
| **3번** 윤우 | `dev/yoonwoo` |

개인 브랜치는 수명이 길어 드리프트가 쌓입니다. **받기 → 커밋 → 올리기** 순서를 지키세요.

```bash
git checkout dev/<내이름>
git merge main                  # ① 받기 — 자주 할수록 충돌이 작다
git add -A && git commit
bash server/check_secrets.sh    # ② push 전 필수
git push                        # ③ 올리기
```

`main` 반영은 PR로. **머지 승인은 3번이 합니다.** 다른 담당자의 리뷰는 받으면 좋지만 대기 조건이 아닙니다 — 무엇을 왜 바꿨는지 PR에 적고 머지한 뒤 알립니다. 시연이 막히는 것보다 낫습니다.
레시피 변경 PR은 **3번이 덮어쓰기 설치로 직접 확인한 뒤** 머지합니다 — 위 `CURRENT_FIXTURE_VERSION` 함정은 코드 리뷰로 보이지 않습니다.

## 빌드·실행

```bash
./gradlew assembleDebug                                     # 앱
./gradlew testDebugUnitTest

cd server && pip install -r requirements.txt                # 서버
python -m uvicorn server:app --reload --host 0.0.0.0 --port 8000
python smoke.py --url http://127.0.0.1:8000                 # 왕복 확인
```

## 문서

| 문서 | 용도 |
|---|---|
| [`요리어시스턴트_기능명세서.md`](요리어시스턴트_기능명세서.md) | 기획 정본 · 역할 분담(§9) · 기능 ID |
| [`CONTRACT.md`](CONTRACT.md) | `/judge-step` 요청·응답·오류·디버그 헤더 |
| [`docs/contract-compliance.md`](docs/contract-compliance.md) | 알려진 위반·미해결 항목 — **작업 시작 전 확인** |
| [`docs/implementation-checklist.md`](docs/implementation-checklist.md) | 명세서 대비 구현 현황 |
| [`docs/10-dat-replacement.md`](docs/10-dat-replacement.md) | Fake 카메라 → 실제 DAT 교체 절차 (1번) |
| [`server/README.md`](server/README.md) | 서버 진입 문서 — 상태 · 구조 · 실행 · 배포 · 덫 (3번은 작업 전 필독) |
