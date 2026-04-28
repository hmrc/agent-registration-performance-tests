# Agent Details Redirect Flow Fix

## Problem

The test journey for "Agent Account Details" was failing because it didn't account for the real flow of the application:

1. When accessing agent details from the task list, the application redirects to the CYA (Check Your Answers) page
2. The CYA page then checks if all questions in that section are answered
3. If questions are unanswered, it redirects to the first unanswered page (e.g., business name page)
4. The test was expecting to land directly on the CYA page or bypass these redirects

## Solution

Updated the test flow to properly handle the redirect chain:

### Changes Made

#### 1. **AgentRegistrationRequests.scala**

- **`getAgentDetailsCheckYourAnswersPage`**: Now properly accepts a 303 redirect status and saves the redirect location
  - Accepts both 200 and 303 status codes
  - Saves the redirect URL for following
  - Detects which page was actually landed on

- **`followAgentDetailsInitialRedirect`**: NEW request that follows the redirect from the agent details entry point
  - Follows the redirect to either the CYA page or the first unanswered question page
  - Saves the CSRF token and other necessary data

- **`followAgentDetailsRedirectIfNeeded` and `followAgentDetailsRedirectIfNeeded2`**: Simplified versions
  - Removed complex file-writing logic
  - Streamlined to handle intermediate redirects if needed

- **`getBusinessNamePage`**: Simplified to use redirect URLs from the agent details section
  - Removed complex fallback logic with many session variables
  - Now cleanly uses the redirect flow to reach the correct page

#### 2. **AgentRegistrationSimulation.scala**

Updated the "agent-account-details" setup to include the new redirect-following step:

```scala
setup("agent-account-details", "Agent Account Details") withRequests (
  getAgentDetailsCheckYourAnswersPage,           // Navigate to agent details entry
  followAgentDetailsInitialRedirect,              // Follow redirect (new step!)
  getBusinessNamePage,
  postBusinessName,
  getAgentTelephoneNumberPage,
  postAgentTelephoneNumber,
  getAgentEmailPage,
  postAgentEmail
)
```

Updated the "applicant-contact-details" setup to use the proper task list navigation:

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
  getTaskListAfterApplicantCYA              // Navigate back to task list
)
```

## How It Works Now

### Agent Details Flow:
1. Click "Agent services account details" link from task list (session variable from earlier request)
2. Application redirects to CYA page which checks for unanswered questions
3. Test follows that redirect (which points to the first unanswered question, i.e., business name page)
4. Complete the agent details questions (business name, telephone, email)

### Applicant Details Flow:
1. Complete applicant name, telephone, email
2. Verify email with passcode
3. View applicant CYA
4. Return to task list to access other sections

## Key Insights

- **CYA pages always check for missing data first**: If any question is unanswered, the CYA page redirects to the first unanswered question instead of showing the review page
- **The real flow uses these redirects**: This is by design to guide users through incomplete sections
- **Tests must mirror this behavior**: The performance test needs to follow these same redirects to accurately reflect real user behavior

## Testing the Fix

Run the simulation with:
```bash
sbt "gatling:testOnly uk.gov.hmrc.perftests.example.AgentRegistrationSimulation"
```

The flow should now:
1. ✅ Navigate to agent details without errors
2. ✅ Follow redirects properly
3. ✅ Land on the business name page (first unanswered question)
4. ✅ Complete all agent details questions
5. ✅ Return to task list after applicant details

