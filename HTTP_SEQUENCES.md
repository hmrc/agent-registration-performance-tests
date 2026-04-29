# HTTP Request Sequences - Prove Identity Journey

## Complete Journey Map

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ PROVE IDENTITY JOURNEY - Complete HTTP Sequence                             │
└─────────────────────────────────────────────────────────────────────────────┘

REQUEST 1: Get Sign In Page After List Details
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GET /bas-gateway/sign-in
    ?continue_url=http://localhost:22201/agent-registration/...match-application/[UUID]
    &origin=agent-registration-frontend

Expected Response:
  Status: 200 or 303
  Headers (if 303): Location: /gg/sign-in?continue=...
  Body: Login form (if 200)

Session Variables Saved:
  ✓ listDetailsSignInNextUrl (from Location header)
  ✓ listDetailsGgSignInAction (from form action)


REQUEST 2: Get GG Sign In Page After List Details
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GET /gg/sign-in?continue=http://localhost:22201/agent-registration/...match-application/[UUID]

Expected Response:
  Status: 200
  Headers: Set-Cookie: (authentication cookies)
  Body: Login form with:
    - <input name="csrfToken" value="...">
    - <form action="..." id="loginForm">

Session Variables Saved:
  ✓ csrfToken (from form)
  ✓ listDetailsGgSignInAction (from form action)
  ✓ individualUserId (randomly generated: perf-XXXXXXXX)


REQUEST 3: Post Sign In With Individual User
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
POST /gg/sign-in (or action from form)
Content-Type: application/x-www-form-urlencoded

REQUEST BODY:
  userId=perf-XXXXXXXX
  &planetId=perf-YYYYYYYY
  &csrfToken=[CSRF_TOKEN]

Expected Response:
  Status: 303
  Headers: Location: /agents-external-stubs/user/create?continue=http%3A%2F%2F...
           Location: /agents-external-stubs/user/edit?continue=http%3A%2F%2F...

Session Variables Saved:
  ✓ userEditPageUrl (either /user/create or /user/edit)


REQUEST 4: Get Stubs User Edit Page After List Details
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GET /agents-external-stubs/user/create?continue=http%3A%2F%2F...
OR
GET /agents-external-stubs/user/edit?continue=http%3A%2F%2F...

Expected Response (Create Flow):
  Status: 200
  Body: User creation form with:
    - <input name="csrfToken" value="...">
    - <input name="affinityGroup">
    - <input name="principalEnrolmentService">
    - <form action="..." id="initialUserDataForm">

Expected Response (Edit Flow):
  Status: 200
  Body: User edit form with:
    - <input name="csrfToken" value="...">
    - <input name="name" value="">
    - <input name="groupId" value="...">
    - <form action="..." id="userForm">

Session Variables Saved:
  ✓ csrfToken (from form)
  ✓ groupIdAfterListDetails (optional, from groupId field)
  ✓ currentUserName (optional, from name field)
  ✓ stubsUserUpdateActionAfterListDetails (from form action)


REQUEST 5: Post Stubs User Edit Page After List Details
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
POST /agents-external-stubs/user/create (FROM REQUEST 4)
OR
POST /agents-external-stubs/user/edit (FROM REQUEST 4)

Content-Type: application/x-www-form-urlencoded

IF CREATE FLOW (URL contains /user/create):
  REQUEST BODY:
    affinityGroup=Individual
    &principalEnrolmentService=HMRC-PT
    &csrfToken=[CSRF_TOKEN]

IF EDIT FLOW (URL contains /user/edit):
  REQUEST BODY:
    name=Test User
    &csrfToken=[CSRF_TOKEN]
    &groupId=[GROUP_ID]
    &credentialRole=User
    &credentialStrength=none
    &address.line1=[ADDRESS_LINE1]
    &address.line2=[ADDRESS_LINE2]
    &address.postcode=[ADDRESS_POSTCODE]
    &address.countryCode=[ADDRESS_COUNTRY]

Expected Response:
  Status: 303
  Headers: Location: /agent-registration/provide-details/match-application/[UUID]
           Location: /agents-external-stubs/user/edit?continue=... (multi-step)

Session Variables Saved:
  ✓ userEditFinalRedirectUrl (from Location header)


REQUEST 6: Get Match Application Page
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
GET /agent-registration/provide-details/match-application/[UUID]

Expected Response:
  Status: 200 or 303
  Body: Match application page
         OR Redirect to further page

✅ JOURNEY COMPLETE
```

## Multi-Step Edit Scenario

If the server requires both CREATE and EDIT steps:

```
REQUEST 5a: POST /user/create
  Body: affinityGroup=Individual&principalEnrolmentService=HMRC-PT...
  Response: 303 → /agents-external-stubs/user/edit?continue=...
            ↓
REQUEST 4b: GET /agents-external-stubs/user/edit (IMPLICIT - handled by session)
            ↓
REQUEST 5b: POST /agents-external-stubs/user/edit
  Body: name=Test User&csrfToken=...
  Response: 303 → /agent-registration/provide-details/match-application/[UUID]
            ↓
REQUEST 6: GET /agent-registration/provide-details/match-application/[UUID]
  Response: 200
  ✅ COMPLETE
```

## Critical Session Variables

### From Previous Journey (List Details)
```
listDetailsBasSignInUrl = "http://localhost:9099/bas-gateway/sign-in?..."
planetId = "perf-YYYYYYYY"  (from earlier authentication)
```

### Generated During Prove Identity
```
individualUserId = "perf-XXXXXXXX"  (randomly generated)
csrfToken = "..." (varies by request)
listDetailsGgSignInAction = "/gg/sign-in (or absolute URL)"
userEditPageUrl = "/agents-external-stubs/user/(create|edit)?continue=..."
stubsUserUpdateActionAfterListDetails = "/agents-external-stubs/user/(create|edit)"
userEditFinalRedirectUrl = "/agent-registration/provide-details/match-application/[UUID]"
```

## Error Scenarios & Recovery

### Scenario 1: User Already Exists
```
POST /user/create
  ↓
Response: 303 → /agents-external-stubs/user/edit (because user already exists)
  ↓
Test automatically detects: isEditFlow = true
  ↓
Next request: POST /user/edit with name=Test User
  ↓
✅ Recovers automatically
```

### Scenario 2: Invalid CSRF Token
```
POST /user/create|edit
  ↓
Response: 400 Bad Request (or 401/403)
  ↓
❌ TEST FAILS - Need to re-fetch form to get new CSRF token
  ↓
Check: Did Request 4 actually extract csrfToken?
```

### Scenario 3: URL Encoding Issues
```
continue=http%3A%2F%2Flocalhost%3A22201%2Fagent-registration%2F...
  ↓
Test uses normalizeSignInLocation() to handle:
  - URL decoding
  - Relative vs absolute URLs
  - Domain/port manipulation
  ↓
✅ Handles automatically
```

## Regex Patterns Used

### Form Action Pattern
```regex
<form[^>]*action=\"([^\"]+)\"[^>]*id=\"(?:userForm|initialUserDataForm)\"
```
Captures the form action from either:
- `id="initialUserDataForm"` (for user creation)
- `id="userForm"` (for user edit)

### User Create/Edit URL Pattern
```regex
.*/agents-external-stubs/user/(create|edit)\\?.*continue=.*
```
Captures the URL from POST redirect to determine flow type:
- `/agents-external-stubs/user/create?continue=...` = CREATE
- `/agents-external-stubs/user/edit?continue=...` = EDIT

### Login Form Pattern
```regex
<form[^>]*action=\"([^\"]+)\"[^>]*id=\"loginForm\"
```
Captures where to POST credentials

### Match Application Pattern
```regex
.*/agent-registration/provide-details/match-application/.*
```
Confirms successful completion

## Debugging URLs

To manually test each step:

```bash
# Step 1: Visit BAS sign-in
curl -i 'http://localhost:9099/bas-gateway/sign-in?continue_url=...&origin=...'

# Step 2: Extract form and visit GG sign-in
curl -i 'http://localhost:9099/[gg-url-from-step1]'

# Step 3: Get CSRF and post credentials
curl -i -X POST 'http://localhost:9099/gg/sign-in' \
  -d 'userId=test&planetId=test&csrfToken=...'

# Step 4: Visit user create/edit
curl -i 'http://localhost:9099/agents-external-stubs/user/create?continue=...'

# Step 5: Create or edit user
curl -i -X POST 'http://localhost:9099/agents-external-stubs/user/create' \
  -d 'affinityGroup=Individual&principalEnrolmentService=HMRC-PT&csrfToken=...'

# Step 6: Visit match-application
curl -i 'http://localhost:22201/agent-registration/provide-details/match-application/...'
```

