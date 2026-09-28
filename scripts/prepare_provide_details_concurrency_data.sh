#!/usr/bin/env bash
set -euo pipefail

# Compatibility entry point for preparing provide-details concurrency data.
# The seeding implementation is Scala; this wrapper delegates to the sbt task.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

# Preserve the old script's staging defaults when it is invoked directly.
export BACKEND_URL="${BACKEND_URL:-https://agent-registration.protected.mdtp}"
export FRONTEND_URL="${FRONTEND_URL:-https://www.staging.tax.service.gov.uk}"
export STUBS_URL="${STUBS_URL:-https://www.staging.tax.service.gov.uk/agents-external-stubs}"
export RESET_BEFORE_SEED="${RESET_BEFORE_SEED:-true}"
export PROVIDE_DETAILS_APPS="${PROVIDE_DETAILS_APPS:-5}"
export INDIVIDUALS_PER_APP="${INDIVIDUALS_PER_APP:-6}"
export APPLICATION_SEED_MODE="${APPLICATION_SEED_MODE:-frontend-fast-forward}"
export FAST_FORWARD_SECTION="${FAST_FORWARD_SECTION:-LlpPartnersAndOtherRelevantTaxAdvisers6}"
export OUTPUT="${OUTPUT:-src/test/resources/data/provide-details-concurrency.csv}"

exec sbt -DrunLocal=false prepareProvideDetailsConcurrencyData
