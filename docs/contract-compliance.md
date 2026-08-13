# API 계약 준수 점검 — `/judge-step`

기준 문서: 루트 `CONTRACT.md` v1.1 (2026-08-10)  
점검 기준일: 2026-08-12  
점검 대상: 머지 커밋 `7b31b63` 시점의 `app/` · `server/` 소스  
점검자: 3번

2026-08-13 재점검: 위반 8건 해결 · 권고 1건 이행. 아래 내용은 발견 당시 원인과 수정 근거를 보존한 이력이다.

---

## ⚠️ 먼저 읽을 것 — 앱 쪽 위반은 지금 "잠복" 상태입니다

`JudgeApiService`는 **저장소 어디에서도 호출되지 않습니다.** 참조가 정의부 한 줄뿐입니다.

```kotlin
// CookingSessionViewModel.kt:31
private val judgmentGateway = FakeJudgmentGateway()   // 하드코딩, 교체 지점 없음

// CookingSessionUiState.kt:38
val useMockJudgment: Boolean = true                   // 기본값 true
```

판정은 전부 앱 안의 Fake가 만들어냅니다. 즉 **현재 상태는 "2번과 3번 코드가 같은 저장소에 있다"이지 "연결됐다"가 아닙니다.**
(`docs/implementation-checklist.md`에도 *"실제 촬영 이미지의 서버 판정 — Fake 판정만 현재 조리 흐름에 연결됨"*으로 이미 기록돼 있습니다.)

**아래 1~6번은 배선하는 순간 동시에 살아납니다.** 배선 작업과 함께 처리하는 것을 권합니다.

## 표시 기준

- **위반**: 계약서가 명시한 동작과 코드가 다름
- **권고 미이행**: 계약서가 `💡`로 권한 사항을 따르지 않음 (위반 아님)
- 행 번호는 점검 기준일의 소스 기준

---

## A. 2번(앱) 담당 — 6건

### [x] A-1. `STATE_CHANGE`에도 `startImage`를 보냄 · §3.2

**위치** `JudgeApiService.kt:79`

```kotlin
if (step.checkType == CheckType.COLOR_CHANGE || step.checkType == CheckType.STATE_TRANSITION) {
    put("startImage", SAMPLE_JPEG_BASE64)
}
```

계약 §3.2 표는 `COLOR_CHANGE`만 전송, `PRESENCE`·`IDENTIFY`·`COUNT`·`STATE_CHANGE`는 **null**입니다.

**왜 중요한가** — 이미지 2장이면 페이로드가 2배가 되어 왕복 3초 목표(기능명세서 12절 #4)를 압박합니다. 계약서 82번째 줄에 이 이유가 적혀 있습니다. 3번 서버의 지연 예산에 직접 영향이 갑니다.

**고치는 법** — 조건에서 `STATE_TRANSITION`을 제거.

---

### [x] A-2. `checkType`에 `COUNT`가 없음 · §3.1

**위치** `RecipeModels.kt:46-51` (enum 5종) · `JudgeApiService.kt:150-158` (매핑)

계약 §3.1은 6종입니다: `PRESENCE` · `COUNT` · `IDENTIFY` · `COLOR_CHANGE` · `STATE_CHANGE` · `TIME_ONLY`.
앱에는 `COUNT`가 없어 "만두 6개가 다 올라갔는가" 같은 단계를 표현할 수 없습니다.

**참고** — 서버는 이미 `COUNT`를 받을 준비가 돼 있습니다 (`server.py:51-52`).

**고치는 법** — `CheckType`에 `COUNT` 추가 후 `toServerType()`에 `COUNT -> "COUNT"` 매핑.

---

### [x] A-3. 모르는 `reasonCode`를 `NETWORK_RETRY`로 처리 · §4

**위치** `JudgeApiService.kt:173`

```kotlin
else -> ReasonCode.NETWORK_RETRY
```

계약 §4(120번째 줄)는 **"미지의 값은 `OTHER`로 처리하세요"** 라고 명시합니다.

**영향 범위** — 판정 흐름은 `verdict`로 돌아가므로 카운터가 섞이지는 **않습니다.** 다만 200 응답이 로그에 "네트워크 재시도"로 찍혀, 나중에 평가 로그를 볼 때 실제 네트워크 실패와 구분되지 않습니다.

**고치는 법** — `else -> ReasonCode.OTHER`

> 덧붙임: 앱 `ReasonCode` enum의 `CONDITION_NOT_MET`, `MODEL_UNCERTAIN`은 **서버가 보내지 않는 값**입니다 (`server.py:49-50`, `judge/base.py:27`에서 5종으로 고정). 죽은 분기이니 계약에 없는 값을 기대하고 있는 게 아닌지 확인 바랍니다.

---

### [x] A-4. 429에 백오프 재시도가 없음 · §5

**위치** `JudgeApiService.kt:99`

```kotlin
val retryable = responseCode == 503
```

계약 §5는 `429 → 백오프 후 재시도`인데, 429가 `retryable`에서 빠져 `shouldRetry = false`로 나갑니다. 호출자에게 잘못된 신호를 줍니다.

**왜 중요한가** — 무료 티어라 429는 실제로 납니다. 서버 쪽에도 대응 코드가 있습니다 (`judge/base.py:76-77`, `judge/nemotron.py:188`).

**고치는 법** — 429를 재시도 대상에 포함하되 즉시 재시도 대신 지연을 둘 것. 503과 달리 곧바로 다시 때리면 한도를 더 깎습니다.

---

### [x] A-5. `X-Mock-Status` · `X-Mock-Delay-Ms` 미구현 · §6 ⭐ 최우선

**위치** `JudgeApiService.kt:65-67` — `X-Mock-Verdict`만 설정합니다.

계약 v1.1에서 추가한 두 헤더가 앱에 반영되지 않았습니다. 그 결과 **계약서 §6이 "🚨 반드시 확인해야 할 것"으로 지정한 검증을 실행할 수 없습니다:**

```
X-Mock-Verdict: CANNOT_TELL  ×3  →  수동 모드 전환되어야 함    (F4-5 정상)
X-Mock-Status:  503          ×3  →  수동 모드 전환되면 안 됨 ❗
                                    "연결이 불안정해요" 후 단계 유지
```

두 번째가 수동 모드로 넘어가면 카운터가 합쳐진 것이고, 실제 서비스에서 **와이파이가 잠깐 끊긴 것만으로 사용자가 수동 모드로 쫓겨납니다.** 503은 실측상 드물지 않습니다 (`server/notes/nemotron.md` §8).

**현재 상태** — 카운터 분리 **로직 자체는 올바릅니다.** `CookingSessionViewModel.kt:509-528`의 `Failure` 분기가 `cannotTellStreak`을 건드리지 않고 `WAITING_FOR_CHECK`로 돌아갑니다. 다만 Fake 게이트웨이 상대로만 검증됐고, **진짜 503으로는 아직 확인되지 않았습니다.**

**고치는 법** — 두 헤더를 파라미터로 받아 설정. 이게 되어야 A-1~A-4 수정도 검증할 수 있으므로 **가장 먼저 처리하는 것을 권합니다.**

---

### [x] A-6. 토큰 하드코딩 · §2

**위치** `JudgeApiService.kt:12`

계약 §2(37번째 줄): *"토큰은 `local.properties` / 환경변수에 두고 **커밋하지 않습니다.**"*

**⚠️ 이미 발생한 사고입니다.** 실제 `TEAM_TOKEN`이 커밋 `0acb1e5`로 이력에 들어갔습니다.

**3번 조치 완료 (2026-08-12)** — Render 대시보드에서 토큰을 교체했고, 옛 토큰이 403을 받는 것까지 확인했습니다. **유출된 값은 더 이상 아무 문도 열지 못합니다.** `NVIDIA_API_KEY`는 유출되지 않았습니다(이력 전체 확인, 자리표시자뿐).

**아직 남은 위험** — 하드코딩 **구조**가 그대로입니다. 새 토큰을 같은 자리에 넣고 커밋하면 처음으로 돌아갑니다.

**고치는 법** — `local.properties`(이미 `.gitignore:15`에 등재) → `BuildConfig` 경유로 주입. 새 토큰은 git이 아닌 경로(DM 등)로 받으세요.

> 재발 방지로 `server/check_secrets.sh`를 보강했습니다. 이제 `.env`의 실제 값이 추적 파일이나 커밋 이력에 나타나는지 저장소 전체에서 검사합니다. push 전에 `bash server/check_secrets.sh`를 돌려주세요.

---

## B. 3번(서버) 담당 — 2건

### [x] B-1. 요청 형식 오류가 400이 아니라 422 · §5

**위치** `server.py:191-199`

```python
def judge_step(
    req: JudgeRequest,                              # ← 여기서 실패하면 422
    authorization: Optional[str] = Header(None),
):
    check_auth(authorization)                       # ← 여기까지 오지 못함
```

Pydantic 본문 검증이 함수 진입 **전에** 실행되어, 스키마 오류는 422로 나갑니다. 계약 §5 표에는 422 행이 없고 "400 = 요청 형식 오류"로 약속돼 있습니다.

부수 효과로 **인증되지 않은 요청에도 스키마 오류가 응답됩니다.** 토큰 없이 필드 구조를 알아낼 수 있습니다.

이미지 관련 400(`require_jpeg`, `server.py:105-113`)은 `check_auth` 이후라 정상입니다. **스키마 검증만 샙니다.**

### [x] B-2. 422 응답 본문이 계약과 다른 형태 · §5

계약 §5는 `{"detail": "사람이 읽을 수 있는 오류 설명"}` — **문자열**입니다.
FastAPI 기본 422는 `{"detail": [ {...}, {...} ]}` — **배열**입니다.

**B-1·B-2 권장 해법** — `RequestValidationError` 핸들러를 추가해 400 + 문자열 `detail`로 변환. 계약서는 이미 2번에게 배포된 문서이므로 **코드를 계약에 맞추는 쪽**이 낫습니다.

---

## C. 권고 이행 — 1건

### [x] C-1. `BASE_URL`이 하드코딩 · §1 💡

**위치** `JudgeApiService.kt:11`

계약 §1(25~26번째 줄)은 *"BASE_URL을 디버그 화면에서 바꿀 수 있게 만들어두세요. 로컬 IP와 배포 URL을 계속 오가게 됩니다"* 라고 권합니다. `private const val`이라 재빌드 없이는 못 바꿉니다.

---

## D. 지켜지고 있는 것

과잉 대응을 막기 위해 확인된 항목도 남깁니다.

- [x] **네트워크 실패 / `CANNOT_TELL` 카운터 분리** — §5의 가장 중요한 규칙. 로직상 올바름 (A-5의 단서 참조)
- [x] `vlmLatencyMs` / `roundTripMs` 분리 — §4의 경고대로 이름이 나뉘어 있음
- [x] 503 · 타임아웃 각 1회 재시도 — §5 표와 일치
- [x] 앱 8초 / 서버 7.5초 타임아웃 배치 — §5 의도대로
- [x] 요청 9개 필드 · 응답 5개 필드 모두 계약과 일치
- [x] 서버 `ReasonCode` 5종이 §4와 정확히 일치
- [x] `TIME_ONLY` 요청을 서버가 400으로 거부 (`server.py:238-242`) — 앱이 실수로 보내도 방어됨
- [x] `Authorization` 401/403 분기 — 실측 확인 (401·403·200 모두 기대대로)

---

## E. 처리 순서 제안

1. **A-5** (mock 헤더) — 나머지를 검증할 수단이므로 먼저
2. **A-6** (토큰 구조) — 배선 전에 해야 재발이 없음
3. **B-1/B-2** (422→400) — 3번, 앱 배선과 무관하게 지금 가능
4. **A-1 ~ A-4** — 배선 작업과 함께
5. **C-1** — 여유 있을 때

## F. 수정 후 검증 방법

A-5가 끝나면 계약 §6의 검증을 실제로 돌릴 수 있습니다.

```bash
# 카운터 분리 — 이게 핵심
X-Mock-Verdict: CANNOT_TELL  ×3   →  수동 모드 O
X-Mock-Status:  503          ×3   →  수동 모드 X  ← 넘어가면 카운터가 합쳐진 것

# A-1 검증: STATE_CHANGE 요청에 startImage 가 없어야 함
# A-2 검증: COUNT 단계가 400 없이 통과해야 함
# A-4 검증: X-Mock-Status: 429 → 지연 후 재시도가 관측되어야 함
```

서버 직접 확인 (3번):

```bash
curl -s https://ddaracook-server.onrender.com/health
# {"status":"ok","backend":"nemotron","realJudgeReady":true, ...}
```

> 무료 티어라 15분 무응답 후 첫 요청은 30초 이상 걸립니다. 느린 것은 실패가 아닙니다.

---

## 부록. 이 점검에서 함께 처리한 저장소 정리

계약과 무관하지만 같은 점검에서 나온 것들입니다.

- `server/check_secrets.sh` — 과거 커밋 검사가 경로 불일치로 **항상 통과하던 버그** 수정. `git log --name-only`는 저장소 루트 기준 경로를 내는데 `grep -x "\.env"`로 비교하고 있었습니다. 더해 비밀값 자체를 저장소 전역에서 찾는 검사를 추가했고, 옛 토큰으로 탐지력을 검증했습니다
- `.gitattributes` — `*.sh`·`gradlew`는 LF, `*.bat`은 CRLF 고정. 기존 `* text=auto`만으로는 셸 스크립트가 Windows 체크아웃에서 CRLF가 되어 bash/WSL·Render에서 깨집니다
- `server/DEPLOY.md` — 옛 토큰 2곳 제거
- `server/README.md` · `server/DEPLOY.md` — `server/CONTRACT.md` 삭제로 끊긴 길안내 3곳 정정. **계약서 정본은 저장소 루트 1벌**입니다 (2벌이면 조용히 갈라집니다)
- 루트 `RecipeFixtures.kt` 삭제 — `app/` 쪽과 바이트 단위로 동일한 중복본이었고, 소스셋 밖이라 빌드에 포함되지 않았습니다

### 미처리 (별도 결정 필요)

- `gradlew`가 실행 권한 없이 커밋돼 있음 (`100644`). Mac/Linux에서 `./gradlew`가 `Permission denied`로 실패합니다. `git update-index --chmod=+x gradlew`
- `server.py:37` `DEBUG_MODE` 기본값이 `"true"` — 환경변수 주입 실패 시 위험한 쪽으로 떨어집니다(fail-open). `TEAM_TOKEN`도 동일 (`server.py:38`). `render.yaml`에 값이 명시돼 있어 실동작은 그대로이므로 기본값만 뒤집어도 안전합니다
- `DEBUG_MODE`는 W3 시연 전 `false` 필요 (`DEPLOY.md:116-117`, T3-1). 지금은 헤더 하나로 판정을 조작할 수 있습니다
