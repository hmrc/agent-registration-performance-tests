# Debug Guide for Prove Identity Journey

## If Still Experiencing Issues

### 1. Check the Continue URL
The `continue_url` parameter in the BAS sign-in page should contain:
```
http://localhost:22201/agent-registration/provide-details/match-application/[APPLICATION-ID]
```

If you're getting redirected to a different path, check:
- The `listDetailsSignInRedirectUrl` is being read correctly
- The URL normalization is working: `normalizeSignInLocation()`

### 2. User Creation vs Edit Flow
The test automatically handles both scenarios:

**Scenario A: User Doesn't Exist (Create)**
```
POST /agents-external-stubs/user/create?continue=[encoded-url]
Body: affinityGroup=Individual&principalEnrolmentService=HMRC-PT&csrfToken=[token]
Response: 303 → /agents-external-stubs/user/edit?continue=[encoded-url]
```

**Scenario B: User Exists (Edit)**
```
POST /agents-external-stubs/user/edit?continue=[encoded-url]
Body: name=Test User&csrfToken=[token]&groupId=[groupId]&...
Response: 303 → /agent-registration/provide-details/match-application/[id]
```

### 3. Session Variable Flow

| Variable | Set By | Used By | Value |
|----------|--------|---------|-------|
| `listDetailsBasSignInUrl` | `followListDetailsSignOutRedirectToSignIn` | `getSignInPageAfterListDetails` | BAS sign-in URL |
| `listDetailsGgSignInAction` | `getGgSignInPageAfterListDetails` | `postSignInWithIndividualUser` | Form action |
| `individualUserId` | `getGgSignInPageAfterListDetails` | `postSignInWithIndividualUser` | Random user ID |
| `userEditPageUrl` | `postSignInWithIndividualUser` | `getStubsUserEditPageAfterListDetails` | User create/edit URL |
| `stubsUserUpdateActionAfterListDetails` | `getStubsUserEditPageAfterListDetails` | `postStubsUserEditPageAfterListDetails` | Form action |
| `userEditFinalRedirectUrl` | `postStubsUserEditPageAfterListDetails` | `getMatchApplicationPage` | Redirect to match-application |

### 4. Debug Output Files
The test saves debug HTML files to `target/debug/`:
- `task-list-after-agent-standard-final.html` - Final task list before List Details entry
- No explicit debug files for prove-identity section yet

To add more debug output, you can modify the requests to save responses:
```scala
.check(
  bodyString.transform { body =>
    try {
      java.nio.file.Files.createDirectories(java.nio.file.Paths.get("target/debug"))
      java.nio.file.Files.write(
        java.nio.file.Paths.get("target/debug/user-edit-page.html"), 
        body.getBytes("UTF-8")
      )
    } catch { case _: Exception => }
    body
  }.exists
)
```

### 5. Common Error Patterns

#### Error: "Unable to derive GG sign-in URL from BAS sign-in redirect"
- **Cause:** The BAS sign-in page didn't return a redirect or form
- **Solution:** Check `getSignInPageAfterListDetails` is returning 200 with expected content

#### Error: "User edit page URL missing"
- **Cause:** `postSignInWithIndividualUser` didn't capture the user create/edit URL
- **Solution:** Verify the regex pattern matches the response:
  ```scala
  ".*/agents-external-stubs/user/(create|edit)\\?.*continue=.*"
  ```

#### Error: "Redirect URL not matching pattern"
- **Cause:** Final redirect in `postStubsUserEditPageAfterListDetails` doesn't match expected pattern
- **Solution:** The pattern expects either:
  - `/agent-registration/provide-details/match-application/[id]`, OR
  - `/agents-external-stubs/user/edit?continue=[url]` (for multi-step edit)

### 6. Testing Individual Requests

To verify each step independently:

```bash
# Step 1: Get BAS sign-in page
curl -v 'http://localhost:9099/bas-gateway/sign-in?continue_url=http://localhost:22201/agent-registration/provide-details/match-application/b6ee3fb5-1478-4f32-85da-ec0667e24d38&origin=agent-registration-frontend&affinityGroup=individual'

# Step 2: Create user
curl -v -X POST 'http://localhost:9099/agents-external-stubs/user/create?continue=...' \
  -d 'affinityGroup=Individual&principalEnrolmentService=HMRC-PT&csrfToken=...'

# Step 3: Edit user
curl -v -X POST 'http://localhost:9099/agents-external-stubs/user/edit?continue=...' \
  -d 'name=Test User&csrfToken=...'
```

### 7. Enable Gatling Debug Logging
Add to `gatling.conf` or create it:
```
gatling {
  core {
    defaultsAreOverridable = true
    shutdownTimeout = 30000
  }
  http {
    allowPoolingConnection = true
  }
}
```

Enable debug logs:
```bash
export JAVA_OPTS="-Dlogback.configurationFile=logback.xml"
sbt "gatling:testOnly uk.gov.hmrc.perftests.example.AgentRegistrationSimulation"
```

