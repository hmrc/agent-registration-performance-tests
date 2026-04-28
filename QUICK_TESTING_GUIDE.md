# Quick Testing Guide

## What Was Fixed

The agent registration performance test had a **missing redirect step** that caused test failures when navigating to agent account details.

### The Problem
When a user clicks the "Agent services account details" link from the task list:
1. The backend navigates to `/agent-details/check-your-answers`
2. The backend validates answers
3. If questions are unanswered, it **redirects (303)** to the first unanswered page (e.g., `/agent-details/business-name`)
4. The **test wasn't following this redirect**, so it failed

### The Solution
Added the missing `followAgentDetailsInitialRedirect` request step that:
- Captures the redirect location from the CYA entry point
- Follows the redirect to the actual page the user sees
- Extracts the CSRF token for form submission

## Testing the Fix

### Option 1: Run Full Test Suite
```bash
cd /Users/markbennett/workspace/agent-registration-performance-tests
sbt "gatling:testOnly uk.gov.hmrc.perftests.example.AgentRegistrationSimulation"
```

### Option 2: Run Individual Steps
Test just the applicant contact details:
```bash
sbt "gatling:testOnly uk.gov.hmrc.perftests.example.AgentRegistrationSimulation -s applicant-contact-details"
```

Test just the agent account details:
```bash
sbt "gatling:testOnly uk.gov.hmrc.perftests.example.AgentRegistrationSimulation -s agent-account-details"
```

### Option 3: Run with Debug Output
```bash
sbt "gatling:testOnly uk.gov.hmrc.perftests.example.AgentRegistrationSimulation" -Dgatling.core.directory.simulations=target/scala-2.13/test
```

## What to Look For

### Success Indicators ✅
1. Test completes without 404 or connection errors
2. Response times are reasonable (< 2 seconds per request)
3. HTML debug files are created in `/target/debug/`
4. Gatling reports show 100% success rate

### Debug Files Created
Check these files in `target/debug/` to verify the flow:
- `task-list-after-applicant-cya-final.html` - Task list showing "Agent services account details" link
- `agent-details-entry-response.html` - First response from agent details (may show redirect)
- `agent-details-after-redirect.html` - Response after following the redirect (should show business name form)

### In Browser DevTools (for manual verification)
1. Navigate to the task list
2. Click "Agent services account details"
3. Open Network tab - you should see:
   - GET `/agent-details/check-your-answers` → 303 redirect
   - GET `/agent-details/business-name` → 200 OK (follows redirect)
4. The form should be visible and ready to fill

## Key Changes Made

| File | Change | Line(s) |
|------|--------|---------|
| AgentRegistrationRequests.scala | `getAgentDetailsCheckYourAnswersPage` now accepts 303 status | 549 |
| AgentRegistrationRequests.scala | **NEW** `followAgentDetailsInitialRedirect` request added | 579 |
| AgentRegistrationRequests.scala | Simplified `getBusinessNamePage` logic | 625 |
| AgentRegistrationSimulation.scala | Added `followAgentDetailsInitialRedirect` to agent-account-details flow | 102 |

## If Tests Still Fail

### 404 on followAgentDetailsInitialRedirect
- Check that `agentDetailsRedirectUrl` was captured in previous step
- Verify the task list is returning the agent details link
- Check `target/debug/` HTML files

### Missing CSRF token
- Ensure the redirect page contains `<input name="csrfToken"`
- Check the debug HTML in `agent-details-after-redirect.html`

### Wrong page content
- The test expects to land on business-name page after redirect
- If you're getting a different page, it may have different unanswered questions
- Review backend validation logic

## Performance Test Structure

### Setup Hierarchy
```
AgentRegistrationSimulation
├── select-agent-type (Agent Type selection)
├── select-business-type (Business Type selection)
├── select-business-owner (Business Owner confirmation)
├── sign-in (Full OAuth/GRS flow)
├── grs-test-data (Data setup)
├── applicant-contact-details (✅ FIXED: Includes return to task list)
│   └── getTaskListAfterApplicantCYA (Returns to task list)
└── agent-account-details (✅ FIXED: Handles redirect)
    ├── getAgentDetailsCheckYourAnswersPage
    ├── followAgentDetailsInitialRedirect (NEW)
    └── getBusinessNamePage (now correctly positioned)
```

## Verification Checklist

- [ ] Run `sbt compile` - no errors
- [ ] Run agent-account-details test - completes successfully
- [ ] Check `/target/debug/` HTML files exist
- [ ] Verify response times are acceptable
- [ ] Review Gatling HTML report for any failures
- [ ] Manual browser test confirms flow works

## Contact Info
For issues or questions about this implementation, refer to:
- `IMPLEMENTATION_COMPLETE.md` - Detailed technical changes
- `FIX_VERIFICATION.md` - Initial verification notes

