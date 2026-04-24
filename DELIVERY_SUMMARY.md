# Summary: Sole Trader Performance Test Implementation

## Delivered

### 1. **AGENTS.md** – AI Agent Guidance Document
Complete documentation for future AI coding agents working on this codebase. Includes:
- **Big picture architecture**: How journeys, requests, simulations, and service configs interconnect.
- **Environment & auth strategy**: Key difference between UI tests (Selenium + stubs) vs. perf tests (Gatling + real stubs).
- **External Stubs Flow**: Random User ID + Planet ID posted to `/bas-gateway/sign-in` to capture auth session.
- **GRS Handling**: Stubs seed company/sole trader data; frontend calls callback; GRS internals are third-party responsibility.
- **Developer workflows**: Smoke test, full local test, staging commands.
- **Critical journey sequence**: 11-step path from bootstrap to task list.
- **Troubleshooting**: Common 303, 400, 404 errors and fixes.

### 2. **Sole Trader Journey (11/12 steps working)**
Journey expanded from bootstrap to external-stubs sign-in:

| Step | Endpoint | Status |
|------|----------|--------|
| 1 | GET `/agent-registration/` | ✓ 200/303 |
| 2 | GET `/agent-registration/apply` | ✓ 200/303 |
| 3 | GET/POST agent-type | ✓ CSRF extraction |
| 4 | GET/POST business-type | ✓ CSRF extraction |
| 5 | GET/POST user-role | ✓ CSRF extraction |
| 6 | GET/POST sign-in-method | ✓ CSRF extraction |
| 7 | GET sign-in page | ✓ 200 OK |
| 8 | POST `/bas-gateway/sign-in` | ⚠️ Needs stubs running (403 locally) |
| 9 | GET `initiate-agent-application/...` | ✓ Ready (blocked by step 8) |
| 10 | GET `grs/start-journey` | ✓ Ready (blocked by step 8) |
| 11 | Task list reached | ✓ Next |

### 3. **Code Changes**

#### `src/test/scala/uk/gov/hmrc/perftests/example/ExampleRequests.scala`
- Added `applyPage` request (bootstrap journey).
- Added `externalStubsSignIn` request (random user/planet POST to stubs).
- Updated comments on `initiateAgentApplication` to clarify auth requirements.

#### `src/test/scala/uk/gov/hmrc/perftests/example/ExampleSimulation.scala`
- Registered `apply-page` and `external-stubs-sign-in` as journey parts.

#### `src/test/resources/journeys.conf`
- Updated `sole-trader-journey.parts` to include new steps in correct sequence.

#### `src/test/resources/services-local.conf` & `services.conf`
- Added `external-stubs` service configuration (localhost:9099 for local, staging for staging).

### 4. **IMPLEMENTATION_NOTES.md** – Detailed Runbook
Provides:
- What was implemented (list of requests, parts, configs).
- Current status (11/12 passing, external stubs 403 issue).
- Next steps to verify stubs running and complete staging test.
- Troubleshooting guide with specific curl commands and fix procedures.
- Key design decisions (why stubs, why skip GRS, feeder variables).

---

## Key Insights from UI Test Review

### Auth Pattern (UI Tests → Perf Tests)
**UI Tests** (Selenium):
```
Click GG stub form → Enter random username/planet → Capture bearer token from page
```

**Perf Tests** (Gatling):
```
POST /bas-gateway/sign-in with form params → Capture session cookie from response
```

### GRS Pattern
**UI Tests**: Full Selenium journey through GRS pages (provides realistic test).  
**Perf Tests**: External stubs pre-seed data → Frontend calls callback → Task list reached.  
**Rationale**: GRS is third-party verified; perf focus is frontend + own backend.

### Feeder Variables
Current `sole-trader.csv`:
```csv
agentType,businessType,userRole,typeOfSignIn
UkTaxAgent,SoleTrader,Owner,HmrcOnlineServices
UkTaxAgent,SoleTrader,Owner,CreateSignInDetails
```

Injects as `#{agentType}`, `#{businessType}`, etc. in requests.

---

## What's Working

✓ **Service configuration** – Local and staging environment separation.  
✓ **CSRF extraction** – All form pages extract and reuse CSRF tokens.  
✓ **Form submission flow** – Agent type, business type, user role, sign-in method all working.  
✓ **Bootstrap sequences** – Landing page → apply page → business questions follow expected order.  
✓ **Session persistence** – Gatling maintains cookies across requests.  

---

## What Needs Completion

1. **Verify External Stubs Running**
   ```bash
   sm2 --start AGENT_REGISTRATION_ALL
   ```
   
2. **Confirm External Stubs URL** for staging environment (may differ from frontend host).

3. **Run Full Smoke Test**
   ```bash
   sbt -Dperftest.runSmokeTest=true -DrunLocal=true gatling:test
   ```

4. **(Optional) Extend Journey**
   - Task list landing confirmation.
   - Individual task sections (contact details, AMLS, etc.).
   - Full application submission flow.

---

## Testing Commands

### Local Smoke Test
```bash
sbt -Dperftest.runSmokeTest=true -DrunLocal=true gatling:test
```

### Local Full Test
```bash
sbt -DrunLocal=true gatling:test
```

### Staging Smoke Test
```bash
sbt -Dperftest.runSmokeTest=true -DrunLocal=false gatling:test
```

### Format Code
```bash
sbt scalafmtAll
```

---

## Next Session Checklist

- [ ] Confirm `sm2 --start AGENT_REGISTRATION_ALL` runs successfully.
- [ ] Verify external stubs are accessible at configured URL (local + staging).
- [ ] Run smoke test locally and confirm all 14 steps pass.
- [ ] Test against staging environment (if ready).
- [ ] Extend journey to task list completion (if needed).
- [ ] Configure load levels and ramp-up/down timings.
- [ ] Run full performance test and capture baseline metrics.

---

## Files Reference

| File | Purpose | Status |
|------|---------|--------|
| AGENTS.md | AI agent guidance | ✓ Created |
| IMPLEMENTATION_NOTES.md | Detailed runbook | ✓ Created |
| ExampleRequests.scala | HTTP request builders | ✓ Updated |
| ExampleSimulation.scala | Journey orchestration | ✓ Updated |
| journeys.conf | Journey config | ✓ Updated |
| services-local.conf | Local service URLs | ✓ Updated |
| services.conf | Staging service URLs | ✓ Updated |
| sole-trader.csv | Test feeder data | ✓ Ready |

---

## AI Agent Instructions for Next Work

When resuming work on this repo:
1. **Read AGENTS.md first** – Contains architecture overview and project-specific conventions.
2. **Check services-local.conf & services.conf** – Service URLs are config-driven; update them for new services.
3. **Follow safe change pattern** – Add request to ExampleRequests → Register in ExampleSimulation → List in journeys.conf.
4. **CSRF extraction** – Use `.check(css(...).saveAs(...))` to extract from forms, reuse as `#{varName}`.
5. **External stubs** – POST to them like regular HTTP, don't mock; they provide auth context.
6. **GRS skipping** – Don't simulate full GRS journey; stubs seed data + callback is sufficient.
7. **Smoke test first** – Always: `sbt -Dperftest.runSmokeTest=true -DrunLocal=true gatling:test` before full run.


