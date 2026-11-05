# MVP contract and module boundaries

## Product boundary

The first usable release is a web-only teacher–student loop, targeted for **3 working days**, followed by feedback-driven iteration. It is not a four-week feature-complete release.

**In scope:** teacher-owned classes, manual student association, assignment drafts and publishing, student assignment visibility, repeated versioned submissions, late/closed-submission behavior, teacher grading history, published feedback visibility, and private attachments.

**Out of scope:** parent accounts, native/mobile apps, bulk import, notifications, reports/analytics, automatic grading, Redis, and a message queue. Add infrastructure only if a concrete v1 requirement demands it.

### Rules of record

- An assignment is invisible to students until published. Drafts are teacher-only.
- A student may create multiple submission versions for a published assignment. Keep every version; do not overwrite earlier submissions.
- If a deadline passes, submission remains allowed by default and the version is marked late. If the teacher set `closeAfterDeadline=true`, reject new submissions after the deadline. Compare against the stored UTC instant; display the deadline in the class's IANA time zone.
- Store instants as UTC (`timestamptz` in PostgreSQL); a class stores its IANA time-zone ID (for example `Asia/Tokyo`).
- Exactly one attachment may be associated with an assignment and one with each submission, up to 20 MB each. Allowed extensions/media types: PDF, JPG, PNG, DOCX, XLSX, PPTX. Validate size and content type server-side; do not trust the browser's file name or MIME header alone.
- A grading action creates a new history record. Only an explicitly published result is visible to the student, and only for that student's submission. A later published grading record becomes the current result; prior records remain available to the teacher.
- Object storage is private. Every upload/download requires server-side authorization and a short-lived signed URL. Never expose raw bucket paths or rely on frontend-hidden controls for access control.

## Module boundaries (modular monolith)

Keep one deployable Spring Boot application, one PostgreSQL database, and separate Java packages/modules with explicit dependencies. No cross-module repository access; communicate through module APIs/ports.

| Module | Owns | Depends on |
| --- | --- | --- |
| `identity` | Authenticated principal, user ID, `TEACHER`/`STUDENT` role mapping | Spring Security integration; token issuer/account lifecycle unresolved |
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

See `openapi.yaml`. Resource IDs are opaque. JSON timestamps are ISO-8601 instants in UTC (`Z`). The API receives authenticated identity from a bearer-token principal; **it does not define how a person obtains an account or token**.

### Account/onboarding decision — unresolved, do not assume

The manual-add-student requirement needs a product choice: does the teacher add an existing account, invite an email address, or create a student record/credential? For now `POST /classes/{classId}/students` links an already-existing student user ID. This is only a contract placeholder, not approval to build invitations or public registration. The product owner must decide the account lifecycle and identity/token issuer before an end-to-end release with real sign-in.

### Other implementation choices still open

- Select and configure a private object-storage provider and local development substitute.
- Define production token issuer/JWT validation keys after the onboarding decision.
- Confirm class time-zone input validation and defaulting; require an explicit IANA time-zone on class creation for v1 rather than guessing.
- Agree a minimal grading model (numeric score optional, text feedback required) before finalizing UI semantics; the contract supports both score and feedback.

## Three-working-day delivery sequence

1. **Day 1 — contract and runnable base:** settle API/module boundaries, application builds, database migrations, authorization policy skeleton, PR checks and test-environment deployment skeleton.
2. **Day 2 — core workflow:** class creation/student association, assignment draft/publish, student assignment view and versioned submission with late/closed behavior.
3. **Day 3 — grading and trial:** grading history/current published result, private-file permission checks, UI/API integration, smoke + security acceptance tests, deploy a testable environment and capture known limitations.

A working core loop takes priority over polished edge-case breadth. Account onboarding remains a visible release blocker unless the product owner decides it in time; do not invent a login model.

## Minimum acceptance checks

- Unauthenticated requests fail; a teacher from another account cannot read or mutate the class.
- Students cannot see drafts, classmates' submissions, or unpublished/prior grading records.
- A deadline-past submission is accepted and late by default; it is rejected when `closeAfterDeadline` is true. Deadline display uses the class time zone while the stored instant is UTC.
- A resubmission creates a second version without changing the first.
- A second attachment is rejected; files over 20 MB or outside the allowlist are rejected by the server.
- Unauthorized users cannot mint upload/download URLs. URLs expire promptly and object storage remains private.
