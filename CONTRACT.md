# API 계약서 · `/judge-step`

> **이 문서는 지침입니다. 다만 여기 적힌 필드·URL·오류 코드는 앱과 서버가 동시에 맞아야 하는 배선입니다.**
> 바꾸는 건 자유지만 **한쪽만 바꾸면 런타임에 깨집니다** — 앱과 서버를 같은 PR에서 고치고, 아래 변경 이력에 한 줄 남기세요.
> 담당: 3번 · 사용: 2번(앱) · 버전 1.11

> ✅ **2026-08-10 (T1-4): 실제 AI 판정이 연결되었습니다.**
> Mock 헤더(§6)는 그대로 살아 있으니 기존 테스트는 계속 쓰시면 됩니다.
> 헤더를 빼면 진짜 판정이 옵니다 — 이때는 **진짜 JPEG**를 보내야 합니다(§5의 400 참조).
> v1.6의 `cropTarget`은 선택 필드입니다. 보내지 않는 기존 앱의 이미지는 서버가 다시 크롭하지 않습니다.

---

## 1. 엔드포인트

```
POST  {BASE_URL}/judge-step
POST  {BASE_URL}/debug/crop-preview  # 인증 필요 · VLM 없이 서버 YOLO 결과 확인
POST  {BASE_URL}/extract-recipe
GET   {BASE_URL}/health          # 배포 확인 · 시연 전 워밍업
```

| 환경 | BASE_URL |
|---|---|
| 로컬 | `http://<PC의 LAN IP>:8000` |
| 배포 | **`https://ddaracook-server.onrender.com`** ← 이걸 쓰세요 |

> 💡 앱에 BASE_URL을 **디버그 화면에서 바꿀 수 있게** 만들어두세요.
> 로컬 IP와 배포 URL을 계속 오가게 됩니다.

---

## 2. 공통 헤더

| 헤더 | 필수 | 값 |
|---|---|---|
| `Content-Type` | ✅ | `application/json` |
| `Authorization` | ✅ | `Bearer <TEAM_TOKEN>` |

토큰은 `local.properties` / 환경변수에 두고 **커밋하지 않습니다.**

---

## 3. 요청

```json
{
  "requestId": "550e8400-e29b-41d4-a716-446655440000",
  "recipeId": "kimchi-fried-rice",
  "stepOrder": 3,
  "instruction": "양파를 투명해질 때까지 볶으세요",
  "checkType": "COLOR_CHANGE",
  "checkCondition": "팬 안의 양파가 흰색/불투명에서 반투명하게 변했는가",
  "elapsedSeconds": 260,
  "startImage": "<base64 JPEG 또는 null>",
  "currentImage": "<base64 JPEG>",
  "cropTarget": "AUTO_ROI"
}
```

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `requestId` | string | ✅ | UUID. 재시도 시 중복 판별용 |
| `recipeId` | string | ✅ | |
| `stepOrder` | int | ✅ | |
| `instruction` | string | ✅ | 사용자에게 읽어주는 단계 내용 |
| `checkType` | enum | ✅ | 아래 §3.1 |
| `checkCondition` | string | ✅ | LLM에 전달할 완료 조건 |
| `elapsedSeconds` | int | ✅ | 단계 시작 후 경과 **초** |
| `startImage` | string \| **null** | ❌ | base64 JPEG. §3.2 규칙 참조 |
| `currentImage` | string | ✅ | base64 JPEG |
| `cropTarget` | enum | ❌ | `AUTO_ROI` · `CUTTING_BOARD_ROI` · `PAN_COOKING_ROI` · `LEGACY_BOTTOM_60` · `NO_CROP`. 아래 §3.3 참조. 생략하면 기존 앱이 이미 정규화한 이미지로 간주 |

### 3.1 `checkType` 값

`PRESENCE` · `COUNT` · `IDENTIFY` · `COLOR_CHANGE` · `STATE_CHANGE` · `TIME_ONLY`

> `TIME_ONLY` 단계는 **서버를 호출하지 않습니다.** 2번이 로컬 타이머로만 처리합니다.

### 3.2 ⭐ `startImage` 전송 규칙

**기준은 판정 유형이 아니라 완료 조건입니다.**

| 완료 조건이 묻는 것 | `startImage` | 예 |
|---|---|---|
| **시작 시점 대비 변화** (상대 판정) | **전송** | "시작 시점 사진과 비교해 양파가 조금이라도 더 반투명해졌는가" |
| 현재 상태만 (절대 판정) | **null** | "도마 위에 통째로 남은 야채 덩어리가 없는가" |

판단은 레시피가 내리고 앱은 그대로 따릅니다 — `RecipeStep.needsStartImage`.

**기준 사진 촬영 시점**: 상대 판정 단계는 단계 시작 **직후가 아니라 15초 뒤**에 찍습니다.
안내 직후에는 아직 재료가 팬에 들어가지 않아 빈 팬이 기준이 되기 때문입니다.

> **왜 유형 기준을 버렸나 (v1.2 → v1.3)**
> 쏘야 1단계와 4단계는 둘 다 `STATE_CHANGE` 인데, 1단계는 "덩어리가 없는가"(절대),
> 4단계는 "처음보다 벌어졌는가"(상대)입니다. **유형만으로는 가를 수 없습니다.**
> 유형 기준을 택했던 이유(2장이면 페이로드 2배라 3초를 압박)는 실측에서 근거가 약했습니다 —
> **1장 3511ms vs 2장 3648ms, 차이 137ms.** 지연의 지배 요인은 페이로드가 아니라 추론 시간입니다.

### 3.3 이미지 규격 (1번·3번 공동)

자동 모드 앱은 회전이 반영된 전체 시야에서 폰 YOLO로 관심 영역을 크롭한 뒤
`cropTarget=NO_CROP`으로 전송합니다. 폰 크롭이 실패한 경우에만 전체 시야와 원래
`cropTarget`을 보내 서버 YOLO가 같은 규격으로 fallback합니다.

`AUTO_ROI`는 레시피가 도마·팬을 미리 고르지 않아도 두 대상의 검출 후보를 모두 비교해
시선 기준점에 가장 가까운 것을 선택합니다. 선택된 실제 클래스가 도마면 4:3, 팬·웍이면
1:1로 크롭합니다. 새 사용자 작성·YouTube 추출 레시피의 자동 판정 단계는 이 값을 기본으로
사용하고, 내장 레시피의 명시적인 도마·팬 값은 선택 힌트로 유지합니다.

| 항목 | 자동 모드 카메라 | 수동 모드 |
|---|---|---|
| 앱 전처리 | 전체 프레임 · 긴 변 최대 1365px → 폰 YOLO 크롭 | 크롭 없음 · 긴 변 최대 1024px |
| `cropTarget` | 성공 시 `NO_CROP`, 폰 크롭 실패 시 원래 ROI 값 | `NO_CROP` |
| 최종 규격 | 도마 `768×576` · 팬 `768×768` · fallback은 아래 60% 후 긴 변 최대 768px | 입력 그대로 사용 |
| 포맷 | JPEG, quality 80 | JPEG, quality 80 |
| **회전** | 앱에서 **픽셀에 반영 후 EXIF 제거** | 앱에서 **픽셀에 반영 후 EXIF 제거** |
| 색공간 | sRGB | sRGB |
| 인코딩 | base64 (표준, 줄바꿈 없음) | base64 (표준, 줄바꿈 없음) |

`startImage`와 `currentImage`에는 같은 ROI 규칙을 적용합니다. 폰 YOLO 모델 로드·추론 자체가
실패하면 전체 프레임을 서버에 보내 서버 YOLO로 재시도합니다. 폰 또는 서버에서 시선 기준점
부근에 대상이 없으면 요청을 실패시키지 않고 기존 **아래 60%** 크롭으로 fallback합니다.
`cropTarget`을 생략한 v1.5 이하 앱은 이미 위 40%를 제거한 것으로 간주해 서버에서
byte-for-byte 그대로 사용하므로 이중 크롭되지 않습니다.

---

## 4. 응답 · 성공 (200)

```json
{
  "verdict": "DONE",
  "reasonCode": "VISIBLE_CHANGE",
  "vlmLatencyMs": 1840,
  "promptVersion": "v1",
  "backend": "nemotron",
  "timing": {
    "serverHandlerMs": 1925,
    "validationMs": 3,
    "currentCropMs": 74,
    "startCropMs": 0,
    "cropTotalMs": 74,
    "judgeSetupMs": 0,
    "promptBuildMs": 0,
    "vlmWallMs": 1842,
    "otherMs": 6,
    "currentCropMode": "YOLO_ROI",
    "startCropMode": null,
    "currentDetectionCount": 3,
    "startDetectionCount": null
  }
}
```

| 필드 | 값 | 설명 |
|---|---|---|
| `verdict` | `DONE` / `NOT_DONE` / `CANNOT_TELL` | |
| `reasonCode` | `VISIBLE_CHANGE` / `NO_CHANGE` / `TARGET_NOT_VISIBLE` / `BLURRY` / `OTHER` | |
| `vlmLatencyMs` | int | **서버가 잰 모델 호출 시간** |
| `promptVersion` | string | 평가 추적용 |
| `backend` | string | 어느 모델이 판정했는지 |
| `timing` | object \| null | 서버 구간별 지연과 실제 crop mode. 구버전 호환을 위해 앱은 생략을 허용 |

> ⚠️ **앱은 모르는 `reasonCode` 값이 와도 크래시하지 않아야 합니다.** 미지의 값은 `OTHER`로 처리하세요.

> ⚠️ **`vlmLatencyMs` ≠ 앱의 `roundTripMs`.** 서버가 잰 모델 시간과 앱의 이미지 준비·재시도·HTTP·응답 해석을
> 모두 포함한 총 체감시간은 다른 값입니다. `HTTP 왕복 - timing.serverHandlerMs`는 네트워크뿐 아니라
> FastAPI 본문 파싱과 응답 직렬화도 포함하므로 앱에서는 `전송·프레임워크 추정`으로 표시합니다.

### 4.1 Debug YOLO 크롭 미리보기

`POST /debug/crop-preview`는 `Authorization`이 필요하며 VLM을 호출하지 않습니다.

```json
{ "image": "<base64 JPEG>", "cropTarget": "PAN_COOKING_ROI" }
```

응답은 실제 판정 직전 JPEG인 `croppedImage`와 `cropMode`, `detectionCount`, `width`, `height`,
`timing(serverHandlerMs · validationMs · cropMs · otherMs)`을 반환합니다. 앱은 Debug 빌드에서만 이 기능을 노출합니다.

---

## 5. 응답 · 실패

```json
{ "detail": "사람이 읽을 수 있는 오류 설명" }
```

| 코드 | 의미 | 앱의 대응 |
|---|---|---|
| 400 | 요청 형식 오류 · **이미지가 JPEG가 아니거나 base64가 깨짐** | 버그. 로그 남기고 개발자 확인 |
| 401 | 토큰 없음 | 설정 확인 |
| 403 | 토큰 불일치 | 설정 확인 |
| 429 | 레이트 리밋 (무료 한도) | 백오프 후 재시도 |
| 500 | **서버 설정 오류** (API 키 누락 등) | 재시도해도 안 됨. 3번에게 알릴 것 |
| 503 | **모델 타임아웃 · 모델 서버 오류** | 1회 재시도 → 실패 시 단계 유지 |

> 💡 **400은 대부분 이미지 문제입니다.** 서버가 모델에 보내기 전에 JPEG 매직바이트를
> 검사합니다. `detail` 에 앞 3바이트가 찍히니 그대로 3번에게 보내주세요.
> 이걸 안 잡으면 "판정이 이상하다"로 오해한 채 반나절을 씁니다.

> ⏱️ **503(타임아웃)은 생각보다 자주 납니다.** 실사진 2장 기준 모델 응답이
> 3~4초이고 간헐적으로 7.5초를 넘깁니다(`notes/nemotron.md` §8 실측).
> 서버는 앱보다 짧은 7.5초에서 끊고 503을 돌려줍니다. **이건 `CANNOT_TELL`이 아닙니다.**

### 🚨 가장 중요한 규칙

> **네트워크·서버 오류는 `CANNOT_TELL`이 아닙니다.**
>
> - `CANNOT_TELL` = AI가 사진을 **봤는데** 판단 못 함
> - 네트워크 오류 = AI가 사진을 **보지도 못함**
>
> 이 둘을 섞으면 와이파이가 잠깐 끊겼을 뿐인데 F4-5(3회 연속 → 수동 모드)에 걸립니다.
> **카운터를 반드시 분리하세요.**

| 상황 | 2번의 처리 |
|---|---|
| 타임아웃 (8초) | 1회 재시도 → 실패 시 "연결이 불안정해요" 안내 → 단계 유지 → 다음 검사 예약 |
| 캡처 실패 | 판정 요청 자체를 보내지 않음 |
| `CANNOT_TELL` 카운터 | 별도 관리 (F4-5) |
| 네트워크 실패 카운터 | 별도 관리 |

---

## 6. 🧪 디버그 헤더 (2번 전용)

**AI를 호출하지 않고 원하는 판정을 강제로 받습니다.** `DEBUG_MODE=true` 일 때만 동작합니다.

| 헤더 | 값 | 효과 |
|---|---|---|
| `X-Mock-Verdict` | `DONE` / `NOT_DONE` / `CANNOT_TELL` | 그 판정을 그대로 반환 |
| `X-Mock-Status` | `400` `401` `403` `429` `500` `503` | **그 오류를 그대로 반환** |
| `X-Mock-Delay-Ms` | 정수 (최대 30000) | 지연 흉내 — 위 둘 모두에 적용 |

> `X-Mock-Status` 는 `X-Mock-Verdict` 보다 **우선**합니다. 둘을 같이 보내면 오류가 납니다.
> `X-Mock-Delay-Ms` 와 조합하면 "8초 뒤 503" 같은 상황도 만들 수 있습니다.

```bash
curl -X POST "$BASE_URL/judge-step" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TEAM_TOKEN" \
  -H "X-Mock-Verdict: CANNOT_TELL" \
  -d '{"requestId":"t1","recipeId":"kfr","stepOrder":3,"instruction":"x",
       "checkType":"COLOR_CHANGE","checkCondition":"y","elapsedSeconds":260,
       "currentImage":"AAAA"}'
```

### 이걸로 테스트할 수 있는 것

| 명세서 기능 | 방법 |
|---|---|
| F4-3 연속 DONE 2회 → 자동 진행 | `X-Mock-Verdict: DONE` 2회 |
| F4-5 CANNOT_TELL 3회 → 수동 모드 | `X-Mock-Verdict: CANNOT_TELL` 3회 |
| F4-6 장시간 미완료 안내 | `X-Mock-Verdict: NOT_DONE` 반복 |
| 타임아웃 fallback | `X-Mock-Delay-Ms: 9000` |
| **모델 타임아웃 처리 (503)** | `X-Mock-Status: 503` |
| **레이트 리밋 백오프 (429)** | `X-Mock-Status: 429` |
| **서버 설정 오류 (500)** | `X-Mock-Status: 500` |

> 이미지는 아무 문자열(`"AAAA"`)이어도 됩니다. Mock 경로는 이미지를 보지 않습니다.

### 🚨 이걸로 반드시 확인해야 할 것 — 카운터 분리

§5의 가장 중요한 규칙이 실제로 지켜지는지 검증하는 방법입니다.

```
X-Mock-Verdict: CANNOT_TELL   ×3   → 수동 모드로 전환되어야 함 (F4-5 정상)
X-Mock-Status:  503           ×3   → 수동 모드로 전환되면 안 됨 ❗
                                     "연결이 불안정해요" 안내 후 단계 유지
```

**두 번째가 수동 모드로 넘어가면 카운터가 합쳐져 있는 것입니다.**
실제 서비스에서 와이파이가 잠깐 끊기거나 무료 티어가 느려진 것만으로
사용자가 수동 모드로 쫓겨납니다. 503은 실측상 드물지 않게 발생합니다.

---

## 7. YouTube 레시피 추출

`POST /extract-recipe`는 공개 YouTube 영상의 자막을 읽어 앱 편집기에 넣을 `Recipe` 초안을 만듭니다.
공통 헤더(§2)를 그대로 사용하며, 앱은 응답을 즉시 저장하지 않고 사용자가 검토·수정한 뒤 저장합니다.

요청:

```json
{ "url": "https://www.youtube.com/watch?v=<video-id>" }
```

성공 응답의 `recipe`는 앱의 저장 모델과 같은 필드를 사용합니다. `checkType`만 판정 API 이름
(`PRESENCE` · `COUNT` · `IDENTIFY` · `COLOR_CHANGE` · `STATE_CHANGE` · `TIME_ONLY`)으로 전송하고,
앱이 저장 enum 이름으로 매핑합니다.

```json
{
  "source": {
    "videoId": "...",
    "url": "https://www.youtube.com/watch?v=...",
    "title": "영상 제목",
    "transcriptLanguage": "ko"
  },
  "recipe": {
    "id": "",
    "title": "레시피 제목",
    "ingredients": [{ "name": "양파", "amount": "1개" }],
    "steps": [{
      "order": 1,
      "instruction": "양파를 썬다",
      "checkType": "STATE_CHANGE",
      "checkCondition": "통째로 남은 양파 덩어리가 없는가",
      "needsStartImage": false,
      "inspectionPolicy": {
        "earliestCheckSeconds": 30,
        "checkIntervalSeconds": 30,
        "burstSeconds": 3,
        "requiredConsecutiveDone": 1,
        "maxExpectedSeconds": 180
      },
      "targetIngredients": ["양파"],
      "voicePrompt": "양파를 썰어 주세요.",
      "isAutoCheck": true,
      "parallelTimer": null,
      "waitsForParallelTimer": false,
      "baselineOnStepStart": false
    }],
    "heroNote": "YouTube 자막에서 추출 · 저장 전 확인",
    "isMvpReady": false
  },
  "warnings": ["자막에서 불 세기를 확인할 수 없습니다."]
}
```

추가 실패 코드는 `422`(자막 없음·레시피 정보 부족)입니다. `400`·`401`·`403`·`429`·`500`·`503`은
§5와 같은 형태인 `{ "detail": "사람이 읽을 수 있는 오류 설명" }`으로 반환합니다.

---

## 8. 변경 이력

| 버전 | 날짜 | 변경 |
|---|---|---|
| 1.0 | | 최초 확정 |
| 1.1 | 2026-08-10 | 실제 AI 판정 연결 · 오류 코드 정리(§5) · `X-Mock-Status` 추가(§6) |
| 1.2 | 2026-08-13 | §3.3 이미지 규격 확정 — 위 40% 제거 + 긴 변 1024 |
| 1.3 | 2026-08-14 | **§3.2 개정** — `startImage` 정책을 `checkType` 기준에서 "완료 조건이 시작 대비 변화를 묻는가" 기준으로. 기준 사진은 단계 시작 15초 뒤 촬영 |
| 1.4 | 2026-08-19 | **§3.3 개정** — 수동 모드는 크롭 없이 긴 변 1024px로만 축소. JPEG q80·회전 반영·EXIF 제거·sRGB는 공통 유지 |
| 1.5 | 2026-08-24 | `/extract-recipe` 추가 — YouTube 자막을 현재 앱 `Recipe` 초안으로 변환하고 편집 후 저장 |
| 1.6 | 2026-08-25 | 선택 필드 `cropTarget` 추가 · 자동 카메라는 전체 프레임을 보내고 서버 YOLO가 도마/팬 ROI를 규격화 · 실패 시 기존 아래 60%로 fallback |
| 1.7 | 2026-08-25 | `/debug/crop-preview` 추가 · `/judge-step`에 선택 응답 `timing` 추가 · 앱 총 지연을 전처리/HTTP/서버 검증/YOLO/VLM/기타로 분리 |
| 1.8 | 2026-08-25 | 자동 카메라 ROI 크롭을 폰 ONNX Runtime으로 이동 · 성공 시 `NO_CROP` 전송 · 폰 런타임 실패 시 서버 YOLO fallback 유지 |
| 1.9 | 2026-08-25 | 도마·팬 ROI의 bbox 바깥 추가 여백을 22%에서 0%로 제거해 판정 대상을 최대한 크게 유지 |
| 1.10 | 2026-08-26 | VLM 입력 지연을 줄이기 위해 자동 ROI 출력을 도마 `768×576`·팬 `768×768`, fallback 긴 변 768px로 축소 |
| 1.11 | 2026-08-26 | `AUTO_ROI` 추가 — 새 사용자·YouTube 추출 레시피는 도마·팬 전체 후보 중 시선에 가장 가까운 ROI를 폰에서 자동 선택. 기존 사용자 레시피와 내장 기본값은 fixture v12에서 이 정책으로 이관 |

> **1.1은 추가만 있고 변경·삭제가 없습니다.** 요청/응답 필드, URL, 인증 방식이
> 그대로이므로 기존 클라이언트 코드는 수정 없이 동작합니다.
