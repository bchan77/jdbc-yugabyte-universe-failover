#!/usr/bin/env bash
# Shared helpers for failover-lab scripts.
set -euo pipefail

failover_lab_dir() {
  cd "$(dirname "${BASH_SOURCE[1]}")" && pwd
}

load_hosts_env() {
  local script_dir="$1"
  local hosts_env="${HOSTS_ENV:-${script_dir}/hosts.env}"
  if [[ ! -f "${hosts_env}" ]]; then
    echo "Missing ${hosts_env}" >&2
    echo "Copy hosts.env.example to hosts.env (or hosts-internal.env.example to hosts-internal.env) and set your node addresses." >&2
    exit 1
  fi
  # shellcheck source=/dev/null
  source "${hosts_env}"
  : "${PRIMARY_NODES:?Set PRIMARY_NODES in ${hosts_env}}"
  : "${STANDBY_NODES:?Set STANDBY_NODES in ${hosts_env}}"
  SSH_KEY="${SSH_KEY:-}"
  SSH_USER="${SSH_USER:-yugabyte}"
  if [[ -z "${SSH_KEY}" ]]; then
    echo "Set SSH_KEY in ${hosts_env} or the environment." >&2
    exit 1
  fi
}

# primary|standby (also accepts us|eu for backward compatibility)
resolve_universe_nodes() {
  local universe="$1"
  case "${universe}" in
    primary|us) printf '%s' "${PRIMARY_NODES}" ;;
    standby|eu) printf '%s' "${STANDBY_NODES}" ;;
    *)
      echo "Unknown universe: ${universe} (expected primary or standby)" >&2
      return 1
      ;;
  esac
}

primary_host_grep_pattern() {
  local first
  first="$(echo "${PRIMARY_NODES}" | awk '{print $1}')"
  echo "${first//./\\.}"
}
