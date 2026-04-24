# Gatling Performance Test Suite - Complete Documentation

## 📚 Documentation Package Overview

This package contains everything you need to create and run a comprehensive Gatling performance test suite for the agent-registration-frontend service.

### Files Created

#### 1. **GATLING_TEST_GUIDE.md** (Primary Reference)
- **Size**: 1,026 lines, comprehensive reference document
- **Contents**:
  - Architecture overview and external service integrations
  - Detailed endpoint mapping for all 11 journey stages
  - Stage 1: Landing & Authentication
  - Stage 2: Business Information Collection
  - Stage 3: GRS Journey Initiation
  - Stage 4: Task List & Dashboard
  - Stage 5: Applicant Contact Details
  - Stage 6: Agent Business Details
  - Stage 7: AMLS Details
  - Stage 8: Directors/Partners/Members Lists (3 routes)
  - Stage 9: HMRC Standard for Agents
  - Stage 10: Declaration & Submission
  - Stage 11: Application Status Pages
  - Test data requirements
  - 4 complete scenario sequences
  - Performance test configuration

**Use this document when**: You need detailed information about any specific endpoint, understanding the flow, or planning test scenarios

---

#### 2. **GATLING_SUMMARY.md** (Executive Overview)
- **Size**: ~400 lines, high-level summary
- **Contents**:
  - Quick stats (52 unique endpoints, 35-45 calls per journey)
  - 5-stage registration flow with ASCII diagrams
  - Parallel individual share link journey
  - External service integration points
  - 5 recommended test scenarios (A-E)
  - Load profile recommendations (4 phases)
  - Performance metrics table
  - Data requirements
  - Gatling test structure recommendations
  - Key simulation patterns
  - Monitoring & analysis points
  - Success criteria checklist

**Use this document when**: You want a quick overview, presenting to stakeholders, or planning the test approach

---

#### 3. **GATLING_CODE_EXAMPLES.md** (Implementation Guide)
- **Size**: ~450 lines, ready-to-use code examples
- **Contents**:
  - HTTP protocol configuration
  - Feeders for test data variation
  - Session helpers for ID extraction
  - Request chains by journey stage:
    - Business Classification (4 endpoints)
    - GRS Journey (6 endpoints)
    - Task List & Details (15+ endpoints)
    - Declaration & Submission (3 endpoints)
  - Complete Sole Trader scenario
  - Multiple simulation classes:
    - RampUp Simulation
    - Sustained Load Simulation
  - Running the tests (Maven, SBT, Docker)
  - Key implementation points

**Use this document when**: Building your Gatling test classes, need Scala code templates, or setting up simulation configurations

---

## 🎯 Quick Start Guide

### Step 1: Choose Your Reference
Start with **GATLING_SUMMARY.md** for the big picture:
- Understand the 52 endpoints at a glance
- Review the 5-stage flow
- Pick your scenario approach
- Check the load profile recommendations

### Step 2: Plan Your Test Scenarios
Reference **GATLING_SUMMARY.md** scenarios section:
- Scenario A: Sole Trader Happy Path (35 calls)
- Scenario B: Limited Company with Few Officers (39-42 calls)
- Scenario C: Limited Company with Many Officers (45+ calls)
- Scenario D: Partnership with Multiple Partners (41+ calls)
- Scenario E: Individual Provides Details via Share Link (11 calls)

**Recommended starting point**: Begin with Scenario A (Sole Trader) as it's the simplest, then build up complexity

### Step 3: Deep Dive into Endpoints
For each journey stage, use **GATLING_TEST_GUIDE.md**:
- Navigate to the specific stage
- Read the endpoint specifications
- Note the request/response patterns
- Identify correlation points (IDs to extract)

Example flow:
1. STAGE 2: Business Information Collection (6 endpoints)
   - Agent type selection
   - Business type selection
   - Partnership type (conditional)
   - User role selection
   - Sign-in type selection
   - Sign-in page display

### Step 4: Build Your Test Code
Use **GATLING_CODE_EXAMPLES.md** as templates:
1. Copy HTTP protocol configuration
2. Create feeders for your test data
3. Build request chains from the examples
4. Combine chains into scenarios
5. Create simulation classes

Example implementation:
```
1. Copy HttpProtocol from examples
2. Create Feeders.scala with your data
3. Create BusinessClassificationChain.scala
4. Create GRSJourneyChain.scala
5. Create DetailsCollectionChain.scala
6. Create SubmissionChain.scala
7. Create SoleTraderScenario.scala
8. Create RampUpSimulation.scala
```

### Step 5: Run Your First Test
Start with ramp-up simulation:
```bash
# Using SBT
sbt "Gatling / testOnly uk.gov.hmrc.agentregistration.gatling.simulations.RampUpSimulation"

# Duration: ~5 minutes ramp from 10 to 100 users/sec
# Expected output: baseline performance metrics
```

### Step 6: Analyze Results
Check Gatling reports:
- Response time percentiles (p50, p95, p99)
- Error rates by endpoint
- Throughput (requests/second)
- Identify slow endpoints

### Step 7: Scale Up
Progress through load profiles:
1. Ramp-up (5 min) - Establish baseline
2. Sustained Load (15 min) - Measure steady-state
3. Stress Test (10 min) - Find breaking point
4. Soak Test (4 hours) - Detect degradation

---

## 🔄 Journey Sequences At-A-Glance

### Sole Trader (35 calls)
```
Classification (6) → GRS (4) → Checks (3) → 
Task List (1) → Applicant (5) → Business (6) → 
AMLS (5) → Standard (1) → Declaration (1) → 
Status (2)
```

### Limited Company ≤5 Officers (39 calls)
```
Same as Sole Trader + CH Status Check (1) + 
Officers Confirmation (2)
```

### Limited Company >5 Officers (45+ calls)
```
Same as Sole Trader + CH Status Check (1) + 
Manual Officer Addition (6+)
```

### Partnership (41+ calls)
```
Classification (7 - includes partnership type) → GRS (4) → 
Checks (2 - no CH check) → Task List (1) → 
Applicant (5) → Business (6) → AMLS (5) → 
Key Individuals (6) → Other Individuals (4) → 
Standard (1) → Declaration (1) → Status (2)
```

### Individual Details via Link (11 calls)
```
Start (1) → Match (1) → Phone (1) → 
Email (2) → DOB (1) → NINO (1) → 
Approve (1) → Standard (1) → Check (1) → 
Confirm (1)
```

---

## 📊 Key Metrics to Monitor

### Response Time Targets (p95)
| Endpoint Type | Target |
|---|---|
| Frontend Pages | 300ms |
| Form Submissions | 400ms |
| GRS Integration | 2s |
| Backend API | 800ms |
| Email Verification | 500ms |
| File Upload Polling | 200ms |

### Overall Journey Targets
- End-to-end time (p95): < 5 minutes
- Task list load (p95): < 1 second
- Submission success: > 99.5%
- GRS completion: > 98%

### Error Rate Targets
- Overall: < 0.5%
- By endpoint: < 0.1% (most endpoints)
- GRS integration: < 1% (due to external dependency)

---

## 🛠️ External Service Integration

### Mock vs. Real Considerations

**Use Mocks (Recommended for Load Testing)**:
- GRS Stub (enabled in config)
- Email Verification (set ignoreEmailVerification=true)
- Address Lookup (mock responses)
- Upscan (mock upload responses)

**Benefits**: 
- Consistent latency
- No rate limiting issues
- Full control over test conditions
- Faster feedback cycles

**Use Real Services (For Acceptance Testing)**:
- GRS integration testing
- End-to-end verification
- Real email delivery
- Real file upload

**Benefits**:
- Tests actual integrations
- Catches real integration issues
- Production-like behavior

---

## 📋 Pre-Test Checklist

- [ ] All backend services running (agent-registration, auth, etc.)
- [ ] MongoDB up and populated with test data
- [ ] GRS stub enabled (or external GRS available)
- [ ] Test accounts created in Government Gateway
- [ ] AMLS supervisors loaded from conf/amls.csv
- [ ] Test company numbers available (if testing Ltd Co)
- [ ] Email verification service mocked or available
- [ ] File upload storage (object-store) accessible
- [ ] Network connectivity to all services verified
- [ ] Load testing tool (Gatling) installed and configured
- [ ] Test reports directory writable
- [ ] Monitoring/logging enabled on backend services

---

## 🚀 Execution Timeline

### Day 1: Setup
- [ ] Install Gatling and dependencies
- [ ] Create project structure
- [ ] Copy HTTP protocol and feeders from examples
- [ ] Deploy all backend services
- [ ] Verify connectivity to all services

### Day 2: Simple Scenario
- [ ] Implement BusinessClassificationChain
- [ ] Implement GRSJourneyChain (with simulated GRS)
- [ ] Implement DetailsCollectionChain
- [ ] Implement SubmissionChain
- [ ] Create SoleTraderScenario
- [ ] Create RampUpSimulation
- [ ] Run first test (5 min ramp-up)
- [ ] Analyze baseline results

### Day 3: Complex Scenarios
- [ ] Add Limited Company scenario
- [ ] Add Partnership scenario
- [ ] Add Individual details scenario
- [ ] Create SustainedLoadSimulation
- [ ] Run 15-minute sustained load test
- [ ] Analyze metrics against targets

### Day 4: Stress & Soak
- [ ] Create StressTestSimulation
- [ ] Run stress test (100→500 users/sec)
- [ ] Identify breaking points
- [ ] Create SoakTestSimulation
- [ ] Run 4-hour soak test overnight
- [ ] Analyze long-term degradation

### Day 5: Analysis & Tuning
- [ ] Generate consolidated reports
- [ ] Identify slow endpoints
- [ ] Analyze error patterns
- [ ] Make recommendations
- [ ] Document findings

---

## 📈 Success Criteria Checklist

### Performance ✓
- [ ] All endpoints meet p95 target
- [ ] No endpoints exceed p99 of 5 seconds
- [ ] Error rate < 0.5% under sustained load
- [ ] Throughput supports ≥100 concurrent users

### Load Testing ✓
- [ ] 100 users/sec sustained for 15+ minutes
- [ ] No degradation over 4-hour soak test
- [ ] Graceful degradation under stress
- [ ] No cascading failures

### Data & Integration ✓
- [ ] All external services stable
- [ ] GRS success rate > 98%
- [ ] Email verification working
- [ ] File uploads completing
- [ ] No connection pool exhaustion

### Reporting ✓
- [ ] Graphs of response times over time
- [ ] Error distribution by endpoint
- [ ] Throughput trending
- [ ] Resource utilization metrics
- [ ] Recommendations for optimization

---

## 🔗 Document Navigation

| I Want To... | Read This | Sections |
|---|---|---|
| Understand the big picture | GATLING_SUMMARY.md | Overview, 5-stage flow, external services |
| Get all endpoint details | GATLING_TEST_GUIDE.md | All 11 stages, request/response details |
| See code examples | GATLING_CODE_EXAMPLES.md | Chains, scenarios, simulations |
| Plan my scenarios | GATLING_SUMMARY.md | Scenarios A-E, load profiles |
| Build Gatling code | GATLING_CODE_EXAMPLES.md | Copy templates, customize feeders |
| Find a specific endpoint | GATLING_TEST_GUIDE.md | Use Ctrl+F, search endpoint name |
| Understand error rates | GATLING_SUMMARY.md | Performance metrics table |
| Know what to monitor | GATLING_SUMMARY.md | Monitoring section |
| Get started quickly | This document (README) | Quick start guide section |

---

## 💡 Pro Tips

1. **Start simple**: Begin with Sole Trader scenario, add complexity later
2. **Extract IDs**: Always save dynamic IDs from responses for correlation
3. **Use feeders**: Randomize data to avoid caching effects
4. **Add think time**: Realistic pauses make tests more meaningful
5. **Monitor backend**: Watch service logs during tests for errors
6. **Ramp gradually**: Don't jump to 500 users, ramp up slowly
7. **Baseline first**: Get baseline metrics before optimization
8. **Analyze trends**: Compare metrics across test runs
9. **Mock externals**: Use stubs for consistent, fast testing
10. **Document findings**: Keep records of each test run for trending

---

## 📞 Troubleshooting

### Test fails at GRS integration
- Check GRS stub is enabled in config
- Verify journey ID is being extracted correctly
- Ensure mock GRS data is configured
- Check network connectivity to GRS service

### High error rate
- Check backend service logs for exceptions
- Verify all required data is seeded
- Check for connection pool exhaustion
- Ensure email verification is mocked (if needed)

### Slow response times
- Identify which endpoints are slowest
- Check backend service performance
- Look for database query issues
- Verify network latency

### Out of memory errors
- Reduce concurrent users
- Increase JVM heap size
- Check for memory leaks in session storage
- Ensure feeders aren't accumulating data

### Connection pool exhausted
- Increase HTTP connection pool size
- Check if connections are being properly closed
- Reduce concurrent users
- Add debugging to see connection state

---

## 📚 Additional Resources

### Within This Package
- Full endpoint reference: GATLING_TEST_GUIDE.md
- Code templates: GATLING_CODE_EXAMPLES.md
- Overview & strategy: GATLING_SUMMARY.md

### External References
- Gatling official docs: https://gatling.io/docs/
- Scala syntax: https://docs.scala-lang.org/
- Play Framework testing: https://www.playframework.com/documentation

### Codebase References
- Routes: `conf/app.routes` and `conf/api.routes`
- Controllers: `app/uk/gov/hmrc/agentregistrationfrontend/controllers/`
- Config: `conf/application.conf`
- Connectors: `app/uk/gov/hmrc/agentregistrationfrontend/connectors/`

---

## 📝 Document Versions

| Document | Version | Last Updated | Purpose |
|---|---|---|---|
| GATLING_TEST_GUIDE.md | 1.0 | [Date] | Comprehensive endpoint reference |
| GATLING_SUMMARY.md | 1.0 | [Date] | Executive overview & strategy |
| GATLING_CODE_EXAMPLES.md | 1.0 | [Date] | Implementation templates |
| GATLING_README.md | 1.0 | [Date] | Navigation & quick start |

---

## ✅ Validation

This documentation covers:
- ✅ All 52+ endpoints in the agent-registration-frontend
- ✅ All business type flows (Sole Trader, Limited Company, Partnerships, LLP)
- ✅ All individual detail collection flows
- ✅ All external service integrations
- ✅ Performance test configuration
- ✅ Implementation examples
- ✅ Success criteria and metrics
- ✅ Troubleshooting guidance

Ready to build your Gatling performance test suite!


