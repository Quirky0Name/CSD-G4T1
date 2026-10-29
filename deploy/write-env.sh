#!/bin/bash
# Writes the server's .env from Parameter Store: every parameter under the path becomes NAME=value.
# Run again after changing a parameter, then deploy/update.sh.
# Usage: write-env.sh <parameter path, e.g. /research-assistant/> <region>
set -euo pipefail

PARAMETER_PATH="${1:?parameter path}"
REGION="${2:?region}"
ENV_FILE=/opt/research-assistant/.env

umask 077
{
    echo "# written by deploy/write-env.sh from Parameter Store ($PARAMETER_PATH); edit there, not here"
    # the server's fixed settings: plain HTTP on 80 behind CloudFront, which does the HTTPS
    echo "WEB_PORT=80"
    # Caddy needs a port for 443 even though nothing reaches it; the security group keeps it shut
    echo "HTTPS_PORT=8443"
    # the images the deploy workflow pushes, named after the repo (lowercase, as the registry wants)
    repo=$(git -C /opt/research-assistant remote get-url origin | sed -E 's#^.*github\.com[:/]##; s#\.git$##' | tr '[:upper:]' '[:lower:]')
    echo "IMAGE_PREFIX=ghcr.io/$repo"
    echo "IMAGE_TAG=main"
    aws ssm get-parameters-by-path --region "$REGION" --path "$PARAMETER_PATH" \
        --with-decryption --recursive --query "Parameters[].[Name,Value]" --output text |
    while IFS=$'\t' read -r name value; do
        case "${name##*/}" in
            # set above; a hostname in SITE_ADDRESS would also send CloudFront round in redirects
            WEB_PORT | HTTPS_PORT | SITE_ADDRESS | IMAGE_PREFIX | IMAGE_TAG) continue ;;
        esac
        # compose's .env has no way to escape a quote inside a quoted value
        if [[ "$value" == *"'"* ]]; then
            echo "parameter ${name##*/} contains a single quote, which .env can't hold" >&2
            exit 1
        fi
        # single quotes, so a bcrypt hash's $ signs reach the containers as they are
        printf "%s='%s'\n" "${name##*/}" "$value"
    done
} > "$ENV_FILE.new"
mv "$ENV_FILE.new" "$ENV_FILE"
echo "wrote $ENV_FILE"
