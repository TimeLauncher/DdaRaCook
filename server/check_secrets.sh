#!/bin/bash
# push 전에 실행하세요:  bash check_secrets.sh
cd "$(dirname "$0")"
fail=0

if [ ! -f .gitignore ]; then
  echo "🚨 .gitignore 가 없습니다! 복구 후 다시 시도하세요."
  exit 1
fi

if git ls-files --cached | grep -qx "\.env"; then
  echo "🚨 .env 가 커밋 대상입니다!"
  echo "   git rm --cached .env  로 제외 후 다시 커밋하세요."
  fail=1
fi

if git log --all --name-only --pretty=format: 2>/dev/null | grep -qx "\.env"; then
  echo "🚨 과거 커밋에 .env 가 있습니다 — 키를 즉시 폐기·재발급하세요."
  fail=1
fi

if [ $fail -eq 0 ]; then
  echo "✅ 안전 — push 해도 됩니다"
  echo "   추적 파일 $(git ls-files --cached | wc -l)개"
fi
exit $fail
