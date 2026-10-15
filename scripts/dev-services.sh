#!/usr/bin/env bash
# =============================================================================
# 全栈开发服务一键启停（Git Bash on Windows 专用）
#
# 用法：
#   ./scripts/dev-services.sh start     # 启动全部应用服务（已起的自动跳过）
#   ./scripts/dev-services.sh stop      # 停止全部应用服务
#   ./scripts/dev-services.sh restart   # 停止后再启动
#   ./scripts/dev-services.sh status    # 只看各服务端口状态
#
# 服务清单与启动顺序（网关最后——需业务服务先注册 Nacos）：
#   cloud-sso     9201   java -jar
#   cloud-system  9202   java -jar
#   cloud-bpmn    9203   java -jar
#   cloud-base-ai 8010   uvicorn（uv 绝对路径——不在 Git Bash PATH）
#   cloud-gateway 18080  java -jar（最后起）
#   cloud-web     5173   npm run dev（前端，与网关并级最后）
#
# 前置假设（本脚本不管理基础设施）：MySQL 3306 / Nacos 8848 / Redis 6379 /
#   （上传链路另需 Milvus 19530 + Ollama 11434）已在运行。
# 日志：logs/dev-services-<服务名>.log（logs/ 整目录已 gitignore）。
#
# Windows 陷阱（踩坑记档，勿改）：
#   - Git Bash 的 $! 是 MSYS 壳进程 PID，不是真实 java/node PID——停服只能
#     netstat -ano 按 LISTENING 端口反查 PID 再 taskkill //F //T //PID；
#   - taskkill 在 Git Bash 里斜杠要双写（//F //T //PID）。
# =============================================================================
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"

# 服务定义：名称|端口|启动命令（在 $ROOT 下执行）|就绪方式
# 网关必须排在业务服务之后；前端独立放最后。
SERVICES=(
  "cloud-sso|9201|java -jar cloud-base/cloud-sso/target/cloud-sso-1.0.0-SNAPSHOT.jar"
  "cloud-system|9202|java -jar cloud-base/cloud-system/target/cloud-system-1.0.0-SNAPSHOT.jar"
  "cloud-bpmn|9203|java -jar cloud-base/cloud-bpmn/target/cloud-bpmn-1.0.0-SNAPSHOT.jar"
)

# uv 不在 Git Bash PATH，按候选路径探测（conda env 优先，会话实证口径）
find_uv() {
  local candidate
  for candidate in \
    "$HOME/.conda/envs/cloud-ai/Scripts/uv.exe" \
    "$HOME/.local/bin/uv.exe" \
    "$(command -v uv 2>/dev/null || true)"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

AI_CMD="" # 延迟解析（start 时才找 uv）
GATEWAY_NAME="cloud-gateway"
GATEWAY_PORT=18080
GATEWAY_CMD="java -jar cloud-base/cloud-gateway/target/cloud-gateway-1.0.0-SNAPSHOT.jar"
WEB_NAME="cloud-web"
WEB_PORT=5173
WEB_CMD="npm run dev" # 需在 cloud-web 目录执行

# ---------- 基础工具 ----------

# 端口是否 LISTENING（netstat 行数 > 0）
port_up() {
  netstat -ano | grep LISTENING | grep -q ":$1 "
}

# 按端口取全部监听 PID（去重；一个端口可能 IPv4/IPv6 各一行同 PID 或不同 PID）
pids_of_port() {
  netstat -ano | grep LISTENING | grep ":$1 " | awk '{print $5}' | sort -u
}

# 轮询等端口就绪：wait_port <端口> <名称> <最大秒>
wait_port() {
  local port="$1" name="$2" max="${3:-90}" i
  for ((i = 0; i < max; i += 3)); do
    port_up "$port" && return 0
    sleep 3
  done
  echo "  [TIMEOUT] $name (:$port) ${max}s 内未监听——看 $LOG_DIR/ 下对应日志"
  return 1
}

# 启动单个服务（已监听则跳过）：start_one <名称> <端口> <命令> [工作目录]
start_one() {
  local name="$1" port="$2" cmd="$3" dir="${4:-$ROOT}"
  if port_up "$port"; then
    echo "  [SKIP ] $name (:$port) 已在运行"
    return 0
  fi
  echo "  [START] $name (:$port) ..."
  (
    cd "$dir" || exit 1
    nohup $cmd > "$LOG_DIR/dev-services-$name.log" 2>&1 &
  )
}

# 停止单个服务：kill_port <名称> <端口>
kill_port() {
  local name="$1" port="$2" pid
  if ! port_up "$port"; then
    echo "  [SKIP ] $name (:$port) 未在运行"
    return 0
  fi
  for pid in $(pids_of_port "$port"); do
    taskkill //F //T //PID "$pid" >/dev/null 2>&1 && echo "  [KILL ] $name (:$port) pid $pid"
  done
  sleep 1
  if port_up "$port"; then
    echo "  [FAIL ] $name (:$port) 端口仍被占用——手动查：netstat -ano | grep :$port"
    return 1
  fi
}

# ---------- 子命令 ----------

cmd_status() {
  printf "%-14s %-7s %-8s %s\n" "SERVICE" "PORT" "STATE" "PID"
  local row name port
  for row in "${SERVICES[@]}" \
    "cloud-base-ai|8010|" \
    "$GATEWAY_NAME|$GATEWAY_PORT|" \
    "$WEB_NAME|$WEB_PORT|"; do
    name="${row%%|*}"; port="$(echo "$row" | cut -d'|' -f2)"
    if port_up "$port"; then
      printf "%-14s %-7s %-8s %s\n" "$name" "$port" "UP" "$(pids_of_port "$port" | tr '\n' ' ')"
    else
      printf "%-14s %-7s %-8s\n" "$name" "$port" "DOWN"
    fi
  done
}

cmd_start() {
  echo "==> 启动后端业务服务（sso → system → bpmn）"
  local row name port cmd
  for row in "${SERVICES[@]}"; do
    name="${row%%|*}"; port="$(echo "$row" | cut -d'|' -f2)"; cmd="${row#*|*|}"
    start_one "$name" "$port" "$cmd"
  done
  for row in "${SERVICES[@]}"; do
    port="$(echo "$row" | cut -d'|' -f2)"
    wait_port "$port" "${row%%|*}" 90
  done

  echo "==> 启动 AI 基座 cloud-base-ai (:8010)"
  if [ -z "$AI_CMD" ]; then
    local uv
    if uv="$(find_uv)"; then
      AI_CMD="$uv run --project cloud-base-ai uvicorn cloud_base_ai.main:app --host 127.0.0.1 --port 8010"
    else
      echo "  [FAIL ] 未找到 uv（候选路径均不存在）——AI 基座跳过，其余继续"
    fi
  fi
  if [ -n "$AI_CMD" ]; then
    start_one "cloud-base-ai" 8010 "$AI_CMD"
    wait_port 8010 "cloud-base-ai" 60
  fi

  echo "==> 启动网关 $GATEWAY_NAME (:$GATEWAY_PORT，最后起)"
  start_one "$GATEWAY_NAME" "$GATEWAY_PORT" "$GATEWAY_CMD"
  wait_port "$GATEWAY_PORT" "$GATEWAY_NAME" 90

  echo "==> 启动前端 $WEB_NAME (:$WEB_PORT)"
  start_one "$WEB_NAME" "$WEB_PORT" "$WEB_CMD" "$ROOT/cloud-web"
  wait_port "$WEB_PORT" "$WEB_NAME" 60

  echo "==> 就绪冒烟：网关经 system 服务取 demo/ping"
  local i code=000
  for ((i = 0; i < 10; i++)); do
    code="$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$GATEWAY_PORT/system/demo/ping" 2>/dev/null)"
    [ "$code" = "200" ] && break
    sleep 2
  done
  if [ "$code" = "200" ]; then
    echo "  [OK   ] http://localhost:$GATEWAY_PORT/system/demo/ping -> 200"
  else
    echo "  [WARN ] 网关 ping 非 200（当前 $code）——服务可能仍在注册 Nacos，稍等重试；持续不通则查 logs/"
  fi
  echo "==> 完成。前端访问 http://localhost:$WEB_PORT（admin / admin123）"
}

cmd_stop() {
  # 与启动相反顺序：前端 → 网关 → AI → 业务服务
  echo "==> 停止全部服务（前端 → 网关 → AI 基座 → 业务服务）"
  kill_port "$WEB_NAME" "$WEB_PORT"
  kill_port "$GATEWAY_NAME" "$GATEWAY_PORT"
  kill_port "cloud-base-ai" 8010
  local row
  for ((idx = ${#SERVICES[@]} - 1; idx >= 0; idx--)); do
    row="${SERVICES[$idx]}"
    kill_port "${row%%|*}" "$(echo "$row" | cut -d'|' -f2)"
  done
  echo "==> 完成。复检："
  cmd_status
}

case "${1:-}" in
  start) cmd_start ;;
  stop) cmd_stop ;;
  restart) cmd_stop; echo; cmd_start ;;
  status) cmd_status ;;
  *)
    grep '^# 用法' -A 4 "$0" | sed 's/^# \?//'
    exit 1
    ;;
esac
