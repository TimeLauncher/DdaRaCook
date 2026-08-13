#!/bin/bash
# push 전에 실행하세요:  bash check_secrets.sh
cd "$(dirname "$0")"
fail=0

if [ ! -f .gitignore ]; then
  echo "🚨 .gitignore 가 없습니다! 복구 후 다시 시도하세요."
  exit 1
fi

# ── 1. .env 가 추적 대상인가 ──────────────────────────────
#    ls-files 는 현재 디렉터리 기준 경로를 내므로 -- :/ 로 저장소 전체를 본다.
if git ls-files --cached -- :/ | grep -qE '(^|/)\.env$'; then
  echo "🚨 .env 가 커밋 대상입니다!"
  echo "   git rm --cached <경로>  로 제외 후 다시 커밋하세요."
  fail=1
fi

# ── 2. 과거 커밋에 .env 가 있었나 ─────────────────────────
#    ⚠️ log --name-only 는 저장소 루트 기준 경로(server/.env)를 낸다.
#       예전 grep -qx "\.env" 는 줄 전체 일치라 이걸 절대 못 잡았다. (2026-08-12 수정)
if git log --all --name-only --pretty=format: 2>/dev/null | grep -qE '(^|/)\.env$'; then
  echo "🚨 과거 커밋에 .env 가 있습니다 — 키를 즉시 폐기·재발급하세요."
  fail=1
fi

# ── 3. ⭐ 비밀값 자체가 코드에 박혀 있는가 ────────────────
#    파일명만 보는 검사는 .kt/.md 안에 하드코딩된 값을 놓친다.
#    실제로 TEAM_TOKEN 이 JudgeApiService.kt 에 박힌 채 머지됐고
#    이 스크립트는 "✅ 안전" 을 출력했다. (2026-08-12)
check_value() {
  local name="$1" placeholder="$2" val
  [ -f .env ] || return 0
  val=$(grep -E "^[[:space:]]*${name}[[:space:]]*=" .env | head -1 \
        | sed 's/^[^=]*=//' | tr -d '"\r' | sed "s/'//g" \
        | sed 's/^[[:space:]]*//; s/[[:space:]]*$//')
  [ -n "$val" ] && [ "$val" != "$placeholder" ] || return 0

  if git grep -qF "$val" -- :/ 2>/dev/null; then
    echo "🚨 ${name} 값이 추적 중인 파일에 있습니다 — 커밋 전에 제거하세요."
    echo "   위치: $(git grep -lF "$val" -- :/ 2>/dev/null | tr '\n' ' ')"
    fail=1
  fi
  if [ -n "$(git log --all -S"$val" --oneline 2>/dev/null | head -1)" ]; then
    echo "🚨 ${name} 값이 커밋 이력에 있습니다 — 파일 수정으로는 못 지웁니다."
    echo "   값 자체를 교체하세요 (Render 대시보드 + .env + 2번에게 공유)."
    fail=1
  fi
}

check_value TEAM_TOKEN     cookassist-dev-change-me
check_value NVIDIA_API_KEY nvapi-xxxxxxxxxxxxxxxxxxxx

# ── 4. ⭐ 토큰 "모양" 이 소스에 박혀 있는가 ────────────────
#    3번 검사는 .env 의 **현재** 값만 찾는다. 그래서 이미 교체한 옛 토큰이나
#    .env 에 없는 값은 통과한다. 실제로 2026-08-12 에 폐기한 구 TEAM_TOKEN 이
#    JudgeApiService.kt:12 에 남아 있는데 이 스크립트는 "✅ 안전" 을 냈다.
#    폐기된 값이라도 커밋에 남으면 안 된다 — 다음 사람이 그대로 복사한다.
scan_pattern() {
  local label="$1" pattern="$2" hits
  #  제외 패스스펙(:!)은 cwd 기준이라 server/ 안의 파일을 못 걸러낸다.
  #  출력에서 거르는 편이 확실하다 — 플레이스홀더가 사는 두 파일만 뺀다.
  hits=$(git grep -nIE "$pattern" -- :/ 2>/dev/null \
         | grep -vE '(^|/)(\.env\.example|check_secrets\.sh):')
  if [ -n "$hits" ]; then
    echo "🚨 ${label} 모양의 값이 추적 중인 파일에 있습니다 — 폐기된 값이라도 제거하세요."
    echo "$hits" | sed 's/^/   /'
    echo "   앱은 local.properties → BuildConfig, 서버는 .env 를 쓰세요 (AGENTS.md)."
    fail=1
  fi
}

# 접두사 뒤 20자 이상만 잡는다 — 플레이스홀더(cookassist-dev-change-me)는 13자라 안 걸린다.
scan_pattern TEAM_TOKEN     'cookassist-[A-Za-z0-9_-]{20,}'
scan_pattern NVIDIA_API_KEY 'nvapi-[A-Za-z0-9_-]{20,}'

if [ $fail -eq 0 ]; then
  echo "✅ 안전 — push 해도 됩니다"
  echo "   추적 파일 $(git ls-files --cached -- :/ | wc -l)개"
fi
exit $fail
