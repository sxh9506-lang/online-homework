"use client";

import Link from "next/link";
import { FormEvent, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { RoleGate } from "@/components/role-gate";
import { ApiError, apiPathForFileDownload, apiRequest, putToSignedUrl } from "@/lib/api";
import { ACCEPTED_FILE_TYPES, contentTypeForHomeworkFile, formatFileSize, uploadHeadersForHomework, validateHomeworkFile } from "@/lib/files";
import { formatInTimeZone, localDateTimeToUtc, utcToLocalDateTime } from "@/lib/time";
import type { Assignment, ClassSummary, UploadIntent } from "@/lib/types";

interface AssignmentUploadAttempt {
  key: string;
  file: File;
  intent?: UploadIntent;
  uploaded: boolean;
  phase: "creating" | "uploading" | "completing" | "retryCreate" | "retryUpload" | "retryComplete";
}

function explain(error: unknown) { return error instanceof ApiError ? error.message : "请求失败，请稍后重试。"; }

export default function AssignmentEditorClient({ classId, assignmentId }: { classId: string; assignmentId: string | null }) {
  const router = useRouter();
  const [classInfo, setClassInfo] = useState<ClassSummary | null>(null);
  const [assignment, setAssignment] = useState<Assignment | null>(null);
  const [title, setTitle] = useState("");
  const [instructions, setInstructions] = useState("");
  const [dueAt, setDueAt] = useState("");
  const [closeAfterDeadline, setCloseAfterDeadline] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [uploadAttempt, setUploadAttempt] = useState<AssignmentUploadAttempt | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    let active = true;
    Promise.all([
      apiRequest<ClassSummary>(`/classes/${classId}`),
      assignmentId ? apiRequest<Assignment>(`/assignments/${assignmentId}`) : Promise.resolve(null),
    ]).then(([info, draft]) => {
      if (!active) return;
      setClassInfo(info);
      if (draft) {
        setAssignment(draft);
        setTitle(draft.title);
        setInstructions(draft.instructions || "");
        setCloseAfterDeadline(draft.closeAfterDeadline);
        setDueAt(draft.dueAt ? utcToLocalDateTime(draft.dueAt, info.timeZone) : "");
      }
    }).catch((cause) => { if (active) setError(explain(cause)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [classId, assignmentId]);

  async function uploadAttachment(draftId: string, file: File, existingAttempt?: AssignmentUploadAttempt | null) {
    const validation = validateHomeworkFile(file);
    if (validation) { setError(validation); return; }
    let current = existingAttempt || uploadAttempt;
    if (!current) current = { key: crypto.randomUUID(), file, uploaded: false, phase: "creating" };
    setUploadAttempt(current); setError(""); setNotice(""); setUploading(true);
    const contentType = contentTypeForHomeworkFile(file);
    try {
      if (!current.intent) {
        const intent = await apiRequest<UploadIntent>(`/assignments/${draftId}/attachment-upload-intents`, {
          method: "POST",
          headers: { "Idempotency-Key": current.key },
          body: JSON.stringify({ fileName: file.name, contentType, sizeBytes: file.size }),
        });
        current = { ...current, intent, phase: "uploading" };
        setUploadAttempt(current);
      }
      const intent = current.intent;
      if (!intent) throw new Error("未能取得附件上传记录，请保持当前文件并重试。");
      if (!current.uploaded) {
        await putToSignedUrl(intent.uploadUrl, file, uploadHeadersForHomework(file, intent.requiredHeaders));
        current = { ...current, uploaded: true, phase: "completing" };
        setUploadAttempt(current);
      }
      await apiRequest(`/assignments/${draftId}/attachment-upload-intents/${intent.uploadId}/complete`, { method: "POST" });
      const updated = await apiRequest<Assignment>(`/assignments/${draftId}`);
      setAssignment(updated); setUploadAttempt(null); setSelectedFile(null);
      setNotice("作业草稿及附件已保存。附件经服务端校验后才会关联到草稿。");
    } catch (cause) {
      const phase = !current.intent ? "retryCreate" : !current.uploaded ? "retryUpload" : "retryComplete";
      setUploadAttempt({ ...current, phase });
      setError(explain(cause));
    } finally { setUploading(false); }
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(""); setNotice("");
    if (!title.trim()) { setError("请填写作业标题。"); return; }
    if (assignment && assignment.status !== "DRAFT") { setError("作业发布后不能修改核心内容。"); return; }
    if (uploadAttempt) { setError("请先完成或重试当前附件上传，再保存其他更改。"); return; }
    if (selectedFile) {
      const validation = validateHomeworkFile(selectedFile);
      if (validation) { setError(validation); return; }
      if (assignment?.attachment) { setError("一个作业最多只能附加一个文件，现有附件不能在首版中替换。"); return; }
    }
    setSaving(true);
    try {
      const zone = classInfo?.timeZone || "UTC";
      const payload = {
        title: title.trim(),
        instructions: instructions.trim() || null,
        dueAt: dueAt ? localDateTimeToUtc(dueAt, zone) : null,
        closeAfterDeadline,
      };
      const draft = assignment
        ? await apiRequest<Assignment>(`/assignments/${assignment.id}`, { method: "PATCH", body: JSON.stringify(payload) })
        : await apiRequest<Assignment>(`/classes/${classId}/assignments`, { method: "POST", body: JSON.stringify(payload) });
      setAssignment(draft);
      setNotice("草稿已保存。");
      if (!assignment) router.replace(`/teacher/${classId}/assignments/new?assignmentId=${draft.id}`);
      if (selectedFile) await uploadAttachment(draft.id, selectedFile);
    } catch (cause) { setError(explain(cause)); } finally { setSaving(false); }
  }

  async function publish() {
    if (!assignment || assignment.status !== "DRAFT") return;
    if (selectedFile || uploadAttempt) { setError("请先完成附件上传，再发布作业。"); return; }
    setPublishing(true); setError(""); setNotice("");
    try {
      const published = await apiRequest<Assignment>(`/assignments/${assignment.id}/publish`, { method: "POST" });
      setAssignment(published); setNotice("作业已发布，班级学生现在可以查看。");
    } catch (cause) { setError(explain(cause)); } finally { setPublishing(false); }
  }

  async function downloadAttachment() {
    if (!assignment?.attachment) return;
    setError("");
    try {
      const link = await apiPathForFileDownload(`/assignments/${assignment.id}/attachment-download`);
      const anchor = document.createElement("a"); anchor.href = link.url; anchor.target = "_blank"; anchor.rel = "noreferrer noopener"; anchor.click();
    } catch (cause) { setError(explain(cause)); }
  }

  const canEdit = !assignment || assignment.status === "DRAFT";

  return <RoleGate role="TEACHER"><div className="mx-auto max-w-3xl space-y-6 p-6">
    {loading ? <div className="loading-card"><span className="spinner" />正在加载作业…</div> : <>
      <header><Link href={`/teacher/${classId}`} className="text-sm text-slate-500 hover:underline">← 返回班级</Link><h1 className="mt-2 text-3xl font-bold">{assignment ? assignment.status === "DRAFT" ? "编辑作业草稿" : "作业详情" : "新建作业"}</h1>{classInfo && <p className="mt-1 text-sm text-slate-500">截止时间按班级时区 {classInfo.timeZone} 解释并保存为 UTC。</p>}</header>
      {error && <p role="alert" className="rounded-md bg-red-50 p-3 text-red-700">{error}</p>}
      {notice && <p role="status" className="rounded-md bg-green-50 p-3 text-green-700">{notice}</p>}
      <form onSubmit={save} className="space-y-5 rounded-xl border p-6">
        <label className="grid gap-1 font-medium">标题<input value={title} onChange={(event) => setTitle(event.target.value)} required disabled={!canEdit} className="rounded-md border p-2 font-normal disabled:bg-slate-100" /></label>
        <label className="grid gap-1 font-medium">说明<textarea value={instructions} onChange={(event) => setInstructions(event.target.value)} rows={6} disabled={!canEdit} className="rounded-md border p-2 font-normal disabled:bg-slate-100" /></label>
        <label className="grid gap-1 font-medium">截止时间（班级时区）<input type="datetime-local" value={dueAt} onChange={(event) => setDueAt(event.target.value)} disabled={!canEdit} className="rounded-md border p-2 font-normal disabled:bg-slate-100" /></label>
        <p className="text-xs text-slate-500">截止时刻完成文件校验才算提交；完成时间等于截止时间仍按时，只有晚于才标记逾期。服务端按 UTC 判定。</p>
        <label className="flex items-center gap-2"><input type="checkbox" checked={closeAfterDeadline} onChange={(event) => setCloseAfterDeadline(event.target.checked)} disabled={!canEdit} />截止后关闭提交</label>
        {assignment?.attachment ? <div className="attachment-row"><span className="file-type-mark">FILE</span><span><strong>{assignment.attachment.fileName}</strong><small>{formatFileSize(assignment.attachment.sizeBytes)} · 每个作业最多一个附件</small></span><button className="text-button" type="button" onClick={() => void downloadAttachment()}>下载附件</button></div> : !uploadAttempt && canEdit && <label className="file-picker"><span className="file-picker-icon">＋</span><span><strong>{selectedFile ? selectedFile.name : "附加一份作业资料（可选）"}</strong><small>{selectedFile ? formatFileSize(selectedFile.size) : "PDF、JPG、PNG、DOCX、XLSX、PPTX；单文件最多 20 MiB"}</small></span><input type="file" accept={ACCEPTED_FILE_TYPES} disabled={saving || uploading} onChange={(event) => { setError(""); setSelectedFile(event.target.files?.[0] || null); }} aria-label="选择作业附件" /></label>}
        {uploadAttempt && <div className="selected-file"><span className="file-type-mark">FILE</span><span className="selected-file-info"><strong>{uploadAttempt.file.name}</strong><small>{formatFileSize(uploadAttempt.file.size)} · 重试沿用同一 Idempotency-Key</small></span><button type="button" disabled={uploading} className="text-button" onClick={() => { setUploadAttempt(null); setSelectedFile(null); setError(""); setNotice("已清除本页重试状态；服务器上的待上传记录不会成为附件。"); }}>放弃重试</button></div>}
        <div className="flex flex-wrap gap-3"><button disabled={saving || uploading || publishing || !canEdit} className="rounded-md bg-slate-900 px-4 py-2 text-white disabled:opacity-50">{saving ? "保存中…" : selectedFile ? "保存草稿并上传附件" : "保存草稿"}</button>{assignment?.status === "DRAFT" && <button type="button" disabled={saving || uploading || publishing || Boolean(selectedFile) || Boolean(uploadAttempt)} onClick={publish} className="rounded-md border border-slate-900 px-4 py-2 disabled:opacity-50">{publishing ? "发布中…" : "发布作业"}</button>}</div>
        {uploadAttempt && assignment && <button type="button" disabled={uploading || saving} onClick={() => void uploadAttachment(assignment.id, uploadAttempt.file, uploadAttempt)} className="rounded-md border px-4 py-2 text-sm disabled:opacity-50">{uploading ? "正在上传附件…" : uploadAttempt.phase === "retryComplete" ? "重试附件校验" : uploadAttempt.phase === "retryUpload" ? "重试附件上传" : "继续附件上传"}</button>}
      </form>
      {assignment?.dueAt && <p className="text-sm text-slate-500">当前截止时间：{formatInTimeZone(assignment.dueAt, classInfo?.timeZone || "UTC")}</p>}
    </>}
  </div></RoleGate>;
}
