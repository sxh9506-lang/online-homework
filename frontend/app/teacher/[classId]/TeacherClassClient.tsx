"use client";

import Link from "next/link";
import { FormEvent, useCallback, useEffect, useState } from "react";
import { ApiError, apiRequest } from "@/lib/api";
import type { Assignment, ClassMember, ClassSummary } from "@/lib/types";
import { formatInTimeZone } from "@/lib/time";
import { RoleGate } from "@/components/role-gate";

function errorText(error: unknown) {
  return error instanceof ApiError ? error.message : "请求失败，请稍后重试。";
}

function list<T>(data: T[] | { items: T[] } | { members: T[] } | { assignments: T[] }, key: "members" | "assignments") {
  if (Array.isArray(data)) return data;
  if (key === "members" && "members" in data) return data.members;
  if (key === "assignments" && "assignments" in data) return data.assignments;
  return "items" in data ? data.items : [];
}

export default function TeacherClassPage({ classId }: { classId: string }) {
  const [classInfo, setClassInfo] = useState<ClassSummary | null>(null);
  const [members, setMembers] = useState<ClassMember[]>([]);
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [memberEmail, setMemberEmail] = useState("");
  const [adding, setAdding] = useState(false);
  const [removingId, setRemovingId] = useState<string | null>(null);
  const [notice, setNotice] = useState("");

  const loadClass = useCallback(async () => {
    setError("");
    try {
      const [info, memberData, assignmentData] = await Promise.all([
        apiRequest<ClassSummary>(`/classes/${classId}`),
        apiRequest<ClassMember[] | { members: ClassMember[] }>(`/classes/${classId}/members`),
        apiRequest<Assignment[] | { assignments: Assignment[] }>(`/classes/${classId}/assignments`),
      ]);
      setClassInfo(info);
      setMembers(list(memberData, "members"));
      setAssignments(list(assignmentData, "assignments"));
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setLoading(false);
    }
  }, [classId]);

  useEffect(() => {
    let active = true;
    void (async () => {
      await loadClass();
      if (!active) return;
    })();
    return () => { active = false; };
  }, [loadClass]);

  async function addMember(event: FormEvent) {
    event.preventDefault();
    setAdding(true); setError(""); setNotice("");
    try {
      const member = await apiRequest<ClassMember>(`/classes/${classId}/members`, {
        method: "POST",
        body: JSON.stringify({ email: memberEmail.trim() }),
      });
      setMembers((items) => [...items, member]);
      setMemberEmail("");
      setNotice("学生已加入班级。");
    } catch (cause) {
      if (cause instanceof ApiError && (cause.code === "STUDENT_ACCOUNT_NOT_FOUND" || cause.code === "STUDENT_NOT_REGISTERED")) {
        setError("未找到已注册的学生账号，请先让学生完成注册后再按邮箱添加。此处不支持邀请或入班码。");
      } else setError(errorText(cause));
    } finally { setAdding(false); }
  }

  async function removeMember(member: ClassMember) {
    if (!window.confirm(`确定要移除 ${member.displayName} 吗？历史提交会保留，但该学生将立即失去班级内容访问权。`)) return;
    setRemovingId(member.userId); setError(""); setNotice("");
    try {
      await apiRequest<void>(`/classes/${classId}/members/${member.userId}`, { method: "DELETE" });
      setMembers((items) => items.filter((item) => item.userId !== member.userId));
      setNotice(`${member.displayName} 已移出班级。`);
    } catch (cause) { setError(errorText(cause)); }
    finally { setRemovingId(null); }
  }

  return <RoleGate role="TEACHER"><div className="page-wrap workspace-page">
    {loading ? <div className="loading-card"><span className="spinner" />正在加载班级…</div> : <>
      <header className="page-heading">
        <div><Link href="/teacher" className="back-link">← 返回班级</Link><p className="eyebrow">教师工作台 · 班级</p><h1>{classInfo?.name || "班级详情"}</h1><p className="muted">{classInfo?.description || "暂无描述"} · 截止时间按 {classInfo?.timeZone || "UTC"} 展示</p></div>
        <Link href={`/teacher/${classId}/assignments/new`} className="button button-primary">新建作业</Link>
      </header>
      {error && <div className="notice error" role="alert">{error}</div>}
      {notice && <div className="notice success" role="status">{notice}</div>}
      <div className="teacher-columns">
        <section className="panel" aria-labelledby="members-title">
          <div className="section-heading"><div><p className="eyebrow">成员管理</p><h2 id="members-title">学生名单</h2></div><span className="summary-chip">{members.length} 人</span></div>
          <p className="muted">按学生已注册的登录邮箱精确添加。服务器会再次校验教师的班级所有权。</p>
          <form onSubmit={addMember} className="member-form"><label className="sr-only" htmlFor="member-email">学生登录邮箱</label><input id="member-email" type="email" required value={memberEmail} onChange={(event) => setMemberEmail(event.target.value)} placeholder="student@example.com"/><button className="button button-primary button-small" disabled={adding}>{adding ? "添加中…" : "添加学生"}</button></form>
          {members.length ? <ul className="member-list">{members.map((member) => <li key={member.userId}><div><strong>{member.displayName}</strong><small>{member.email || "未提供邮箱"}</small></div><button type="button" className="text-button danger-text" disabled={removingId === member.userId} onClick={() => void removeMember(member)}>{removingId === member.userId ? "移除中…" : "移除"}</button></li>)}</ul> : <div className="empty-state compact"><span className="empty-icon">＋</span><h3>还没有学生</h3><p>学生注册后，用其登录邮箱添加到本班。</p></div>}
        </section>
        <section className="panel" aria-labelledby="assignments-title">
          <div className="section-heading"><div><p className="eyebrow">教学内容</p><h2 id="assignments-title">作业</h2></div><span className="summary-chip">{assignments.length} 项</span></div>
          {assignments.length ? <ul className="teacher-assignment-list">{assignments.map((assignment) => <li key={assignment.id}><div><div className="assignment-card-top"><strong>{assignment.title}</strong><span className={assignment.status === "PUBLISHED" ? "status-dot" : "quiet-tag"}>{assignment.status === "PUBLISHED" ? "已发布" : "草稿"}</span></div><p className="muted">截止：{formatInTimeZone(assignment.dueAt, classInfo?.timeZone || "UTC")}{assignment.closeAfterDeadline ? " · 截止后关闭" : ""}</p></div><div className="inline-actions"><Link className="text-button" href={`/teacher/assignments/${assignment.id}`}>查看提交</Link>{assignment.status === "DRAFT" && <Link className="text-button" href={`/teacher/${classId}/assignments/new?assignmentId=${assignment.id}`}>编辑草稿</Link>}</div></li>)}</ul> : <div className="empty-state compact"><span className="empty-icon">✦</span><h3>还没有作业</h3><p>创建草稿，确认内容后再发布给本班学生。</p></div>}
        </section>
      </div>
    </>}
  </div></RoleGate>;
}
