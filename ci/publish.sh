#!/usr/bin/env bash
# 把 CI 产物(日志、APK、截图)作为一个全新的孤立提交强推到指定分支,方便直接读取。
# 用法: publish.sh <分支名> <路径> [<路径> ...]   需要环境变量 GITHUB_TOKEN / GITHUB_REPOSITORY / GITHUB_RUN_ID
set -eu
BRANCH="$1"; shift
TMP=$(mktemp -d)
for p in "$@"; do
  if [ -e "$p" ]; then cp -r "$p" "$TMP/"; fi
done
cd "$TMP"
git init -q -b "$BRANCH"
git config user.name "github-actions"
git config user.email "actions@users.noreply.github.com"
git add -A
git commit -q --allow-empty -m "CI output for run ${GITHUB_RUN_ID:-unknown}"
git remote add origin "https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
git push -q -f origin "$BRANCH"
echo "published to $BRANCH"
