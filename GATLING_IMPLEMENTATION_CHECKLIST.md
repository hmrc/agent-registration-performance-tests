# Gatling Implementation Checklist

## Pre-Implementation Setup

### Environment
- [ ] Java 11+ installed
- [ ] Maven 3.6+ or SBT 1.5+ installed
- [ ] Gatling 3.9+ installed
- [ ] Git configured
- [ ] IDE set up (IntelliJ, VS Code, etc.)

### Dependencies
- [ ] Create new SBT project or Maven project
- [ ] Add Gatling dependency: `"io.gatling.highcharts" % "gatling-charts-highcharts" % gatlingVersion`
- [ ] Add Gatling core: `"io.gatling" % "gatling-core" % gatlingVersion`
- [ ] Add Scala library: `"org.scala-lang" % "scala-library" % scalaVersion`

### Backend Services
- [ ] Start agent-registration-frontend on port 22201
- [ ] Start agent-registration backend on port 22202
- [ ] Start agent-registration-risking on port 22203
- [ ] Start auth service on port 8500
- [ ] Start enrolment-store-proxy on port 9595
- [ ] Start citizen-details on port 9337
- [ ] Start companies-house-api-proxy on port 9991
- [ ] Start sole-trader-identification-frontend (GRS stub) on port 9717
- [ ] Start incorporated-entity-identification-frontend (GRS stub) on port 9718
- [ ] Start partnership-identification-frontend (GRS stub) on port 9722
- [ ] Start email-verification on port 9891
- [ ] Start address-lookup-frontend on port 9028
- [ ] Start upscan-initiate on port 9570
- [ ] Start agent-assurance on port 9565
- [ ] Start object-store on port 8464
- [ ] Start MongoDB
- [ ] Verify all services running: `curl localhost:PORT/health`

### Data Seeding
- [ ] Load AMLS supervisors from conf/amls.csv
- [ ] Create test Government Gateway accounts
- [ ] Seed GRS stub with test data:
  - [ ] Sole Trader: Test NINO + DOB
  - [ ] Limited Company: Test CRNs (11111111, 11111116, 22222222, etc.)
  - [ ] Partnership: Test UTRs/Postcodes
- [ ] Create test user accounts with no existing HMRC ASA enrolment

---

## Phase 1: Project Setup

### Create Gatling Project Structure
```
gatling-tests/
├── src/
│   └── test/
│       └── scala/
│           └── uk/gov/hmrc/agentregistration/
│               ├── simulations/
│               ├── scenarios/
│               ├── chains/
│               ├── utils/
│               └── feeders/
├── build.sbt
└── pom.xml (if using Maven)
```

### Create Directories
- [ ] `src/test/scala/uk/gov/hmrc/agentregistration/`
- [ ] `src/test/scala/uk/gov/hmrc/agentregistration/simulations/`
- [ ] `src/test/scala/uk/gov/hmrc/agentregistration/scenarios/`
- [ ] `src/test/scala/uk/gov/hmrc/agentregistration/chains/`
- [ ] `src/test/scala/uk/gov/hmrc/agentregistration/utils/`
- [ ] `src/test/scala/uk/gov/hmrc/agentregistration/feeders/`
- [ ] `src/test/resources/` (for test data files)

### Create Core Files
- [ ] `build.sbt` with Gatling dependencies
- [ ] `gatling.conf` (optional, for Gatling configuration)
- [ ] `.gitignore` (exclude results, target directories)

---

## Phase 2: Core Infrastructure

### HttpProtocol
- [ ] Create `HttpProtocol.scala`
- [ ] Configure base URL: `http://localhost:22201`
- [ ] Set accept headers
- [ ] Set encoding headers
- [ ] Configure connection pooling
- [ ] Set cache policy (disable for testing)

### Feeders
- [ ] Create `Feeders.scala`
- [ ] Implement `applicantNameFeeder`
- [ ] Implement `businessNameFeeder`
- [ ] Implement `emailFeeder`
- [ ] Implement `phoneFeeder`
- [ ] Implement `amlsFeeder`
- [ ] Implement `partnerNameFeeder`
- [ ] Test feeders produce valid data

### Session Helpers
- [ ] Create `SessionHelpers.scala`
- [ ] Implement `extractApplicationId`
- [ ] Implement `extractJourneyId`
- [ ] Implement `extractLinkId`
- [ ] Implement `extractUploadId`
- [ ] Implement `storePathParams`

---

## Phase 3: Request Chains

### Chain 1: Business Classification
- [ ] Create `BusinessClassificationChain.scala`
- [ ] Implement `selectAgentType` (GET + POST)
- [ ] Implement `selectBusinessType` (GET + POST)
- [ ] Implement `selectUserRole` (GET + POST)
- [ ] Implement `selectSignInType` (GET + POST)
- [ ] Implement `viewSignInPage` (GET)
- [ ] Add realistic think times (2-3 seconds)
- [ ] Test chain in isolation

### Chain 2: GRS Journey
- [ ] Create `GRSJourneyChain.scala`
- [ ] Implement `initiateApplication` (GET, extract URL)
- [ ] Implement `startGRSJourney` (GET, extract URL)
- [ ] Implement `simulateGRSCompletion` (simulate time on GRS)
- [ ] Implement `grsCallback` (GET with journeyId)
- [ ] Implement `refusalToDealCheck` (GET)
- [ ] Implement `deceasedCheck` (GET)
- [ ] Implement `companiesHouseStatusCheck` (GET, conditional)
- [ ] Add error handling for failed checks
- [ ] Test chain in isolation

### Chain 3: Details Collection
- [ ] Create `DetailsCollectionChain.scala`
- [ ] Implement `viewTaskList` (GET)
- [ ] Implement `collectApplicantDetails` (6-7 requests)
- [ ] Implement `collectBusinessDetails` (6-7 requests)
- [ ] Implement `collectAMLSDetails` (7-8 requests)
- [ ] Add email verification (mock or real)
- [ ] Add file upload simulation
- [ ] Test chain in isolation

### Chain 4: Declaration & Submission
- [ ] Create `SubmissionChain.scala`
- [ ] Implement `acceptHMRCStandard` (GET + POST)
- [ ] Implement `submitDeclaration` (GET + POST)
- [ ] Implement `viewSubmissionConfirmation` (GET)
- [ ] Add error handling
- [ ] Test chain in isolation

### Optional Chains
- [ ] Create `LimitedCompanyChain.scala` (officers handling)
- [ ] Create `PartnershipChain.scala` (key individuals)
- [ ] Create `IndividualDetailsChain.scala` (share link journey)

---

## Phase 4: Scenarios

### Scenario 1: Sole Trader
- [ ] Create `SoleTraderScenario.scala`
- [ ] Import all necessary chains
- [ ] Create scenario definition
- [ ] Wire chains in correct order
- [ ] Set `businessType = "SoleTrader"`
- [ ] Set `userRole = "Owner"`
- [ ] Verify 35 total requests in chain
- [ ] Test scenario runs without errors

### Scenario 2: Limited Company (Optional)
- [ ] Create `LimitedCompanyScenario.scala`
- [ ] Set `businessType = "LimitedCompany"`
- [ ] Set `userRole = "Director"`
- [ ] Include officers handling
- [ ] Verify 39-45 total requests
- [ ] Test scenario runs

### Scenario 3: Partnership (Optional)
- [ ] Create `PartnershipScenario.scala`
- [ ] Set `businessType = "GeneralPartnership"`
- [ ] Set `userRole = "Partner"`
- [ ] Include key individuals handling
- [ ] Verify 41+ total requests
- [ ] Test scenario runs

### Scenario 4: Individual Details (Optional)
- [ ] Create `IndividualDetailsScenario.scala`
- [ ] Implement share link journey (11 requests)
- [ ] Extract linkId from session/seed
- [ ] Test scenario runs

---

## Phase 5: Simulations

### Simulation 1: Ramp-Up Test
- [ ] Create `RampUpSimulation.scala`
- [ ] Import `SoleTraderScenario`
- [ ] Configure: 10 users/sec → 100 users/sec over 5 minutes
- [ ] Set assertions:
  - [ ] Response time p95 ≤ 800ms
  - [ ] Success rate ≥ 99.5%
- [ ] Test locally with `gatling:execute` or `sbt Gatling/test`

### Simulation 2: Sustained Load
- [ ] Create `SustainedLoadSimulation.scala`
- [ ] Import all 4 scenarios
- [ ] Configure: 100 users/sec constant for 15 minutes
- [ ] Set assertions:
  - [ ] Response time p95 ≤ 1s
  - [ ] Response time p99 ≤ 3s
  - [ ] Success rate ≥ 99.5%
- [ ] Test locally

### Simulation 3: Stress Test (Advanced)
- [ ] Create `StressTestSimulation.scala`
- [ ] Configure: Ramp 100 → 500 users/sec over 10 minutes
- [ ] Monitor for breaking point
- [ ] Collect baseline for breaking point

### Simulation 4: Soak Test (Advanced)
- [ ] Create `SoakTestSimulation.scala`
- [ ] Configure: 50 users/sec for 4 hours
- [ ] Monitor memory usage over time
- [ ] Check for connection pool issues

---

## Phase 6: Testing & Validation

### Pre-Test Verification
- [ ] All backend services running and responding
- [ ] Test data is seeded correctly
- [ ] Network connectivity to all services verified
- [ ] Gatling installation verified: `gatling.bat --version` or `gatling -v`
- [ ] Results directory is writable: `results/`

### Test 1: Single User Walk-Through
- [ ] Run Sole Trader scenario with 1 user
- [ ] Check all requests succeed (200/303 responses)
- [ ] Verify no exceptions in logs
- [ ] Check application created in backend
- [ ] Verify all data persisted correctly

### Test 2: Ramp-Up Test
- [ ] Run `RampUpSimulation`
- [ ] Monitor: Does it complete without errors?
- [ ] Generate report: Check Gatling HTML report
- [ ] Verify: Response times meet targets
- [ ] Check: Error rate is acceptable (< 0.5%)

### Test 3: Data Variation
- [ ] Update feeders to produce more variation
- [ ] Re-run ramp-up test
- [ ] Verify no caching issues
- [ ] Check database for diverse test data

### Test 4: Load Profile Progression
- [ ] Run 25 users/sec for 5 minutes
- [ ] Note performance metrics
- [ ] Increase to 50 users/sec for 5 minutes
- [ ] Compare metrics (should be similar)
- [ ] Increase to 100 users/sec for 5 minutes
- [ ] Identify if degradation starts

---

## Phase 7: Analysis & Optimization

### Metrics Collection
- [ ] Collect baseline metrics from ramp-up
- [ ] Generate response time percentile graphs
- [ ] Generate throughput trending graph
- [ ] Generate error rate by endpoint graph
- [ ] Identify slowest endpoints

### Performance Analysis
- [ ] Which endpoints exceed p95 targets?
- [ ] Is GRS integration the bottleneck?
- [ ] Are there connection pool issues?
- [ ] Is email verification slowing things down?
- [ ] Are there database query issues?

### Optimization
- [ ] Add connection pool tuning
- [ ] Increase backend service capacity
- [ ] Optimize slow database queries
- [ ] Review GRS integration
- [ ] Consider caching strategies

### Re-testing
- [ ] Run ramp-up test again
- [ ] Compare metrics with baseline
- [ ] Verify improvements
- [ ] Document changes made

---

## Phase 8: Final Load Testing

### Run All Simulations in Sequence
1. [ ] Warm-up: 25 users/sec for 2 minutes
2. [ ] Ramp-up: 10 → 100 users/sec over 5 minutes
3. [ ] Sustained: 100 users/sec for 15 minutes
4. [ ] Stress: 100 → 500 users/sec over 10 minutes (optional)
5. [ ] Soak: 50 users/sec for 4 hours (overnight optional)

### Document Results
- [ ] Save all Gatling HTML reports
- [ ] Extract metrics to spreadsheet
- [ ] Create graphs for trending
- [ ] Document any errors encountered
- [ ] Note any recommendations

### Validate Against Success Criteria
- [ ] All endpoints meet p95 target ✓ / ✗
- [ ] No endpoints exceed p99 of 5s ✓ / ✗
- [ ] Error rate < 0.5% ✓ / ✗
- [ ] Throughput ≥ 100 users/sec ✓ / ✗
- [ ] No degradation over 4-hour soak ✓ / ✗
- [ ] GRS integration > 98% success ✓ / ✗

---

## Phase 9: Reporting

### Create Test Report
- [ ] Executive summary (1 page)
- [ ] Metrics tables (response times, throughput, errors)
- [ ] Graphs (response time over time, percentiles)
- [ ] Analysis (bottlenecks, recommendations)
- [ ] Appendix (raw data, configuration)

### Present Findings
- [ ] Share report with stakeholders
- [ ] Highlight key metrics
- [ ] Present recommendations
- [ ] Discuss remediation plan
- [ ] Schedule follow-up testing

---

## Phase 10: Continuous Testing

### Set Up Automation
- [ ] Configure CI/CD pipeline to run Gatling tests
- [ ] Schedule regular test runs (weekly/monthly)
- [ ] Store results for trending
- [ ] Alert on metric degradation
- [ ] Create dashboard for monitoring

### Maintain Tests
- [ ] Update scenarios when endpoints change
- [ ] Refresh test data regularly
- [ ] Update assertions as system evolves
- [ ] Document all changes to tests
- [ ] Keep README updated

### Trending
- [ ] Compare metrics month-over-month
- [ ] Track improvements from optimizations
- [ ] Identify patterns or issues
- [ ] Use trending to capacity plan
- [ ] Report to management

---

## Success Checklist

### Functional
- [ ] All scenarios run without errors
- [ ] All requests succeed (success rate > 99%)
- [ ] No cascading failures
- [ ] Proper session management
- [ ] Correct data persistence

### Performance
- [ ] Response times meet targets
- [ ] Throughput meets expectations
- [ ] Error rates acceptable
- [ ] No connection pool exhaustion
- [ ] No memory leaks detected

### Operational
- [ ] Tests run reliably
- [ ] Reports generated successfully
- [ ] Metrics extractable
- [ ] Reproducible results
- [ ] Easy to scale up

### Documentation
- [ ] Code documented
- [ ] Scenarios clearly defined
- [ ] Results clearly reported
- [ ] Findings actionable
- [ ] Recommendations clear

---

## Troubleshooting Checklist

### If Tests Fail
- [ ] Check all backend services are running
- [ ] Verify network connectivity
- [ ] Check firewall rules
- [ ] Review server logs for errors
- [ ] Try single user test first

### If Performance is Poor
- [ ] Identify slowest endpoint
- [ ] Check backend service performance
- [ ] Monitor database queries
- [ ] Review GRS integration latency
- [ ] Check network latency

### If Data Issues
- [ ] Verify test data is seeded correctly
- [ ] Check feeders are working
- [ ] Verify session correlation
- [ ] Check database for duplicates
- [ ] Review ID extraction logic

### If Out of Memory
- [ ] Increase JVM heap: `-Xmx4G`
- [ ] Reduce concurrent users
- [ ] Check session storage for leaks
- [ ] Verify feeders aren't accumulating
- [ ] Clear temp files before test

---

## Final Validation

Before declaring success:

- [ ] ✅ All code written and tested
- [ ] ✅ All scenarios working
- [ ] ✅ Ramp-up test completed
- [ ] ✅ Sustained load test completed
- [ ] ✅ Metrics extracted and analyzed
- [ ] ✅ Report generated
- [ ] ✅ Success criteria verified
- [ ] ✅ Recommendations documented
- [ ] ✅ Team trained on using tests
- [ ] ✅ Tests integrated into CI/CD

**GATLING PERFORMANCE TEST SUITE READY FOR PRODUCTION USE**


