#!/usr/bin/env bash
# Build a failover demo JAR locally, deploy to a jump host (e.g. YBA), and run failover tests.
#
# Required:
#   YBA_HOST          SSH target, e.g. ec2-user@203.0.113.1
#   hosts-internal.env or hosts.env (copy from *.example)
#
# Optional:
#   SSH_KEY           local key for YBA and DB nodes (also set in hosts.env)
#   REMOTE_DIR        remote install path (default /home/ec2-user/failover-test)
#   FAILOVER_DEMO_JAR pre-built demo JAR (skips Maven build)
#   DEMO_MODULE_DIR   Maven module to build (default examples/senior-geometry-demo)
#   HOSTS_DEPLOY_ENV  env file copied to remote scripts/hosts.env
#
# Usage: ./run-on-yba.sh [deploy|demo|failover|recover|all]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

YBA_HOST="${YBA_HOST:-}"
REMOTE_DIR="${REMOTE_DIR:-/home/ec2-user/failover-test}"
DEMO_MODULE_DIR="${DEMO_MODULE_DIR:-${REPO_ROOT}/examples/senior-geometry-demo}"
HOSTS_DEPLOY_ENV="${HOSTS_DEPLOY_ENV:-}"
MODE="${1:-all}"

if [[ -z "${YBA_HOST}" ]]; then
  echo "Set YBA_HOST (e.g. export YBA_HOST=ec2-user@203.0.113.1)" >&2
  exit 1
fi

load_hosts_env "${SCRIPT_DIR}"

if [[ -z "${HOSTS_DEPLOY_ENV}" ]]; then
  if [[ -f "${SCRIPT_DIR}/hosts-internal.env" ]]; then
    HOSTS_DEPLOY_ENV="${SCRIPT_DIR}/hosts-internal.env"
  else
    HOSTS_DEPLOY_ENV="${SCRIPT_DIR}/hosts.env"
  fi
fi

PRIMARY_ID="${PRIMARY_UNIVERSE_ID:-primary}"
STANDBY_ID="${STANDBY_UNIVERSE_ID:-standby}"

ssh_yba() {
  ssh -i "${SSH_KEY}" -o StrictHostKeyChecking=no "${YBA_HOST}" "$@"
}

scp_yba() {
  scp -i "${SSH_KEY}" -o StrictHostKeyChecking=no "$@"
}

resolve_demo_jar() {
  if [[ -n "${FAILOVER_DEMO_JAR:-}" ]]; then
    echo "${FAILOVER_DEMO_JAR}"
    return
  fi
  if [[ ! -d "${DEMO_MODULE_DIR}" ]]; then
    echo "Demo module not found at ${DEMO_MODULE_DIR}." >&2
    echo "Set FAILOVER_DEMO_JAR to a built JAR, or DEMO_MODULE_DIR to your demo app." >&2
    exit 1
  fi
  (cd "${REPO_ROOT}" && mvn clean install -DskipTests -q)
  (cd "${DEMO_MODULE_DIR}" && mvn package -DskipTests -q)
  ls "${DEMO_MODULE_DIR}"/target/*-SNAPSHOT.jar 2>/dev/null | grep -v original | head -1
}

deploy() {
  echo "==> Building fat JAR..."
  local jar
  jar="$(resolve_demo_jar)"

  echo "==> Installing Java on jump host (if needed)..."
  ssh_yba 'command -v java >/dev/null || sudo dnf install -y java-17-openjdk-headless 2>/dev/null || sudo apt-get install -y openjdk-17-jre-headless'

  echo "==> Copying demo and scripts to ${YBA_HOST}:${REMOTE_DIR}..."
  ssh_yba "mkdir -p ${REMOTE_DIR}/scripts"
  scp_yba "${jar}" "${YBA_HOST}:${REMOTE_DIR}/failover-demo.jar"
  scp_yba "${SSH_KEY}" "${YBA_HOST}:${REMOTE_DIR}/cluster_ssh_key"
  scp_yba "${HOSTS_DEPLOY_ENV}" "${YBA_HOST}:${REMOTE_DIR}/scripts/hosts.env"
  scp_yba "${SCRIPT_DIR}/stop-universe-tservers.sh" \
    "${SCRIPT_DIR}/start-universe-tservers.sh" \
    "${SCRIPT_DIR}/check-universe-tservers.sh" \
    "${SCRIPT_DIR}/lib.sh" \
    "${YBA_HOST}:${REMOTE_DIR}/scripts/"
  ssh_yba "chmod 600 ${REMOTE_DIR}/cluster_ssh_key && chmod +x ${REMOTE_DIR}/scripts/*.sh"

  ssh_yba "cat > ${REMOTE_DIR}/run-demo.sh" <<'REMOTE'
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=scripts/hosts.env
source ./scripts/hosts.env
export SSH_KEY="${PWD}/cluster_ssh_key"
export PRIMARY_HOSTS STANDBY_HOSTS PRIMARY_TOPOLOGY_KEYS STANDBY_TOPOLOGY_KEYS SMART_DRIVER_LOAD_BALANCE
# Aliases for demo apps that use US_/EU_ env names
export US_HOSTS="${PRIMARY_HOSTS:-}"
export EU_HOSTS="${STANDBY_HOSTS:-}"
export US_TOPOLOGY_KEYS="${PRIMARY_TOPOLOGY_KEYS:-}"
export EU_TOPOLOGY_KEYS="${STANDBY_TOPOLOGY_KEYS:-}"
export PROBE_INTERVAL_SEC="${PROBE_INTERVAL_SEC:-3}"
export PROBE_FAILURES="${PROBE_FAILURES:-3}"
export COOLDOWN_SEC="${COOLDOWN_SEC:-30}"
export QUERY_INTERVAL_SEC="${QUERY_INTERVAL_SEC:-2}"
exec java -jar failover-demo.jar
REMOTE
  ssh_yba "chmod +x ${REMOTE_DIR}/run-demo.sh"
  echo "Deploy complete."
}

run_demo_bg() {
  echo "==> Starting demo on jump host (background)..."
  ssh_yba "if [ -f ${REMOTE_DIR}/demo.pid ]; then kill \"\$(cat ${REMOTE_DIR}/demo.pid)\" 2>/dev/null || true; rm -f ${REMOTE_DIR}/demo.pid; fi"
  sleep 1
  ssh_yba "nohup ${REMOTE_DIR}/run-demo.sh > ${REMOTE_DIR}/demo.log 2>&1 & echo \$! > ${REMOTE_DIR}/demo.pid"
  sleep 8
  ssh_yba "tail -20 ${REMOTE_DIR}/demo.log"
}

trigger_failover() {
  echo "==> Stopping primary (${PRIMARY_ID}) tservers from jump host..."
  ssh_yba "SSH_KEY=${REMOTE_DIR}/cluster_ssh_key HOSTS_ENV=${REMOTE_DIR}/scripts/hosts.env ${REMOTE_DIR}/scripts/stop-universe-tservers.sh primary"
  echo "==> Waiting for failover (~30s)..."
  sleep 30
  ssh_yba "grep -E 'FAILOVER|Active universe changed|active=${STANDBY_ID}' ${REMOTE_DIR}/demo.log | tail -5 || tail -30 ${REMOTE_DIR}/demo.log"
}

recover() {
  echo "==> Restarting primary (${PRIMARY_ID}) tservers..."
  ssh_yba "SSH_KEY=${REMOTE_DIR}/cluster_ssh_key HOSTS_ENV=${REMOTE_DIR}/scripts/hosts.env ${REMOTE_DIR}/scripts/start-universe-tservers.sh primary"
  ssh_yba "if [ -f ${REMOTE_DIR}/demo.pid ]; then kill \"\$(cat ${REMOTE_DIR}/demo.pid)\" 2>/dev/null || true; rm -f ${REMOTE_DIR}/demo.pid; fi"
}

case "${MODE}" in
  deploy) deploy ;;
  demo) deploy && run_demo_bg ;;
  failover) trigger_failover ;;
  recover) recover ;;
  all) deploy && run_demo_bg && trigger_failover && recover ;;
  *)
    echo "Usage: $0 [deploy|demo|failover|recover|all]" >&2
    exit 1
    ;;
esac
