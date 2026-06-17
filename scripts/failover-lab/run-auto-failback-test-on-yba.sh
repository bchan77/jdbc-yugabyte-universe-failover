#!/usr/bin/env bash
# Live auto-failback integration test via a jump host (e.g. YBA).
# 1. Start demo with ALLOW_AUTO_FAILBACK=true
# 2. Stop all primary tservers -> failover primary -> standby
# 3. Restart primary tservers before cooldown ends
# 4. After cooldown, policy failbacks standby -> primary; verify queries on primary
#
# Requires the same env vars as run-on-yba.sh (YBA_HOST, hosts.env, SSH_KEY).
#
# Usage: ./run-auto-failback-test-on-yba.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

YBA_HOST="${YBA_HOST:-}"
SSH_KEY="${SSH_KEY:-}"
REMOTE_DIR="${REMOTE_DIR:-/home/ec2-user/failover-test}"
LOG="${REMOTE_DIR}/failback-test.log"
COOLDOWN_SEC="${COOLDOWN_SEC:-30}"

if [[ -z "${YBA_HOST}" ]]; then
  echo "Set YBA_HOST (e.g. export YBA_HOST=ec2-user@203.0.113.1)" >&2
  exit 1
fi

load_hosts_env "${SCRIPT_DIR}"

PRIMARY_ID="${PRIMARY_UNIVERSE_ID:-primary}"
STANDBY_ID="${STANDBY_UNIVERSE_ID:-standby}"
PRIMARY_HOST_PATTERN="$(primary_host_grep_pattern)"

stop_demo() {
  ssh -i "${SSH_KEY}" -o StrictHostKeyChecking=no "${YBA_HOST}" \
    "if [ -f ${REMOTE_DIR}/demo.pid ]; then kill \"\$(cat ${REMOTE_DIR}/demo.pid)\" 2>/dev/null || true; rm -f ${REMOTE_DIR}/demo.pid; fi"
}

ssh_yba() {
  ssh -i "${SSH_KEY}" -o StrictHostKeyChecking=no "${YBA_HOST}" "$@"
}

wait_for_log() {
  local pattern="$1"
  local timeout_sec="$2"
  local desc="$3"
  echo "==> Waiting for: ${desc} (up to ${timeout_sec}s)..."
  if ssh_yba "for i in \$(seq 1 ${timeout_sec}); do grep -qE '${pattern}' '${LOG}' 2>/dev/null && exit 0; sleep 1; done; exit 1"; then
    echo "    OK: ${desc}"
    return 0
  fi
  echo "    FAIL: ${desc} not seen in ${LOG}" >&2
  ssh_yba "tail -30 '${LOG}'" >&2 || true
  return 1
}

echo "==> Deploying demo to jump host..."
"${SCRIPT_DIR}/run-on-yba.sh" deploy

echo "==> Starting demo with ALLOW_AUTO_FAILBACK=true..."
stop_demo
sleep 2
ssh_yba "rm -f '${LOG}'"
ssh_yba "cat > ${REMOTE_DIR}/run-failback-test.sh" <<REMOTE
#!/usr/bin/env bash
set -euo pipefail
cd "\$(dirname "\$0")"
source ./scripts/hosts.env
export SSH_KEY="\${PWD}/cluster_ssh_key"
export PRIMARY_HOSTS STANDBY_HOSTS PRIMARY_TOPOLOGY_KEYS STANDBY_TOPOLOGY_KEYS SMART_DRIVER_LOAD_BALANCE
export US_HOSTS="\${PRIMARY_HOSTS:-}"
export EU_HOSTS="\${STANDBY_HOSTS:-}"
export US_TOPOLOGY_KEYS="\${PRIMARY_TOPOLOGY_KEYS:-}"
export EU_TOPOLOGY_KEYS="\${STANDBY_TOPOLOGY_KEYS:-}"
export ALLOW_AUTO_FAILBACK=true
export PROBE_INTERVAL_SEC=3
export PROBE_FAILURES=3
export COOLDOWN_SEC=${COOLDOWN_SEC}
export QUERY_INTERVAL_SEC=2
exec java -jar failover-demo.jar
REMOTE
ssh_yba "chmod +x ${REMOTE_DIR}/run-failback-test.sh"
ssh_yba "nohup ${REMOTE_DIR}/run-failback-test.sh >> '${LOG}' 2>&1 & echo \$! > ${REMOTE_DIR}/demo.pid"

sleep 15
wait_for_log "OK  host=${PRIMARY_HOST_PATTERN}" 45 "initial primary query"

echo "==> Stopping primary tservers (trigger failover to standby)..."
ssh_yba "SSH_KEY=${REMOTE_DIR}/cluster_ssh_key HOSTS_ENV=${REMOTE_DIR}/scripts/hosts.env ${REMOTE_DIR}/scripts/stop-universe-tservers.sh primary"
wait_for_log "Active universe changed: ${PRIMARY_ID} -> ${STANDBY_ID}|FAILOVER ${PRIMARY_ID} -> ${STANDBY_ID}" 60 "failover ${PRIMARY_ID} -> ${STANDBY_ID}"

echo "==> Restarting primary tservers before cooldown ends..."
ssh_yba "SSH_KEY=${REMOTE_DIR}/cluster_ssh_key HOSTS_ENV=${REMOTE_DIR}/scripts/hosts.env ${REMOTE_DIR}/scripts/start-universe-tservers.sh primary"
sleep 15

failback_wait=$((COOLDOWN_SEC + 45))
wait_for_log "Active universe changed: ${STANDBY_ID} -> ${PRIMARY_ID}|FAILBACK ${STANDBY_ID} -> ${PRIMARY_ID}" "${failback_wait}" "auto-failback ${STANDBY_ID} -> ${PRIMARY_ID}"

sleep 8
echo "==> Verifying post-failback queries on primary..."
primary_line=$(ssh_yba "grep -E 'OK  host=${PRIMARY_HOST_PATTERN}' '${LOG}' | tail -1" || true)
if [[ -n "${primary_line}" ]]; then
  echo "    ${primary_line}"
  echo ""
  echo "PASS: auto-failback live test succeeded"
  stop_demo
  exit 0
fi

echo "FAIL: no successful primary query after failback" >&2
ssh_yba "tail -40 '${LOG}'" >&2
stop_demo
exit 1
