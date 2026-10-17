#!/usr/bin/env bash
# Installs (or updates) the Octopus Live relay as a systemd service.
# Run from this folder:  sudo ./install.sh
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Please run with sudo:  sudo ./install.sh" >&2
  exit 1
fi
command -v python3 >/dev/null || { echo "python3 is required (sudo apt install python3)" >&2; exit 1; }

HERE="$(cd "$(dirname "$0")" && pwd)"

install -d -m 755 /opt/octopus-relay
install -m 644 "$HERE/octopus_relay.py" /opt/octopus-relay/octopus_relay.py
install -m 644 "$HERE/octopus-relay.service" /etc/systemd/system/octopus-relay.service

if [[ ! -f /etc/octopus-relay.env ]]; then
  install -m 600 "$HERE/octopus-relay.env.example" /etc/octopus-relay.env
  read -rp "Paste your Octopus API key (sk_live_...): " key
  key="$(echo "$key" | tr -d '[:space:]')"
  sed -i "s|^OCTOPUS_API_KEY=.*|OCTOPUS_API_KEY=${key}|" /etc/octopus-relay.env
else
  echo "Keeping existing settings in /etc/octopus-relay.env"
fi

# A token is required before exposing the relay to the internet (e.g. via a Cloudflare tunnel).
if ! grep -qE '^RELAY_TOKEN=.+' /etc/octopus-relay.env; then
  token="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
  sed -i "s|^RELAY_TOKEN=.*|RELAY_TOKEN=${token}|" /etc/octopus-relay.env
  echo "Generated a relay token (stored in /etc/octopus-relay.env)"
fi

systemctl daemon-reload
systemctl enable --now octopus-relay
systemctl restart octopus-relay
sleep 3

PORT="$(grep -E '^RELAY_PORT=' /etc/octopus-relay.env | cut -d= -f2)"; PORT="${PORT:-8765}"
IP="$(hostname -I | awk '{print $1}')"

if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow "${PORT}/tcp" comment "Octopus Live relay" >/dev/null && echo "Opened port ${PORT} in ufw"
fi

echo
systemctl --no-pager --lines=5 status octopus-relay || true
echo
TOKEN="$(grep -E '^RELAY_TOKEN=' /etc/octopus-relay.env | cut -d= -f2)"
Q=""; [[ -n "$TOKEN" ]] && Q="/?token=${TOKEN}"
echo "Done. Server address for your devices (home network):  http://${IP}:${PORT}${Q}"
echo "Open it in a browser to check it's working."
echo "Publishing it through a Cloudflare tunnel? Use  https://YOUR-HOSTNAME${Q}"
echo "Logs:  journalctl -u octopus-relay -f"
