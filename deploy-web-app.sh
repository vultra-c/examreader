#!/bin/bash
# Cloudflare Pages 部署脚本
# 用法: ./deploy-web-app.sh <CLOUDFLARE_API_TOKEN>
# 
# 获取 API Token:
# 1. 登录 Cloudflare Dashboard (https://dash.cloudflare.com)
# 2. 进入 My Profile → API Tokens
# 3. 创建新 Token，选择 "Edit Cloudflare Pages" 模板
# 4. 复制 Token 并作为参数传入

if [ -z "$1" ]; then
  echo "用法: $0 <CLOUDFLARE_API_TOKEN>"
  echo ""
  echo "示例: $0 v1.0_abc123..."
  exit 1
fi

export CLOUDFLARE_API_TOKEN="$1"
PROJECT_NAME="extransmit"
WEB_APP_DIR="/workspace/web-app"

echo "=== 部署 Web 应用到 Cloudflare Pages ==="
echo "项目: $PROJECT_NAME"
echo "目录: $WEB_APP_DIR"
echo ""

# 使用 wrangler 部署
npx wrangler pages deploy "$WEB_APP_DIR" --project-name="$PROJECT_NAME" --commit-dirty

if [ $? -eq 0 ]; then
  echo ""
  echo "✅ 部署成功!"
  echo "访问: https://extransmit.dpdns.org"
else
  echo ""
  echo "❌ 部署失败"
  echo "请检查 API Token 是否有效，或手动登录:"
  echo "  npx wrangler login"
  echo "  npx wrangler pages deploy $WEB_APP_DIR --project-name=$PROJECT_NAME"
fi
