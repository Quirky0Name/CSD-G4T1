#!/bin/bash
# Deploys the latest main on the server: run it through Session Manager (docs/DEPLOY.md).
# Usage: sudo /opt/research-assistant/deploy/update.sh
set -euo pipefail

cd /opt/research-assistant
git pull --ff-only
docker compose up -d --build --wait
docker compose restart updating
# old images pile up with every rebuild; the disk is only 30 GB
docker image prune -f
