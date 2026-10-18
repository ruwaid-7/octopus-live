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

# Optional settings file (poll interval, port, time zone). The API key is added on the web page.
if [[ ! -f /etc/octopus-relay.env ]]; then
  install -m 600 "$HERE/octopus-relay.env.example" /etc/octopus-relay.env
fi

systemctl daemon-reload
systemctl enable --now octopus-relay
systemctl restart octopus-relay
sleep 3

PORT="$(grep -E '^RELAY_PORT=' /etc/octopus-relay.env | cut -d= -f2)"; PORT="${PORT:-8765}"
# LAN address (the interface used for the default route), not a Docker bridge
IP="$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<NF;i++) if($i=="src"){print $(i+1); exit}}')"
[[ -n "$IP" ]] || IP="$(hostname -I | awk '{print $1}')"

if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow "${PORT}/tcp" comment "Octopus Live relay" >/dev/null && echo "Opened port ${PORT} in ufw"
fi

echo
systemctl --no-pager --lines=5 status octopus-relay || true
echo
echo "Done. Finish setting up in your browser:  http://${IP}:${PORT}/"
echo "(Paste your Octopus API key there; it gives you a private link for your devices.)"
echo "Publishing it through a Cloudflare tunnel? Open https://YOUR-HOSTNAME/ instead."
echo "Logs:  journalctl -u octopus-relay -f"
