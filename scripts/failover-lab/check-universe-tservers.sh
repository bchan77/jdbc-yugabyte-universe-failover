#!/usr/bin/env bash
# Check yb-tserver process and YSQL port on universe nodes.
# Usage: ./check-universe-tservers.sh primary|standby
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

for host in ${NODES}; do
  printf "%-18s " "${host}"
  ssh -i "${SSH_KEY}" -o StrictHostKeyChecking=no -o ConnectTimeout=10 \
    "${SSH_USER}@${host}" \
    "pgrep -af 'yb-tserver --flagfile' >/dev/null && echo -n 'tserver=UP ' || echo -n 'tserver=DOWN '; (echo >/dev/tcp/127.0.0.1/5433) 2>/dev/null && echo 'ysql=UP' || echo 'ysql=DOWN'" \
    2>/dev/null || echo "SSH failed"
done
