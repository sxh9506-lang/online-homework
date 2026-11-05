"use client";

import Link from "next/link";
import { FormEvent, useEffect, useState } from "react";
import { RoleGate } from "@/components/role-gate";
import { ApiError, apiPathForFileDownload, apiRequest, putToSignedUrl } from "@/lib/api";
import { ACCEPTED_FILE_TYPES, contentTypeForHomeworkFile, formatFileSize, uploadHeadersForHomework, validateHomeworkFile } from "@/lib/files";
import { formatInTimeZone } from "@/lib/time";
import type { Assignment, Submission, UploadIntent } from "@/lib/types";

type SubmissionList = Submission[] | { submissions: Submission[] } | { items: Submission[] };
interface UploadAttempt {
  key: string;
  file: File;
  intent?: UploadIntent;
  uploaded: boolean;
  phase: "creating" | "uploading" | "completing" | "retryCreate" | "retryUpload" | "retryComplete";
}
function unwrapSubmissions(data: SubmissionList) {
  if (Array.isArray(data)) return data;
  return "submissions" in data ? data.submissions : data.items;
}
function explain(error: unknown) {
  if (error instanceof ApiError && error.code === "ASSIGNMENT_CLOSED") return "作业已关闭，当前不能提交。";
  if (error instanceof ApiError && error.code === "ASSIGNMENT_NOT_PUBLISHED") return "该作业暂不可见，请返回作业列表刷新。";
  return error instanceof Error ? error.message : "操作失败，请稍后重试。";
}

export function StudentAssignmentClient({ assignmentId, timeZone, className }: { assignmentId: string; timeZone: string; className?: string }) {
  const [assignment, setAssignment] = useState<Assignment | null>(null);
  const [submissions, setSubmissions] = useState<Submission[]>([]);
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [attempt, setAttempt] = useState<UploadAttempt | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshCount, setRefreshCount] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    let active = true;
    Promise.all([
      apiRequest<Assignment>(`/assignments/${assignmentId}`),
      apiRequest<SubmissionList>(`/assignments/${assignmentId}/my-submissions`),
    ]).then(async ([assignmentData, submissionData]) => {
      const records = unwrapSubmissions(submissionData).sort((a, b) => b.version - a.version);
      if (!active) return;
      setAssignment(assignmentData);
      setSubmissions(records);
    }).catch((cause) => { if (active) setError(explain(cause)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [assignmentId, refreshCount]);

  function refreshSubmissions() { setError(""); setRefreshCount((count) => count + 1); }

  async function completeAttempt(current: UploadAttempt) {
    if (!current.intent) return;
    setBusy(true); setError(""); setNotice("");
    try {
      await apiRequest(`/submissions/${current.intent.uploadId}/complete`, { method: "POST" });
      setAttempt(null); setSelectedFile(null); setNotice("提交成功。教师完成批改并发布后，你会在这里看到反馈。");
      setRefreshCount((count) => count + 1);
    } catch (cause) {
      if (cause instanceof ApiError && cause.code === "UPLOAD_NOT_READY") {
        setAttempt({ ...current, phase: "retryComplete" });
        setNotice("文件已上传，服务端仍在确认文件状态。请稍后重试校验；此时还没有生成正式提交版本。");
      } else {
        setAttempt({ ...current, phase: "retryComplete" });
        setError(explain(cause));
      }
    } finally { setBusy(false); }
  }

  async function submitOrResume(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    setError(""); setNotice("");
    let current = attempt;
    if (!current) {
      const file = selectedFile;
      const validation = validateHomeworkFile(file);
      if (validation) { setError(validation); return; }
      if (!file) return;
      current = { key: crypto.randomUUID(), file, uploaded: false, phase: "creating" };
      setAttempt(current);
    }
    setBusy(true);
    try {
      if (!current.intent) {
        current = { ...current, phase: "creating" };
        setAttempt(current);
        const contentType = contentTypeForHomeworkFile(current.file);
        const intent = await apiRequest<UploadIntent>(`/assignments/${assignmentId}/submissions`, {
          method: "POST",
          headers: { "Idempotency-Key": current.key },
          body: JSON.stringify({ fileName: current.file.name, contentType, sizeBytes: current.file.size }),
        });
        current = { ...current, intent, phase: "uploading" };
        setAttempt(current);
      }
      const intent = current.intent;
      if (!intent) throw new Error("未能取得上传记录，请保持当前提交信息并重试。");
      if (!current.uploaded) {
        await putToSignedUrl(intent.uploadUrl, current.file, uploadHeadersForHomework(current.file, intent.requiredHeaders));
        current = { ...current, uploaded: true, phase: "completing" };
        setAttempt(current);
      }
      setBusy(false);
      await completeAttempt(current);
    } catch (cause) {
      const phase = !current.intent ? "retryCreate" : !current.uploaded ? "retryUpload" : "retryComplete";
      setAttempt({ ...current, phase });
      setError(explain(cause));
      setBusy(false);
    }
  }

  async function downloadSubmission(submission: Submission) {
    if (!submission.attachment) return;
    setError("");
    try {
      const link = await apiPathForFileDownload(`/submissions/${submission.id}/attachment-download`);
      const anchor = document.createElement("a");
      anchor.href = link.url;
      anchor.target = "_blank";
      anchor.rel = "noreferrer noopener";
      anchor.click();
    } catch (cause) { setError(explain(cause)); }
  }

  async function downloadAssignment() {
    if (!assignment?.attachment) return;
    setError("");
    try {
      const link = await apiPathForFileDownload(`/assignments/${assignment.id}/attachment-download`);
      const anchor = document.createElement("a");
      anchor.href = link.url; anchor.target = "_blank"; anchor.rel = "noreferrer noopener"; anchor.click();
    } catch (cause) { setError(explain(cause)); }
  }

  function clearAttempt() {
    setAttempt(null); setSelectedFile(null); setError(""); setNotice("当前待上传记录未生成正式提交版本；已清除本页重试状态。若重新提交，请重新选择文件。");
  }

  const displayZone = timeZone || "UTC";

  return <RoleGate role="STUDENT"><div className="mx-auto max-w-5xl space-y-7 p-6">
    <header className="page-heading">
      <div><Link href="/student" className="back-link">← 返回我的作业</Link><p className="eyebrow">{className || "班级作业"}{className ? ` · ${displayZone}` : ` · ${displayZone}`}</p><h1>{assignment?.title || "作业详情"}</h1><p className="muted">{assignment?.instructions || "教师尚未添加详细说明。"}</p></div>
    </header>
    {error && <div className="notice error" role="alert">{error}</div>}
    {notice && <div className="notice success" role="status">{notice}</div>}
    {loading ? <div className="loading-card"><span className="spinner" />正在加载作业详情…</div> : <>
      <section className="deadline-card">
        <div><p className="eyebrow">提交截止</p><h2>{formatInTimeZone(assignment?.dueAt, displayZone)}</h2><p className="timezone-caption">按班级时区展示（{displayZone}）。逾期状态由服务端按文件完成校验时刻与 UTC 截止时刻判定。</p></div>
        <div className="deadline-side"><span className="deadline-icon">◷</span><p>{assignment?.closeAfterDeadline ? "截止后关闭提交" : "截止后仍可提交，系统会按服务端结果标记逾期"}</p></div>
      </section>
      {assignment?.attachment && <div className="attachment-row"><span className="file-type-mark">FILE</span><span><strong>{assignment.attachment.fileName}</strong><small>{formatFileSize(assignment.attachment.sizeBytes)} · 教师附加资料</small></span><button className="text-button" type="button" onClick={() => void downloadAssignment()}>下载资料</button></div>}
      <section className="upload-card">
        <div className="section-heading"><div><p className="eyebrow">提交作业</p><h2>上传你的文件</h2></div><span className="quiet-tag">每个版本一个附件</span></div>
        <p className="muted">支持 PDF、JPG、PNG、DOCX、XLSX、PPTX。单附件上限 <strong>20 MiB（20 × 1024 × 1024 字节）</strong>；浏览器预检不代替服务端校验。</p>
        <form onSubmit={submitOrResume} className="upload-form">
          {!attempt && <label className="file-picker"><span className="file-picker-icon">＋</span><span><strong>{selectedFile ? selectedFile.name : "选择一个文件"}</strong><small>{selectedFile ? formatFileSize(selectedFile.size) : "或将文件拖入此区域"}</small></span><input type="file" accept={ACCEPTED_FILE_TYPES} onChange={(event) => { setError(""); setSelectedFile(event.target.files?.[0] || null); }} aria-label="选择作业文件" /></label>}
          {attempt && <div className="selected-file"><span className="file-type-mark">DOC</span><span className="selected-file-info"><strong>{attempt.file.name}</strong><small>{formatFileSize(attempt.file.size)} · 当前提交请求保持同一 Idempotency-Key</small></span>{!busy && <button type="button" className="text-button" onClick={clearAttempt}>放弃并重新选择</button>}</div>}
          <div className="upload-actions">
            {!attempt ? <button className="button button-primary" type="submit" disabled={busy || !selectedFile}>{busy ? "正在提交…" : "上传并提交"}</button> : attempt.phase === "retryComplete" ? <button className="button button-primary" type="button" disabled={busy} onClick={() => attempt && void completeAttempt(attempt)}>{busy ? "正在校验…" : "重试文件校验"}</button> : <button className="button button-primary" type="button" disabled={busy} onClick={() => void submitOrResume()}>{busy ? attempt.phase === "retryCreate" ? "正在申请上传…" : attempt.phase === "retryUpload" ? "正在上传…" : "正在处理…" : attempt.phase === "retryUpload" ? "重试上传" : "继续提交"}</button>}
            <span className="secure-note"><span aria-hidden="true">⌑</span> 文件通过短时私有链接直传</span>
          </div>
        </form>
      </section>
      <section className="submission-section">
        <div className="section-heading"><div><p className="eyebrow">版本记录</p><h2>我的提交</h2></div><button className="button button-quiet button-small" onClick={() => void refreshSubmissions()}>刷新</button></div>
        {submissions.length === 0 ? <div className="empty-state compact"><span className="empty-icon">↥</span><h3>还没有提交版本</h3><p>上传文件并通过服务端校验后，正式版本才会出现在这里。</p></div> : <ol className="version-list">{submissions.map((submission) => {
          const grade = submission.currentPublishedGrade;
          return <li key={submission.id} className="version-card">
            <div className="version-header"><div><span className="version-number">版本 {submission.version}</span><span className={submission.late ? "late-badge" : "on-time-badge"}>{submission.late ? "逾期提交" : "按时提交"}</span></div><time dateTime={submission.completedAt}>{formatInTimeZone(submission.completedAt, displayZone)}</time></div>
            {submission.attachment && <div className="attachment-row"><span className="file-type-mark">FILE</span><span><strong>{submission.attachment.fileName}</strong><small>{formatFileSize(submission.attachment.sizeBytes)}</small></span><button className="text-button" type="button" onClick={() => void downloadSubmission(submission)}>下载</button></div>}
            {submission.text && <p className="submission-text">{submission.text}</p>}
            <div className="review-box"><p className="eyebrow">教师反馈</p>{grade ? <><div className="review-score">{grade.score == null ? "已发布" : `得分 ${grade.score}`}</div><p className="review-feedback">{grade.feedback || "暂无文字反馈。"}</p></> : <p className="muted">教师发布批改后，会在这里显示当前反馈。</p>}</div>
          </li>;
        })}</ol>}
      </section>
    </>}
  </div></RoleGate>;
}
