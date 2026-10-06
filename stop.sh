#!/bin/bash
# ============================================================
# G2G Agent 停止脚本（含本地中间件）
# 用法:
#   ./stop.sh           # 仅停止应用
#   ./stop.sh --infra   # 同时停止中间件（MySQL/Redis/RocketMQ/Presidio）
#   ./stop.sh --all     # 停止应用 + 中间件
# 策略: 先优雅 kill(SIGTERM)，超时后强制 kill -9(SIGKILL)
# ============================================================
cd "$(dirname "$0")"

APP_PORT=8090
DATA_DIR="./data/infra"
STOP_INFRA=false

case "${1:-}" in
  --infra|--all) STOP_INFRA=true ;;
esac

# ------------------------------------------------------------
# 工具函数
# ------------------------------------------------------------
graceful_kill_by_port() {
  local port=$1
  local name=$2
  local timeout=${3:-15}
  local pids
  pids=$(lsof -ti:$port 2>/dev/null || true)
  if [ -z "$pids" ]; then
    echo "  ⏭️  $name (port $port) 未运行"
    return 0
  fi
  echo "  🛑 停止 $name (PID: $pids) ..."
  kill $pids 2>/dev/null || true
  for i in $(seq 1 "$timeout"); do
    if ! lsof -ti:$port >/dev/null 2>&1; then
      echo "  ✅ $name 已停止"
      return 0
    fi
    sleep 1
  done
  echo "  ⏳ 优雅停止超时，强制 kill -9 ..."
  kill -9 $pids 2>/dev/null || true
  sleep 1
  if lsof -ti:$port >/dev/null 2>&1; then
    echo "  ❌ $name 停止失败"
    return 1
  fi
  echo "  ✅ $name 已强制停止"
}

# ------------------------------------------------------------
# 1. 停止应用
# ------------------------------------------------------------
echo "==> 停止应用 (port $APP_PORT)"
graceful_kill_by_port $APP_PORT "WikiAgent App" 20

# ------------------------------------------------------------
# 2. 停止中间件（可选）
# ------------------------------------------------------------
if [ "$STOP_INFRA" = true ]; then
  echo ""
  echo "==> 停止中间件"

  # Presidio
  graceful_kill_by_port 5051 "Presidio Anonymizer" 10
  graceful_kill_by_port 5050 "Presidio Analyzer" 10

  # RocketMQ Broker / NameServer
  if command -v mqshutdown >/dev/null 2>&1; then
    echo "  🛑 通过 mqshutdown 停止 RocketMQ ..."
    mqshutdown broker 2>/dev/null || true
    mqshutdown namesrv 2>/dev/null || true
  fi
  graceful_kill_by_port 10911 "RocketMQ Broker" 10
  graceful_kill_by_port 9876  "RocketMQ NameServer" 10

  # Redis
  if is_running 6379 2>/dev/null || lsof -ti:6379 >/dev/null 2>&1; then
    if command -v redis-cli >/dev/null 2>&1; then
      echo "  🛑 通过 redis-cli SHUTDOWN 停止 Redis ..."
      redis-cli -p 6379 SHUTDOWN NOSAVE 2>/dev/null || true
      sleep 1
    fi
  fi
  graceful_kill_by_port 6379 "Redis" 10

  # MySQL
  if lsof -ti:3306 >/dev/null 2>&1; then
    # 优先 mysqladmin shutdown（需要密码）
    if [ -n "${MYSQL_PASSWORD:-}" ] && command -v mysqladmin >/dev/null 2>&1; then
      echo "  🛑 通过 mysqladmin 停止 MySQL ..."
      mysqladmin -uroot -p"${MYSQL_PASSWORD}" shutdown 2>/dev/null || true
      sleep 2
    fi
  fi
  graceful_kill_by_port 3306 "MySQL" 20
fi

echo ""
echo "✅ 停止完成"
