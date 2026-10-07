#!/bin/bash
# ============================================================
# G2G Agent 后台启动脚本（一键拉起全部本地中间件 + 应用）
# 用法: ./start.sh [--skip-web] [--force-web]
#   --skip-web   跳过前端构建（远端无 Node.js 时使用，沿用已部署静态产物）
#   --force-web  强制重新构建前端（默认仅源码比静态产物新时才重建）
# 特性:
#   - 幂等：已运行的中间件跳过启动
#   - 非 Docker：直接调用本机二进制（brew / 手动安装）
#   - 排除 Milvus / DashScope（外部依赖，需自行准备）
#   - 启动前自动构建/同步前端静态产物，避免后端托管旧页面
#   - nohup 后台运行，关闭终端仍可正常运行
# ============================================================
set -e
cd "$(dirname "$0")"

SKIP_WEB=false
FORCE_WEB=false
for arg in "$@"; do
  case "$arg" in
    --skip-web)  SKIP_WEB=true ;;
    --force-web) FORCE_WEB=true ;;
    *) echo "未知参数: $arg（支持: --skip-web / --force-web）"; exit 1 ;;
  esac
done

APP_PORT=8090
LOG_DIR="logs"
LOG_FILE="$LOG_DIR/app.log"
ENV_FILE=".env"

# 数据/日志目录（全部使用相对路径，可随项目目录整体迁移部署）
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
# 1.5 统一锁定 JDK 21（RocketMQ 等中间件也需在 JDK21 下运行；
#      应用本身 run.sh 会再次定位，此处 export 对其无害且保持一致）
#      Spring AI Alibaba 最低要求 JDK 21
# ------------------------------------------------------------
if [ -z "$JAVA_HOME" ] || ! "$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -q '"21'; then
  for candidate in \
    "$JDK21_HOME" \
    "$HOME"/.local/share/jdks/jdk-21*.jdk/Contents/Home \
    "$HOME"/.local/share/jdks/jdk-21* \
    "$(/usr/libexec/java_home -v 21 2>/dev/null)"; do
    if [ -x "$candidate/bin/java" ]; then
      JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
  echo "==> 使用 JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1) ($JAVA_HOME)"
else
  echo "⚠️  未定位到 JDK 21（Spring AI Alibaba 最低要求）。可设置 JDK21_HOME 指定路径。"
fi

# RocketMQ 本地开发内存约束（runbroker/runserver 均支持 JAVA_OPT_EXT 追加覆盖）
export JAVA_OPT_EXT="${JAVA_OPT_EXT:-} -XX:MaxDirectMemorySize=1g"

# ------------------------------------------------------------
# 2. MySQL 启动（port 3306）
# ------------------------------------------------------------
echo ""
echo "==> 启动 MySQL (port 3306) ..."
if is_running 3306; then
  echo "  ⏭️  MySQL 已在运行，跳过"
else
  # 定位/启动 mysqld。优先级：brew 服务 > $MYSQLD_HOME > 项目自管官方包 > PATH
  MYSQLD_BIN=""
  MYSQL_CLI=""
  if brew list mysql >/dev/null 2>&1; then
    brew services start mysql
    MYSQL_CLI="mysql"
    if ! wait_for_port 3306 "MySQL" 60; then
      echo "  ❌ brew MySQL 启动失败，请检查: brew services info mysql"
      exit 1
    fi
  else
    # 1) 显式指定：MYSQLD_HOME=/path/to/mysql-prefix
    if [ -n "${MYSQLD_HOME-}" ] && [ -x "$MYSQLD_HOME/bin/mysqld" ]; then
      MYSQLD_BIN="$MYSQLD_HOME/bin/mysqld"
    fi
    # 2) 项目自管（官方 tarball 解压到 ~/.local/share/mysql，与生产版本一致）
    #    glob 按版本排序取最新；目录不存在时不产生匹配
    if [ -z "$MYSQLD_BIN" ]; then
      for _d in "$HOME"/.local/share/mysql/mysql-8.*/bin/mysqld; do
        [ -x "$_d" ] && MYSQLD_BIN="$_d"
      done
    fi
    # 3) PATH 中查找（apt/yum/conda/brew-linked）
    if [ -z "$MYSQLD_BIN" ] && command -v mysqld >/dev/null 2>&1; then
      MYSQLD_BIN="$(command -v mysqld)"
    fi
    if [ -z "$MYSQLD_BIN" ]; then
      echo "  ❌ 未找到 mysqld（可 brew/apt 安装，或解压官方包并设置 MYSQLD_HOME）"
      exit 1
    fi
    MYSQL_PREFIX="$(cd "$(dirname "$MYSQLD_BIN")/.." && pwd)"
    MYSQL_CLI="$MYSQL_PREFIX/bin/mysql"; [ -x "$MYSQL_CLI" ] || MYSQL_CLI="mysql"
    # errmsg.sys 位置随发行版不同，动态探测（非写死路径，迁移到其他机器同样生效）：
    #   官方 tarball -> <prefix>/share/english/errmsg.sys
    #   conda-forge -> <prefix>/share/mysql/english/errmsg.sys
    #   旧 brew/系统 -> <prefix>/share/mysql/errmsg.sys
    MYSQL_ERRMSG_ARG=""
    if [ -f "$MYSQL_PREFIX/share/english/errmsg.sys" ]; then
      MYSQL_ERRMSG_ARG="--lc-messages-dir=$MYSQL_PREFIX/share/english"
    elif [ -f "$MYSQL_PREFIX/share/mysql/english/errmsg.sys" ]; then
      MYSQL_ERRMSG_ARG="--lc-messages-dir=$MYSQL_PREFIX/share/mysql/english"
    elif [ -f "$MYSQL_PREFIX/share/mysql/errmsg.sys" ]; then
      MYSQL_ERRMSG_ARG="--lc-messages-dir=$MYSQL_PREFIX/share/mysql"
    fi
    # root 运行系统包时 mysqld 要求降权 --user；普通用户无需传（某些包传了反崩）
    MYSQL_USER_ARG=""
    [ "$(id -u)" = "0" ] && MYSQL_USER_ARG="--user=mysql"
    # MySQL 8.0 仅允许 lower_case_table_names=0/1 且初始化与启动必须一致；
    # macOS 文件系统大小写不敏感，自动检测为 2 会在初始化时直接 signal 11。
    MYSQL_LCTN_ARG="--lower-case-table-names=1"
    MYSQL_DATADIR="$DATA_DIR/mysql"
    if [ ! -f "$MYSQL_DATADIR/auto.cnf" ]; then
      echo "  🔧 首次初始化 MySQL 数据目录 $MYSQL_DATADIR ..."
      ( cd "$MYSQL_DATADIR" && \
        "$MYSQLD_BIN" --no-defaults $MYSQL_ERRMSG_ARG \
          --initialize-insecure --datadir=. \
          $MYSQL_LCTN_ARG $MYSQL_USER_ARG )
    fi
    # mysqld 启动时会 chdir 到 datadir，--socket/--pid-file/--log-error 的相对路径
    # 都会相对 datadir 解析。因此在子 shell 内 cd 到 datadir，统一使用文件名，
    # 既兼容该行为又保持全相对路径部署。--no-defaults 必须置于首位，避免读取
    # 系统全局 my.cnf 干扰。
    (
      cd "$MYSQL_DATADIR"
      exec nohup "$MYSQLD_BIN" --no-defaults $MYSQL_ERRMSG_ARG \
        --datadir=. \
        $MYSQL_LCTN_ARG $MYSQL_USER_ARG \
        --port=3306 \
        --socket=mysql.sock \
        --pid-file=mysql.pid \
        --log-error=mysql-error.log \
        --character-set-server=utf8mb4 \
        --collation-server=utf8mb4_unicode_ci
    ) > "$LOG_DIR/mysql.out" 2>&1 &
    disown
    if ! wait_for_port 3306 "MySQL" 60; then
      echo "  📋 MySQL 错误日志（最后 20 行）:"
      tail -n 20 "$MYSQL_DATADIR/mysql-error.log" 2>/dev/null \
        || tail -n 20 "$LOG_DIR/mysql.out" 2>/dev/null
      exit 1
    fi
  fi

  # 首次启动：建库 + 设置 root 密码
  sleep 2
  MYSQL_SOCK=""
  [ -S "$DATA_DIR/mysql/mysql.sock" ] && MYSQL_SOCK="--socket=$DATA_DIR/mysql/mysql.sock"
  # 尊重 .env：MYSQL_PASSWORD 允许为空（initialize-insecure 即空密码）。
  # 注意用 ${VAR-} 而非 ${VAR:-root}，否则显式空密码会被错误兜底成 root。
  MYSQL_PWD="${MYSQL_PASSWORD-}"
  MYSQL_PWD_ARG=""
  [ -n "$MYSQL_PWD" ] && MYSQL_PWD_ARG="-p${MYSQL_PWD}"
  # 尝试空密码登录（刚 initialize-insecure 的实例）
  if "$MYSQL_CLI" -uroot $MYSQL_SOCK -e "SELECT 1" >/dev/null 2>&1; then
    echo "  🔧 初始化 root 账户与 wikiagent 库 ..."
    if [ -n "$MYSQL_PWD" ]; then
      "$MYSQL_CLI" -uroot $MYSQL_SOCK <<SQL
ALTER USER 'root'@'localhost' IDENTIFIED BY '${MYSQL_PWD}';
CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '${MYSQL_PWD}';
GRANT ALL PRIVILEGES ON *.* TO 'root'@'127.0.0.1' WITH GRANT OPTION;
FLUSH PRIVILEGES;
SQL
    fi
    "$MYSQL_CLI" -uroot $MYSQL_SOCK -e \
      "CREATE DATABASE IF NOT EXISTS wikiagent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  else
    # 已有密码，仅确保库存在（空密码时不带 -p，否则会进入交互式密码提示）
    "$MYSQL_CLI" -uroot $MYSQL_PWD_ARG $MYSQL_SOCK -e \
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
  # redis.conf 为可选：首次启动时数据目录下没有配置文件，
  # 若把不存在的文件作为位置参数传给 redis-server 会直接 fatal 退出。
  REDIS_CONF_ARG=""
  if [ -f "$DATA_DIR/redis/redis.conf" ]; then
    REDIS_CONF_ARG="$DATA_DIR/redis/redis.conf"
  fi
  nohup redis-server $REDIS_CONF_ARG \
    --port 6379 \
    --dir "$DATA_DIR/redis" \
    --appendonly yes \
    --maxmemory 512mb \
    --maxmemory-policy allkeys-lru \
    --daemonize no \
    > "$LOG_DIR/redis.log" 2>&1 &
  disown
  if ! wait_for_port 6379 "Redis" 30; then
    echo "  📋 Redis 日志（最后 20 行）:"
    tail -n 20 "$LOG_DIR/redis.log" 2>/dev/null
    exit 1
  fi
fi

# ------------------------------------------------------------
# 4. RocketMQ NameServer (port 9876) + Broker (port 10911)
# ------------------------------------------------------------
echo ""
echo "==> 启动 RocketMQ ..."
# 项目自管安装目录优先，避免被 shell profile 中遗留的旧版 ROCKETMQ_HOME
# （如 4.x，runserver.sh 仍使用 CMS GC）污染——旧版在 JDK21 下无法启动。
DEFAULT_ROCKETMQ_HOME="$HOME/.local/share/rocketmq"
if [ -x "$DEFAULT_ROCKETMQ_HOME/bin/mqnamesrv" ]; then
  if [ -n "$ROCKETMQ_HOME" ] && [ "$ROCKETMQ_HOME" != "$DEFAULT_ROCKETMQ_HOME" ]; then
    echo "  ℹ️  忽略环境变量 ROCKETMQ_HOME=$ROCKETMQ_HOME（旧版/外部版本），使用项目自管版本:"
    echo "     $DEFAULT_ROCKETMQ_HOME"
  fi
  ROCKETMQ_HOME="$DEFAULT_ROCKETMQ_HOME"
elif [ -n "$ROCKETMQ_HOME" ] && [ -x "$ROCKETMQ_HOME/bin/mqnamesrv" ]; then
  # JDK21 兼容性校验：5.x 的 runserver.sh 含 JDK9+ 的 G1GC 分支；
  # 只有 CMS、没有 G1GC 的旧 4.x 会在 JDK21 下直接 fatal。
  if grep -q "UseConcMarkSweepGC" "$ROCKETMQ_HOME/bin/runserver.sh" \
     && ! grep -q "UseG1GC" "$ROCKETMQ_HOME/bin/runserver.sh"; then
    echo "  ❌ 外部 ROCKETMQ_HOME 为不兼容 JDK21 的旧版本（仅含 CMS GC 参数）:"
    echo "     $ROCKETMQ_HOME"
    echo "     请安装 RocketMQ 5.3.x 到 $DEFAULT_ROCKETMQ_HOME"
    exit 1
  fi
else
  ROCKETMQ_HOME="$DEFAULT_ROCKETMQ_HOME"
fi
if [ ! -x "$ROCKETMQ_HOME/bin/mqnamesrv" ]; then
  echo "  ❌ 未找到 RocketMQ，请先安装到 $ROCKETMQ_HOME"
  echo "     下载: https://rocketmq.apache.org/download/  (推荐 5.3.x 二进制)"
  echo "     解压: unzip rocketmq-all-*.zip -d $ROCKETMQ_HOME --strip-components=1"
  exit 1
fi
# 官方 runserver/runbroker 脚本在 macOS 上默认用 hdiutil 创建 /Volumes/RAMDisk
# 存放 GC 日志；无权限（CI/沙箱/部分远端）时会静默失败并连锁导致 JVM 启动异常。
# 幂等改写为项目内 logs 目录（运行时动态解析，不写死机器路径）。
RMQ_GC_DIR="$(cd "$LOG_DIR" && pwd)/rocketmq-gc"
mkdir -p "$RMQ_GC_DIR"
for _rmq_sh in runserver.sh runbroker.sh; do
  if grep -q 'GC_LOG_DIR="/Volumes/RAMDisk"' "$ROCKETMQ_HOME/bin/$_rmq_sh" 2>/dev/null; then
    sed -i.bak "s|GC_LOG_DIR=\"/Volumes/RAMDisk\"|GC_LOG_DIR=\"$RMQ_GC_DIR\"|g" "$ROCKETMQ_HOME/bin/$_rmq_sh"
    echo "  🔧 已将 $_rmq_sh 的 GC 日志目录重定向到 $RMQ_GC_DIR"
  fi
done
unset _rmq_sh

if is_running 9876; then
  echo "  ⏭️  RocketMQ NameServer 已在运行，跳过"
else
  export NAMESRV_ADDR="127.0.0.1:9876"
  nohup sh "$ROCKETMQ_HOME/bin/mqnamesrv" > "$LOG_DIR/rocketmq-namesrv.log" 2>&1 &
  disown
  if ! wait_for_port 9876 "RocketMQ NameServer" 30; then
    echo "  📋 NameServer 日志（最后 20 行）:"
    tail -n 20 "$LOG_DIR/rocketmq-namesrv.log" 2>/dev/null
    exit 1
  fi
fi

# Broker 通过 10911 端口探测
if lsof -ti:10911 >/dev/null 2>&1; then
  echo "  ⏭️  RocketMQ Broker 已在运行，跳过"
else
  nohup sh "$ROCKETMQ_HOME/bin/mqbroker" -n 127.0.0.1:9876 \
    -c "$ROCKETMQ_HOME/conf/broker.conf" \
    > "$LOG_DIR/rocketmq-broker.log" 2>&1 &
  disown
  if ! wait_for_port 10911 "RocketMQ Broker" 45; then
    echo "  📋 Broker 日志（最后 20 行）:"
    tail -n 20 "$LOG_DIR/rocketmq-broker.log" 2>/dev/null
    exit 1
  fi
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
  # Presidio 为可选组件，环境不全时跳过，绝不阻塞主流程。
  # 2.2.355+ 起 HTTP 服务被拆为 server 扩展（flask/gunicorn），
  # 且运行需要 spacy 语言模型（如 en_core_web_lg）。
  PRESIDIO_READY=""
  if command -v python3 >/dev/null 2>&1 \
     && python3 -c "import presidio_analyzer, presidio_anonymizer, flask, gunicorn" 2>/dev/null \
     && python3 -c "import spacy; spacy.load('en_core_web_lg')" 2>/dev/null; then
    PRESIDIO_READY=1
  fi
  if [ -n "$PRESIDIO_READY" ]; then
    # analyzer / anonymizer 均以 gunicorn 拉起 flask 应用
    nohup python3 -m gunicorn -b 127.0.0.1:5050 \
      'presidio_analyzer.app:create_app()' \
      > "$LOG_DIR/presidio-analyzer.log" 2>&1 &
    disown
    nohup python3 -m gunicorn -b 127.0.0.1:5051 \
      'presidio_anonymizer.app:create_app()' \
      > "$LOG_DIR/presidio-anonymizer.log" 2>&1 &
    disown
    wait_for_port 5050 "Presidio Analyzer" 30 || true
  else
    echo "  ⚠️  未检测到完整的 Presidio 运行环境（缺 server 扩展或 spacy 模型），PII 检测跳过（不阻塞）"
    echo "      如需启用: pip install 'presidio-analyzer[server]' 'presidio-anonymizer[server]' && python3 -m spacy download en_core_web_lg"
  fi
fi

# ------------------------------------------------------------
# 5.5 RAGAS 离线评测环境预检（可选功能，缺失只提示不阻塞应用启动）
#     看板"执行 RAGAS 测评"按钮由后端 spawn 该解释器并 import ragas；
#     可用 .env 中 WIKIAGENT_RAGAS_PYTHON 指定解释器
#     （Spring Boot 松散绑定到 wikiagent.ragas.python）。
#     只用 find_spec 探测安装状态（亚秒级），不执行 ragas 包初始化
#     （完整 import 冷启动约 18s，不应拖慢开机）；点击按钮时后端会再做完整检查。
# ------------------------------------------------------------
echo ""
echo "==> 检查 RAGAS 评测环境 ..."
RAGAS_PYTHON="${WIKIAGENT_RAGAS_PYTHON:-python3}"
RAGAS_READY=false
if command -v "$RAGAS_PYTHON" >/dev/null 2>&1 \
   && "$RAGAS_PYTHON" -c "import importlib.util; raise SystemExit(0 if importlib.util.find_spec('ragas') else 1)" 2>/dev/null; then
  echo "  ✅ RAGAS 就绪: $RAGAS_PYTHON ($("$RAGAS_PYTHON" --version 2>&1))，ragas 已安装"
  RAGAS_READY=true
else
  echo "  ⚠️  RAGAS 评测环境未就绪（不影响应用启动；看板点击\"执行 RAGAS 测评\"将被前置检查拦截）"
  echo "      当前解释器: $RAGAS_PYTHON"
  echo "      安装依赖:   $RAGAS_PYTHON -m pip install -r eval/ragas/requirements.txt"
  echo "      指定解释器: 在 .env 中设置 WIKIAGENT_RAGAS_PYTHON=/path/to/python3"
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
if [ "$RAGAS_READY" = true ]; then
  printf "  %-25s %s\n" "RAGAS 评测:"          "✅ $RAGAS_PYTHON"
else
  printf "  %-25s %s\n" "RAGAS 评测:"          "⚠️  可选（未装 ragas，见上方提示）"
fi
echo ""

# ------------------------------------------------------------
# 6.5 前端构建（Next.js 静态导出 → Spring Boot 静态目录）
#     默认仅在前端源码新于已部署产物时重建；
#     --force-web 强制重建，--skip-web 跳过（无 Node 的远端机器使用）。
#     构建失败必须中止启动，否则会继续使用旧页面且无任何提示。
# ------------------------------------------------------------
echo ""
echo "==> 前端静态产物 ..."

WEB_STATIC_DIR="src/main/resources/static"

# 跨平台文件 mtime：macOS 为 stat -f %m，Linux 为 stat -c %Y（远端部署兼容）
if stat -f %m . >/dev/null 2>&1; then
  STAT_MTIME="stat -f %m"
else
  STAT_MTIME="stat -c %Y"
fi

# 前端源码是否比已部署静态产物新；返回 0=需要构建，1=无需构建
web_needs_build() {
  local marker="$WEB_STATIC_DIR/index.html"
  if [ ! -f "$marker" ]; then
    echo "  🔧 静态产物不存在，执行首次构建"
    return 0
  fi
  local marker_ts newest_ts
  marker_ts=$($STAT_MTIME "$marker")
  # 排除 node_modules/out/.next，取前端源文件最新 mtime
  newest_ts=$(
    find frontend \
      \( -path frontend/node_modules -o -path frontend/out -o -path frontend/.next \) -prune \
      -o -type f -print0 2>/dev/null \
      | xargs -0 $STAT_MTIME 2>/dev/null | sort -nr | head -1
  )
  if [ -z "$newest_ts" ]; then
    echo "  ⚠️ 未找到前端源码，沿用已有静态产物"
    return 1
  fi
  if [ "$newest_ts" -gt "$marker_ts" ]; then
    echo "  🔧 检测到前端源码更新，重新构建"
    return 0
  fi
  echo "  ✅ 前端产物为最新，跳过构建（--force-web 可强制重建）"
  return 1
}

# 执行构建并同步；返回码: 0=成功 1=构建失败 2=缺少 node/npm
build_web() {
  if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    echo "  ⚠️ 未检测到 node/npm，无法构建前端"
    return 2
  fi
  echo "  node $(node -v) / npm $(npm -v)"
  # 依赖缺失或 package-lock 比 node_modules 新时安装（npm ci 失败回退 npm install）
  if [ ! -d frontend/node_modules ] \
     || [ "$($STAT_MTIME frontend/package-lock.json)" -gt "$($STAT_MTIME frontend/node_modules)" ]; then
    echo "  📦 安装/更新前端依赖 ..."
    ( cd frontend && npm ci ) || ( cd frontend && npm install ) || return 1
  fi
  echo "  🏗️  Next.js 静态导出构建 ..."
  ( cd frontend && NEXT_BUILD_STATIC=true npm run build ) || return 1
  if ! command -v rsync >/dev/null 2>&1; then
    echo "  ❌ 缺少 rsync，无法同步静态产物"
    return 1
  fi
  rsync -a --delete frontend/out/ "$WEB_STATIC_DIR/"
  # run.sh 以 spring-boot:run 启动，静态资源实际从 target/classes 加载，
  # 不同步会导致重启后仍是旧页面
  if [ -d target/classes/static ]; then
    rsync -a --delete frontend/out/ target/classes/static/
  fi
  echo "  ✅ 前端构建并同步完成"
}

if [ "$SKIP_WEB" = true ]; then
  echo "  ⏭️ 已指定 --skip-web，跳过前端构建"
  if [ ! -f "$WEB_STATIC_DIR/index.html" ]; then
    echo "  ❌ $WEB_STATIC_DIR/index.html 不存在，无法跳过构建"
    exit 1
  fi
elif [ "$FORCE_WEB" = true ]; then
  if ! build_web; then
    echo "❌ 前端构建失败，终止启动（避免继续使用旧页面）"
    exit 1
  fi
elif web_needs_build; then
  rc=0
  build_web || rc=$?
  if [ "$rc" = 2 ]; then
    if [ ! -f "$WEB_STATIC_DIR/index.html" ]; then
      echo "❌ 无静态产物且本机无法构建，终止启动"
      exit 1
    fi
    echo "  ⚠️ 沿用已有静态产物（可能不是最新；安装 Node.js 后去掉 --skip-web 重新启动）"
  elif [ "$rc" != 0 ]; then
    echo "❌ 前端构建失败，终止启动（避免继续使用旧页面）"
    exit 1
  fi
fi

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
