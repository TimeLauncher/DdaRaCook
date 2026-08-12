# 요리 어시스턴트 · 판정 서버 (3번 파트)

명세서 8절의 "얇은 판정 서버". VLM API 키를 숨기고 `/judge-step` 하나만 중계합니다.

---

## 빠른 시작

```bash
cd server
pip install -r requirements.txt
cp .env.example .env          # 값을 채우세요 (T0-1)
python -m uvicorn server:app --reload --host 0.0.0.0 --port 8000
```

확인:
```bash
curl http://127.0.0.1:8000/health          # realJudgeReady: true 여야 실제 판정 가능
python test_parser.py                       # JSON 파서 단위 테스트 (T1-1 DoD)
python eval.py --selftest                   # 평가 지표 계산 검산 (T2-2 · 사진·API 불필요)
python smoke.py --direct                    # 서버 없이 모델만 호출 (T1-2 DoD)
python smoke.py --url http://127.0.0.1:8000 # HTTP 왕복 + 지연 분리 (T1-5)
```

`smoke.py` 주요 옵션:

| 옵션 | 용도 |
|---|---|
| `--mock DONE` | AI 호출 없이 배관만 확인 |
| `--no-start` | 1장 모드 (T2-4 비교 실험) |
| `--repeat 5` | 지연 편차 측정 |
| `--start / --current` | 다른 사진으로 교체 |
| `--show-raw` | 모델 원문 출력 (프롬프트 개선용) |

---

## 📱 2번 담당자에게

**실제 AI 판정이 연결되었습니다 (T1-4 완료).** 계약(`CONTRACT.md`)의
요청·응답 필드는 **하나도 바뀌지 않았으니** 기존 클라이언트 코드는 그대로 씁니다.

- Mock 헤더를 **붙이면** → 예전처럼 AI 없이 원하는 판정 (상태 머신 테스트용)
- Mock 헤더를 **빼면** → 진짜 판정. 이때는 **진짜 JPEG**를 보내야 합니다
  (아닌 경우 400 + 원인 메시지가 옵니다)
- 새로 생긴 상태: **503 = 모델 타임아웃.** `CANNOT_TELL`과 **절대 섞지 마세요**
  (`CONTRACT.md` §5)
- 그 503 분기를 테스트할 헤더도 만들어뒀습니다: **`X-Mock-Status: 503`**
  (`CONTRACT.md` §6 — 429·500도 됩니다)

| 항목 | 값 |
|---|---|
| 로컬 주소 | `http://192.168.2.206:8000` (같은 Wi-Fi 필요) |
| 배포 주소 | **`https://ddaracook-server.onrender.com`** |
| 토큰 | `.env` 의 `TEAM_TOKEN` — 별도 전달 |
| 계약서 | **`CONTRACT.md` 를 읽으세요** |

```bash
# CANNOT_TELL 3회 연속 시나리오(F4-5)를 요리 없이 테스트
curl -X POST http://192.168.2.206:8000/judge-step \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TEAM_TOKEN>" \
  -H "X-Mock-Verdict: CANNOT_TELL" \
  -d '{"requestId":"t1","recipeId":"kfr","stepOrder":3,"instruction":"x",
       "checkType":"COLOR_CHANGE","checkCondition":"y","elapsedSeconds":260,
       "currentImage":"AAAA"}'
```

자세한 사용법은 `CONTRACT.md` §6.

---

## 📷 1번 담당자에게

`이미지규격_요청서.md` 의 **Part 1** 을 읽어주세요.

요청드리는 건 **사진이 아니라 캡처 빌드**입니다 (촬영은 3번이 직접 합니다):

1. `start → 2~3초 → capturePhoto() → stop` 되고 파일이 폰에 남는 빌드
   (W0 실험 2 코드 그대로면 충분합니다)
2. 그 빌드에 **정규화 포함** — 특히 **EXIF 회전을 픽셀에 반영 후 제거**
3. **검증 샘플 3~5쌍** — 파이프라인 동일성 확인용

---

## 파일 구조

```
server/
├── server.py                  # FastAPI · /judge-step        (T0-3 ✅ / T1-4 ✅)
├── prompts.py                 # 프롬프트 v1 (벤더 무관)        (T1-3 ✅)
├── judge/
│   ├── __init__.py            # 백엔드 팩토리 get_judge()
│   ├── base.py                # Verdict · Protocol · JSON 파서 (T1-1 ✅)
│   ├── nemotron.py            # 1순위 어댑터                   (T1-2 ✅)
│   ├── mock.py                # API 없는 가짜 어댑터 — 하네스 검증용
│   └── openai_backend.py      # 2순위 어댑터                   (T2-5 조건부·미구현)
├── test_parser.py             # 파서 단위 테스트 16케이스       (T1-1 DoD)
├── smoke.py                   # 판정 경로 확인 · 지연 분리      (T1-2 / T1-5)
├── probe.py                   # 다중 이미지 지원 확인          (T0-2 ✅)
├── eval.py                    # 정확도 평가                    (T2-2 ✅)
├── imageprep.py               # 축소 · 색공간 (원본 불변)       (T2-1/T2-2 ✅)
├── inspect_image.py           # 이미지 규격 감사 (EXIF·ICC)
├── CONTRACT.md                # API 계약서 — 2번이 읽을 것     (T0-4 ✅)
├── 이미지규격_요청서.md         # 1번 요청 + 내 촬영 계획         (T0-6 ✅)
├── notes/nemotron.md          # 모델 조사 + 지연 실측 기록
└── testdata/
    ├── raw/                   # 안경 원본 — 수정 금지 · git 제외 (개인정보·용량)
    ├── images/                # 평가용 정규화본                 (T2-1 대기)
    └── labels.example.json    # 라벨 스키마 예시
```

> 상세 해설은 루트의 **`3번_진행상황_해설서.md`** (코드 단위 설명 · 실측 데이터 · 용어 사전).

> 벤더 종속 코드는 `judge/` 안에만 있습니다. 백엔드 전환은 환경변수 한 줄:
> `VLM_BACKEND=nemotron` / `VLM_BACKEND=openai`

---

## 진행 상황

| Task | 내용 | 상태 |
|---|---|---|
| T0-1 | NVIDIA 계정·모델 조사 | ✅ 완료 (`notes/nemotron.md`) |
| T0-2 | 다중 이미지 확인 | ✅ **게이트 통과** |
| T0-3 | Mock 서버 | ✅ 완료 · 검증됨 |
| T0-4 | API 계약서 | ✅ 완료 · 팀 합의 대기 |
| T0-5 | 배포 | ✅ 완료 (https://ddaracook-server.onrender.com) |
| T0-6 | 이미지 규격 요청서 | ✅ 완료 · 1번에게 전달 필요 |
| T1-1 | 어댑터 규격 · JSON 파서 | ✅ 완료 · 16케이스 통과 |
| T1-2 | Nemotron 어댑터 | ✅ 완료 · 실제 판정 확인 |
| T1-3 | 프롬프트 v1 | ✅ 완료 |
| T1-4 | `/judge-step` 실제 연결 | ✅ **배포 반영 완료** |
| T1-5 | 통합 테스트 | ⏳ 서버 측 준비 완료 · **2번 합동 대기** |
| T2-2 | 평가 스크립트 `eval.py` | ✅ 구현 완료 · 실데이터만 대기 |
| T2-1 | 테스트 데이터셋 촬영·라벨링 | ⏳ **현재 병목** — T2-3/T2-4가 여기에 걸림 |

> ✅ **배포본 확인 (2026-08-10)** — `/health` 응답:
> `promptVersion: v1` · `realJudgeReady: true` · `envMissing: []`
> **2번은 지금 Mock 헤더만 빼면 진짜 판정을 받습니다.**

---

## 배포 (T0-5)

무료 티어 3곳 중 택1. **Render 기준:**

1. 이 폴더를 GitHub에 push (`.env` 는 `.gitignore` 로 제외됨)
2. Render → New → Web Service → 저장소 연결
3. `render.yaml` 이 자동 인식됨
4. **Environment 탭에서 직접 입력** (저장소에 넣지 않음):
   - `NVIDIA_API_KEY`
   - `NVIDIA_BASE_URL`
   - `NVIDIA_MODEL`
   - `TEAM_TOKEN`
5. 배포 후 `https://<앱이름>.onrender.com/health` 확인
6. **URL을 `CONTRACT.md` §1 과 이 README에 기록하고 2번에게 공유**

> ⚠️ **무료 티어는 15분 무응답 시 슬립**에 들어가 첫 요청이 30초 이상 걸립니다.
> 시연 전 반드시 `/health` 를 몇 번 호출해 깨우세요 (T3-5).

---

## 🔑 보안

- `.env` 는 `.gitignore` 에 있습니다. **절대 커밋하지 마세요.**
- 실수로 키를 커밋했다면 **즉시 폐기 후 재발급**하세요. 커밋 삭제만으로는 부족합니다.
- 배포 환경변수는 각 플랫폼 대시보드에서 입력합니다.
