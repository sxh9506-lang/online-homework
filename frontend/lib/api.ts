import type { ApiProblem, UploadUrl } from "./types";

const API_BASE = (process.env.NEXT_PUBLIC_API_BASE_URL || "/api/v1").replace(/\/$/, "");
let accessTokenInMemory: string | null = null;

export class ApiError extends Error {
  status: number;
  code?: string;
  retryable: boolean;

  constructor(status: number, problem: ApiProblem = {}) {
    super(problem.detail || problem.message || problem.title || "请求失败，请稍后重试。");
    this.name = "ApiError";
    this.status = status;
    this.code = problem.code;
    this.retryable = Boolean(problem.retryable);
  }
}

export function setAccessToken(token: string | null) {
  // Keep short-lived credentials in memory only. Prefer an HttpOnly session cookie
  // from the API in production; never move tokens into localStorage.
  accessTokenInMemory = token;
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Accept", "application/json");
  if (typeof init.body === "string" && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (accessTokenInMemory) headers.set("Authorization", `Bearer ${accessTokenInMemory}`);

  let response: Response;
  try {
    response = await fetch(`${API_BASE}${path.startsWith("/") ? path : `/${path}`}`, {
      ...init,
      headers,
      credentials: "include",
      cache: "no-store",
    });
  } catch {
    throw new ApiError(0, { title: "暂时无法连接服务器，请检查网络或稍后重试。", retryable: true });
  }

  if (response.status === 204) return undefined as T;
  const contentType = response.headers.get("content-type") || "";
  const payload = contentType.includes("json")
    ? await response.json().catch(() => ({}))
    : { detail: await response.text().catch(() => "") };

  if (!response.ok) throw new ApiError(response.status, payload as ApiProblem);
  return payload as T;
}

export async function putToSignedUrl(
  url: string,
  file: File,
  requiredHeaders: Record<string, string> = {},
) {
  const headers = new Headers(requiredHeaders);
  if (file.type && !headers.has("Content-Type")) headers.set("Content-Type", file.type);

  // Signed storage URLs are short-lived credentials: use them only for this PUT,
  // never append API auth, persist them, or log them.
  const response = await fetch(url, { method: "PUT", headers, body: file });
  if (!response.ok) {
    throw new ApiError(response.status, {
      title: "文件上传未成功，请重试上传。",
      code: "FILE_UPLOAD_FAILED",
      retryable: response.status >= 500 || response.status === 408,
    });
  }
}

export function issueUploadUrl(uploadId: string) {
  return apiRequest<UploadUrl>(`/upload-intents/${encodeURIComponent(uploadId)}/upload-url`, {
    method: "POST",
  });
}

export function apiPathForFileDownload(path: string) {
  return apiRequest<{ url: string; expiresAt: string }>(path, { method: "POST" });
}
