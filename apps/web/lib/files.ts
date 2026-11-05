export const MAX_FILE_BYTES = 20 * 1024 * 1024;
export const ACCEPTED_FILE_TYPES = ".pdf,.jpg,.jpeg,.png,.docx,.xlsx,.pptx";

const allowedExtensions = new Set(["pdf", "jpg", "jpeg", "png", "docx", "xlsx", "pptx"]);

export function validateHomeworkFile(file?: File | null): string | null {
  if (!file) return "请选择一个文件。";
  if (file.size === 0) return "不能上传空文件。";
  if (file.size > MAX_FILE_BYTES) return "单个文件不能超过 20 MiB（20 × 1024 × 1024 字节）。";
  const extension = file.name.split(".").pop()?.toLowerCase() || "";
  if (!allowedExtensions.has(extension)) return "仅支持 PDF、JPG、PNG、DOCX、XLSX、PPTX 文件。";
  return null;
}

export function formatFileSize(bytes: number) {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KiB` : `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
}

const allowedMimeTypes: Record<string, string> = {
  pdf: "application/pdf",
  jpg: "image/jpeg",
  jpeg: "image/jpeg",
  png: "image/png",
  docx: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  xlsx: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  pptx: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
};

export function contentTypeForHomeworkFile(file: File): string {
  const extension = file.name.split(".").pop()?.toLowerCase() || "";
  return allowedMimeTypes[extension] || "application/octet-stream";
}

export function uploadHeadersForHomework(file: File, requiredHeaders: Record<string, string> = {}): Record<string, string> {
  const headers = { ...requiredHeaders };
  if (!Object.keys(headers).some((name) => name.toLowerCase() === "content-type")) {
    headers["Content-Type"] = contentTypeForHomeworkFile(file);
  }
  return headers;
}
