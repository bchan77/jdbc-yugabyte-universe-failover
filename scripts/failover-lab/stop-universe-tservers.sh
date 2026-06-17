#!/usr/bin/env bash
# Stop yb-tserver on every node in a universe (simulates universe-level outage).
# Usage: ./stop-universe-tservers.sh primary|standby
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"
load_hosts_env "${SCRIPT_DIR}"

UNIVERSE="${1:-}"
if [[ -z "${UNIVERSE}" ]]; then
  echo "Usage: $0 primary|standby" >&2
  exit 1
fi

NODES="$(resolve_universe_nodes "${UNIVERSE}")"

echo "Stopping yb-tserver on ${UNIVERSE} nodes: ${NODES}"

for host in ${NODES}; do
  echo "--- ${host} ---"
  ssh -i "${SSH_KEY}" -o StrictHostKeyChecking=no -o ConnectTimeout=10 \
    "${SSH_USER}@${host}" bash -s <<'REMOTE'
set -euo pipefail
if systemctl --user is-active yb-tserver.service &>/dev/null; then
  systemctl --user stop yb-tserver.service
  echo "Stopped via systemctl --user yb-tserver.service"
elif systemctl is-active yb-tserver.service &>/dev/null 2>&1; then
  systemctl stop yb-tserver.service
  echo "Stopped via systemctl yb-tserver.service"
else
  pkill -TERM -f 'yb-tserver --flagfile' || true
  sleep 2
  if pgrep -f 'yb-tserver --flagfile' >/dev/null; then
    pkill -KILL -f 'yb-tserver --flagfile' || true
    echo "Killed yb-tserver (SIGKILL)"
  else
    echo "Stopped yb-tserver (SIGTERM)"
  fi
fi
REMOTE
done

echo "Done. Verify with: ./check-universe-tservers.sh ${UNIVERSE}"
