#!/usr/bin/env bash
set -euo pipefail

# Reset agent-registration data via frontend test-only endpoint.
# Override RESET_URL for local/non-public environments.

RESET_URL="${RESET_URL:-https://agent-registration-frontend.public.mdtp/agent-registration/test-only/reset}"
RESET_HTTP_METHOD="${RESET_HTTP_METHOD:-GET}"

printf '\nResetting agent-registration data\n'
printf '  URL    : %s\n' "$RESET_URL"
printf '  Method : %s\n' "$RESET_HTTP_METHOD"

case "$RESET_HTTP_METHOD" in
  GET|get)
    curl --fail --show-error --location --request GET "$RESET_URL"
    ;;
  POST|post)
    curl --fail --show-error --location --request POST "$RESET_URL"
    ;;
  *)
    echo "ERROR: RESET_HTTP_METHOD must be GET or POST" >&2
    exit 1
    ;;
esac

printf '\nReset complete.\n'
