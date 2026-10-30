#!/usr/bin/env bash
# 一键发布：一次编译、API 优先推送。
#
# 流程（不要再分步手动操作）：
#   1. 校验工作区干净、版本号已更新
#   2. 打 commit + tag（本地）
#   3. 用 GitHub REST API 推送 main + tag（git 端点常被墙，API 秒级完成）
#   4. 只触发 1 次 CI（workflow 仅在 tag 推送时构建）
#
# 用法: ./script/release.sh v0.3.7-pre 10
#   $1 = tag（同时作为 versionName 前缀）
#   $2 = versionCode
set -euo pipefail
TAG="${1:?用法: ./script/release.sh <tag> <versionCode>}"
CODE="${2:?用法: ./script/release.sh <tag> <versionCode>}"

cd "$(dirname "$0")/.."

if [ -n "$(git status --porcelain)" ]; then
  echo "❌ 工作区有未提交改动，先提交再发布"; exit 1
fi

sed -i "s/versionCode = .*/versionCode = $CODE/; s/versionName = \".*\"/versionName = \"$TAG\"/" app/build.gradle.kts
grep -n "versionCode\|versionName" app/build.gradle.kts

git add -A
git commit -q -m "$TAG"
git tag -f "$TAG" HEAD >/dev/null 2>&1

echo "→ 用 API 推送（跳过 git push，避免网络卡死）"
python3 script/push-via-api.py

echo "✅ 已推送 $TAG（$CODE），CI 将由 tag 触发 1 次构建"
