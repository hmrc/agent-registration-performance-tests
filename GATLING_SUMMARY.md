# Gatling Test Suite - Executive Summary

## Document Location
Full detailed guide: `GATLING_TEST_GUIDE.md`

## Quick Stats

### Total Endpoints to Test
- **52 unique endpoints** across the registration flow
- **3 main business type journeys** (Sole Trader, Limited Company, Partnership variants)
- **Multiple individual share link journeys** (parallel scenarios)

### Journey Lengths
- **Sole Trader**: 35 API calls minimum
- **Limited Company (≤5 officers)**: 39 API calls
- **Limited Company (>5 officers)**: 42+ API calls
- **General Partnership**: 41+ API calls
- **Individual Details via Share Link**: 11 API calls

---

## 5-Stage Registration Flow

```
┌─────────────────────────────────────────────────────────────────┐
│ STAGE 1: BUSINESS CLASSIFICATION (4-6 endpoints)               │
│ • Agent Type Selection                                           │
│ • Business Type (Sole Trader / Limited Company / Partnership)   │
│ • User Role (Owner / Director / Partner / Member)               │
│ • Sign-in Type Selection                                         │
│ • Sign-in Page Display                                          │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ STAGE 2: GRS VERIFICATION (3-4 endpoints + external service)    │
│ • Initiate Application (checks enrolments, creates app)         │
│ • Start GRS Journey (delegates to external GRS service)         │
│ • GRS Callback (gets journey data, checks entity status)        │
│ • Entity Checks (refusal to deal, deceased, CH status)          │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ STAGE 3: DETAILS COLLECTION (22-28 endpoints)                   │
│ • Task List Display                                              │
│ • Applicant Contact Details (name, phone, email + verify)       │
│ • Business Details (name, phone, email + verify)                │
│ • Correspondence Address (address lookup integration)            │
│ • AMLS Details (supervisor, reg number, expiry, upload)         │
│ • Director/Partner/Member Lists (varies by type)                │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ STAGE 4: FINAL CONFIRMATIONS (2 endpoints)                      │
│ • HMRC Standard for Agents Acceptance                            │
│ • Declaration & Submission                                      │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ STAGE 5: STATUS PAGES (1-3 endpoints)                           │
│ • Application Submitted Confirmation                             │
│ • View Progress                                                  │
│ • View Full Application (optional)                               │
└─────────────────────────────────────────────────────────────────┘
```

---

## Parallel Journey: Individual Share Link

When applicant invites directors/partners/members to complete their details:

```
┌─────────────────────────────────────────────────────────────────┐
│ STAGE 1: INDIVIDUAL RECEIVE LINK (1 endpoint)                   │
│ • GET /provide-details/start/:linkId                             │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ STAGE 2: IDENTITY VERIFICATION (2 endpoints)                    │
│ • Match Application Details                                      │
│ • Identity Confirmation                                          │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ STAGE 3: PERSONAL DETAILS (7 endpoints)                         │
│ • Telephone Number                                               │
│ • Email Address + Verification                                   │
│ • Date of Birth                                                  │
│ • NINO                                                           │
│ • Approve Applicant                                              │
│ • Agree to HMRC Standard                                         │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│ STAGE 4: CONFIRMATION (2 endpoints)                             │
│ • Review Answers                                                 │
│ • Success Confirmation                                           │
└─────────────────────────────────────────────────────────────────┘
```

---

## External Service Integration Points

### GRS (Government Registration Service)
- **When**: After business classification, before task list
- **Services**: 
  - Sole Trader: `sole-trader-identification-frontend` (port 9717)
  - Limited Company: `incorporated-entity-identification-frontend` (port 9718)
  - Partnerships: `partnership-identification-frontend` (port 9722)
- **What they verify**: Business identity and details (NINO/CRN/UTR)

### Email Verification
- **When**: After entering applicant and business emails
- **Service**: `email-verification` (port 9891)
- **What**: Sends passcode/link to verify email ownership

### Address Lookup
- **When**: When selecting correspondence address
- **Service**: `address-lookup-frontend` (port 9028)
- **What**: Postcode search and address selection

### File Upload (AMLS Evidence)
- **When**: Uploading AMLS supervision evidence
- **Service**: `upscan-initiate` (port 9570) + `object-store` (port 8464)
- **What**: File upload and storage

### Backend Services
- **agent-registration** (22202): Stores application data
- **agent-registration-risking** (22203): Application status/risking
- **companies-house-api-proxy** (9991): Officer retrieval for companies
- **enrolment-store-proxy** (9595): Check existing HMRC ASA enrolments
- **agent-assurance** (9565): Application submission and processing

---

## Gatling Test Scenarios

### Scenario A: Sole Trader Happy Path
- **Duration**: ~2-3 minutes per user
- **Calls**: 35
- **Complexity**: Low (no directors/partners to manage)
- **Key checkpoint**: GRS verification

### Scenario B: Limited Company with Few Officers
- **Duration**: ~2-3 minutes per user
- **Calls**: 39-42
- **Complexity**: Medium (5 or fewer officers)
- **Key checkpoint**: Companies House officer confirmation

### Scenario C: Limited Company with Many Officers
- **Duration**: ~3-4 minutes per user
- **Calls**: 45+
- **Complexity**: High (manual addition of officers)
- **Key checkpoint**: Officer list management

### Scenario D: Partnership with Multiple Partners
- **Duration**: ~3-4 minutes per user
- **Calls**: 41+
- **Complexity**: High (partner list entry and other individuals)
- **Key checkpoint**: Key individual list management

### Scenario E: Individual Provides Details (Parallel)
- **Duration**: ~1-2 minutes per individual
- **Calls**: 11
- **Complexity**: Low (simple sequential form)
- **Key checkpoint**: Email verification for individual

---

## Recommended Load Profile

### Phase 1: Ramp-up (5 minutes)
```
Users: 10/sec → 100/sec
Tests: All 5 scenarios in rotation
Goal: Establish baseline performance
```

### Phase 2: Sustained Load (15 minutes)
```
Users: 100/sec steady state
Distribution:
  - 40% Sole Trader (Scenario A)
  - 20% Limited Company ≤5 (Scenario B)
  - 15% Limited Company >5 (Scenario C)
  - 15% Partnership (Scenario D)
  - 10% Individual Details (Scenario E)
Goal: Measure sustainable throughput and response times
```

### Phase 3: Stress Test (10 minutes)
```
Users: 100/sec → 500/sec
Tests: Repeat all scenarios
Goal: Find breaking point and error rate threshold
```

### Phase 4: Soak Test (4 hours)
```
Users: 50/sec steady state
Tests: All scenarios in natural distribution
Goal: Detect memory leaks, connection pool issues, degradation over time
```

---

## Critical Performance Metrics

### Per-Endpoint Targets (based on typical UK Gov services)
| Endpoint Category | p50 | p95 | p99 | Error Rate |
|---|---|---|---|---|
| **Frontend Pages** | 100ms | 300ms | 800ms | <0.5% |
| **Form Submissions** | 150ms | 400ms | 1s | <0.5% |
| **GRS Integration** | 500ms | 2s | 5s | <1% |
| **Backend API Calls** | 200ms | 800ms | 2s | <0.5% |
| **Email Verification** | 100ms | 500ms | 1.5s | <0.5% |
| **File Upload Polling** | 50ms | 200ms | 500ms | <0.1% |

### Overall Journey Targets
| Metric | Target |
|--------|--------|
| End-to-end journey time (p95) | <5 minutes |
| Task list load time (p95) | <1 second |
| Submission success rate | >99.5% |
| GRS verification completion | >98% |

---

## Data Requirements

### Pre-seeded Data (Must Exist Before Tests)

1. **AMLS Supervisors** (from `conf/amls.csv`)
   - At least 5 supervisors with validation rules

2. **GRS Test Data** (via GRS Stub)
   - Sole Trader test credentials (NINO + DOB)
   - Limited Company test CRNs:
     - 11111111 (2 officers)
     - 11111116 (6 officers)
   - Partnership test UTRs/Postcodes

3. **Government Gateway Test Accounts**
   - Multiple test user credentials
   - Accounts without existing HMRC ASA enrolments

4. **Companies House Mock Officers** (if using stub)
   - Sample officer records for test company numbers

### Data Generated During Tests
- Agent Applications (in agent-registration backend)
- Individual Provided Details records
- Email verification tokens
- Upload IDs and file references
- Session data and cookies

---

## Key Implementation Considerations

### 1. Session Management
- **Maintain cookies/session tokens** across all 35-45 consecutive requests
- **Extract dynamic IDs** from responses:
  - Application ID after initiate call
  - Journey ID from GRS callback
  - Link IDs for individuals
  - Upload IDs from upscan

### 2. Correlation & Parameterization
```
Variables to extract and reuse:
- agentApplicationId
- journeyId
- linkId
- uploadId
- addressLookupJourneyId
- emailVerificationToken (if visible)
```

### 3. External Service Handling
```
Option 1: Use Stubs (Recommended for load testing)
- GRS Stub enabled in config
- Mock email verification service
- Mock address lookup responses
- Mock upscan responses

Option 2: Use Real Services
- Risk of external latency affecting internal measurements
- May hit rate limits on external services
- More realistic but less controlled

Option 3: Hybrid
- Use stubs for most calls
- Real calls to critical paths only
```

### 4. Think Time & Realistic Pacing
```
Add realistic delays:
- Form reading: 2-3 seconds between pages
- Information entry: 5-10 seconds per form
- File upload: Simulate user waiting for upload
- Email verification: Wait for email (mock instantly or delay 1-2s)
```

### 5. Data Variation
```
Randomize for each user:
- Business names
- Email addresses (use template: user@test.com → user123@test.com)
- Phone numbers
- Addresses (from pool of test addresses)
- Officer/partner names
- Keep: Applicant credentials, test CRNs (controlled)
```

---

## Gatling Test Structure

### Recommended File Organization
```
src/test/scala/uk/gov/hmrc/agentregistration/
├── scenarios/
│   ├── SoleTraderScenario.scala
│   ├── LimitedCompanyScenario.scala
│   ├── PartnershipScenario.scala
│   └── IndividualDetailsScenario.scala
├── simulations/
│   ├── RampUpSimulation.scala
│   ├── SustainedLoadSimulation.scala
│   ├── StressTestSimulation.scala
│   └── SoakTestSimulation.scala
├── utils/
│   ├── HttpProtocol.scala
│   ├── SessionBuilders.scala
│   └── Feeders.scala
└── RequestChains/
    ├── AuthenticationChain.scala
    ├── BusinessClassificationChain.scala
    ├── GRSJourneyChain.scala
    ├── DetailsCollectionChain.scala
    └── SubmissionChain.scala
```

### Key Simulation Patterns

**Ramp-up Simulation:**
```scala
setUp(
  soleTrader.inject(
    rampUsers(100) during (5 minutes)
  ),
  limitedCompany.inject(
    rampUsers(50) during (5 minutes)
  ),
  // ... other scenarios
).protocols(httpConf)
```

**Sustained Load Simulation:**
```scala
setUp(
  allScenarios.inject(
    constantUsersPerSec(100) during (15 minutes)
  )
).protocols(httpConf)
```

**Stress Test Simulation:**
```scala
setUp(
  allScenarios.inject(
    rampUsersPerSec(10).to(500).during(10 minutes),
    constantUsersPerSec(500) during (5 minutes)
  )
).protocols(httpConf)
```

---

## Monitoring & Analysis Points

### During Test Execution
1. Monitor backend service response times
2. Check GRS integration latency
3. Watch for connection pool exhaustion
4. Track email verification queue depth
5. Monitor file upload processing

### Post-Test Analysis
1. **Error Analysis**:
   - Group errors by endpoint
   - Identify if external service timeouts
   - Check for session/state management issues

2. **Response Time Analysis**:
   - Identify slowest endpoints
   - Check if GRS is bottleneck
   - Compare p50/p95/p99 distributions

3. **Throughput Analysis**:
   - Requests/second by endpoint type
   - Identify capacity limits
   - Calculate max sustainable load

4. **Journey Completion**:
   - % of users completing full journey
   - Where do users drop off?
   - Any systematic errors at specific stages?

---

## Success Criteria

### Performance Targets
- [ ] All endpoints respond within SLA (see metrics table above)
- [ ] No endpoints exceed p99 of 5 seconds
- [ ] Error rate stays below 0.5% under steady load
- [ ] Throughput supports at least 100 concurrent users

### Load Test Targets
- [ ] System sustains 100 users/sec for 15 minutes
- [ ] No degradation in response time over 4-hour soak test
- [ ] Error rate doesn't increase under stress (up to 500 users/sec)
- [ ] GRS integration remains stable (>98% success)

### Resilience Targets
- [ ] Graceful degradation under load (no cascading failures)
- [ ] Proper error messages to users on backend failures
- [ ] Session recovery on transient failures
- [ ] No connection pool exhaustion

---

## Next Steps

1. **Set up Gatling project** with recommended directory structure
2. **Create scenarios** for each business type (start with Sole Trader)
3. **Implement scenario chains** for each journey stage
4. **Configure load profiles** matching recommended phases
5. **Run warm-up test** with 10 users for 5 minutes
6. **Execute ramp-up test** and analyze baseline metrics
7. **Execute sustained load** and compare against targets
8. **Execute stress test** to find breaking point
9. **Generate reports** with graphs and recommendations
10. **Analyze results** against success criteria

---

## References

- Full Endpoint Guide: `GATLING_TEST_GUIDE.md`
- Endpoint Details Table: See attached documentation
- Code Routes: `conf/app.routes` and `conf/api.routes`
- Controller Code: `app/uk/gov/hmrc/agentregistrationfrontend/controllers/`
- Service Config: `conf/application.conf`


