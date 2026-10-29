#!/bin/bash
# Deploys a commit on the server. The deploy workflow runs it through Systems Manager after every
# merge to main; it also works by hand through Session Manager (docs/DEPLOY.md).
# Usage: sudo /opt/research-assistant/deploy/update.sh [commit, default the latest main]
set -euo pipefail

APP_DIR=/opt/research-assistant
REF="${1:-origin/main}"

# written by bootstrap.sh; a server set up before it existed gets the stack's defaults
CONF=/etc/research-assistant.conf
if [ -f "$CONF" ]; then
    # shellcheck source=/dev/null
    . "$CONF"
fi
PARAMETER_PATH="${PARAMETER_PATH:-/research-assistant/}"
if [ -z "${REGION:-}" ]; then
    token=$(curl -sf -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60")
    REGION=$(curl -sf -H "X-aws-ec2-metadata-token: $token" http://169.254.169.254/latest/meta-data/placement/region)
fi

cd "$APP_DIR"
git fetch --quiet origin
# a branch name typed by hand means GitHub's copy of it
if git rev-parse --verify --quiet "origin/$REF" > /dev/null; then
    REF="origin/$REF"
fi
git checkout --quiet --detach "$REF"
# picks up any setting changed in Parameter Store since the last deploy
deploy/write-env.sh "$PARAMETER_PATH" "$REGION"
# the deploy workflow tags each commit's images with its SHA; keeping it in .env means a reboot
# brings back this commit's images, not whatever is newest
sha=$(git rev-parse HEAD)
sed -i "s/^IMAGE_TAG=.*/IMAGE_TAG=$sha/" .env
# bootstrap.sh installed the boot service once; keep it in step with the repo's copy
install -m 644 deploy/research-assistant.service /etc/systemd/system/research-assistant.service
systemctl daemon-reload

# the deploy workflow pushed this commit's images as :main; with none to pull (before its first
# run, or packages that aren't public yet), build them here instead
if docker compose pull --quiet storage users research-evaluation updating web; then
    docker compose up -d --no-build --wait
else
    echo "no images to pull, so building them on the server"
    docker compose up -d --build --wait
fi
# its startup poll may have run before Storage Management was up
docker compose restart updating
# old images pile up with every deploy; the disk is only 30 GB
docker image prune -f
