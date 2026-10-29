#!/bin/bash
# First boot on the EC2 instance (deploy/stack.yml runs it as root): installs Docker, writes .env
# from Parameter Store, and starts the stack through a systemd service so a reboot brings it back.
# Usage: bootstrap.sh <parameter path, e.g. /research-assistant/> <region>
set -euo pipefail

PARAMETER_PATH="${1:?parameter path}"
REGION="${2:?region}"
APP_DIR=/opt/research-assistant

apt-get update
# compose builds the images through buildx, which Ubuntu packages on its own
apt-get install -y docker.io docker-compose-v2 docker-buildx
snap install aws-cli --classic

# GROBID alone takes 2 GB; swap keeps a build or a busy moment from killing a container
if [ ! -f /swapfile ]; then
    fallocate -l 2G /swapfile
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo "/swapfile none swap sw 0 0" >> /etc/fstab
fi

# for update.sh, which every later deploy runs
printf 'PARAMETER_PATH=%s\nREGION=%s\n' "$PARAMETER_PATH" "$REGION" > /etc/research-assistant.conf

"$APP_DIR/deploy/write-env.sh" "$PARAMETER_PATH" "$REGION"

# the deploy workflow's images, if it has run; the service builds whatever's missing
(cd "$APP_DIR" && docker compose pull --quiet storage users research-evaluation updating web) || true

cp "$APP_DIR/deploy/research-assistant.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now research-assistant.service
