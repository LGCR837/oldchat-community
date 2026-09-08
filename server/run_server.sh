#!/bin/sh

SERVER_DIR=$(cd "$(dirname "$0")" && pwd)

# 后端启动配置
# 生产环境的 OSS 凭据不写入源码或构建产物；启动时从受限环境文件导入。
# 可用 OSS_ENV_FILE 覆盖路径；文件不存在时仍可作为无 OSS 的本地服务启动。
OSS_ENV_FILE=${OSS_ENV_FILE:-/etc/oldchat-oss.env}
if [ -r "$OSS_ENV_FILE" ]; then
  set -a
  . "$OSS_ENV_FILE"
  set +a
  echo "Loaded OSS environment: $OSS_ENV_FILE"
else
  echo "OSS environment not loaded: $OSS_ENV_FILE"
fi

export PORT=${PORT:-8080}
export DATABASE_URL=${DATABASE_URL:-"$SERVER_DIR/metrochat.db"}
export JWT_SECRET=${JWT_SECRET:-"local-dev-secret-change-me-please-32-chars"}
export JWT_ISSUER=${JWT_ISSUER:-"metrochat"}
export ACCESS_TOKEN_TTL=${ACCESS_TOKEN_TTL:-3600}  # 1 小时；客户端通过 refresh token 无感续期
export OAUTH_ISSUER=${OAUTH_ISSUER:-https://oc.mcl0.dpdns.org}
export OAUTH_SIGNING_KEY_PATH=${OAUTH_SIGNING_KEY_PATH:-oauth_ed25519.key}
export REFRESH_TOKEN_TTL=${REFRESH_TOKEN_TTL:-2592000} # 30 days

BIN_PATH=""
if [ -n "${BIN_NAME:-}" ] && [ -x "$SERVER_DIR/$BIN_NAME" ]; then
  BIN_PATH="$SERVER_DIR/$BIN_NAME"
elif [ -x "$SERVER_DIR/server" ]; then
  BIN_PATH="$SERVER_DIR/server"
else
  OS=$(uname -s | tr '[:upper:]' '[:lower:]')
  ARCH=$(uname -m)
  case "$ARCH" in
    x86_64) ARCH=amd64 ;;
    arm64|aarch64) ARCH=arm64 ;;
  esac
  if [ -x "$SERVER_DIR/server_${OS}_${ARCH}" ]; then
    BIN_PATH="$SERVER_DIR/server_${OS}_${ARCH}"
  elif [ "$OS" = "linux" ] && [ -x "$SERVER_DIR/server_amd64" ]; then
    BIN_PATH="$SERVER_DIR/server_amd64"
  fi
fi

if [ -z "$BIN_PATH" ]; then
  echo "Server binary not found. Build it with: ./build_amd64.sh"
  exit 1
fi

echo "Starting server on port $PORT..."
"$BIN_PATH"
