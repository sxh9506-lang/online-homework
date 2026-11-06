"use client";

import Link from "next/link";
import { FormEvent, useEffect, useState } from "react";
import { RoleGate } from "@/components/role-gate";
import { UnavailableAttachmentAction } from "@/components/attachment-unavailable";
import { ApiError, apiRequest } from "@/lib/api";
import { formatFileSize } from "@/lib/files";
import { formatInTimeZone } from "@/lib/time";
import type { Assignment, ClassSummary, Grade, Submission } from "@/lib/types";

type SubmissionList = Submission[] | { submissions: Submission[] };
type GradeList = Grade[] | { grades: Grade[] };

function unpackSubmissions(data: SubmissionList) { return Array.isArray(data) ? data : data.submissions; }
function unpackGrades(data: GradeList) { return Array.isArray(data) ? data : data.grades; }
function explain(error: unknown) { return error instanceof ApiError ? error.message : "请求失败，请稍后重试。"; }
function studentLabel(submission: Submission) {
  return submission.studentName || submission.studentEmail || `学生 ${submission.studentUserId?.slice(0, 8) || ""}`;
}

export default function TeacherAssignmentClient({ assignmentId }: { assignmentId: string }) {
  const [assignment, setAssignment] = useState<Assignment | null>(null);
  const [timeZone, setTimeZone] = useState("UTC");
  const [submissions, setSubmissions] = useState<Submission[]>([]);
  const [selected, setSelected] = useState<Submission | null>(null);
  const [history, setHistory] = useState<Grade[]>([]);
  const [score, setScore] = useState("");
  const [feedback, setFeedback] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  useEffect(() => {
    let active = true;
    apiRequest<Assignment>(`/assignments/${assignmentId}`).then(async (info) => {
      const [classInfo, submissionData] = await Promise.all([
        apiRequest<ClassSummary>(`/classes/${info.classId}`),
        apiRequest<SubmissionList>(`/assignments/${assignmentId}/submissions`),
      ]);
      if (!active) return;
      setAssignment(info);
      setTimeZone(classInfo.timeZone);
      setSubmissions(unpackSubmissions(submissionData));
    }).catch((cause) => { if (active) setError(explain(cause)); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [assignmentId]);

  async function openGrading(submission: Submission) {
    setSelected(submission); setError(""); setNotice(""); setHistory([]);
    setScore(submission.currentPublishedGrade?.score?.toString() || "");
    setFeedback(submission.currentPublishedGrade?.feedback || "");
    try {
      const data = await apiRequest<GradeList>(`/submissions/${submission.id}/grades`);
      setHistory(unpackGrades(data));
    } catch (cause) { setError(explain(cause)); }
  }

  async function saveGrade(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected) return;
    setSaving(true); setError(""); setNotice("");
    try {
      const grade = await apiRequest<Grade>(`/submissions/${selected.id}/grades`, {
        method: "POST",
        body: JSON.stringify({ score: score === "" ? null : Number(score), feedback }),
      });
      setHistory((current) => [...current.filter((item) => item.id !== grade.id), grade]);
      setNotice("批改草稿已保存，仅教师可见；发布后学生才能看到。");
    } catch (cause) { setError(explain(cause)); } finally { setSaving(false); }
  }

  async function publishGrade(grade: Grade) {
    setPublishing(true); setError(""); setNotice("");
    try {
      const published = await apiRequest<Grade>(`/grades/${grade.id}/publish`, { method: "POST" });
      setHistory((current) => current.map((item) => item.id === published.id ? published : item));
      setSelected((current) => current ? { ...current, currentPublishedGrade: published } : current);
      setNotice("批改已发布；学生只会看到当前最新发布结果。");
    } catch (cause) { setError(explain(cause)); } finally { setPublishing(false); }
  }

  return <RoleGate role="TEACHER"><div className="mx-auto max-w-6xl space-y-6 p-6">
    {loading ? <div className="loading-card"><span className="spinner" />正在加载提交…</div> : <>
      <header><Link href="/teacher" className="text-sm text-slate-500 hover:underline">← 返回教师首页</Link><h1 className="mt-2 text-3xl font-bold">{assignment?.title || "作业提交"}</h1><p className="mt-1 text-sm text-slate-500">截止：{formatInTimeZone(assignment?.dueAt, timeZone)} · {timeZone}</p>
        {assignment?.attachment && <div className="attachment-row mt-4 max-w-xl"><span className="file-type-mark">FILE</span><span><strong>{assignment.attachment.fileName}</strong><small>{formatFileSize(assignment.attachment.sizeBytes)} · 作业附件</small></span><UnavailableAttachmentAction label="下载资料暂不可用" /></div>}
      </header>
      {error && <p role="alert" className="rounded-md bg-red-50 p-3 text-red-700">{error}</p>}
      {notice && <p role="status" className="rounded-md bg-green-50 p-3 text-green-700">{notice}</p>}
      <div className="grid gap-6 lg:grid-cols-[1fr_1.1fr]">
        <section className="rounded-xl border p-5"><h2 className="text-xl font-semibold">学生提交 <span className="text-sm font-normal text-slate-500">({submissions.length})</span></h2>
          {submissions.length ? <ul className="mt-4 divide-y">{submissions.map((submission) => <li key={submission.id} className="py-4"><button type="button" onClick={() => void openGrading(submission)} className="w-full text-left hover:bg-slate-50"><div className="flex items-center justify-between gap-2"><span className="font-medium">{studentLabel(submission)}</span><span className={`text-xs ${submission.late ? "text-red-700" : "text-slate-500"}`}>{submission.late ? "逾期提交" : "按时提交"}</span></div><p className="mt-1 text-sm text-slate-500">版本 {submission.version} · {formatInTimeZone(submission.completedAt, timeZone)}</p></button></li>)}</ul> : <p className="mt-4 rounded-md bg-slate-50 p-5 text-slate-500">暂无学生提交。</p>}
        </section>
        <section className="rounded-xl border p-5"><h2 className="text-xl font-semibold">批改</h2>
          {selected ? <>
            <p className="mt-2 text-sm text-slate-600">{studentLabel(selected)} · 版本 {selected.version}</p>
            {selected.attachment && <div className="attachment-row mt-4"><span className="file-type-mark">FILE</span><span><strong>{selected.attachment.fileName}</strong><small>{formatFileSize(selected.attachment.sizeBytes)}</small></span><UnavailableAttachmentAction label="下载提交暂不可用" /></div>}
            {selected.text && <div className="mt-4 whitespace-pre-wrap rounded-md bg-slate-50 p-4 text-sm">{selected.text}</div>}
            <form onSubmit={saveGrade} className="mt-5 space-y-4"><label className="grid gap-1 font-medium">分数<input type="number" min="0" step="0.01" value={score} onChange={(event) => setScore(event.target.value)} className="rounded-md border p-2 font-normal" /></label><label className="grid gap-1 font-medium">反馈<textarea rows={5} value={feedback} onChange={(event) => setFeedback(event.target.value)} className="rounded-md border p-2 font-normal" /></label><button disabled={saving || publishing} className="rounded-md bg-slate-900 px-4 py-2 text-white disabled:opacity-50">{saving ? "保存中…" : "保存批改草稿"}</button></form>
            <div className="mt-6"><h3 className="font-semibold">批改历史</h3>{history.length ? <ul className="mt-2 space-y-3">{history.map((grade) => <li key={grade.id} className="rounded-md border p-3"><div className="flex justify-between"><span>第 {grade.revision} 版 · {grade.status === "PUBLISHED" ? "已发布" : "草稿"}</span><span>{grade.score ?? "未评分"}</span></div><p className="mt-2 whitespace-pre-wrap text-sm text-slate-600">{grade.feedback || "无反馈"}</p>{grade.status === "DRAFT" && <button type="button" disabled={publishing} onClick={() => void publishGrade(grade)} className="mt-3 rounded-md border px-3 py-1 text-sm">{publishing ? "发布中…" : "发布批改"}</button>}</li>)}</ul> : <p className="mt-2 text-sm text-slate-500">暂无批改历史。</p>}</div>
          </> : <p className="mt-4 rounded-md bg-slate-50 p-5 text-slate-500">请选择一条学生提交开始批改。</p>}
        </section>
      </div>
    </>}
  </div></RoleGate>;
}
