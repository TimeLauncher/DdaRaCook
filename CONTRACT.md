# API 계약서 · `/judge-step`

> **팀 3명 합의 후 임의 변경 금지.** 변경이 필요하면 3명이 함께 결정합니다.
> 담당: 3번 · 사용: 2번(앱) · 버전 1.0 · 상태: **확정 대기**

---

## 1. 엔드포인트

```
POST  {BASE_URL}/judge-step
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
  "currentImage": "<base64 JPEG>"
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

### 3.1 `checkType` 값

`PRESENCE` · `COUNT` · `IDENTIFY` · `COLOR_CHANGE` · `STATE_CHANGE` · `TIME_ONLY`

> `TIME_ONLY` 단계는 **서버를 호출하지 않습니다.** 2번이 로컬 타이머로만 처리합니다.

### 3.2 ⭐ `startImage` 전송 규칙

| `checkType` | `startImage` | 이유 |
|---|---|---|
| `PRESENCE` / `IDENTIFY` / `COUNT` / `STATE_CHANGE` | **null** | 절대 판정이라 비교 기준이 불필요 |
| `COLOR_CHANGE` | **전송** | "반투명하다"는 상대적 개념이라 기준점 필요 |

**왜 나누는가**: 이미지 2장이면 페이로드가 2배가 되어 왕복 3초 목표(명세서 12절 #4)에 압박이 됩니다.
불필요한 유형에서는 보내지 않습니다.

> 📌 이 규칙은 **T2-4 실험 결과에 따라 확정**됩니다. 그때까지는 위 표를 기본값으로 사용하세요.

### 3.3 이미지 규격 (1번 담당)

| 항목 | 값 |
|---|---|
| 포맷 | JPEG, quality 80 |
| 해상도 | **긴 변 1280px** (⚠️ 확정 대기 — `이미지규격_요청서.md` 참조) |
| **회전** | **픽셀에 반영 후 EXIF 제거** |
| 색공간 | sRGB |
| 인코딩 | base64 (표준, 줄바꿈 없음) |
| 정규화 위치 | 1번 모듈에서 1회만 |

---

## 4. 응답 · 성공 (200)

```json
{
  "verdict": "DONE",
  "reasonCode": "VISIBLE_CHANGE",
  "vlmLatencyMs": 1840,
  "promptVersion": "v3",
  "backend": "nemotron"
}
```

| 필드 | 값 | 설명 |
|---|---|---|
| `verdict` | `DONE` / `NOT_DONE` / `CANNOT_TELL` | |
| `reasonCode` | `VISIBLE_CHANGE` / `NO_CHANGE` / `TARGET_NOT_VISIBLE` / `BLURRY` / `OTHER` | |
| `vlmLatencyMs` | int | **서버가 잰 모델 호출 시간** |
| `promptVersion` | string | 평가 추적용 |
| `backend` | string | 어느 모델이 판정했는지 |

> ⚠️ **앱은 모르는 `reasonCode` 값이 와도 크래시하지 않아야 합니다.** 미지의 값은 `OTHER`로 처리하세요.

> ⚠️ **`vlmLatencyMs` ≠ 앱의 `roundTripMs`.** 서버가 잰 모델 시간과 앱이 잰 전체 왕복은 다른 값입니다.
> 이름을 분리하지 않으면 평가할 때 반드시 섞입니다. 둘의 차이가 네트워크 구간입니다.

---

## 5. 응답 · 실패

```json
{ "detail": "사람이 읽을 수 있는 오류 설명" }
```

| 코드 | 의미 | 앱의 대응 |
|---|---|---|
| 400 | 요청 형식 오류 | 버그. 로그 남기고 개발자 확인 |
| 401 | 토큰 없음 | 설정 확인 |
| 403 | 토큰 불일치 | 설정 확인 |
| 429 | 레이트 리밋 | 백오프 후 재시도 |
| 500 | 서버 내부 오류 | 1회 재시도 |
| 501 | 실제 판정 미연결 (T1-4 이전) | Mock 헤더 사용 |
| 503 | 모델 응답 실패 | 1회 재시도 |

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
| `X-Mock-Verdict` | `DONE` / `NOT_DONE` / `CANNOT_TELL` | 그 값을 그대로 반환 |
| `X-Mock-Delay-Ms` | 정수 (최대 30000) | 지연 흉내 — 타임아웃 테스트용 |

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

> 이미지는 아무 문자열(`"AAAA"`)이어도 됩니다. Mock 경로는 이미지를 보지 않습니다.

---

## 7. 변경 이력

| 버전 | 날짜 | 변경 | 합의 |
|---|---|---|---|
| 1.0 | | 최초 확정 | ☐1번 ☐2번 ☐3번 |
