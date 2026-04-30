#!/usr/bin/env bash
# Install the Remotex daemon as a systemd --user service on Linux.
#
# Sets up: ~/.local/share/remotex/venv (python deps),
#          ~/.remotex/config.toml (daemon config),
#          ~/.config/systemd/user/remotex-daemon.service (unit file).
#
# Re-run anytime — it's idempotent. Pass flags to skip prompts.
# Run with --help for the full list.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SERVICES_DIR="${REPO_ROOT}/services"
UNIT_TEMPLATE="${SCRIPT_DIR}/remotex-daemon.service"

RELAY_URL=""
BRIDGE_TOKEN=""
NICKNAME=""
DEFAULT_CWD=""
MODE="stdio"
CODEX_BINARY="codex"
FORCE_CONFIG=0
NO_ENABLE=0
UNINSTALL=0
NON_INTERACTIVE=0
SYSTEM=0
RUN_AS_USER=""

# Per-scope paths get set after we parse --system. User scope (default)
# uses ${HOME}; system scope uses /root for the daemon, /etc for the unit,
# and runs as either root or the user passed via --run-as-user.

bold()   { printf '\033[1m%s\033[0m\n' "$*"; }
info()   { printf '\033[36m==>\033[0m %s\n' "$*"; }
warn()   { printf '\033[33m!!!\033[0m %s\n' "$*" >&2; }
err()    { printf '\033[31mxxx\033[0m %s\n' "$*" >&2; }

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]

Options:
  --relay-url URL       Relay WebSocket URL (e.g. wss://relay.example.com/ws/daemon)
  --bridge-token TOKEN  Bridge token issued by the relay admin
  --nickname NAME       Host nickname shown in clients (default: \$HOSTNAME)
  --default-cwd PATH    Workspace dir Codex turns run in (default: \$HOME)
  --mode MODE           stdio | mock (default: stdio)
  --codex-binary PATH   Codex executable name or path (default: codex)
  --force-config        Overwrite an existing config file
  --no-enable           Install the unit but do not enable/start it
  --non-interactive     Fail instead of prompting for missing values
  --uninstall           Stop, disable, and remove the unit (keeps config + venv)
  --system              Install system-wide (requires sudo). Unit goes to
                        /etc/systemd/system, venv to /root/.local/share/remotex,
                        config to /root/.remotex/config.toml. Runs as root by
                        default — use --run-as-user to drop privileges.
  --run-as-user USER    With --system, set User= in the unit (default: root).
                        That user must own ~/.codex/auth.json.
  -h, --help            Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --relay-url)       RELAY_URL="$2"; shift 2 ;;
    --bridge-token)    BRIDGE_TOKEN="$2"; shift 2 ;;
    --nickname)        NICKNAME="$2"; shift 2 ;;
    --default-cwd)     DEFAULT_CWD="$2"; shift 2 ;;
    --mode)            MODE="$2"; shift 2 ;;
    --codex-binary)    CODEX_BINARY="$2"; shift 2 ;;
    --force-config)    FORCE_CONFIG=1; shift ;;
    --no-enable)       NO_ENABLE=1; shift ;;
    --non-interactive) NON_INTERACTIVE=1; shift ;;
    --uninstall)       UNINSTALL=1; shift ;;
    --system)          SYSTEM=1; shift ;;
    --run-as-user)     RUN_AS_USER="$2"; shift 2 ;;
    -h|--help)         usage; exit 0 ;;
    *) err "unknown flag: $1"; usage; exit 2 ;;
  esac
done

if [[ ${SYSTEM} -eq 1 ]]; then
  if [[ $EUID -ne 0 ]]; then
    err "--system requires root (try: sudo $0 --system ...)"
    exit 1
  fi
  RUN_AS_USER="${RUN_AS_USER:-root}"
  RUN_AS_HOME="$(getent passwd "${RUN_AS_USER}" | cut -d: -f6)"
  if [[ -z "${RUN_AS_HOME}" ]]; then
    err "user ${RUN_AS_USER} not found in passwd"
    exit 1
  fi
  VENV_DIR="${RUN_AS_HOME}/.local/share/remotex/venv"
  CONFIG_DIR="${RUN_AS_HOME}/.remotex"
  CONFIG_PATH="${CONFIG_DIR}/config.toml"
  UNIT_DIR="/etc/systemd/system"
  UNIT_PATH="${UNIT_DIR}/remotex-daemon.service"
else
  VENV_DIR="${HOME}/.local/share/remotex/venv"
  CONFIG_DIR="${HOME}/.remotex"
  CONFIG_PATH="${CONFIG_DIR}/config.toml"
  UNIT_DIR="${HOME}/.config/systemd/user"
  UNIT_PATH="${UNIT_DIR}/remotex-daemon.service"
fi

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { err "required command not found: $1"; exit 1; }
}

prompt() {
  local var_name="$1" prompt_text="$2" default="${3:-}"
  if [[ ${NON_INTERACTIVE} -eq 1 ]]; then
    err "missing --${var_name//_/-} (running with --non-interactive)"; exit 2
  fi
  local input
  if [[ -n "${default}" ]]; then
    read -r -p "${prompt_text} [${default}]: " input
    input="${input:-$default}"
  else
    read -r -p "${prompt_text}: " input
  fi
  printf -v "${var_name}" '%s' "${input}"
}

# systemctl scope flag: empty for system, "--user" for the per-user service.
SYSTEMCTL_SCOPE=""
if [[ ${SYSTEM} -eq 0 ]]; then
  SYSTEMCTL_SCOPE="--user"
fi

uninstall() {
  info "stopping and disabling remotex-daemon.service"
  systemctl ${SYSTEMCTL_SCOPE} stop remotex-daemon.service 2>/dev/null || true
  systemctl ${SYSTEMCTL_SCOPE} disable remotex-daemon.service 2>/dev/null || true
  if [[ -f "${UNIT_PATH}" ]]; then
    rm -f "${UNIT_PATH}"
    systemctl ${SYSTEMCTL_SCOPE} daemon-reload || true
    info "removed ${UNIT_PATH}"
  else
    info "no unit file at ${UNIT_PATH} (already gone)"
  fi
  bold "Uninstalled. Config at ${CONFIG_PATH} and venv at ${VENV_DIR} were left in place."
}

if [[ ${UNINSTALL} -eq 1 ]]; then
  uninstall
  exit 0
fi

require_cmd python3
require_cmd systemctl

PY_VER="$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
PY_MAJOR="${PY_VER%%.*}"
PY_MINOR="${PY_VER##*.}"
if (( PY_MAJOR < 3 || (PY_MAJOR == 3 && PY_MINOR < 11) )); then
  err "python3 ${PY_VER} found, but 3.11+ is required (tomllib + asyncio.TaskGroup)"
  exit 1
fi

if [[ ! -f "${SERVICES_DIR}/requirements.txt" ]]; then
  err "expected ${SERVICES_DIR}/requirements.txt — is the repo intact?"
  exit 1
fi

bold "Remotex daemon installer"
echo "  repo root:   ${REPO_ROOT}"
echo "  venv:        ${VENV_DIR}"
echo "  config:      ${CONFIG_PATH}"
echo "  unit file:   ${UNIT_PATH}"
echo "  python:      $(python3 --version 2>&1)"
echo

if [[ ! -d "${VENV_DIR}" ]]; then
  info "creating venv at ${VENV_DIR}"
  mkdir -p "$(dirname "${VENV_DIR}")"
  python3 -m venv "${VENV_DIR}"
  if [[ ${SYSTEM} -eq 1 && "${RUN_AS_USER}" != "root" ]]; then
    chown -R "${RUN_AS_USER}:" "$(dirname "${VENV_DIR}")"
  fi
else
  info "venv already exists at ${VENV_DIR}"
fi

info "installing/updating Python dependencies"
"${VENV_DIR}/bin/pip" install --upgrade pip >/dev/null
"${VENV_DIR}/bin/pip" install -r "${SERVICES_DIR}/requirements.txt"

if [[ -f "${CONFIG_PATH}" && ${FORCE_CONFIG} -eq 0 ]]; then
  info "config already exists at ${CONFIG_PATH} (use --force-config to overwrite)"
else
  if [[ -z "${RELAY_URL}" ]]; then
    prompt RELAY_URL "Relay WebSocket URL (ws:// or wss://, ends with /ws/daemon)"
  fi
  if [[ -z "${BRIDGE_TOKEN}" ]]; then
    prompt BRIDGE_TOKEN "Bridge token (issued by the relay admin)"
  fi
  if [[ -z "${NICKNAME}" ]]; then
    NICK_DEFAULT="$(hostname)"
    if [[ ${SYSTEM} -eq 1 ]]; then
      NICK_DEFAULT="${NICK_DEFAULT} (${RUN_AS_USER})"
    fi
    prompt NICKNAME "Host nickname shown in clients" "${NICK_DEFAULT}"
  fi
  if [[ -z "${DEFAULT_CWD}" ]]; then
    CWD_DEFAULT="${HOME}"
    if [[ ${SYSTEM} -eq 1 ]]; then
      CWD_DEFAULT="${RUN_AS_HOME}"
    fi
    prompt DEFAULT_CWD "Default workspace dir for codex turns" "${CWD_DEFAULT}"
  fi

  mkdir -p "${CONFIG_DIR}"
  info "writing ${CONFIG_PATH}"
  ( cd "${SERVICES_DIR}" && \
    "${VENV_DIR}/bin/python3" -m daemon init \
      --relay-url    "${RELAY_URL}" \
      --bridge-token "${BRIDGE_TOKEN}" \
      --nickname     "${NICKNAME}" \
      --mode         "${MODE}" \
      --codex-binary "${CODEX_BINARY}" \
      --default-cwd  "${DEFAULT_CWD}" \
      --config       "${CONFIG_PATH}" )
  if [[ ${SYSTEM} -eq 1 && "${RUN_AS_USER}" != "root" ]]; then
    chown -R "${RUN_AS_USER}:" "${CONFIG_DIR}"
  fi
fi

info "rendering systemd unit to ${UNIT_PATH}"
mkdir -p "${UNIT_DIR}"
RENDERED="$(sed \
  -e "s|@@WORKING_DIR@@|${SERVICES_DIR}|g" \
  -e "s|@@VENV_BIN@@|${VENV_DIR}/bin|g" \
  -e "s|@@CONFIG_PATH@@|${CONFIG_PATH}|g" \
  "${UNIT_TEMPLATE}")"
if [[ ${SYSTEM} -eq 1 ]]; then
  # System units must declare User= and target multi-user.target.
  RENDERED="$(printf '%s\n' "${RENDERED}" \
    | sed -e "/^\[Service\]/a User=${RUN_AS_USER}" \
          -e 's|^WantedBy=default.target|WantedBy=multi-user.target|')"
fi
printf '%s\n' "${RENDERED}" > "${UNIT_PATH}"

systemctl ${SYSTEMCTL_SCOPE} daemon-reload

if [[ ${NO_ENABLE} -eq 1 ]]; then
  info "skipping enable/start (--no-enable)"
else
  info "enabling and starting remotex-daemon.service"
  systemctl ${SYSTEMCTL_SCOPE} enable --now remotex-daemon.service
fi

echo
bold "Done."
systemctl ${SYSTEMCTL_SCOPE} --no-pager status remotex-daemon.service || true

if [[ ${SYSTEM} -eq 1 ]]; then
  cat <<EOF

Next steps (system service):
  - Tail logs:     journalctl -u remotex-daemon -f
  - Check status:  systemctl status remotex-daemon
  - Restart:       systemctl restart remotex-daemon
EOF
else
  cat <<EOF

Next steps:
  - Tail logs:     journalctl --user -u remotex-daemon -f
  - Check status:  systemctl --user status remotex-daemon
  - Restart:       systemctl --user restart remotex-daemon
  - Run on boot when you're not logged in:
      sudo loginctl enable-linger ${USER}
EOF
fi
