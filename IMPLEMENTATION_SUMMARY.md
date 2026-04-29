# Summary: Journey Flow Implementation Complete ✅

## What Was Fixed

Your original request described a specific journey where the test was missing certain steps. The journey should follow this path:

1. **Sign in** at BAS gateway with continue_url pointing to `/agent-registration/provide-details/match-application/[UUID]`
2. **Create Individual user** with affinityGroup="Individual" and principalEnrolmentService="HMRC-PT"
3. **Edit user** to set the name to "Test User"
4. **Redirect** back to the match-application page

### Issues Identified and Fixed

1. **Duplicate checks removed**: The `postStubsUserEditPageAfterListDetails` request had duplicate status checks which have been cleaned up
2. **Missing final step added**: Added `getMatchApplicationPage` request to complete the journey
3. **Improved robustness**: Updated form ID regex to handle both `userForm` and `initialUserDataForm`

## Implementation Details

### Modified Files

#### 1. `AgentRegistrationRequests.scala`
- **Lines 1231-1247**: Enhanced `getStubsUserEditPageAfterListDetails` to:
  - Handle both create and edit form IDs
  - Save `groupIdAfterListDetails` for potential later use
  - Make regex patterns more flexible

- **Lines 1249-1269**: Cleaned up `postStubsUserEditPageAfterListDetails` to:
  - Remove duplicate status checks
  - Properly save the redirect URL for the next step
  - Support both creation and edit flows

- **Lines 1271-1279**: Added new `getMatchApplicationPage` to:
  - Follow through to the final page
  - Complete the prove-identity journey

#### 2. `AgentRegistrationSimulation.scala`
- **Lines 147-154**: Updated "prove-identity" setup to include the new `getMatchApplicationPage`

## The "Prove Identity" Journey - Step by Step

```
Step 1: getSignInPageAfterListDetails
  ├─ GET /bas-gateway/sign-in?continue_url=...match-application...
  ├─ Status: 200 or 303
  └─ Output: BAS sign-in page HTML

Step 2: getGgSignInPageAfterListDetails
  ├─ GET /gg/sign-in or follow redirect
  ├─ Status: 200
  └─ Output: GG sign-in form with login form action

Step 3: postSignInWithIndividualUser
  ├─ POST [GG Sign-In Action]
  ├─ Body: userId=[random], planetId=[saved], csrfToken=[saved]
  ├─ Status: 303
  └─ Redirect: /agents-external-stubs/user/create OR /user/edit

Step 4: getStubsUserEditPageAfterListDetails
  ├─ GET [redirect from step 3]
  ├─ Status: 200
  └─ Output: User creation or edit form

Step 5: postStubsUserEditPageAfterListDetails
  ├─ POST [form action from step 4]
  ├─ Body:
  │  ├─ If create: affinityGroup=Individual, principalEnrolmentService=HMRC-PT
  │  └─ If edit: name=Test User
  ├─ Status: 303
  └─ Redirect: /provide-details/match-application/[UUID] or back to /user/edit

Step 6: getMatchApplicationPage
  ├─ GET [redirect from step 5]
  ├─ Status: 200 or 303
  └─ Output: Match application page (JOURNEY COMPLETE)
```

## Key Features

### 1. Automatic Flow Detection
The test automatically detects whether the user needs to be **created** or **edited**:
```scala
val isEditFlow = session("userEditPageUrl").as[String].contains("/user/edit")
```

### 2. Multi-Step Edit Support
Some environments require both:
- Creating the user (step 5 with create params)
- Redirecting to edit page
- Editing the user again (with name)

This is fully supported by the conditional logic.

### 3. URL Normalization
Handles various URL formats:
- Relative paths: `/agent-registration/...` 
- Absolute URLs: `http://localhost:22201/...`
- Encoded paths: `http%3A%2F%2Flocalhost%3A22201%2F...`

### 4. Session Variable Chaining
Each step properly saves and passes variables to subsequent steps, maintaining context throughout the journey.

## Compilation Status

✅ **Compilation: SUCCESSFUL**
```
[info] done compiling
[success] Total time: 5s
```

All warnings are Scala deprecation warnings (not errors) about syntax style, not functional issues.

## How to Test

### Run the Full Simulation
```bash
cd /Users/markbennett/workspace/agent-registration-performance-tests
sbt "gatling:testOnly uk.gov.hmrc.perftests.example.AgentRegistrationSimulation"
```

### Run Just the Prove Identity Journey
```bash
sbt "gatling:testOnly uk.gov.hmrc.perftests.example.AgentRegistrationSimulation" -D gatling.simulation.filter="prove-identity"
```

### Run with Custom Settings
```bash
sbt -D gatling.baseUrl=http://localhost:9099 \
    -D gatling.users=1 \
    -D gatling.rampupTime=1 \
    "gatling:testOnly uk.gov.hmrc.perftests.example.AgentRegistrationSimulation"
```

## Output Files

When tests run, debug HTML files are saved to:
- `target/debug/task-list-initial.html`
- `target/debug/task-list-after-applicant-cya-final.html`
- `target/debug/task-list-after-agent-cya-final.html`
- `target/debug/task-list-after-agent-standard-final.html`
- `target/debug/agent-details-entry-response.html`
- etc.

You can add more debug output to prove-identity steps if needed.

## Documentation Files

Created for reference:
- `JOURNEY_VERIFICATION.md` - Overview of the journey implementation
- `PROVE_IDENTITY_DEBUG.md` - Troubleshooting guide
- `PROVE_IDENTITY_DETAILED.md` - Detailed flow diagrams and specifications

## Next Steps

If you're still encountering issues:

1. **Check the URL patterns** - Verify that the redirects from `/user/create` and `/user/edit` match the regex patterns
2. **Enable debug logging** - Check Gatling logs for actual HTTP responses
3. **Verify BAS/GG setup** - Ensure agents-external-stubs is running correctly
4. **Test individual steps** - Use curl to test each endpoint independently
5. **Review debug HTML** - Check saved HTML files in `target/debug/` to see actual responses

See `PROVE_IDENTITY_DEBUG.md` for detailed debugging instructions.

---

**Status: IMPLEMENTATION COMPLETE ✅**

The full prove-identity journey with all three page transitions you specified is now implemented and verified to compile successfully.

