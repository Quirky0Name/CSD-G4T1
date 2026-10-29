#!/bin/bash
# Run at boot by research-assistant.service: merges that landed while the instance was stopped
# couldn't be deployed, so if main has moved on, deploy it now. Only from images the deploy
# workflow built, which it does only for commits that passed its tests; nothing is built here.
set -euo pipefail

cd /opt/research-assistant
git fetch --quiet origin
latest=$(git rev-parse origin/main)
current=$(sed -n 's/^IMAGE_TAG=//p' .env)

if [ "$latest" = "$current" ]; then
    echo "already on the latest main ($latest)"
    exit 0
fi
if IMAGE_TAG="$latest" docker compose pull --quiet storage users research-evaluation updating web; then
    exec deploy/update.sh "$latest"
fi
echo "no images for $latest (still building, or its tests failed); staying on $current"
