export function AttachmentUnavailableNotice({
  title = "首发暂不开放附件上传",
  description = "免费演示版未接入附件存储；附件下载和文件校验也暂不可用，请仅使用虚构数据。",
  compact = false,
}: {
  title?: string;
  description?: string;
  compact?: boolean;
}) {
  return (
    <div className={`attachment-unavailable${compact ? " compact" : ""}`} role="note">
      <span className="unavailable-label">暂不可用</span>
      <div>
        <strong>{title}</strong>
        <p>{description}</p>
      </div>
    </div>
  );
}

export function UnavailableAttachmentAction({ label = "下载暂不可用" }: { label?: string }) {
  return (
    <button type="button" className="unavailable-action" disabled aria-disabled="true">
      {label}
    </button>
  );
}
