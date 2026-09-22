#!/usr/bin/env bash
set -euo pipefail

# Compatibility entry point for preparing risk-outcome data.
# The seeding implementation is Scala; this wrapper delegates to the sbt task.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

export BACKEND_URL="${BACKEND_URL:-https://agent-registration.protected.mdtp}"
export FRONTEND_URL="${FRONTEND_URL:-https://www.staging.tax.service.gov.uk}"
export STUBS_URL="${STUBS_URL:-https://www.staging.tax.service.gov.uk}"
export RESET_BEFORE_SEED="${RESET_BEFORE_SEED:-false}"
export RISK_OUTCOME_POOLS="${RISK_OUTCOME_POOLS:-load}"
export RISK_OUTCOME_OUTPUT_DIR="${RISK_OUTCOME_OUTPUT_DIR:-src/test/resources/data/risk-outcomes}"
export RISK_OUTCOME_TOTAL_PEAK_JPS="${RISK_OUTCOME_TOTAL_PEAK_JPS:-0.1}"
export RISK_OUTCOME_RAMPUP_MINUTES="${RISK_OUTCOME_RAMPUP_MINUTES:-1}"
export RISK_OUTCOME_STEADY_MINUTES="${RISK_OUTCOME_STEADY_MINUTES:-8}"
export RISK_OUTCOME_RAMPDOWN_MINUTES="${RISK_OUTCOME_RAMPDOWN_MINUTES:-1}"
export RISK_OUTCOME_BUFFER_PERCENT="${RISK_OUTCOME_BUFFER_PERCENT:-20}"
export RISK_OUTCOME_SMOKE_RECORDS="${RISK_OUTCOME_SMOKE_RECORDS:-1}"
export RISK_OUTCOME_DRY_RUN="${RISK_OUTCOME_DRY_RUN:-false}"

exec sbt -DrunLocal=false prepareRiskOutcomeData
