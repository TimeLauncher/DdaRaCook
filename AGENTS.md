# AGENTS.md — 따라쿡 협업 규약

기능 요구사항은 [`요리어시스턴트_기능명세서.md`](요리어시스턴트_기능명세서.md)가 정본입니다.
이 문서는 **누가 어디를 건드리는가**만 다룹니다.

## 소유 영역

| 담당 | 경로 |
|---|---|
| **1번** 기기·카메라 | `app/` — **2번과 공동 소유** |
| **2번** 앱·상태·음성 | `app/` — **1번과 공동 소유** |
| **3번** AI·서버·평가 | `server/` · **레시피 정의**(`app/`의 `RecipeFixtures.kt` — 아래 경계 표 참조) |

⚠️ **`app/`은 경로로 나누지 않습니다.** 그래서 **무엇을 건드릴지 먼저 알리고** 작업합니다. 아래는 소유가 아니라 **주로 누가 보는가**입니다.

| 주로 | 무엇 |
|---|---|
| **1번** | `camera/` · DAT 연동 · **이미지 정규화**(`judgment/ImageNormalizer.kt` — 위치는 `camera/` 밖이지만 담당은 1번, 아래 경계 표 참조) |
| **2번** | 화면 · 상태 머신 · 검사 스케줄러 · 음성(STT/TTS) · Room |
| **3번** | 레시피 정의(`RecipeFixtures.kt`) · 판정 계약 매핑(`judgment/JudgeApiService.kt`의 `toServerType()` · `shouldSendStartImage()`) |

**단독 변경 금지** — `CONTRACT.md` · `요리어시스턴트_기능명세서.md` · `AGENTS.md` · `.gitattributes` · `.gitignore` · `*.gradle.kts`

## 경계

| 경계 | 규정 문서 | 규칙 |
|---|---|---|
| 촬영 **1↔2** | `camera/CameraContracts.kt` | Meta DAT 타입은 `camera/` 밖으로 나가지 않음. 모든 실패는 `CaptureOutcome.Failure`로만 전달 |
| 판정 **2↔3** | [`CONTRACT.md`](CONTRACT.md) | 코드가 계약을 따름. 변경은 3명 합의 → 버전 올림 → 코드 수정 순서. `CheckType` 값 **추가**는 4곳을 함께(앱 enum · `toServerType()` · 서버 `Literal` · `CHECK_TYPE_HINT`), 값 **이름 변경·삭제는 금지** — 저장된 사용자 레시피가 파싱에 실패해 조용히 사라집니다 |
| 레시피 **2↔3** | 기능명세서 §5.1.1 | 완료 조건 문장·판정 유형의 정본은 §5.1.1 표. `RecipeFixtures.kt`는 그 표를 옮긴 데이터이며 **3번 소유**(로직 없음, 참조처는 `CookingSessionViewModel`뿐). **문장 수정은 3번 단독**, `checkType`·단계 수·`InspectionPolicy` 변경은 2번과 합의 — `FakeJudgmentGatewayTest`가 단계 인덱스와 유형을 단언합니다. 편집기 UI(`MainActivity`)·저장(`AppPersistence`)·네트워크 배관은 2번 |
| 이미지 **1↔3** | [`CONTRACT.md`](CONTRACT.md) §3.3 · [`server/이미지규격_요청서.md`](server/이미지규격_요청서.md) | JPEG q80 · **위 40% 제거 후 긴 변 1024px** · 회전을 픽셀에 반영 후 EXIF 제거. 정규화는 1번에서 1회만 |

## 규칙

- 내 담당 밖을 건드려야 하면 **먼저 알리고** 작업합니다. `app/` 안(1↔2)은 알리는 것으로 충분하고, `app/`↔`server/`를 넘을 때는 브랜치 + PR로 처리합니다. 권한이 애매하면 직접 고치지 말고 점검 문서에 기록해 넘깁니다
- **비밀값을 소스에 쓰지 않습니다.** 앱은 `local.properties` → `BuildConfig`, 서버는 `.env` / Render 환경변수
- push 전 `bash server/check_secrets.sh`
- `CONTRACT.md`와 코드가 다르면 **코드를 고치거나 불일치를 보고**합니다. 계약을 임의로 수정하지 않습니다
- **`RecipeFixtures.kt`를 고치면 `AppPersistence.CURRENT_FIXTURE_VERSION`을 함께 +1** 합니다. 안 올리면 기존 설치본은 저장된 옛 레시피를 계속 씁니다 — 빌드도 테스트도 통과해 **증상이 없습니다**
- 단계 **수**를 바꾼 레시피 변경은 저장된 세션이 `order` 기준으로 어긋납니다. 앱 데이터를 지우고 확인합니다. `InspectionPolicy`의 `earliestCheckSeconds`·`checkIntervalSeconds`는 런타임에 30초로 덮이므로(`withAutomaticInspectionInterval`) `helperText` 문구를 실제와 맞춥니다
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

`main` 반영은 PR로. **`app/`을 건드린 PR은 1번·2번이 서로 리뷰**하고, `app/`↔`server/`를 넘은 PR은 해당 담당자 리뷰를 받습니다.
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
| [`server/README.md`](server/README.md) · [`server/DEPLOY.md`](server/DEPLOY.md) | 서버 실행 · 배포 · 트러블슈팅 |
