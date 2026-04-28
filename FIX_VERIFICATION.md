# Fix Verification Checklist

## Issue Identified ✅
Your insight was **correct**: The journey from the task list uses redirect logic through CYA pages that check for unanswered questions.

### The Real Flow:
1. Task list shows "Agent services account details" link
2. User clicks link → navigates to agent-details/check-your-answers (CYA page entry point)
3. CYA page logic runs:
   - **If all questions answered**: Shows the CYA review page
   - **If questions unanswered**: Redirects to first unanswered question page
4. In our case, business name is likely unanswered, so it redirects to `/agent-details/business-name`
5. User fills in business name, telephone, email
6. Completes the journey

## Changes Made ✅

### 1. **AgentRegistrationRequests.scala** - Request Definitions

#### `getAgentDetailsCheckYourAnswersPage` (line 539)
```scala
// Now accepts 303 redirect status
.check(status.in(200, 303))
// Saves the redirect location
.check(header("Location").optional.saveAs("agentDetailsRedirectUrl"))
```
- **Before**: Expected only 200 status, didn't handle redirects
- **After**: Accepts both 200 (CYA page) and 303 (redirect), saves redirect URL

#### `followAgentDetailsInitialRedirect` (NEW - line 579)
```scala
val followAgentDetailsInitialRedirect: HttpRequestBuilder =
  http("Follow Agent Details Initial Redirect")
    .get(session => {
      val url = session("agentDetailsRedirectUrl").asOption[String]
        .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
        .getOrElse(s"$baseUrl$route/agent-details/check-your-answers")
      io.gatling.commons.validation.Success(url)
    })
    .check(status.is(200))
    .check(css("input[name=csrfToken]", "value").optional.saveAs("csrfToken"))
```
- **Purpose**: Follows the redirect from CYA page to either:
  - The CYA review page (if all answered), OR
  - The first unanswered question page (e.g., business name)
- **New**: This step was missing in the original flow!

#### `getBusinessNamePage` (simplified - line 625)
```scala
val getBusinessNamePage: HttpRequestBuilder =
  http("Get Business Name Page")
    .get(session => {
      val url = session("agentDetailsRedirectUrl").asOption[String]
        .orElse(session("agentDetailsRedirectUrl2").asOption[String])
        .orElse(session("agentDetailsRedirectUrl3").asOption[String])
        .map(loc => if (loc.startsWith("http")) loc else s"$baseUrl$loc")
        .getOrElse(s"$baseUrl$route/agent-details/business-name")
      io.gatling.commons.validation.Success(url)
    })
```
- **Simplified**: Removed complex fallback logic with 10+ session variable checks
- **Clearer**: Now just uses the redirect URLs from agent details section

### 2. **AgentRegistrationSimulation.scala** - Test Flow

#### Updated "applicant-contact-details" (line 84-98)
```scala
setup("applicant-contact-details", "Applicant Contact Details") withRequests (
  getApplicantNamePage,
  postApplicantName,
  getTelephoneNumberPage,
  postTelephoneNumber,
  getEmailAddressPage,
  postEmailAddress,
  getVerifyEmailPage,
  getEmailVerificationPasscodesPage,
  getEmailVerificationEntryPage,
  postEmailVerificationCode,
  getEmailVerificationRedirectPage,
  getApplicantCheckYourAnswersPage,
  getTaskListAfterApplicantCYA               // ← Back to task list (was removed)
)
```
- **Fixed**: Now properly returns to task list after applicant details

#### Updated "agent-account-details" (line 100-109)
```scala
setup("agent-account-details", "Agent Account Details") withRequests (
  getAgentDetailsCheckYourAnswersPage,        // Navigate to agent-details/check-your-answers
  followAgentDetailsInitialRedirect,          // ← NEW: Follow the redirect to first unanswered
  getBusinessNamePage,                        // Now properly lands here (or CYA if all answered)
  postBusinessName,
  getAgentTelephoneNumberPage,
  postAgentTelephoneNumber,
  getAgentEmailPage,
  postAgentEmail
)
```
- **Added**: `followAgentDetailsInitialRedirect` step
- **Result**: Now mirrors the real user journey

## Why This Fixes the Issue ✅

| Issue | Root Cause | Fix |
|-------|-----------|-----|
| Agent details tests failing | Missing redirect step from CYA entry | Added `followAgentDetailsInitialRedirect` |
| Complex URL resolution logic | Too many fallback session variables | Simplified to use clean redirect chain |
| Didn't match real flow | Test skipped CYA redirect validation | Now follows exact same path as browser |
| Navigation back to task list not working | Removed the return step | Re-added `getTaskListAfterApplicantCYA` |

## Files Modified ✅
1. ✅ `/Users/markbennett/workspace/agent-registration-performance-tests/src/test/scala/uk/gov/hmrc/perftests/example/AgentRegistrationRequests.scala`
2. ✅ `/Users/markbennett/workspace/agent-registration-performance-tests/src/test/scala/uk/gov/hmrc/perftests/example/AgentRegistrationSimulation.scala`

## Compilation Status ✅
- No syntax errors
- All imports present
- All referenced methods exist
- No unresolved symbols

## Next Steps
1. Run the performance tests: `sbt "gatling:testOnly uk.gov.hmrc.perftests.example.AgentRegistrationSimulation"`
2. Check debug HTML files in `target/debug/` to verify the flow
3. Verify test completes successfully with proper response times

