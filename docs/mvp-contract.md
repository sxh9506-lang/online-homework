# MVP contract and module boundaries

## Product boundary

The first usable release is a web-only teacher–student loop, targeted for **3 working days**, followed by feedback-driven iteration. It is not a four-week feature-complete release.

**In scope:** teacher-owned classes, manual student association, assignment drafts and publishing, student assignment visibility, repeated versioned submissions, late/closed-submission behavior, teacher grading history, published feedback visibility, and private attachments.

**Out of scope:** parent accounts, native/mobile apps, bulk import, notifications, reports/analytics, automatic grading, Redis, and a message queue. Add infrastructure only if a concrete v1 requirement demands it.

### Rules of record

- An assignment is invisible to students until published. Drafts are teacher-only.
- A student may create multiple submission versions for a published assignment. Keep every version; do not overwrite earlier submissions. Create the visible submission version only after the uploaded object has passed server-side completion checks.
- If a deadline passes, submission remains allowed by default and the version is marked late. If the teacher set `closeAfterDeadline=true`, reject new submissions after the deadline. Compare against the stored UTC instant; display the deadline in the class's IANA time zone.
- Store instants as UTC (`timestamptz` in PostgreSQL); a class stores its IANA time-zone ID (for example `Asia/Tokyo`).
- Exactly one attachment may be associated with an assignment and one with each submission, up to 20 MB each. Allowed extensions/media types: PDF, JPG, PNG, DOCX, XLSX, PPTX. Validate size and content server-side; do not trust the browser's file name or MIME header alone.
- A student upload creation requires `Idempotency-Key`, scoped to authenticated user + assignment + key. A retry returns the same upload record. Completing an upload is also idempotent and must not create another submission version. If the object is not ready, return retryable `UPLOAD_NOT_READY` and create no visible submission. Reject invalid files with stable error codes; only the first successful validation allocates the submission version, UTC submission time, and late flag.
- A grading action creates a new history record. Only an explicitly published result is visible to the student, and only for that student's submission. A later published grading record becomes the current result; prior records remain available to the teacher.
- Object storage is private. Every upload/download requires server-side authorization and a short-lived signed URL. Never expose raw bucket paths or rely on frontend-hidden controls for access control.

## Module boundaries (modular monolith)

Keep one deployable Spring Boot application, one PostgreSQL database, and separate Java packages/modules with explicit dependencies. No cross-module repository access; communicate through module APIs/ports.

| Module | Owns | Depends on |
| --- | --- | --- |
| `identity` | Registration/login identity, verified-email state, authenticated principal, user ID and `TEACHER`/`STUDENT` role mapping | Spring Security integration; production identity/token provider and teacher-account provisioning remain open |
| `classroom` | Class, owner teacher, class time zone, student membership | `identity` user references |
| `assignment` | Draft/published assignment, due instant, close-after-deadline setting, assignment attachment reference | `classroom` authorization/read port, `identity` |
| `submission` | Immutable student submission versions, submitted instant, late flag, submission attachment reference | `assignment` and `classroom` authorization/read ports, `identity` |
| `grading` | Append-only grading revisions, draft/published state, published-result selection | `submission` ownership/read port, `identity` |
| `file` | Upload intents, file metadata, private object-storage adapter, signed URL minting and authorization policy | Calling module authorization ports; storage SDK adapter |
| `shared` | Small common primitives only (IDs, clock, API error format) | None; must not become a domain dumping ground |

File authorization is enforced by the server using the target domain object and current principal. A signed URL is short-lived and scoped to one object and one operation. Never accept an arbitrary object key from a browser.

## Authorization invariants

- A teacher may read/change only classes they own and their assignments/submissions/grading.
- A student may read only classes they belong to, published assignments in those classes, their own submission versions, and the current published grading result for their own submission.
- A student cannot list another student's submissions or access draft grading/history.
- Assignment publishing, submission acceptance, grade publication, and every file upload/download are checked again by the backend. Frontend visibility is not authorization.
- For a teacher's write action, verify ownership at the service boundary and, where practical, again in the database query predicate.

## API contract overview

See `openapi.yaml`. Resource IDs are opaque. JSON timestamps are ISO-8601 instants in UTC (`Z`). Students register and log in before they can be added to a class. A teacher adds an existing account by exact login email; if no account matches, the UI tells the student to register first. There are no invitation emails or self-service join codes. The server creates and removes class membership.

### Account and rollout decisions

The test environment uses pre-created test accounts. Before real students are onboarded, login email addresses must be verified. The verification delivery/mechanism and production token issuer still need configuration. Teacher accounts are provisioned as test accounts for the MVP; the production teacher-account provisioning path remains a product decision. Do not substitute invitations, email matching with fuzzy/normalized aliases, or open teacher registration without approval.

### Other implementation choices still open

- Select and configure a private object-storage provider and local development substitute; keep an adapter boundary.
- Configure production token issuer/JWT validation and email verification before connecting real students.
- Require an explicit valid IANA time-zone on class creation rather than guessing.
- Keep the score optional and feedback textual unless product changes the grading model.

## Three-working-day delivery sequence

1. **Day 1 — contract and runnable base:** settle API/module boundaries, application builds, database migrations, test-account authentication, authorization policy skeleton, PR checks and test-environment deployment skeleton.
2. **Day 2 — core workflow:** class creation, exact-email student membership add/remove, assignment draft/publish, student assignment view, and two-phase idempotent upload/submission with late/closed behavior.
3. **Day 3 — grading and trial:** grading history/current published result, private-file permission checks, UI/API integration, smoke + security acceptance tests, deploy a testable environment and capture known limitations.

A working core loop takes priority over polished edge-case breadth. Use test accounts in the test environment; real-student onboarding is blocked on verified-email and production identity configuration.

## Minimum acceptance checks

- Unauthenticated requests fail; a teacher from another account cannot read or mutate the class.
- Students cannot see drafts, classmates' submissions, or unpublished/prior grading records.
- A deadline-past submission is accepted and late by default; it is rejected when `closeAfterDeadline` is true. Deadline display uses the class time zone while the stored instant is UTC.
- A resubmission creates a second version without changing the first. Retrying an upload-creation request with the same user, assignment, and idempotency key returns the same upload record; repeated completion returns the same result without another version.
- An incomplete object yields retryable `UPLOAD_NOT_READY` and no visible version. Invalid/oversized/disallowed files receive stable rejection codes. A second attachment is rejected.
- An exact-email lookup adds only an existing student account; an unknown address returns a clear not-registered outcome. A teacher can remove a membership, which immediately revokes student access to that class's resources.
- Unauthorized users cannot mint upload/download URLs. URLs expire promptly and object storage remains private.
