#!/bin/bash
# ============================================================
# G2G Agent 后台启动脚本（一键拉起全部本地中间件 + 应用）
# 用法: ./start.sh
# 特性:
#   - 幂等：已运行的中间件跳过启动
#   - 非 Docker：直接调用本机二进制（brew / 手动安装）
#   - 排除 Milvus / DashScope（外部依赖，需自行准备）
#   - nohup 后台运行，关闭终端仍可正常运行
# ============================================================
set -e
cd "$(dirname "$0")"

APP_PORT=8090
LOG_DIR="logs"
LOG_FILE="$LOG_DIR/app.log"
ENV_FILE=".env"

# 数据/日志目录
DATA_DIR="./data/infra"
mkdir -p "$LOG_DIR" "$DATA_DIR"/{mysql,redis,rocketmq/namesrv,rocketmq/broker,presidio}

# ------------------------------------------------------------
# 工具函数
# ------------------------------------------------------------

# 幂等检查：端口已被占用则视为已启动
is_running() {
  local port=$1
  lsof -ti:$port >/dev/null 2>&1
}

# 等待端口就绪
wait_for_port() {
  local port=$1
  local name=$2
  local timeout=${3:-30}
  for i in $(seq 1 "$timeout"); do
    if is_running "$port"; then
      echo "  ✅ $name (port $port) 已就绪"
      return 0
    fi
    sleep 1
  done
  echo "  ❌ $name (port $port) 启动超时（${timeout}s）"
  return 1
}

# 通过 brew 安装（仅在缺失时）
brew_install_if_missing() {
  local formula=$1
  local cmd=$2
  if command -v "$cmd" >/dev/null 2>&1; then
    return 0
  fi
  echo "  📦 未检测到 $cmd，尝试 brew install $formula ..."
  if ! command -v brew >/dev/null 2>&1; then
    echo "  ❌ 未安装 Homebrew，请先手动安装 $formula"
    return 1
  fi
  brew install "$formula"
}

# ------------------------------------------------------------
# 1. 加载环境变量（提前，后续中间件启动可能用到）
# ------------------------------------------------------------
if [ ! -f "$ENV_FILE" ]; then
  echo "⚠️  未找到 .env 文件，请先复制 .env.example 为 .env 并填入真实配置"
  echo "   cp .env.example .env && vi .env"
  exit 1
fi
set -a
. "./$ENV_FILE"
set +a

# ------------------------------------------------------------
# 2. MySQL 启动（port 3306）
# ------------------------------------------------------------
echo ""
echo "==> 启动 MySQL (port 3306) ..."
if is_running 3306; then
  echo "  ⏭️  MySQL 已在运行，跳过"
else
  # 优先 brew services
  if brew list mysql >/dev/null 2>&1; then
    brew services start mysql
    wait_for_port 3306 "MySQL" 60
  # 其次 anaconda 自带的 mysqld
  elif command -v mysqld >/dev/null 2>&1; then
    MYSQL_DATADIR="$DATA_DIR/mysql"
    if [ ! -f "$MYSQL_DATADIR/auto.cnf" ]; then
      echo "  🔧 首次初始化 MySQL 数据目录 $MYSQL_DATADIR ..."
      mysqld --initialize-insecure --datadir="$MYSQL_DATADIR" --user="$(whoami)"
    fi
    nohup mysqld --datadir="$MYSQL_DATADIR" \
      --port=3306 \
      --socket="$MYSQL_DATADIR/mysql.sock" \
      --pid-file="$MYSQL_DATADIR/mysql.pid" \
      --log-error="$LOG_DIR/mysql.log" \
      --character-set-server=utf8mb4 \
      --collation-server=utf8mb4_unicode_ci \
      > "$LOG_DIR/mysql.out" 2>&1 &
    disown
    wait_for_port 3306 "MySQL" 60
  else
    echo "  ❌ 未找到 mysqld，请执行: brew install mysql"
    exit 1
  fi

  # 首次启动：建库 + 设置 root 密码
  sleep 2
  MYSQL_SOCK=""
  [ -S "$DATA_DIR/mysql/mysql.sock" ] && MYSQL_SOCK="--socket=$DATA_DIR/mysql/mysql.sock"
  MYSQL_PWD="${MYSQL_PASSWORD:-root}"
  # 尝试空密码登录（刚 initialize-insecure 的实例）
  if mysql -uroot $MYSQL_SOCK -e "SELECT 1" >/dev/null 2>&1; then
    echo "  🔧 初始化 root 密码与 wikiagent 库 ..."
    mysql -uroot $MYSQL_SOCK <<SQL
ALTER USER 'root'@'localhost' IDENTIFIED BY '${MYSQL_PWD}';
CREATE DATABASE IF NOT EXISTS wikiagent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '${MYSQL_PWD}';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
FLUSH PRIVILEGES;
SQL
  else
    # 已有密码，仅确保库存在
    mysql -uroot -p"${MYSQL_PWD}" $MYSQL_SOCK -e \
      "CREATE DATABASE IF NOT EXISTS wikiagent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" 2>/dev/null || true
  fi
fi

# ------------------------------------------------------------
# 3. Redis 启动（port 6379）
# ------------------------------------------------------------
echo ""
echo "==> 启动 Redis (port 6379) ..."
if is_running 6379; then
  echo "  ⏭️  Redis 已在运行，跳过"
else
  if ! command -v redis-server >/dev/null 2>&1; then
    brew_install_if_missing redis redis-server
  fi
  nohup redis-server "$DATA_DIR/redis/redis.conf" \
    --port 6379 \
    --dir "$DATA_DIR/redis" \
    --appendonly yes \
    --maxmemory 512mb \
    --maxmemory-policy allkeys-lru \
    --daemonize no \
    > "$LOG_DIR/redis.log" 2>&1 &
  disown
  wait_for_port 6379 "Redis" 30
fi

# ------------------------------------------------------------
# 4. RocketMQ NameServer (port 9876) + Broker (port 10911)
# ------------------------------------------------------------
echo ""
echo "==> 启动 RocketMQ ..."
ROCKETMQ_HOME="${ROCKETMQ_HOME:-$HOME/.local/share/rocketmq}"
if is_running 9876; then
  echo "  ⏭️  RocketMQ NameServer 已在运行，跳过"
else
  if [ ! -x "$ROCKETMQ_HOME/bin/mqnamesrv" ]; then
    echo "  ❌ 未找到 RocketMQ，请先安装到 $ROCKETMQ_HOME"
    echo "     下载: https://rocketmq.apache.org/download/  (推荐 5.3.x 二进制)"
    echo "     解压: unzip rocketmq-all-*.zip -d $ROCKETMQ_HOME --strip-components=1"
    exit 1
  fi
  export NAMESRV_ADDR="127.0.0.1:9876"
  nohup sh "$ROCKETMQ_HOME/bin/mqnamesrv" > "$LOG_DIR/rocketmq-namesrv.log" 2>&1 &
  disown
  wait_for_port 9876 "RocketMQ NameServer" 30
fi

# Broker 通过 10911 端口探测
if lsof -ti:10911 >/dev/null 2>&1; then
  echo "  ⏭️  RocketMQ Broker 已在运行，跳过"
else
  nohup sh "$ROCKETMQ_HOME/bin/mqbroker" -n 127.0.0.1:9876 \
    -c "$ROCKETMQ_HOME/conf/broker.conf" \
    > "$LOG_DIR/rocketmq-broker.log" 2>&1 &
  disown
  echo "  ⏳ 等待 RocketMQ Broker 就绪 ..."
  sleep 5
fi

# ------------------------------------------------------------
# 5. Presidio Analyzer (port 5050) + Anonymizer (port 5051)
#    Python 包，pip 安装后通过 CLI 启动
# ------------------------------------------------------------
echo ""
echo "==> 启动 Presidio ..."
if is_running 5050; then
  echo "  ⏭️  Presidio Analyzer 已在运行，跳过"
else
  if ! python3 -c "import presidio_analyzer" 2>/dev/null; then
    echo "  📦 未检测到 presidio-analyzer，尝试 pip install ..."
    pip3 install presidio-analyzer presidio-anonymizer || {
      echo "  ⚠️  Presidio 安装失败，PII 检测将不可用（不阻塞启动）"
    }
  fi
  if python3 -c "import presidio_analyzer" 2>/dev/null; then
    # 启动 analyzer
    nohup python3 -m presidio_analyzer --port 5050 \
      > "$LOG_DIR/presidio-analyzer.log" 2>&1 &
    disown
    # 启动 anonymizer（若支持）
    if python3 -c "import presidio_anonymizer" 2>/dev/null; then
      nohup python3 -m presidio_anonymizer --port 5051 \
        > "$LOG_DIR/presidio-anonymizer.log" 2>&1 &
      disown
    fi
    wait_for_port 5050 "Presidio Analyzer" 30 || true
  fi
fi

# ------------------------------------------------------------
# 6. 中间件就绪汇总
# ------------------------------------------------------------
echo ""
echo "==> 中间件状态"
printf "  %-25s %s\n" "MySQL (3306):"        "$(is_running 3306 && echo '✅' || echo '❌')"
printf "  %-25s %s\n" "Redis (6379):"        "$(is_running 6379 && echo '✅' || echo '❌')"
printf "  %-25s %s\n" "RocketMQ NS (9876):"  "$(is_running 9876 && echo '✅' || echo '❌')"
printf "  %-25s %s\n" "Presidio (5050):"     "$(is_running 5050 && echo '✅' || echo '⚠️  可选')"
printf "  %-25s %s\n" "Milvus (19530):"      "$(is_running 19530 && echo '✅' || echo '⚠️  需自行启动')"
echo ""

# ------------------------------------------------------------
# 7. 应用启动
# ------------------------------------------------------------
if is_running $APP_PORT; then
  EXISTING_PID=$(lsof -ti:$APP_PORT | head -1)
  echo "⚠️  端口 $APP_PORT 已被占用 (PID: $EXISTING_PID)，应用可能已在运行"
  echo "   如需重启，请先执行 ./stop.sh"
  exit 0
fi

echo "正在后台启动 G2G Agent..."
nohup bash run.sh > "$LOG_FILE" 2>&1 &
PID=$!
disown $PID 2>/dev/null || true

echo "进程 PID: $PID"
echo "日志文件: $LOG_FILE"
echo "等待应用就绪（最多 90 秒）..."

for i in $(seq 1 90); do
  HEALTH=$(curl -s http://localhost:$APP_PORT/actuator/health 2>/dev/null || true)
  if echo "$HEALTH" | grep -q '"status":"UP"'; then
    echo ""
    echo "✅ 启动成功！"
    echo "   访问地址: http://localhost:$APP_PORT/"
    echo "   健康检查: $HEALTH"
    echo "   查看日志: tail -f $LOG_FILE"
    exit 0
  fi
  if [ $((i % 10)) -eq 0 ]; then
    echo "  ...已等待 ${i}s"
  fi
  sleep 1
done

echo ""
echo "⚠️  启动超时（90s），请检查日志: tail -f $LOG_FILE"
exit 1
