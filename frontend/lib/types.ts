export type Role = "TEACHER" | "STUDENT";

export interface CurrentUser {
  id: string;
  email?: string;
  displayName?: string;
  role: Role;
  emailVerified?: boolean;
}

export interface ClassSummary {
  id: string;
  name: string;
  description?: string | null;
  timeZone: string;
  createdAt?: string;
}

export interface ClassMember {
  userId: string;
  email?: string;
  displayName: string;
  addedAt?: string;
}

export type AssignmentStatus = "DRAFT" | "PUBLISHED";

export interface FileMetadata {
  id: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
}

export interface Assignment {
  id: string;
  classId: string;
  title: string;
  instructions?: string | null;
  status: AssignmentStatus;
  dueAt?: string | null;
  closeAfterDeadline: boolean;
  timeZone?: string;
  attachment?: FileMetadata | null;
  publishedAt?: string | null;
  createdAt?: string;
}

export interface Grade {
  id: string;
  submissionId: string;
  revision: number;
  status: "DRAFT" | "PUBLISHED";
  score?: number | null;
  feedback: string;
  createdAt?: string;
  publishedAt?: string | null;
}

export interface Submission {
  id: string;
  assignmentId: string;
  studentUserId?: string;
  studentName?: string;
  studentEmail?: string;
  version: number;
  completedAt: string;
  late: boolean;
  text?: string | null;
  attachment?: FileMetadata | null;
  currentPublishedGrade?: Grade | null;
}

export interface UploadIntent {
  uploadId: string;
  uploadUrl: string;
  expiresAt: string;
  requiredHeaders?: Record<string, string>;
}

export interface UploadCompletion {
  uploadId?: string;
  completedAt?: string;
  submission?: Submission;
  assignment?: Assignment;
}

export interface DownloadLink {
  url: string;
  expiresAt: string;
}

export interface RegistrationResult {
  userId: string;
  email: string;
  role: Role;
  emailVerified: boolean;
  verificationRequired: boolean;
  message?: string;
}

export interface ApiProblem {
  title?: string;
  status?: number;
  code?: string;
  detail?: string;
  message?: string;
  retryable?: boolean;
}
