#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

BACKEND_URL="${BACKEND_URL:-https://agent-registration.protected.mdtp}"
FRONTEND_URL="${FRONTEND_URL:-https://agent-registration-frontend.public.mdtp}"
STUBS_URL="${STUBS_URL:-https://www.staging.tax.service.gov.uk}"
RESET_URL="${RESET_URL:-${FRONTEND_URL}/agent-registration/test-only/reset}"
RESET_BEFORE_SEED="${RESET_BEFORE_SEED:-false}"

RISK_OUTCOME_POOLS="${RISK_OUTCOME_POOLS:-load}"
RISK_OUTCOME_OUTPUT_DIR="${RISK_OUTCOME_OUTPUT_DIR:-src/test/resources/data/risk-outcomes}"
RISK_OUTCOME_TOTAL_PEAK_JPS="${RISK_OUTCOME_TOTAL_PEAK_JPS:-0.1}"
RISK_OUTCOME_RAMPUP_MINUTES="${RISK_OUTCOME_RAMPUP_MINUTES:-1}"
RISK_OUTCOME_STEADY_MINUTES="${RISK_OUTCOME_STEADY_MINUTES:-8}"
RISK_OUTCOME_RAMPDOWN_MINUTES="${RISK_OUTCOME_RAMPDOWN_MINUTES:-1}"
RISK_OUTCOME_BUFFER_PERCENT="${RISK_OUTCOME_BUFFER_PERCENT:-20}"
RISK_OUTCOME_LOAD_MAIN_RECORDS="${RISK_OUTCOME_LOAD_MAIN_RECORDS:-}"
RISK_OUTCOME_LOAD_CONTROL_RECORDS="${RISK_OUTCOME_LOAD_CONTROL_RECORDS:-}"
RISK_OUTCOME_LOAD_SCALE_RECORDS="${RISK_OUTCOME_LOAD_SCALE_RECORDS:-}"
RISK_OUTCOME_SMOKE_RECORDS="${RISK_OUTCOME_SMOKE_RECORDS:-1}"
RISK_OUTCOME_DRY_RUN="${RISK_OUTCOME_DRY_RUN:-false}"

mkdir -p "$RISK_OUTCOME_OUTPUT_DIR"

printf '\nRisk-outcome performance data preparation\n'
printf '  Backend URL            : %s\n' "$BACKEND_URL"
printf '  Frontend URL           : %s\n' "$FRONTEND_URL"
printf '  Stubs URL              : %s\n' "$STUBS_URL"
printf '  Reset URL              : %s\n' "$RESET_URL"
printf '  Reset before seed      : %s\n' "$RESET_BEFORE_SEED"
printf '  Pools                  : %s\n' "$RISK_OUTCOME_POOLS"
printf '  Output directory       : %s\n' "$RISK_OUTCOME_OUTPUT_DIR"
printf '  Peak JPS               : %s\n' "$RISK_OUTCOME_TOTAL_PEAK_JPS"
printf '  Profile                : %sm ramp-up, %sm steady, %sm ramp-down\n' \
  "$RISK_OUTCOME_RAMPUP_MINUTES" "$RISK_OUTCOME_STEADY_MINUTES" "$RISK_OUTCOME_RAMPDOWN_MINUTES"
printf '  Buffer                 : %s%%\n' "$RISK_OUTCOME_BUFFER_PERCENT"
printf '  Load main records      : %s\n' "${RISK_OUTCOME_LOAD_MAIN_RECORDS:-calculated}"
printf '  Load control records   : %s\n' "${RISK_OUTCOME_LOAD_CONTROL_RECORDS:-calculated}"
printf '  Load scale records     : %s\n' "${RISK_OUTCOME_LOAD_SCALE_RECORDS:-calculated}"
printf '  Smoke records/scenario : %s\n\n' "$RISK_OUTCOME_SMOKE_RECORDS"

if [[ "$RESET_BEFORE_SEED" == "true" ]]; then
  RESET_URL="$RESET_URL" "$SCRIPT_DIR/reset_agent_registration_data.sh"
fi

args=(
  --backend-url "$BACKEND_URL"
  --frontend-url "$FRONTEND_URL"
  --stubs-url "$STUBS_URL"
  --output-dir "$RISK_OUTCOME_OUTPUT_DIR"
  --pools "$RISK_OUTCOME_POOLS"
  --total-peak-jps "$RISK_OUTCOME_TOTAL_PEAK_JPS"
  --rampup-minutes "$RISK_OUTCOME_RAMPUP_MINUTES"
  --steady-minutes "$RISK_OUTCOME_STEADY_MINUTES"
  --rampdown-minutes "$RISK_OUTCOME_RAMPDOWN_MINUTES"
  --buffer-percent "$RISK_OUTCOME_BUFFER_PERCENT"
  --smoke-records "$RISK_OUTCOME_SMOKE_RECORDS"
)

if [[ -n "$RISK_OUTCOME_LOAD_MAIN_RECORDS" ]]; then
  args+=(--load-main-records "$RISK_OUTCOME_LOAD_MAIN_RECORDS")
fi

if [[ -n "$RISK_OUTCOME_LOAD_CONTROL_RECORDS" ]]; then
  args+=(--load-control-records "$RISK_OUTCOME_LOAD_CONTROL_RECORDS")
fi

if [[ -n "$RISK_OUTCOME_LOAD_SCALE_RECORDS" ]]; then
  args+=(--load-scale-records "$RISK_OUTCOME_LOAD_SCALE_RECORDS")
fi

if [[ "$RISK_OUTCOME_DRY_RUN" == "true" ]]; then
  args+=(--dry-run)
fi

python3 scripts/seed_risk_outcomes.py "${args[@]}"


