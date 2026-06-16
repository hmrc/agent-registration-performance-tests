#!/usr/bin/env bash
set -euo pipefail

# Prepare feeder data for provide-details concurrency performance tests.
#
# Default behaviour:
#   - reset agent-registration data
#   - calculate how many applications are needed for the load profile
#   - seed applications with 6 individuals each using frontend fast-forward
#
# For a local smoke test, override PROVIDE_DETAILS_APPS=1.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

BACKEND_URL="${BACKEND_URL:-https://agent-registration.protected.mdtp}"
FRONTEND_URL="${FRONTEND_URL:-https://agent-registration-frontend.public.mdtp}"
STUBS_URL="${STUBS_URL:-https://www.staging.tax.service.gov.uk}"
RESET_URL="${RESET_URL:-${FRONTEND_URL}/agent-registration/test-only/reset}"
RESET_BEFORE_SEED="${RESET_BEFORE_SEED:-true}"

# Load profile used to calculate apps when PROVIDE_DETAILS_APPS is not supplied.
PROVIDE_DETAILS_JPS="${PROVIDE_DETAILS_JPS:-6}"
LOAD_PERCENTAGE="${LOAD_PERCENTAGE:-${PERFTEST_LOAD_PERCENTAGE:-100}}"
RAMPUP_MINUTES="${RAMPUP_MINUTES:-1}"
CONSTANT_MINUTES="${CONSTANT_MINUTES:-8}"
RAMPDOWN_MINUTES="${RAMPDOWN_MINUTES:-1}"
BUFFER_PERCENT="${BUFFER_PERCENT:-20}"
INDIVIDUALS_PER_APP="${INDIVIDUALS_PER_APP:-6}"
MIN_APPS="${MIN_APPS:-1}"

APPLICATION_SEED_MODE="${APPLICATION_SEED_MODE:-frontend-fast-forward}"
FAST_FORWARD_SECTION="${FAST_FORWARD_SECTION:-LlpPartnersAndOtherRelevantTaxAdvisers6}"
OUTPUT="${OUTPUT:-src/test/resources/data/provide-details-concurrency.csv}"

ceil_div() {
  local numerator="$1"
  local denominator="$2"
  echo $(( (numerator + denominator - 1) / denominator ))
}

calculate_apps() {
  # Uses integer seconds/minutes. For the default 1/8/1 profile:
  # effective seconds = 0.5*60 + 8*60 + 0.5*60 = 540.
  local effective_seconds=$(( (RAMPUP_MINUTES * 60 / 2) + (CONSTANT_MINUTES * 60) + (RAMPDOWN_MINUTES * 60 / 2) ))
  local effective_jps=$(( PROVIDE_DETAILS_JPS * LOAD_PERCENTAGE / 100 ))

  if [[ "$effective_jps" -lt 1 && "$PROVIDE_DETAILS_JPS" -gt 0 ]]; then
    effective_jps=1
  fi

  local rows_needed=$(( effective_jps * effective_seconds ))
  local rows_with_buffer=$(( rows_needed + (rows_needed * BUFFER_PERCENT / 100) ))
  local apps_needed
  apps_needed="$(ceil_div "$rows_with_buffer" "$INDIVIDUALS_PER_APP")"

  if [[ "$apps_needed" -lt "$MIN_APPS" ]]; then
    apps_needed="$MIN_APPS"
  fi

  echo "$apps_needed"
}

if [[ -n "${PROVIDE_DETAILS_APPS:-}" ]]; then
  APPS="$PROVIDE_DETAILS_APPS"
  APP_SOURCE="manual override from PROVIDE_DETAILS_APPS"
else
  APPS="$(calculate_apps)"
  APP_SOURCE="calculated from load profile"
fi

printf '\nProvide-details concurrency preparation\n'
printf '  Backend URL       : %s\n' "$BACKEND_URL"
printf '  Frontend URL      : %s\n' "$FRONTEND_URL"
printf '  Stubs URL         : %s\n' "$STUBS_URL"
printf '  Reset URL         : %s\n' "$RESET_URL"
printf '  Reset before seed : %s\n' "$RESET_BEFORE_SEED"
printf '  Applications      : %s (%s)\n' "$APPS" "$APP_SOURCE"
printf '  Individuals/app   : %s\n' "$INDIVIDUALS_PER_APP"
printf '  Provide JPS       : %s\n' "$PROVIDE_DETAILS_JPS"
printf '  Profile           : %sm ramp-up, %sm peak, %sm ramp-down\n' "$RAMPUP_MINUTES" "$CONSTANT_MINUTES" "$RAMPDOWN_MINUTES"
printf '  Buffer            : %s%%\n' "$BUFFER_PERCENT"
printf '  Seed mode         : %s\n' "$APPLICATION_SEED_MODE"
printf '  Fast-forward      : %s\n' "$FAST_FORWARD_SECTION"
printf '  Output            : %s\n\n' "$OUTPUT"

if [[ "$RESET_BEFORE_SEED" == "true" ]]; then
  RESET_URL="$RESET_URL" "$SCRIPT_DIR/reset_agent_registration_data.sh"
fi

python3 scripts/seed_provide_details.py \
  --backend-url "$BACKEND_URL" \
  --frontend-url "$FRONTEND_URL" \
  --stubs-url "$STUBS_URL" \
  --apps "$APPS" \
  --individuals "$INDIVIDUALS_PER_APP" \
  --application-seed-mode "$APPLICATION_SEED_MODE" \
  --fast-forward-section "$FAST_FORWARD_SECTION" \
  --output "$OUTPUT"
