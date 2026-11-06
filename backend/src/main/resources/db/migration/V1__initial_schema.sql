create table user_accounts (
    id uuid primary key,
    email varchar(320) not null,
    normalized_email varchar(320) not null unique,
    password_hash varchar(100) not null,
    role varchar(20) not null check (role in ('TEACHER', 'STUDENT')),
    email_verified boolean not null default false,
    enabled boolean not null default true,
    display_name varchar(120) not null,
    created_at timestamp with time zone not null
);

create table email_verification_tokens (
    id uuid primary key,
    user_id uuid not null references user_accounts(id),
    token_hash varchar(64) not null unique,
    expires_at timestamp with time zone not null,
    consumed_at timestamp with time zone
);
create index email_verification_tokens_user_idx on email_verification_tokens(user_id);

create table classrooms (
    id uuid primary key,
    teacher_id uuid not null references user_accounts(id),
    name varchar(120) not null,
    description varchar(2000),
    time_zone varchar(64) not null,
    created_at timestamp with time zone not null,
    version bigint not null default 0
);
create index classrooms_teacher_idx on classrooms(teacher_id, created_at desc);

create table class_memberships (
    id uuid primary key,
    classroom_id uuid not null references classrooms(id),
    student_id uuid not null references user_accounts(id),
    added_at timestamp with time zone not null,
    constraint class_memberships_unique unique (classroom_id, student_id)
);
create index class_memberships_student_idx on class_memberships(student_id, added_at desc);

create table assignments (
    id uuid primary key,
    classroom_id uuid not null references classrooms(id),
    teacher_id uuid not null references user_accounts(id),
    title varchar(200) not null,
    instructions varchar(10000),
    status varchar(20) not null check (status in ('DRAFT', 'PUBLISHED')),
    due_at timestamp with time zone,
    close_after_deadline boolean not null default false,
    attachment_upload_id uuid,
    created_at timestamp with time zone not null,
    published_at timestamp with time zone,
    version bigint not null default 0
);
create index assignments_classroom_idx on assignments(classroom_id, created_at desc);
create index assignments_class_status_idx on assignments(classroom_id, status, created_at desc);

create table upload_intents (
    id uuid primary key,
    actor_id uuid not null references user_accounts(id),
    assignment_id uuid not null references assignments(id),
    purpose varchar(32) not null check (purpose in ('ASSIGNMENT_ATTACHMENT', 'SUBMISSION')),
    idempotency_key_hash varchar(64) not null,
    request_fingerprint varchar(64) not null,
    object_key varchar(512) not null unique,
    file_name varchar(255) not null,
    declared_content_type varchar(128) not null,
    declared_size_bytes bigint not null check (declared_size_bytes > 0),
    submission_text varchar(10000),
    status varchar(20) not null check (status in ('PENDING', 'COMPLETED')),
    expires_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    object_version_id varchar(1024),
    result_id uuid,
    result_version integer,
    result_is_late boolean,
    version bigint not null default 0,
    constraint upload_idempotency_unique unique (actor_id, assignment_id, purpose, idempotency_key_hash)
);
create index upload_intents_assignment_idx on upload_intents(assignment_id, purpose, created_at);

alter table assignments
    add constraint assignments_attachment_upload_fk
    foreign key (attachment_upload_id) references upload_intents(id);

create table submission_versions (
    id uuid primary key,
    assignment_id uuid not null references assignments(id),
    student_id uuid not null references user_accounts(id),
    version integer not null check (version > 0),
    text varchar(10000),
    upload_intent_id uuid not null unique references upload_intents(id),
    submitted_at timestamp with time zone not null,
    is_late boolean not null,
    constraint submission_versions_unique unique (assignment_id, student_id, version)
);
create index submission_versions_assignment_idx on submission_versions(assignment_id, student_id, version desc);

create table grade_revisions (
    id uuid primary key,
    submission_id uuid not null references submission_versions(id),
    teacher_id uuid not null references user_accounts(id),
    revision integer not null check (revision > 0),
    status varchar(20) not null check (status in ('DRAFT', 'PUBLISHED')),
    score numeric(7, 2),
    feedback varchar(10000) not null,
    created_at timestamp with time zone not null,
    published_at timestamp with time zone,
    constraint grade_revisions_unique unique (submission_id, revision)
);
create index grade_revisions_current_idx on grade_revisions(submission_id, status, revision desc);
