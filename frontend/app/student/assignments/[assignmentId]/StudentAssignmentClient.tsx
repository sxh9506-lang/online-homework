"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { RoleGate } from "@/components/role-gate";
import { AttachmentUnavailableNotice, UnavailableAttachmentAction } from "@/components/attachment-unavailable";
import { ApiError, apiRequest } from "@/lib/api";
import { formatFileSize } from "@/lib/files";
import { formatInTimeZone } from "@/lib/time";
import type { Assignment, Submission } from "@/lib/types";

type SubmissionList = Submission[] | { submissions: Submission[] } | { items: Submission[] };

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
  const [loading, setLoading] = useState(true);
  const [refreshCount, setRefreshCount] = useState(0);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    Promise.all([
      apiRequest<Assignment>(`/assignments/${assignmentId}`),
      apiRequest<SubmissionList>(`/assignments/${assignmentId}/my-submissions`),
    ]).then(([assignmentData, submissionData]) => {
      const records = unwrapSubmissions(submissionData).sort((a, b) => b.version - a.version);
      if (!active) return;
      setAssignment(assignmentData);
      setSubmissions(records);
    }).catch((cause) => { if (active) setError(explain(cause)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [assignmentId, refreshCount]);

  function refreshSubmissions() {
    setError("");
    setRefreshCount((count) => count + 1);
  }

  const displayZone = timeZone || "UTC";

  return <RoleGate role="STUDENT"><div className="mx-auto max-w-5xl space-y-7 p-6">
    <header className="page-heading">
      <div><Link href="/student" className="back-link">← 返回我的作业</Link><p className="eyebrow">{className || "班级作业"} · {displayZone}</p><h1>{assignment?.title || "作业详情"}</h1><p className="muted">{assignment?.instructions || "教师尚未添加详细说明。"}</p></div>
    </header>
    {error && <div className="notice error" role="alert">{error}</div>}
    {loading ? <div className="loading-card"><span className="spinner" />正在加载作业详情…</div> : <>
      <section className="deadline-card">
        <div><p className="eyebrow">提交截止</p><h2>{formatInTimeZone(assignment?.dueAt, displayZone)}</h2><p className="timezone-caption">按班级时区展示（{displayZone}）；相关逾期判定为产品规则说明，首发暂不开放附件提交。</p></div>
        <div className="deadline-side"><span className="deadline-icon">◷</span><p>{assignment?.closeAfterDeadline ? "规则设定：截止后关闭提交" : "规则设定：截止后仍允许迟交并标记逾期"} · 首发暂不开放附件上传</p></div>
      </section>
      {assignment?.attachment && <div className="attachment-row"><span className="file-type-mark">FILE</span><span><strong>{assignment.attachment.fileName}</strong><small>{formatFileSize(assignment.attachment.sizeBytes)} · 教师附加资料</small></span><UnavailableAttachmentAction label="下载资料暂不可用" /></div>}
      <section className="upload-card">
        <div className="section-heading"><div><p className="eyebrow">提交作业</p><h2>首发暂不开放附件提交</h2></div><span className="quiet-tag">演示版限制</span></div>
        <AttachmentUnavailableNotice
          title="附件上传暂不可用"
          description="当前免费演示版使用虚构数据且未接入附件存储，因此不能选择或上传作业文件。请勿尝试提交真实作业文件。"
        />
      </section>
      <section className="submission-section">
        <div className="section-heading"><div><p className="eyebrow">版本记录</p><h2>我的提交</h2></div><button className="button button-quiet button-small" onClick={() => void refreshSubmissions()}>刷新</button></div>
        {submissions.length === 0 ? <div className="empty-state compact"><span className="empty-icon">↥</span><h3>还没有提交版本</h3><p>演示版的附件提交暂不可用；已存在的历史记录仍会显示在这里。</p></div> : <ol className="version-list">{submissions.map((submission) => {
          const grade = submission.currentPublishedGrade;
          return <li key={submission.id} className="version-card">
            <div className="version-header"><div><span className="version-number">版本 {submission.version}</span><span className={submission.late ? "late-badge" : "on-time-badge"}>{submission.late ? "逾期提交" : "按时提交"}</span></div><time dateTime={submission.completedAt}>{formatInTimeZone(submission.completedAt, displayZone)}</time></div>
            {submission.attachment && <div className="attachment-row"><span className="file-type-mark">FILE</span><span><strong>{submission.attachment.fileName}</strong><small>{formatFileSize(submission.attachment.sizeBytes)}</small></span><UnavailableAttachmentAction label="下载暂不可用" /></div>}
            {submission.text && <p className="submission-text">{submission.text}</p>}
            <div className="review-box"><p className="eyebrow">教师反馈</p>{grade ? <><div className="review-score">{grade.score == null ? "已发布" : `得分 ${grade.score}`}</div><p className="review-feedback">{grade.feedback || "暂无文字反馈。"}</p></> : <p className="muted">教师发布批改后，会在这里显示当前反馈。</p>}</div>
          </li>;
        })}</ol>}
      </section>
    </>}
  </div></RoleGate>;
}
