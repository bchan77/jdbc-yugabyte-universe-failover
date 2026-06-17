#!/usr/bin/env bash
# Start yb-tserver on every node in a universe (recovery after failover test).
# YBA node agent may also restart tservers; this script covers manual recovery.
# Usage: ./start-universe-tservers.sh primary|standby
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

echo "Starting yb-tserver on ${UNIVERSE} nodes: ${NODES}"
echo "Tip: if processes do not come back, restart them from YBA (Universes -> Actions -> Start)."

for host in ${NODES}; do
  echo "--- ${host} ---"
  ssh -i "${SSH_KEY}" -o StrictHostKeyChecking=no -o ConnectTimeout=10 \
    "${SSH_USER}@${host}" bash -s <<'REMOTE'
set -euo pipefail
if systemctl --user is-active yb-tserver.service &>/dev/null; then
  echo "Already running (systemctl --user)"
  exit 0
fi
if systemctl --user start yb-tserver.service 2>/dev/null; then
  echo "Started via systemctl --user yb-tserver.service"
  exit 0
fi
if systemctl start yb-tserver.service 2>/dev/null; then
  echo "Started via systemctl yb-tserver.service"
  exit 0
fi
if pgrep -f 'yb-tserver --flagfile' >/dev/null; then
  echo "Already running (process)"
  exit 0
fi
CONF="/home/yugabyte/tserver/conf/server.conf"
if [[ -f "${CONF}" ]]; then
  nohup /home/yugabyte/tserver/bin/yb-tserver --flagfile "${CONF}" \
    >>/home/yugabyte/tserver/logs/yb-tserver.out 2>&1 &
  sleep 2
  if pgrep -f 'yb-tserver --flagfile' >/dev/null; then
    echo "Started yb-tserver manually"
  else
    echo "Failed to start yb-tserver — use YBA to start the node" >&2
    exit 1
  fi
else
  echo "No tserver config at ${CONF}; use YBA to start the node" >&2
  exit 1
fi
REMOTE
done

echo "Done. Verify with: ./check-universe-tservers.sh ${UNIVERSE}"
