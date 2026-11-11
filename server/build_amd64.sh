#!/bin/sh
set -e

SERVER_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$SERVER_DIR/.." && pwd)
DATA_SERVER_DIR="$PROJECT_ROOT/data_server"
PACKAGE_DIR=${PACKAGE_DIR:-"$PROJECT_ROOT/server_packaged"}

if ! command -v go >/dev/null 2>&1; then
  echo "Go compiler not found. Install it first (e.g. 'brew install go')."
  exit 1
fi

# data_server 为可选模块：目录不存在时只构建主服务。
WITH_DATA_SERVER=0
if [ -d "$DATA_SERVER_DIR" ]; then
  WITH_DATA_SERVER=1
fi

GOOS=${GOOS:-linux}
GOARCH=${GOARCH:-amd64}
CACHE_ROOT=${XDG_CACHE_HOME:-"$HOME/.cache"}

MAIN_BUILD_DIR=${OLDBUILD:-"$CACHE_ROOT/metrochat_build"}
DATA_BUILD_DIR=${DATA_BUILD:-"$CACHE_ROOT/metrochat_data_build"}

GOMODCACHE=${GOMODCACHE:-$(go env GOMODCACHE)}
GOCACHE=${GOCACHE:-$(go env GOCACHE)}
GOPROXY=${GOPROXY:-https://goproxy.cn,direct}

mkdir -p "$MAIN_BUILD_DIR" "$DATA_BUILD_DIR" "$GOMODCACHE" "$GOCACHE" "$PACKAGE_DIR"

if [ "${CLEAN:-0}" = "1" ]; then
  echo "Cleaning build cache..."
  rm -rf "$MAIN_BUILD_DIR"/* "$DATA_BUILD_DIR"/* "$GOCACHE"/*
  mkdir -p "$MAIN_BUILD_DIR" "$DATA_BUILD_DIR" "$GOCACHE"
  TIDY=1
fi

echo "Using MAIN_BUILD_DIR: $MAIN_BUILD_DIR"
echo "Using DATA_BUILD_DIR: $DATA_BUILD_DIR"
echo "Using GOCACHE: $GOCACHE"
echo "Target: $GOOS/$GOARCH"
echo "Package output dir: $PACKAGE_DIR"

# ---------- Build main server ----------
if [ ! -f "$MAIN_BUILD_DIR/go.mod" ] || ! cmp -s "$SERVER_DIR/go.mod" "$MAIN_BUILD_DIR/go.mod"; then
  cp -p "$SERVER_DIR/go.mod" "$MAIN_BUILD_DIR/"
fi

if [ -f "$SERVER_DIR/go.sum" ]; then
  echo "Copying main go.sum from source..."
  if [ ! -f "$MAIN_BUILD_DIR/go.sum" ] || ! cmp -s "$SERVER_DIR/go.sum" "$MAIN_BUILD_DIR/go.sum"; then
    cp -p "$SERVER_DIR/go.sum" "$MAIN_BUILD_DIR/"
  fi
elif [ ! -f "$MAIN_BUILD_DIR/go.sum" ]; then
  echo "Main go.sum missing, will download dependencies..."
fi

rm -rf "$MAIN_BUILD_DIR/cmd" "$MAIN_BUILD_DIR/internal"
cp -R "$SERVER_DIR/cmd" "$SERVER_DIR/internal" "$MAIN_BUILD_DIR/"

cd "$MAIN_BUILD_DIR"

if [ "${TIDY:-0}" = "1" ]; then
  echo "Running go mod tidy (main)..."
  GOMODCACHE="$GOMODCACHE" GOCACHE="$GOCACHE" GOPROXY="$GOPROXY" go mod tidy
fi

if [ ! -s "$MAIN_BUILD_DIR/go.sum" ]; then
  echo "Main go.sum missing or empty, running go mod download..."
  GOMODCACHE="$GOMODCACHE" GOCACHE="$GOCACHE" GOPROXY="$GOPROXY" go mod download
fi

MAIN_BIN_NAME=${BIN_NAME:-"server_${GOOS}_${GOARCH}"}
MAIN_OUTPUT_BIN="$MAIN_BUILD_DIR/$MAIN_BIN_NAME"

echo "Compiling main server..."
GOMODCACHE="$GOMODCACHE" GOCACHE="$GOCACHE" GOPROXY="$GOPROXY" \
  GOOS="$GOOS" GOARCH="$GOARCH" \
  go build -mod=mod -v -o "$MAIN_OUTPUT_BIN" ./cmd/api

cp -a "$MAIN_OUTPUT_BIN" "$SERVER_DIR/$MAIN_BIN_NAME"

HOST_GOOS=$(go env GOOS)
HOST_GOARCH=$(go env GOARCH)
if [ "$GOOS" = "$HOST_GOOS" ] && [ "$GOARCH" = "$HOST_GOARCH" ]; then
  cp -a "$MAIN_OUTPUT_BIN" "$SERVER_DIR/server"
fi

if [ -f "$MAIN_BUILD_DIR/go.sum" ]; then
  cp -a "$MAIN_BUILD_DIR/go.sum" "$SERVER_DIR/go.sum"
fi

# ---------- Build data server (optional) ----------
if [ "$WITH_DATA_SERVER" = "1" ]; then
mkdir -p "$DATA_BUILD_DIR/src"
rm -rf "$DATA_BUILD_DIR/src"/*
cp -R "$DATA_SERVER_DIR"/. "$DATA_BUILD_DIR/src/"

# 清理运行期文件，避免污染构建输入
rm -rf "$DATA_BUILD_DIR/src/storage"
rm -f "$DATA_BUILD_DIR/src/data_server" "$DATA_BUILD_DIR/src/server" "$DATA_BUILD_DIR/src"/*_linux_* "$DATA_BUILD_DIR/src"/*_darwin_*

cd "$DATA_BUILD_DIR/src"

if [ "${TIDY:-0}" = "1" ]; then
  echo "Running go mod tidy (data_server)..."
  GOMODCACHE="$GOMODCACHE" GOCACHE="$GOCACHE" GOPROXY="$GOPROXY" go mod tidy
fi

if [ -f "$DATA_BUILD_DIR/src/go.sum" ] && [ ! -s "$DATA_BUILD_DIR/src/go.sum" ]; then
  rm -f "$DATA_BUILD_DIR/src/go.sum"
fi

if [ ! -f "$DATA_BUILD_DIR/src/go.sum" ]; then
  echo "data_server go.sum missing, running go mod download..."
  GOMODCACHE="$GOMODCACHE" GOCACHE="$GOCACHE" GOPROXY="$GOPROXY" go mod download
fi

DATA_BIN_NAME=${DATA_BIN_NAME:-"data_server_${GOOS}_${GOARCH}"}
DATA_OUTPUT_BIN="$DATA_BUILD_DIR/$DATA_BIN_NAME"

echo "Compiling data server..."
GOMODCACHE="$GOMODCACHE" GOCACHE="$GOCACHE" GOPROXY="$GOPROXY" \
  GOOS="$GOOS" GOARCH="$GOARCH" \
  go build -mod=mod -v -o "$DATA_OUTPUT_BIN" .

cp -a "$DATA_OUTPUT_BIN" "$DATA_SERVER_DIR/$DATA_BIN_NAME"
if [ "$GOOS" = "$HOST_GOOS" ] && [ "$GOARCH" = "$HOST_GOARCH" ]; then
  cp -a "$DATA_OUTPUT_BIN" "$DATA_SERVER_DIR/data_server"
fi

if [ -f "$DATA_BUILD_DIR/src/go.sum" ]; then
  cp -a "$DATA_BUILD_DIR/src/go.sum" "$DATA_SERVER_DIR/go.sum"
fi
fi # WITH_DATA_SERVER

# ---------- Package outputs ----------
cp -a "$MAIN_OUTPUT_BIN" "$PACKAGE_DIR/$MAIN_BIN_NAME"
if [ "$WITH_DATA_SERVER" = "1" ]; then
cp -a "$DATA_OUTPUT_BIN" "$PACKAGE_DIR/$DATA_BIN_NAME"
fi

# 运行时数据库初始化、官网文档和静态资源必须随发布包交付。
for file in schema.sql AUTH_LOGIN_EXTERNAL_API.md NON_ADMIN_ROUTES.md API_STANDARD.md CLIENT_DEVELOPMENT_GUIDE.md LUA_CIP_DEVELOPMENT_GUIDE.md; do
  if [ -f "$SERVER_DIR/$file" ]; then
    cp -a "$SERVER_DIR/$file" "$PACKAGE_DIR/$file"
  fi
done
if [ -d "$SERVER_DIR/webapp" ]; then
  rm -rf "$PACKAGE_DIR/webapp"
  cp -a "$SERVER_DIR/webapp" "$PACKAGE_DIR/webapp"
fi
if [ -d "$SERVER_DIR/mail_project" ]; then
  rm -rf "$PACKAGE_DIR/mail_project"
  cp -a "$SERVER_DIR/mail_project" "$PACKAGE_DIR/mail_project"
fi
if [ -d "$SERVER_DIR/ooldchat-web/assets" ]; then
  mkdir -p "$PACKAGE_DIR/ooldchat-web"
  rm -rf "$PACKAGE_DIR/ooldchat-web/assets"
  cp -a "$SERVER_DIR/ooldchat-web/assets" "$PACKAGE_DIR/ooldchat-web/assets"
fi

echo "Built main server: $SERVER_DIR/$MAIN_BIN_NAME"
if [ "$WITH_DATA_SERVER" = "1" ]; then
echo "Built data server: $DATA_SERVER_DIR/$DATA_BIN_NAME"
fi
echo "Packaged artifacts:"
echo "  $PACKAGE_DIR/$MAIN_BIN_NAME"
