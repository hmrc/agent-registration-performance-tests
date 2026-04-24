# Agent Registration Frontend - Gatling Performance Test Guide

## Overview
This document outlines the endpoints and user journeys that need to be covered in a Gatling performance test suite for the agent-registration-frontend service. The service provides a frontend for agent registration with multiple business type flows (Sole Trader, Limited Company, General Partnership, LLP, etc.).

## Key Architecture Components

### External Services Called
The frontend integrates with the following microservices:
- **agent-registration** (port 22202): Stores application data
- **agent-registration-risking** (port 22203): Handles application risk assessment
- **auth** (port 8500): Authentication and authorization
- **enrolment-store-proxy** (port 9595/7775): Queries existing enrolments
- **citizen-details** (port 9337): Individual identity lookup
- **companies-house-api-proxy** (port 9991): Companies House data
- **GRS Services** (Government Registration Service):
  - sole-trader-identification-frontend (port 9717)
  - incorporated-entity-identification-frontend (port 9718)
  - partnership-identification-frontend (port 9722)
- **email-verification** (port 9891): Email verification service
- **address-lookup-frontend** (port 9028): Address selection
- **upscan-initiate** (port 9570): File upload initiation
- **agent-assurance** (port 9565): Agent assurance checks
- **object-store** (port 8464): File storage for AMLS evidence

---

## User Journey Flow - High Level

```
1. Landing Page
   ↓
2. Authenticate (via Government Gateway)
   ↓
3. Select Agent Type (UkTaxAgent / Non-UkTaxAgent)
   ↓
4. Select Business Type (SoleTrader / LimitedCompany / Partnership variants)
   ↓
5. Select User Role (Owner / Director / Partner / Member)
   ↓
6. Select Sign-In Method (HmrcOnlineServices / CreateSignInDetails)
   ↓
7. Initiate Agent Application
   ↓
8. GRS Journey (Business verification via external GRS service)
   ↓
9. Task List
   ↓
10. Complete Various Tasks (Contact Details, Business Details, Directors/Partners/Members List, AMLS, etc.)
   ↓
11. Declaration & Submit
   ↓
12. Application Status & Decision
```

---

## Detailed Endpoint Mapping by Journey Stage

### STAGE 1: Landing & Authentication
**Purpose**: Initial page load and authentication flow

#### Endpoints to Test:
```
GET /agent-registration/
  - Renders landing page
  - Redirects to task-list if application in progress
  - Called by: Public users accessing the service

GET /agent-registration/apply
  - Starts registration flow
  - Calls: EnrolmentStoreProxyConnector.queryEnrolmentsAllocatedToGroup(groupId)
  - Response: Redirect to agent-type selection or error if HMRC ASA already allocated

POST /auth/sign-in (external - via Government Gateway)
  - User authenticates with credentials
  - Provides: Username, password
  - Returns: Auth cookie/token
  - Note: In Gatling test, use test credentials or mock
```

**Gatling Scenario**: 
- User lands on homepage
- User sees sign-in option
- System verifies no HMRC ASA enrolment exists for user's group

---

### STAGE 2: Business Information Collection
**Purpose**: Gather high-level business classification

#### Endpoints to Test:

```
GET /agent-registration/apply/about-your-business/agent-type
  - Display: Agent type selection (UkTaxAgent / NonUkTaxAgent)
  - Session Storage: Agent type stored in session

POST /agent-registration/apply/about-your-business/agent-type
  - Submits: AgentType selection
  - Body: { agentType: "UkTaxAgent" }
  - Response: Redirect to business-type selection
  - Next: /apply/about-your-business/business-type
```

```
GET /agent-registration/apply/about-your-business/business-type
  - Display: Business type selection
  - Options: 
    - SoleTrader
    - LimitedCompany
    - PartnershipType
    - Other (exits journey)
  - Session Storage: Business type stored

POST /agent-registration/apply/about-your-business/business-type
  - Submits: BusinessType selection
  - Body: { businessType: "SoleTrader" | "LimitedCompany" | "PartnershipType" | "Other" }
  - Conditionals:
    - If PartnershipType → Route to partnership-type selection
    - Else → Route to user-role selection
```

```
GET /agent-registration/apply/about-your-business/partnership-type
  - Display: Partnership type selection (only if PartnershipType selected)
  - Options:
    - GeneralPartnership
    - LimitedPartnership
    - LimitedLiabilityPartnership
    - ScottishPartnership
    - ScottishLimitedPartnership

POST /agent-registration/apply/about-your-business/partnership-type
  - Submits: Specific partnership type
  - Body: { partnershipType: "GeneralPartnership" | "LimitedPartnership" | ... }
  - Response: Redirect to user-role selection
```

```
GET /agent-registration/apply/about-your-business/user-role
  - Display: User role within business
  - Role options depend on business type:
    - SoleTrader → Owner
    - LimitedCompany → Director
    - LLP → Member
    - Partnership variants → Partner

POST /agent-registration/apply/about-your-business/user-role
  - Submits: UserRole
  - Body: { userRole: "Owner" | "Director" | "Partner" | "Member" }
  - Response: Redirect to sign-in type selection
```

```
GET /agent-registration/apply/about-your-business/agent-online-services-account
  - Display: Sign-in type selection
  - Options:
    - HmrcOnlineServices (use existing HMRC account)
    - CreateSignInDetails (create new sign-in)

POST /agent-registration/apply/about-your-business/agent-online-services-account
  - Submits: TypeOfSignIn
  - Body: { typeOfSignIn: "HmrcOnlineServices" | "CreateSignInDetails" }
  - Response: Redirect to sign-in page
```

```
GET /agent-registration/apply/about-your-business/sign-in
  - Display: Sign-in link/button to initiate application
  - Contains: URI to internal GRS initiation endpoint
  - User Action: Clicks link to start GRS journey
```

**Gatling Scenario**:
- User selects UkTaxAgent
- User selects business type (e.g., SoleTrader, LimitedCompany, or partnership)
- If partnership, user selects specific partnership type
- User selects appropriate role
- User selects sign-in type
- User proceeds to sign-in page

---

### STAGE 3: GRS (Government Registration Service) Journey Initiation
**Purpose**: Trigger external business verification via GRS

#### Endpoints to Test:

```
GET /agent-registration/apply/internal/initiate-agent-application/:agentType/:businessType/:userRole
  - Internal endpoint called after user authentication
  - Path Params: agentType, businessType, userRole
  - Calls:
    1. EnrolmentStoreProxyConnector.queryEnrolmentsAllocatedToGroup(groupId)
       - Check: User's group doesn't already have HMRC ASA enrolment
       - Status: 200 OK with enrolments OR 204 NO_CONTENT
       - If ASA exists → Redirect to external ASA enrollment page
    2. AgentRegistrationConnector.findApplication()
       - Check: If application already exists for this user
       - Calls: GET /application (backend service)
       - If exists → Redirect to GRS journey start
       - If not exists → Create new application (next step)
    3. AgentRegistrationConnector.upsertApplication(application)
       - Creates: New AgentApplication entity
       - Calls: POST /application (backend service)
       - Body: NewAgentApplication with agentType, businessType, userRole
  - Response: Redirect to GRS journey start endpoint
```

```
GET /agent-registration/apply/internal/grs/start-journey
  - Calls: GrsService.createGrsJourney(businessType, includeNamePageLabel)
  - Which calls: GrsConnector.createJourney(journeyConfig, businessType)
    - HTTP: POST to GRS microservice
    - URL: Depends on businessType:
      * SoleTrader → sole-trader-identification-frontend create-journey
      * LimitedCompany → incorporated-entity-identification-frontend create-journey
      * Partnership variants → partnership-identification-frontend create-journey
    - Body: JourneyConfig with:
      * continueUrl: /agent-registration/apply/internal/grs/journey-callback
      * signOutUrl: /agent-registration/sign-out
      * businessVerificationCheck: false
      * labels: Service name in English & Welsh
    - Response: 201 CREATED with journeyStartUrl
  - Response: Redirect to journeyStartUrl (external GRS service)
```

**GRS Journey** (External Service - User lands on GRS-hosted pages):
- User provides/verifies business information
- For Sole Trader:
  - Enter: NINO, Date of Birth, Business Name
- For Limited Company:
  - Enter: Company Registration Number (CRN)
  - Verify: Company name and address
- For Partnerships:
  - Enter: UTR, Postcode
  - Verify: Partnership details
- User completes GRS verification
- GRS redirects back to frontend with journeyId parameter

**GRS Return Callback**:
```
GET /agent-registration/apply/internal/grs/journey-callback?journeyId=<journeyId>
  - Calls: GrsService.getJourneyData(businessType, journeyId)
  - Which calls: GrsConnector.getJourneyData(businessType, journeyId)
    - HTTP: GET to GRS microservice
    - URL: /retrieve-journey-data/{businessType}/{journeyId}
    - Headers: Includes session cookie for stub data retrieval
    - Response: 200 OK with JourneyData containing:
      * Registration status (Success, Failed, etc.)
      * Business details extracted from GRS
  - Processes: JourneyData registration status
    - If Success → Extract business details into AgentApplication
    - Calls: AgentRegistrationConnector.upsertApplication(updatedApp)
    - Then → Continue to entity checks
    - If Failed/NotStarted → Redirect to appropriate error page
```

```
GET /agent-registration/apply/internal/refusal-to-deal-with-check
  - Backend calls: agent-registration service
  - Check: Whether entity on refusal to deal with list
  - Response: Success → proceed to deceased check
  - Response: Failed → Redirect to cannot-register page

GET /agent-registration/apply/cannot-confirm-identity
  - Error page: User failed identity verification
  - Message: Explanation of why registration failed
```

```
GET /agent-registration/apply/internal/deceased-check
  - Backend call: Check if entity/individual on deceased records
  - Response: Not deceased → proceed to companies house status check
  - Response: Deceased → Show error page
```

```
GET /agent-registration/apply/internal/companies-house-status-check
  - Backend call: Verify Companies House entity status (if applicable)
  - For incorporated entities only
  - Response: Active → Proceed to task list
  - Response: Inactive → Show cannot-register page
```

**Gatling Scenario**:
1. Call initiate-agent-application endpoint with parameters
2. Receive redirect to GRS start-journey
3. Simulate user completing GRS journey (mock response or use GRS stub)
4. Call journey-callback with journeyId
5. Backend verifies entity status through check endpoints
6. User proceeds to task list

---

### STAGE 4: Task List & Application Dashboard
**Purpose**: Show application status and available tasks

#### Endpoints to Test:

```
GET /agent-registration/apply/task-list
  - Checks:
    1. AgentRegistrationConnector.getApplicationInProgress()
    2. Ensure: agentApplication.isGrsDataReceived == true
    3. Ensure: agentApplication.hasCheckPassed == true
    4. IndividualProvideDetailsService.findAllByApplicationId(appId)
       - Retrieve: All individuals (directors/partners/members) added so far
    5. AgentRegistrationConnector.getApplicationBusinessPartnerRecord(utr)
       - Retrieve: Business Partner Record from BPR lookup
       - Status: 200 OK or 204 NO_CONTENT
  - Displays: Task list showing status of:
    * About your business
    * Business contact details
    * Business address
    * Directors/Partners/Members list (if applicable)
    * AMLS details
    * Agent details
    * Declaration
  - Data: Shows entity name from BPR
  - Response: Renders TaskListPage with task statuses
```

**Gatling Scenario**:
- User reaches task list after successful GRS journey
- System displays all tasks available for their business type

---

### STAGE 5: Applicant Contact Details
**Purpose**: Collect primary applicant's contact information

#### Endpoints to Test:

```
GET /agent-registration/apply/applicant/applicant-name
  - Display: Form to enter applicant name
  - Pre-fill: From GRS data if available

POST /agent-registration/apply/applicant/applicant-name
  - Submits: Name (firstName, lastName)
  - Validation: Required, max length 100
  - Response: Redirect to telephone-number

GET /agent-registration/apply/applicant/telephone-number
  - Display: Form to enter phone number
  - Validation: Required, valid format

POST /agent-registration/apply/applicant/telephone-number
  - Submits: Phone number
  - Response: Redirect to email-address

GET /agent-registration/apply/applicant/email-address
  - Display: Form to enter email address
  - Validation: Required, valid email format

POST /agent-registration/apply/applicant/email-address
  - Submits: Email address
  - Calls: EmailVerificationConnector.sendVerificationEmail(email)
  - Backend calls: email-verification service
  - Email sent to user with verification link
  - Response: Redirect to email verification page

GET /agent-registration/apply/applicant/verify-email-address
  - Display: Email verification challenge
  - User receives email with passcode
  - User enters passcode or clicks link
  - Calls: EmailVerificationConnector.verifyEmail(email, passcode)
  - Response: Confirmed email OR error

GET /agent-registration/apply/applicant/check-your-answers
  - Display: Summary of entered applicant details
  - User confirms or changes details

POST /agent-registration/apply/applicant/check-your-answers
  - Submits: Confirmation
  - Calls: AgentRegistrationConnector.upsertApplication(updatedApp)
  - Response: Redirect to next section (agent-details or task-list)
```

**Gatling Scenario**:
- Enter applicant name
- Enter telephone number
- Enter email address
- Verify email (mock verification or skip if ignoreEmailVerification=true)
- Review and confirm applicant details

---

### STAGE 6: Agent Business Details
**Purpose**: Collect business contact and operational details

#### Endpoints to Test:

```
GET /agent-registration/apply/agent-details/business-name
  - Display: Business name form
  - Validation: Required

POST /agent-registration/apply/agent-details/business-name
  - Submits: Business name
  - Response: Redirect to telephone-number

GET /agent-registration/apply/agent-details/telephone-number
  - Display: Business phone number form

POST /agent-registration/apply/agent-details/telephone-number
  - Submits: Phone number
  - Response: Redirect to email-address

GET /agent-registration/apply/agent-details/email-address
  - Display: Business email form
  - Calls: EmailVerificationConnector (same as applicant)

POST /agent-registration/apply/agent-details/email-address
  - Submits: Business email
  - Response: Redirect to email verification

GET /agent-registration/apply/agent-details/verify-email-address
  - Email verification for business email

GET /agent-registration/apply/agent-details/correspondence-address
  - Display: Address lookup integration with address-lookup-frontend
  - Calls: AddressLookupFrontendConnector.init(...)
    - Initiates: Address lookup journey
    - Calls: POST to address-lookup-frontend with:
      * Postcode/address search parameters
      * Callback URL: /agent-registration/apply/internal/address-lookup/journey-callback
  - User searches and selects address
  - Address lookup redirects back with journeyId

GET /agent-registration/apply/internal/address-lookup/journey-callback?id=<journeyId>
  - Calls: AddressLookupFrontendConnector.getAddress(journeyId)
  - Backend returns: Selected address
  - Stores: Address in application

GET /agent-registration/apply/agent-details/check-your-answers
  - Summary of business details

POST /agent-registration/apply/agent-details/check-your-answers
  - Submits: Confirmation
  - Response: Redirect to next section
```

**Gatling Scenario**:
- Enter business name
- Enter business telephone
- Enter and verify business email
- Search and select correspondence address
- Review and confirm business details

---

### STAGE 7: AMLS (Anti-Money Laundering Supervision) Details
**Purpose**: Collect AMLS supervision information

#### Endpoints to Test:

```
GET /agent-registration/apply/anti-money-laundering/supervisor-name
  - Display: AMLS supervisor selection form
  - Options: Dropdown list of AMLS supervisors (loaded from config)

POST /agent-registration/apply/anti-money-laundering/supervisor-name
  - Submits: Supervisor name
  - Response: Redirect to registration-number

GET /agent-registration/apply/anti-money-laundering/registration-number
  - Display: AMLS registration number form

POST /agent-registration/apply/anti-money-laundering/registration-number
  - Submits: Registration number
  - Validation: Format depends on supervisor
  - Response: Redirect to expiry-date

GET /agent-registration/apply/anti-money-laundering/supervision-runs-out
  - Display: AMLS expiry date form
  - Format: DD/MM/YYYY
  - Validation: Future date, not more than N years

POST /agent-registration/apply/anti-money-laundering/supervision-runs-out
  - Submits: Expiry date
  - Response: Redirect to evidence upload

GET /agent-registration/apply/anti-money-laundering/evidence
  - Display: File upload page for AMLS evidence
  - Calls: UpscanInitiateConnector.initiate(...)
    - Initiates: File upload journey with upscan-initiate service
    - Calls: POST /upscan/initiate with:
      * redirects (success URL, error URL)
      * uploadRequirements (accepted file types, max size)
  - Upscan returns: Upload form and URL
  - User uploads file via upscan (external service)

POST /amls/process-notification-from-upscan/:uploadId (API endpoint)
  - Async webhook from upscan
  - Notification contains: Upload status (SUCCESS, FAILED, QUARANTINE)
  - Stores: File reference if successful
  - Response: 200 OK

GET /agent-registration/apply/anti-money-laundering/evidence/check-upload-status-js
  - Poll: Check status of uploaded file
  - Returns: Current upload status (Uploading, Success, Failed)
  - Client-side JS polls this endpoint

GET /agent-registration/apply/anti-money-laundering/evidence/upload-result
  - Display: Upload result page (success or error)
  - If error: Shows error code and message with retry option

GET /agent-registration/apply/anti-money-laundering/check-your-answers
  - Summary: AMLS details and uploaded file

POST /agent-registration/apply/anti-money-laundering/check-your-answers
  - Submits: Confirmation
  - Response: Redirect to next section
```

**Gatling Scenario**:
- Select AMLS supervisor
- Enter registration number
- Enter expiry date
- Initiate file upload (mock upload or skip)
- Confirm AMLS details

---

### STAGE 8: Directors/Partners/Members/Key Individuals Lists
**Purpose**: Collect list of people responsible for the business

#### Route 8a: Sole Trader (Simplified)

```
GET /agent-registration/apply/list-details/sole-trader
  - Display: "Prove your identity" page for sole trader
  - Options: Prove identity now OR Ask someone else to prove

POST (if selecting "Ask someone else"):
  - Routes to: ask-sole-trader endpoint

GET /agent-registration/apply/list-details/ask-sole-trader
  - Display: Form to enter owner's contact details
  - Collects: Name, email, phone

POST /agent-registration/apply/list-details/ask-sole-trader
  - Submits: Owner details
  - Creates: Share link for owner to complete details
  - Stores: IndividualProvidedDetails record
  - Generates: LinkId for owner
  - Sends: Email to owner with link to /provide-details/start/:linkId
  - Response: Success page
```

#### Route 8b: Limited Company / LLP (Company House Officers)

```
GET /agent-registration/apply/list-details/companies-house-officers
  - Calls: CompaniesHouseApiProxyConnector.getOfficers(companyNumber)
    - Fetches: Officers list from Companies House via proxy
    - Companies House data embedded in GRS response
  - Display: 
    - If ≤5 officers: Show all officers, ask to confirm
    - If >5 officers: Show first 5, ask how many to list

POST /agent-registration/apply/list-details/confirm-companies-house-officers
  - If ≤5 officers and confirmed: Stores officers in application
  - Calls: AgentRegistrationConnector.upsertApplication()

POST /agent-registration/apply/list-details/how-many-companies-house-officers
  - If >5 officers: Asks user to specify exact count
  - Routes to: enter-companies-house-officer OR list with additions

GET /agent-registration/apply/list-details/enter-companies-house-officer
  - Display: Form to manually add officer
  - Fields: Full name, DOB, address

POST /agent-registration/apply/list-details/enter-companies-house-officer
  - Submits: Officer details
  - Creates: IndividualProvidedDetails record
  - Stores: File or database entry

GET /agent-registration/apply/list-details/change-companies-house-officer/:id
  - Display: Edit form for officer

POST /agent-registration/apply/list-details/change-companies-house-officer/:id
  - Updates: Officer details

GET /agent-registration/apply/list-details/remove-companies-house-officer/:id
  - Display: Confirmation to remove

POST /agent-registration/apply/list-details/remove-companies-house-officer/:id
  - Removes: Officer from list

GET /agent-registration/apply/list-details/companies-house-officers/check-your-answers
  - Summary: All officers confirmed

POST /agent-registration/apply/list-details/companies-house-officers/check-your-answers
  - Submits: Confirmation
```

#### Route 8c: Non-Incorporated Partnerships (Manual Key Individuals & Other Relevant Individuals)

```
GET /agent-registration/apply/list-details/how-many-key-individuals
  - Display: Question - how many key individuals/partners?
  - User enters: Number

POST /agent-registration/apply/list-details/how-many-key-individuals
  - Stores: Count
  - Response: Redirect to enter-key-individual

GET /agent-registration/apply/list-details/enter-key-individual
  - Display: Form for individual details
  - Fields: Name, DOB, Address, share percentage

POST /agent-registration/apply/list-details/enter-key-individual
  - Submits: Individual details
  - Creates: IndividualProvidedDetails record
  - Response: 
    - If more to add: Show list with add more option
    - If complete: Ask about other relevant individuals

GET /agent-registration/apply/list-details/change-key-individual/:id
  - Edit individual details

POST /agent-registration/apply/list-details/change-key-individual/:id
  - Updates: Individual

GET /agent-registration/apply/list-details/remove-key-individual/:id
  - Remove individual

GET /agent-registration/apply/list-details/key-individuals/check-your-answers
  - Summary: All key individuals confirmed

POST /agent-registration/apply/list-details/key-individuals/check-your-answers
  - Submits: Confirmation

GET /agent-registration/apply/list-details/how-many-other-individuals
  - Question: Any other relevant individuals (unofficial directors/partners)?
  - Options: Yes / No

POST /agent-registration/apply/list-details/how-many-other-individuals
  - Stores: Choice
  - If Yes: Routes to enter-other-relevant-individual

GET /agent-registration/apply/list-details/enter-other-relevant-individual
  - Display: Form for other relevant individual
  - Fields: Name, DOB, Address, role/relationship

POST /agent-registration/apply/list-details/enter-other-relevant-individual
  - Submits: Individual details
  - Creates: Another IndividualProvidedDetails record
  - Response: Add more or proceed

GET /agent-registration/apply/list-details/other-relevant-individuals/check-your-answers
  - Summary: All other relevant individuals

POST /agent-registration/apply/list-details/other-relevant-individuals/check-your-answers
  - Submits: Confirmation
```

#### Share Link for Individuals to Provide Details

```
GET /apply/list-details/key-individuals/share-link
  - Display: Share link generation form
  - User can copy link or send via email

POST /apply/list-details/key-individuals/share-link
  - Generates: Shareable link
  - Sends: Email with link if requested
  - Response: Success with link displayed

GET /provide-details/start/:linkId
  - Individual receives this link via email
  - Starts: Individual details collection journey
  - Calls: 
    1. AgentRegistrationConnector.findApplication(linkId)
       - Retrieves: Application by LinkId
    2. Citizenship details verification

GET /provide-details/match-application/:linkId
  - Display: Matching form (name, DOB, etc.)
  - Individual confirms their details match application

POST /provide-details/match-application/:linkId
  - Submits: Confirmation
  - May call: CitizenDetailsConnector for identity lookup
  - Response: Proceed or error

GET /provide-details/telephone-number/:linkId
  - Individual enters phone number

POST /provide-details/telephone-number/:linkId
  - Stores: Phone number

GET /provide-details/email-address/:linkId
  - Individual enters email

POST /provide-details/email-address/:linkId
  - Stores: Email
  - Verification process initiated

GET /provide-details/verify-email-address/:linkId
  - Email verification

GET /provide-details/date-of-birth/:linkId
  - Individual enters/confirms DOB

POST /provide-details/date-of-birth/:linkId
  - Stores: DOB

GET /provide-details/nino/:linkId
  - Individual enters NINO

POST /provide-details/nino/:linkId
  - Stores: NINO
  - Validation: NINO format

GET /provide-details/utr/:linkId
  - Individual enters UTR (for partnerships)

POST /provide-details/utr/:linkId
  - Stores: UTR

GET /provide-details/approve-applicant/:linkId
  - Display: Confirmation that individual approves the applicant's registration

POST /provide-details/approve-applicant/:linkId
  - Submits: Approval
  - Response: Proceed

GET /provide-details/agree-standard/:linkId
  - Display: HMRC Standard for Agents agreement

POST /provide-details/agree-standard/:linkId
  - Submits: Agreement

GET /provide-details/check-your-answers/:linkId
  - Individual's summary of provided details

POST /provide-details/check-your-answers/:linkId
  - Submits: All details
  - Updates: IndividualProvidedDetails record as COMPLETE

GET /provide-details/confirmation/:linkId
  - Success page confirming details recorded
```

**Gatling Scenarios**:

For Sole Trader:
- Display sole trader page
- Either prove identity now OR ask owner to prove
- If asking: Create share link and send to owner

For Limited Company:
- Fetch and display Companies House officers
- If ≤5: Confirm officers
- If >5: Specify count and add/remove as needed
- Confirm officer list

For Partnership:
- Enter number of key individuals/partners
- For each: Enter name, DOB, address, share %
- Decide on other relevant individuals
- If yes: Enter additional individuals
- Confirm all lists

---

### STAGE 9: HMRC Standard for Agents
**Purpose**: Confirm acceptance of HMRC standards

#### Endpoints to Test:

```
GET /agent-registration/apply/agent-standard/accept-agent-standard
  - Display: HMRC Standard for Agents agreement page
  - Content: Terms and conditions

POST /agent-registration/apply/agent-standard/accept-agent-standard
  - Submits: Acceptance checkbox
  - Validation: Must be checked
  - Calls: AgentRegistrationConnector.upsertApplication()
  - Response: Redirect to declaration page
```

**Gatling Scenario**:
- Read and accept HMRC Standard for Agents

---

### STAGE 10: Declaration & Submission
**Purpose**: Final confirmation and application submission

#### Endpoints to Test:

```
GET /agent-registration/apply/agent-declaration/confirm-declaration
  - Display: Declaration page
  - Content: Legal declaration confirming information accuracy
  - Shows: Summary of application

POST /agent-registration/apply/agent-declaration/confirm-declaration
  - Submits: Declaration acceptance
  - Validation: Declaration checkbox required
  - Calls: 
    1. AgentRegistrationConnector.upsertApplication(with submittedAt timestamp)
    2. Calls: AgentAssuranceConnector.submitApplication(agentApplicationId)
       - Backend call to agent-assurance service
       - Triggers: Application risking/assessment
       - Backend: Likely queues application for processing
  - Response: Redirect to application-submitted page
```

**Gatling Scenario**:
- Review application summary
- Accept declaration
- Submit application

---

### STAGE 11: Application Status Pages
**Purpose**: Display application processing status

#### Endpoints to Test:

```
GET /agent-registration/apply/application-submitted
  - Calls: AgentRegistrationRiskingService.getApplicationStatus(agentApplicationId)
    - Backend call to agent-registration-risking service
    - Retrieves: Current risking/processing status
  - Display:
    - If ReadyForSubmission: Show confirmation with expected decision date
    - Else: Redirect to view-application-progress

GET /agent-registration/apply/view-application-progress
  - Calls: 
    1. AgentRegistrationConnector.findApplication(agentApplicationId) - check submitted
    2. AgentRegistrationConnector.getApplicationBusinessPartnerRecord(utr) - get entity details
  - Display: Application progress/status
  - Shows: Current stage, estimated decision date

GET /agent-registration/apply/view-application
  - Display: Full submitted application details
  - User can view previously entered information

GET /agent-registration/apply/view-application-approved
  - Display: Application approval page
  - Shows: Confirmation of registration as agent
```

**Gatling Scenario**:
- Submit application
- Check submission confirmation
- View application progress

---

## Test Data Requirements

### Pre-existing Data Needed in Backend Services:
1. **AMLS Supervisors List** - Needed for AMLS supervisor dropdown (loaded from config: `conf/amls.csv`)
2. **Companies House Mock Data** (if using stub):
   - Company numbers: 11111111 (2 officers), 11111116 (6 officers), 22222222 (2 LLP officers), etc.
3. **Government Gateway Test Accounts** - For authentication
4. **GRS Service Stub/Mock** - Returns test business verification data

### Data Created by Application:
1. **AgentApplication** - Created in agent-registration backend
2. **IndividualProvidedDetails** - Created during individual list collection
3. **Email verification records** - Created during email verification
4. **File upload records** - Created during AMLS evidence upload

---

## Gatling Test Scenario Sequences

### Scenario 1: Sole Trader Journey (Complete)
```
1. GET /agent-registration/
2. POST /agent-registration/apply/about-your-business/agent-type [UkTaxAgent]
3. POST /agent-registration/apply/about-your-business/business-type [SoleTrader]
4. POST /agent-registration/apply/about-your-business/user-role [Owner]
5. POST /agent-registration/apply/about-your-business/agent-online-services-account [HmrcOnlineServices]
6. GET /agent-registration/apply/about-your-business/sign-in
7. GET /agent-registration/apply/internal/initiate-agent-application/UkTaxAgent/SoleTrader/Owner
8. GET /agent-registration/apply/internal/grs/start-journey
   → [External GRS Service - User completes verification]
9. GET /agent-registration/apply/internal/grs/journey-callback?journeyId=xxx
10. GET /agent-registration/apply/internal/refusal-to-deal-with-check
11. GET /agent-registration/apply/internal/deceased-check
12. GET /agent-registration/apply/task-list
13. POST /agent-registration/apply/applicant/applicant-name
14. POST /agent-registration/apply/applicant/telephone-number
15. POST /agent-registration/apply/applicant/email-address
16. GET /agent-registration/apply/applicant/verify-email-address
17. POST /agent-registration/apply/applicant/check-your-answers
18. POST /agent-registration/apply/agent-details/business-name
19. POST /agent-registration/apply/agent-details/telephone-number
20. POST /agent-registration/apply/agent-details/email-address
21. GET /agent-registration/apply/agent-details/verify-email-address
22. POST /agent-registration/apply/agent-details/correspondence-address
23. GET /agent-registration/apply/internal/address-lookup/journey-callback
24. POST /agent-registration/apply/agent-details/check-your-answers
25. POST /agent-registration/apply/anti-money-laundering/supervisor-name
26. POST /agent-registration/apply/anti-money-laundering/registration-number
27. POST /agent-registration/apply/anti-money-laundering/supervision-runs-out
28. GET /agent-registration/apply/anti-money-laundering/evidence
29. POST /amls/process-notification-from-upscan/:uploadId [webhook - mock this]
30. GET /agent-registration/apply/anti-money-laundering/evidence/check-upload-status-js
31. POST /agent-registration/apply/anti-money-laundering/check-your-answers
32. POST /agent-registration/apply/agent-standard/accept-agent-standard
33. POST /agent-registration/apply/agent-declaration/confirm-declaration
34. GET /agent-registration/apply/application-submitted
35. GET /agent-registration/apply/view-application-progress
```

### Scenario 2: Limited Company Journey (Complete)
```
1-8.  [Same as Sole Trader up to GRS]
9. GET /agent-registration/apply/internal/grs/journey-callback?journeyId=xxx
10. GET /agent-registration/apply/internal/refusal-to-deal-with-check
11. GET /agent-registration/apply/internal/deceased-check
12. GET /agent-registration/apply/internal/companies-house-status-check
13. GET /agent-registration/apply/task-list
14-24. [Applicant & Agent Details - same as Sole Trader]
25-31. [AMLS - same as Sole Trader]
32. GET /agent-registration/apply/list-details/companies-house-officers
33. POST /agent-registration/apply/list-details/confirm-companies-house-officers [If ≤5 officers]
34. GET /agent-registration/apply/list-details/companies-house-officers/check-your-answers
35. POST /agent-registration/apply/list-details/companies-house-officers/check-your-answers
36. POST /agent-registration/apply/agent-standard/accept-agent-standard
37. POST /agent-registration/apply/agent-declaration/confirm-declaration
38. GET /agent-registration/apply/application-submitted
39. GET /agent-registration/apply/view-application-progress
```

### Scenario 3: General Partnership Journey (Complete)
```
1. POST /agent-registration/apply/about-your-business/agent-type [UkTaxAgent]
2. POST /agent-registration/apply/about-your-business/business-type [PartnershipType]
3. POST /agent-registration/apply/about-your-business/partnership-type [GeneralPartnership]
4. POST /agent-registration/apply/about-your-business/user-role [Partner]
5. POST /agent-registration/apply/about-your-business/agent-online-services-account [HmrcOnlineServices]
6-12. [GRS & Checks - same flow]
13. GET /agent-registration/apply/task-list
14-31. [Applicant & Agent Details & AMLS - same as Sole Trader]
32. POST /agent-registration/apply/list-details/how-many-key-individuals [e.g., 2]
33. POST /agent-registration/apply/list-details/enter-key-individual [Partner 1 details]
34. POST /agent-registration/apply/list-details/enter-key-individual [Partner 2 details]
35. GET /agent-registration/apply/list-details/key-individuals/check-your-answers
36. POST /agent-registration/apply/list-details/key-individuals/check-your-answers
37. POST /agent-registration/apply/list-details/how-many-other-individuals [No]
38. POST /agent-registration/apply/agent-standard/accept-agent-standard
39. POST /agent-registration/apply/agent-declaration/confirm-declaration
40. GET /agent-registration/apply/application-submitted
41. GET /agent-registration/apply/view-application-progress
```

### Scenario 4: Shared Link - Individual Providing Details
```
1. GET /provide-details/start/:linkId
2. POST /provide-details/match-application/:linkId
3. POST /provide-details/telephone-number/:linkId
4. POST /provide-details/email-address/:linkId
5. GET /provide-details/verify-email-address/:linkId
6. POST /provide-details/date-of-birth/:linkId
7. POST /provide-details/nino/:linkId
8. POST /provide-details/approve-applicant/:linkId
9. POST /provide-details/agree-standard/:linkId
10. POST /provide-details/check-your-answers/:linkId
11. GET /provide-details/confirmation/:linkId
```

---

## Performance Test Configuration

### Load Profile Recommendations:

1. **Ramp-up Test**:
   - Start: 10 users/second
   - Ramp: Over 5 minutes to 100 users/second
   - Duration: 15 minutes at steady state

2. **Stress Test**:
   - Start: 100 users/second
   - Ramp: To 500 users/second over 10 minutes
   - Duration: 10 minutes at peak
   - Monitor for error rate increase

3. **Soak Test**:
   - Constant: 50 users/second
   - Duration: 4 hours
   - Monitor: Memory leaks, connection pool exhaustion

### Key Metrics to Track:
- Response times (p50, p95, p99)
- Error rates by endpoint
- Throughput (requests/second)
- Backend service response times
- File upload success rate (if testing AMLS upload)
- Email verification completion rate

### Mocking Strategies:
1. **GRS Service**: Use GRS stub (enabled in config)
2. **Email Verification**: Set `ignoreEmailVerification=true` or mock email service
3. **File Uploads**: Mock upscan responses or use test file upload
4. **External Services**: Mock or stub as needed

---

## Key Considerations for Gatling Implementation

1. **Session Management**: Maintain session/cookies across requests for state management
2. **Correlation**: Extract journey IDs, link IDs, upload IDs from responses
3. **Pauses**: Add realistic think time between requests
4. **Authentication**: Obtain valid auth cookies/tokens before starting journeys
5. **Data Variation**: Use randomized business types, names, addresses to vary load
6. **Parameterization**: Extract reusable values into session variables (applicationId, linkId, etc.)
7. **Cleanup**: Consider cleanup scenarios or separate stress tests if needed


