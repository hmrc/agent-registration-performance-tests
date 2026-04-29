# Prove Identity Journey - Complete Flow Diagram

## High-Level Journey

```
List Details (Sign Out)
         ↓
[303 Redirect to BAS Sign-In]
         ↓
Get BAS Sign-In Page
         ↓
Get GG Sign-In Form
         ↓
POST Individual User Sign-In
         ↓
[303 Redirect to User Create OR User Edit]
         ↓
Get User Create/Edit Form
         ↓
POST Create Individual User OR Edit User Name
         ↓
[303 Redirect to /provide-details/match-application/[UUID]]
         ↓
Get Match Application Page
         ✅ JOURNEY COMPLETE
```

## Detailed Request Flow

### REQUEST 1: Get Sign In Page After List Details
```
HTTP GET /bas-gateway/sign-in?continue_url=...match-application...&origin=agent-registration-frontend

Expected Response:
- Status: 200 or 303
- Extracts:
  * listDetailsSignInNextUrl (from Location header if 303)
  * listDetailsGgSignInAction (form action from loginForm)
```

### REQUEST 2: Get GG Sign In Page After List Details
```
HTTP GET /gg/sign-in?continue=...

Expected Response:
- Status: 200
- Extracts:
  * csrfToken
  * listDetailsGgSignInAction (from loginForm)
  * individualUserId (randomly generated: perf-XXXXXXXX)
```

### REQUEST 3: Post Sign In With Individual User
```
HTTP POST [GG Sign-In Action URL]

Form Parameters:
- userId: #{individualUserId}       (randomly generated)
- planetId: #{planetId}              (from earlier sign-in)
- csrfToken: #{csrfToken}

Expected Response:
- Status: 303
- Location Header: .*/agents-external-stubs/user/(create|edit)\?continue=...
- Extracts:
  * userEditPageUrl (will be /user/create or /user/edit)
```

### REQUEST 4: Get Stubs User Edit Page After List Details
```
HTTP GET #{userEditPageUrl}?continue=...

Examples:
- http://localhost:9099/agents-external-stubs/user/create?continue=...
- http://localhost:9099/agents-external-stubs/user/edit?continue=...

Expected Response:
- Status: 200
- Extracts:
  * csrfToken
  * groupIdAfterListDetails (optional)
  * currentUserName (optional)
  * stubsUserUpdateActionAfterListDetails (form action)
```

### REQUEST 5: Post Stubs User Edit Page After List Details
```
HTTP POST #{stubsUserUpdateActionAfterListDetails}?continue=...

Form Parameters (determined by flow type):

IF CREATE FLOW (URL contains /user/create):
- affinityGroup: Individual
- principalEnrolmentService: HMRC-PT
- csrfToken: #{csrfToken}

IF EDIT FLOW (URL contains /user/edit):
- name: Test User
- csrfToken: #{csrfToken}
- groupId: #{groupIdAfterListDetails}
- (other address fields from session if present)

Expected Response:
- Status: 303
- Location Header: 
  * Either: .../agent-registration/provide-details/match-application/[UUID]
  * Or: .../agents-external-stubs/user/edit?continue=... (for second edit)
- Extracts:
  * userEditFinalRedirectUrl
```

### REQUEST 6: Get Match Application Page
```
HTTP GET #{userEditFinalRedirectUrl}

URL Pattern:
- http://localhost:22201/agent-registration/provide-details/match-application/[UUID]

Expected Response:
- Status: 200 or 303
- This is the final destination for the prove-identity journey
```

## Session Variable Lifecycle

```
External Input
  ↓
[From List Details Journey]
  ├─ listDetailsBasSignInUrl
  ├─ signInPageUrl
  └─ listDetailsSignInRedirectUrl
         ↓
[Request 1: getSignInPageAfterListDetails]
  ├─ Produces: listDetailsSignInNextUrl
  └─ Produces: listDetailsGgSignInAction
         ↓
[Request 2: getGgSignInPageAfterListDetails]
  ├─ Produces: csrfToken
  ├─ Produces: listDetailsGgSignInAction (overwrite)
  └─ Produces: individualUserId
         ↓
[Request 3: postSignInWithIndividualUser]
  ├─ Uses: userId, planetId, csrfToken
  └─ Produces: userEditPageUrl
         ↓
[Request 4: getStubsUserEditPageAfterListDetails]
  ├─ Uses: userEditPageUrl
  ├─ Produces: csrfToken (overwrite)
  ├─ Produces: groupIdAfterListDetails
  └─ Produces: stubsUserUpdateActionAfterListDetails
         ↓
[Request 5: postStubsUserEditPageAfterListDetails]
  ├─ Uses: userEditPageUrl (to determine CREATE vs EDIT)
  ├─ Produces: userEditFinalRedirectUrl
  └─ Produces: (possibly triggers Request 4 again if edit)
         ↓
[Request 6: getMatchApplicationPage]
  └─ Uses: userEditFinalRedirectUrl
```

## Key Decision Logic

The flow branches based on whether the user needs to be **created** or **edited**:

```scala
val isEditFlow = session("userEditPageUrl").as[String].contains("/user/edit")

if (isEditFlow) {
  // We're doing an edit (user already exists or created in previous step)
  POST_PARAMS: name=Test User
  EXPECTED_REDIRECT: /provide-details/match-application/[UUID]
} else {
  // We're doing a create (user creation form)
  POST_PARAMS: affinityGroup=Individual, principalEnrolmentService=HMRC-PT
  EXPECTED_REDIRECT: Could be to /user/edit or /provide-details/match-application
}
```

## Typical Multi-Step Scenario

Some agents-external-stubs implementations require both CREATE and EDIT:

```
POST /user/create (Request 5)
  ↓
303 → /user/edit?continue=...
  ↓
GET /user/edit (Request 4 implicitly runs again)
  ↓
POST /user/edit with name=Test User (Request 5 implicitly runs again)
  ↓
303 → /provide-details/match-application/[UUID]
  ↓
GET /provide-details/match-application (Request 6)
  ↓
✅ Complete
```

This is handled by:
1. The `userEditPageUrl` variable being overwritten if needed
2. The form-param logic detecting `/user/edit` vs `/user/create`
3. The redirect being captured and used for the next step

