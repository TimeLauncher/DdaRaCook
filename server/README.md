# server/ — 판정 서버 (3번)

명세서 8절의 "얇은 판정 서버". VLM API 키를 숨기고 `/judge-step` 하나만 중계합니다.
**작업이나 세션을 시작할 때 이 문서를 먼저 읽습니다.** 최종 갱신 2026-08-14.

| 정본 | 무엇 |
|---|---|
| [`../CONTRACT.md`](../CONTRACT.md) | 요청·응답·오류·디버그 헤더. **코드가 계약을 따른다** |
| [`../AGENTS.md`](../AGENTS.md) | 소유 경계 · 브랜치 · push 규칙 |
| [`../요리어시스턴트_기능명세서.md`](../요리어시스턴트_기능명세서.md) | 기능 ID(`F4-3` 등) · 역할 분담 |
| [`../docs/contract-compliance.md`](../docs/contract-compliance.md) | 미해결 위반 항목 — **작업 전 확인** |
| [`notes/nemotron.md`](notes/nemotron.md) | 모델 선정 근거 · 지연 실측 |
| [`3번_AI서버_실행계획.md`](3번_AI서버_실행계획.md) | T0~T3 태스크 정의 |
| `3번_진행상황_해설서.md` · `3번_파트_해설서.md` | 코드 단위 해설 (공부용) |

---

## 지금 상태

| | |
|---|---|
| 배포 | `https://ddaracook-server.onrender.com` · `realJudgeReady: true` · `promptVersion: v1` |
| 완료 | T0 전부 · T1-1~T1-5 · T2-2(구현) · B-1/B-2(422→400) |
| 병목 | **T2-1 실기기 촬영·라벨링** — 안경이 붙어서 이제 막는 것이 없다 |
| 평가 최고 | nemotron 12b · 21쌍 · 정확도 95.2% · A등급 85.7% · falseAccept 0 — **폰 사진 · 1280px · 크롭 없음**(아래 ①) |

앱 배선 완료(`useMockJudgment = false`), DAT 안경 연동 완료(`96730f8` · mwdat 0.9.0).
레시피 정의(`RecipeFixtures.kt`)와 계약 매핑은 3번 소유 — `../AGENTS.md` 경계 표.

## 다음 할 일 (우선순위)

1. **T2-1 실기기 재촬영·라벨링** ← 지금 가장 값어치 있는 일
   쏘야 기준 [`testdata/raw/촬영목록_2회차.md`](testdata/raw/촬영목록_2회차.md). 폰 사진으로 낸 95.2%는 제품 검증이 아니다
2. **제품 규격으로 재측정** — `python eval.py --crop-top 40 --long-edge 1024`
   현재 CSV 최고 기록은 1280px·크롭 없음이다. 제품이 실제로 보내는 이미지의 점수는 아직 없다
3. **T2-3** 프롬프트 v1 → v2 — `STATE_CHANGE` 힌트가 1순위 (아래 ④)
4. **G-2** `CONTRACT.md` §3.2 개정안 — `startImage` 정책을 checkType이 아니라
   "완료 조건이 시작 시점 대비 변화를 묻는가"에 걸도록
5. **배포본 `DEBUG_MODE=false`** (T3-1) — 코드 기본값은 고쳤고 남은 건 Render 환경변수뿐

---

## 알고 있어야 할 것

다른 문서에 없거나, **문서가 반대로 적혀 있던** 내용들. 이 절이 이 문서의 핵심입니다.

**① 95.2%는 두 가지 의미에서 제품 숫자가 아니다**

- **폰 사진** — `testdata/raw`의 18장. 명세서 §12 마지막 줄은 *"실제 DAT 스트림 또는 `capturePhoto()` 결과"*를 요구한다. 안경은 1인칭 시점·모션 블러·자동 노출이라 조건이 다르다
- **1280px · 크롭 없음** — 제품은 위 40% 제거 + 긴 변 1024다(CONTRACT §3.3). `eval_history.csv`에 그 규격 행이 하나 있지만 **mock 백엔드**라 정확도 의미가 없다

→ **이 숫자를 제품 검증으로 말하면 안 된다.** T2-1 이후 둘 다 다시 잰다.
안경 연동 자체는 들어왔지만, 명세서 §10 W0의 나머지 항목(스트림 20회 연속, 왕복 3초)은 아직 내 실측 기록이 없다.

**② 3초 목표는 이미 초과**

모델 호출만 중앙값 **3648ms**, 최대 5679ms (명세서 §12 #4 목표 3초). 여기에 캡처·네트워크·TTS가 더해진다. 실측에서 7.5초를 넘겨 503이 나온 사례도 있다. 8b는 정확도가 68%로 무너져 대안이 아니다. → 목표를 5초로 재협상하거나 UX로 흡수해야 한다.

**③ `startImage` 분리 근거가 약하다**

`CONTRACT.md` §3.2가 유형별로 `startImage`를 나눈 이유는 "2장이면 페이로드 2배라 3초를 압박"이었다. 그런데 실측은 **1장 3511ms vs 2장 3648ms — 차이 137ms**. 지연의 지배 요인은 페이로드가 아니라 추론 시간이다. (n이 18 vs 21로 달라 아직 확정 아님)

**④ 쏘야 4단계는 상대 조건인데 이미지가 1장만 간다 — 현재 최대 실질 결함**

`RecipeFixtures.kt:61`의 완료 조건은 **"소세지 칼집이 처음보다 벌어졌는가"**인데, `shouldSendStartImage()`는 `COLOR_CHANGE`에만 `startImage`를 보낸다. 4단계는 `STATE_TRANSITION`이라 **비교 기준 없이 1장으로 "처음보다"를 판정**한다. 서버는 200으로 응답한다 — 오류 없이 조용히 틀린다.

근본 원인은 §3.2가 정책을 checkType에 걸어둔 것이다. 쏘야 1단계와 4단계는 **둘 다 `STATE_CHANGE`인데 필요가 반대**다. → ③과 묶어 G-2로 처리한다.

**⑤ 배포본이 아직 `debugMode: true`**

`/health`로 확인(2026-08-14). 헤더 하나로 판정을 조작할 수 있다. 코드 기본값은 `false`로 고쳤으므로 남은 건 Render 대시보드뿐이다.

---

## 구조

설계 원칙 셋 — 어기면 나중에 원인 분리가 안 된다.

- **벤더 종속 코드는 `judge/` 안에만.** 전환은 환경변수 한 줄: `VLM_BACKEND=nemotron|openai|mock`
- **프롬프트 문자열은 `prompts.py`에만.** 어댑터는 포장만 한다. 고칠 때 `PROMPT_VERSION`도 같이 올린다 — 안 올리면 CSV에서 어느 프롬프트의 점수인지 사라진다
- **계약은 루트 `CONTRACT.md` 1벌.** 서버 문서에 요청·응답 스펙을 복사하지 않는다

```
server/
├── server.py              FastAPI · /judge-step · /health
├── prompts.py             프롬프트 v1 (벤더 무관) · CHECK_TYPE_HINT
├── judge/
│   ├── base.py            Verdict · Protocol · 관대한 JSON 파서
│   ├── nemotron.py        1순위 어댑터
│   ├── mock.py            API 없는 가짜 어댑터 — 하네스 검증용
│   └── openai_backend.py  2순위 (T2-5 조건부·미구현)
├── eval.py                정확도 평가 · --selftest · --mode both
├── smoke.py               판정 경로 확인 · 지연 분리
├── probe.py               다중 이미지 지원 확인
├── imageprep.py           축소 · 크롭 · 색공간 (원본 불변)
├── inspect_image.py       이미지 규격 감사 (EXIF · ICC · 해상도)
├── test_*.py              파서 16케이스 · 계약 · 재시도
└── testdata/
    ├── raw/               안경 원본 — 수정 금지 · git 제외 (개인정보·용량)
    ├── images/            평가용 정규화본
    ├── labels.json        라벨 (1회차 demo-onion 23쌍)
    └── eval_history.csv   평가 이력 — 규격이 섞여 있으니 열을 확인할 것
```

---

## 명령

```bash
cd server
python -m uvicorn server:app --reload --host 0.0.0.0 --port 8000

python eval.py --selftest                     # 지표 검산 (사진·API 불필요)
python test_parser.py                         # 파서 16케이스
python smoke.py --direct                      # 서버 없이 모델만
python smoke.py --url http://127.0.0.1:8000   # HTTP 왕복 + 지연 분리
python inspect_image.py testdata/raw/         # EXIF · ICC · 해상도 감사

curl -s https://ddaracook-server.onrender.com/health

bash check_secrets.sh                          # push 전 필수
```

`smoke.py`: `--mock DONE`(AI 없이 배관만) · `--no-start`(1장 모드) · `--repeat 5`(지연 편차) · `--show-raw`(모델 원문)

레시피를 고쳤을 때 (앱 쪽):

```properties
# local.properties — 에뮬레이터에서 확인할 때만
USE_FAKE_CAMERA=true
```

```bash
./gradlew testDebugUnitTest      # 픽스처 단언 확인
./gradlew installDebug           # 덮어쓰기 설치 — 지우고 깔면 함정이 안 보인다
```

---

## 운영

Render 무료 티어. **`main`에 push하면 자동 재배포**됩니다(5~10분).

환경변수는 **Render 대시보드에서만** 입력합니다 — 저장소에 넣지 않습니다.

| Key | 값 |
|---|---|
| `NVIDIA_API_KEY` | `nvapi-...` |
| `NVIDIA_BASE_URL` | `https://integrate.api.nvidia.com/v1` |
| `NVIDIA_MODEL` | `nvidia/nemotron-nano-12b-v2-vl` |
| `TEAM_TOKEN` | 로컬 `.env`와 **동일하게** — 다르면 앱이 403 |
| `VLM_BACKEND` | `nemotron` |
| `DEBUG_MODE` | `X-Mock-*` 헤더 스위치. **시연 전 `false`** (T3-1) · 지우면 안전한 쪽으로 떨어짐 |

| 증상 | 원인 |
|---|---|
| 첫 요청만 30초+ | 15분 슬립에서 깨는 중. 정상 — 시연 전 워밍업(T3-5) |
| `/health`는 되는데 판정이 403 | `TEAM_TOKEN` 불일치 |
| 판정이 500 | `NVIDIA_API_KEY` / `NVIDIA_MODEL` 누락 |
| 판정이 503 | 모델 7.5초 초과. **`CANNOT_TELL`과 절대 섞지 않는다**(CONTRACT §5) |

---

## 덫

- **레시피를 고치면 `AppPersistence.CURRENT_FIXTURE_VERSION`을 +1.** 안 올리면 기존 설치본에 반영되지 않는데 빌드도 테스트도 통과한다 — 증상이 없다
- **`InspectionPolicy`의 앞 두 값은 죽은 값** — `withAutomaticInspectionInterval()`이 30초로 덮는다. `helperText` 문구를 실제와 맞출 것
- **배포본 `DEBUG_MODE`는 시연 전 `false`** — 코드가 아니라 Render 대시보드에서
- **`eval_history.csv` 최고 기록은 1280px·크롭 없음** — 제품 규격 숫자와 섞어 말하지 않는다
- **토큰은 `.env`와 Render 대시보드에만.** 2026-08-12에 앱 소스 하드코딩으로 유출돼 교체한 이력이 있다
- **`testdata/raw/`는 커밋 금지** — 개인정보 · `.gitignore`에 등재됨
- **`.env`를 커밋했다면 즉시 폐기 후 재발급.** 커밋 삭제만으로는 부족하다
