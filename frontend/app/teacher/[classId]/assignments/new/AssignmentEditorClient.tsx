"use client";

import Link from "next/link";
import { FormEvent, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { RoleGate } from "@/components/role-gate";
import { AttachmentUnavailableNotice, UnavailableAttachmentAction } from "@/components/attachment-unavailable";
import { ApiError, apiRequest } from "@/lib/api";
import { formatFileSize } from "@/lib/files";
import { formatInTimeZone, localDateTimeToUtc, utcToLocalDateTime } from "@/lib/time";
import type { Assignment, ClassSummary } from "@/lib/types";

function explain(error: unknown) { return error instanceof ApiError ? error.message : "请求失败，请稍后重试。"; }

export default function AssignmentEditorClient({ classId, assignmentId }: { classId: string; assignmentId: string | null }) {
  const router = useRouter();
  const [classInfo, setClassInfo] = useState<ClassSummary | null>(null);
  const [assignment, setAssignment] = useState<Assignment | null>(null);
  const [title, setTitle] = useState("");
  const [instructions, setInstructions] = useState("");
  const [dueAt, setDueAt] = useState("");
  const [closeAfterDeadline, setCloseAfterDeadline] = useState(false);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
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

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(""); setNotice("");
    if (!title.trim()) { setError("请填写作业标题。"); return; }
    if (assignment && assignment.status !== "DRAFT") { setError("作业发布后不能修改核心内容。"); return; }
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
      setNotice("草稿已保存。附件功能在免费演示版中暂不可用。");
      if (!assignment) router.replace(`/teacher/${classId}/assignments/new?assignmentId=${draft.id}`);
    } catch (cause) { setError(explain(cause)); } finally { setSaving(false); }
  }

  async function publish() {
    if (!assignment || assignment.status !== "DRAFT") return;
    setPublishing(true); setError(""); setNotice("");
    try {
      const published = await apiRequest<Assignment>(`/assignments/${assignment.id}/publish`, { method: "POST" });
      setAssignment(published); setNotice("作业已发布，班级学生现在可以查看。附件功能仍暂不可用。");
    } catch (cause) { setError(explain(cause)); } finally { setPublishing(false); }
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
        <p className="text-xs text-slate-500">产品规划中的截止规则：完成文件校验时刻决定逾期，完成时间等于截止时间仍按时；首发暂不开放附件上传。</p>
        <label className="flex items-center gap-2"><input type="checkbox" checked={closeAfterDeadline} onChange={(event) => setCloseAfterDeadline(event.target.checked)} disabled={!canEdit} />截止后关闭提交</label>
        {assignment?.attachment ? <div className="attachment-row"><span className="file-type-mark">FILE</span><span><strong>{assignment.attachment.fileName}</strong><small>{formatFileSize(assignment.attachment.sizeBytes)} · 当前演示版不提供附件服务</small></span><UnavailableAttachmentAction label="下载附件暂不可用" /></div> : <AttachmentUnavailableNotice
          compact
          title="首发暂不开放作业附件上传"
          description="免费演示版未接入附件存储，上传与文件校验已关闭；请仅使用虚构数据。"
        />}
        <div className="flex flex-wrap gap-3"><button disabled={saving || publishing || !canEdit} className="rounded-md bg-slate-900 px-4 py-2 text-white disabled:opacity-50">{saving ? "保存中…" : "保存草稿"}</button>{assignment?.status === "DRAFT" && <button type="button" disabled={saving || publishing} onClick={publish} className="rounded-md border border-slate-900 px-4 py-2 disabled:opacity-50">{publishing ? "发布中…" : "发布作业"}</button>}</div>
      </form>
      {assignment?.dueAt && <p className="text-sm text-slate-500">当前截止时间：{formatInTimeZone(assignment.dueAt, classInfo?.timeZone || "UTC")}</p>}
    </>}
  </div></RoleGate>;
}
