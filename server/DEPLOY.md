# T0-5 · 배포 가이드

> 목표: 폰에서 접근 가능한 URL 확보 → 2번에게 전달
> 소요: 20~30분 (대부분 대기 시간)

---

## 왜 지금 배포하나

**"내 노트북에선 되는데" 문제를 첫날에 없애기 위해서**입니다.

로컬로만 개발하다 3주차에 배포하면 그때 처음 보는 문제가 쏟아집니다.
환경변수 누락, 포트 설정, 의존성 버전, 빌드 실패… 시연 직전에 만날 문제가 아닙니다.

또한 2번이 로컬 IP(`192.168.2.206`)로 테스트하려면 **같은 Wi-Fi에 있어야** 합니다.
집에서 각자 작업할 땐 안 됩니다. 배포 URL이 있으면 어디서든 됩니다.

---

## 0. 이미 되어 있는 것

| 항목 | 상태 |
|---|---|
| `Procfile` · `render.yaml` · `runtime.txt` | ✅ 생성됨 |
| `.gitignore` (`.env` 제외) | ✅ 검증됨 (`git check-ignore` 확인) |
| 프로덕션 조건 기동 테스트 | ✅ `.env` 없이 환경변수만으로 정상 |
| git 저장소 초기화 + 스테이징 | ✅ `server/` 에 완료 |

**아직 커밋은 안 했습니다.** 아래 1단계에서 직접 실행하세요.

---

## 1. 첫 커밋

```bash
cd server
git commit -m "판정 서버 초기 구현 (T0-3 Mock + T0-2 probe)"
```

> 커밋 전에 `.env` 가 빠졌는지 마지막으로 확인:
> ```bash
> git ls-files --cached | grep -x "\.env" && echo "위험!" || echo "안전"
> ```

---

## 2. GitHub 저장소 만들기

### 2-1. 저장소 생성

1. https://github.com/new
2. Repository name: `cookassist-judge` (아무거나)
3. **Private 권장** (Public이어도 `.env`는 안 올라가지만, 습관을 들이세요)
4. **저장소 생성 화면의 체크박스 3개를 모두 해제** ← 중요
   - `Add a README file` ☐
   - `Add .gitignore` ☐
   - `Choose a license` ☐

   > ⚠️ **내 `server/.gitignore` 파일을 지우라는 뜻이 절대 아닙니다.**
   > GitHub이 *새* `.gitignore`를 만들면 우리 것과 충돌할 뿐입니다.
   > **로컬 `.gitignore` 는 반드시 그대로 두세요 — `.env`(API 키)를 막아주는 파일입니다.**
5. Create repository

### 2-2. 푸시

GitHub가 보여주는 명령을 그대로 쓰거나:

```bash
git remote add origin https://github.com/<내아이디>/<저장소이름>.git
git push -u origin master     # 브랜치명이 main이면 main으로
```

> 브랜치명은 `git branch` 로 확인하세요. 이 프로젝트는 `master` 입니다.

> 💡 **저장소 구조 결정**: `server/` 만 별도 저장소로 두는 게 배포에 가장 단순합니다.
> 나중에 안드로이드 프로젝트와 하나로 합치고 싶으면, Render의 **Root Directory**
> 설정으로 모노레포도 지원됩니다. 지금은 분리해두세요.

---

## 3. Render 배포

### 3-1. 서비스 생성

1. https://render.com → GitHub 계정으로 가입
2. **New → Web Service**
3. 방금 만든 저장소 선택 (Render에 GitHub 접근 권한 부여 필요)
4. `render.yaml` 이 자동 인식됩니다

수동 설정이 필요하면:

| 항목 | 값 |
|---|---|
| Runtime | Python 3 |
| Build Command | `pip install -r requirements.txt` |
| Start Command | `uvicorn server:app --host 0.0.0.0 --port $PORT` |
| Health Check Path | `/health` |
| Instance Type | **Free** |

### 3-2. 🔑 환경변수 입력 ← 가장 중요

**Environment 탭에서 직접 입력합니다.** 저장소에는 절대 넣지 않습니다.

| Key | Value | 어디서 가져오나 |
|---|---|---|
| `NVIDIA_API_KEY` | `nvapi-...` | 로컬 `.env` 에서 복사 |
| `NVIDIA_BASE_URL` | `https://integrate.api.nvidia.com/v1` | |
| `NVIDIA_MODEL` | `nvidia/nemotron-nano-12b-v2-vl` | T0-2에서 확정 |
| `TEAM_TOKEN` | 로컬 `.env` 에서 복사 | 로컬 `.env` 와 **동일하게** |
| `VLM_BACKEND` | `nemotron` | |
| `DEBUG_MODE` | `true` | 아래 참조 |

> ⚠️ **`TEAM_TOKEN`은 로컬과 반드시 같아야 합니다.** 다르면 2번이 로컬에서 되던 게
> 배포에서 403이 납니다. 원인 찾기 어려운 유형의 버그입니다.

> ⚠️ **`DEBUG_MODE`는 `X-Mock-*` 헤더를 살려두는 스위치입니다.** 2번이 상태 머신
> 시나리오(F4-5 등)를 요리 없이 테스트할 때만 필요합니다.
> **W3 시연 전에 `false`로 바꾸세요** (T3-1). 안 그러면 헤더 하나로 판정을 조작할 수 있습니다.
> 배선이 끝나 앱 기본값이 실제 판정이므로(`useMockJudgment = false`), 2번이 더 안 쓴다고
> 하면 지금 꺼도 됩니다. **2026-08-14 기준 배포본은 아직 `true`입니다.**
>
> 코드 기본값은 `false`이므로(`server.py`), 환경변수를 **지우면** 안전한 쪽으로 떨어집니다.

### 3-3. 배포

**Create Web Service** → 5~10분 대기.

로그에서 이게 보이면 성공:
```
==> Your service is live 🎉
```

---

## 4. 확인

```bash
curl https://<앱이름>.onrender.com/health
```

기대 응답:
```json
{"status":"ok","backend":"nemotron","promptVersion":"v0-mock","debugMode":true,"realJudgeReady":false}
```

Mock 호출까지 확인:

```bash
curl -X POST https://<앱이름>.onrender.com/judge-step \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <TEAM_TOKEN>" \
  -H "X-Mock-Verdict: CANNOT_TELL" \
  -d '{"requestId":"t1","recipeId":"kfr","stepOrder":3,"instruction":"x",
       "checkType":"COLOR_CHANGE","checkCondition":"y","elapsedSeconds":260,
       "currentImage":"AAAA"}'
```

기대: `{"verdict":"CANNOT_TELL", ...}`

---

## 5. URL 기록 및 공유

배포 URL을 **세 곳**에 기록하세요.

- [ ] `CONTRACT.md` §1 의 "배포 URL" 칸
- [ ] `README.md` 의 "배포 주소" 칸
- [ ] 2번에게 전달 (토큰과 함께)

**2번에게 보낼 메시지 예시:**

```
판정 서버 배포됐습니다.

  URL   : https://<앱이름>.onrender.com
  토큰  : (여기 적지 마세요 — DM 등 git 밖 경로로 별도 전달)
  계약서: 저장소 루트의 CONTRACT.md

실제 AI 판정은 아직(T1-4 예정)이지만,
X-Mock-Verdict 헤더로 DONE/NOT_DONE/CANNOT_TELL 3분기를 지금 테스트할 수 있습니다.
사용법은 CONTRACT.md §6 에 있습니다.

※ 무료 티어라 15분 무응답이면 슬립합니다. 첫 요청이 30초 이상 걸리면
   그것 때문이니 한 번 더 호출해주세요.
```

---

## 6. ⚠️ 무료 티어 주의사항

| 항목 | 내용 | 대응 |
|---|---|---|
| **슬립** | 15분 무응답 시 잠듦 | 첫 요청이 30초+ 소요. **시연 전 워밍업 필수** (T3-5) |
| 월 사용시간 | 무료 한도 있음 | 개발용으로는 충분 |
| 빌드 시간 | 5~10분 | push할 때마다 자동 재배포 |

### 시연 전 워밍업 (T3-5에서 사용)

```bash
# 시연 10분 전에 실행
for i in 1 2 3; do curl -s https://<앱이름>.onrender.com/health; sleep 2; done
```

슬립 상태에서 첫 판정 요청이 30초 걸리면 시연이 망합니다. **반드시 미리 깨우세요.**

---

## 7. 문제 해결

| 증상 | 원인 | 해결 |
|---|---|---|
| 빌드 실패 — Python 버전 | `runtime.txt` 의 버전 미지원 | `python-3.11.9` 로 낮춰보기 |
| 빌드 실패 — 패키지 | 의존성 충돌 | 로그에서 실패한 패키지 확인 |
| 502 / 서비스 시작 안 됨 | Start Command 오류 | `uvicorn server:app --host 0.0.0.0 --port $PORT` 확인 |
| `/health` 는 되는데 판정이 403 | `TEAM_TOKEN` 불일치 | 로컬 `.env` 와 대시보드 값 대조 |
| 판정이 500 | 환경변수 누락 | `NVIDIA_API_KEY` / `NVIDIA_MODEL` 확인 |
| 첫 요청만 매우 느림 | 슬립에서 깨는 중 | 정상. 워밍업으로 대응 |

**로그 보는 법**: Render 대시보드 → 서비스 선택 → **Logs** 탭

---

## 부록 · 배포 없이 급하게 테스트하려면

GitHub·Render 설정이 지금 부담스러우면, **ngrok**으로 로컬 서버를 임시 공개할 수 있습니다.

```bash
# 터미널 1
uvicorn server:app --host 0.0.0.0 --port 8000

# 터미널 2
ngrok http 8000
```

ngrok이 준 URL을 2번에게 주면 됩니다.

**단, 임시방편입니다:**
- 내 PC가 켜져 있어야 함
- 무료 플랜은 재시작할 때마다 URL이 바뀜 → 2번이 매번 고쳐야 함

**W1 안에는 반드시 제대로 배포하세요.**
