#!/bin/bash
# Vercel Ignored Build Step：非生产跳过；生产时对比上次成功部署，docs-site 无变化则跳过

if [ "$VERCEL_ENV" != "production" ]; then
  exit 0
fi

if [ -z "$VERCEL_GIT_PREVIOUS_SHA" ]; then
  exit 1
fi

# Vercel 为浅克隆（depth=10），上次部署的提交常不在本地，先补取该提交
if ! git remote get-url origin >/dev/null 2>&1; then
  git remote add origin "https://github.com/${VERCEL_GIT_REPO_OWNER}/${VERCEL_GIT_REPO_SLUG}.git"
fi
git fetch --quiet --depth=1 origin "$VERCEL_GIT_PREVIOUS_SHA" >/dev/null 2>&1 || exit 1
git diff --quiet "$VERCEL_GIT_PREVIOUS_SHA" "$VERCEL_GIT_COMMIT_SHA" -- .
