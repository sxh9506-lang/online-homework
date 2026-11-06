"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { RoleGate } from "@/components/role-gate";
import { ApiError, apiRequest } from "@/lib/api";
import { formatInTimeZone } from "@/lib/time";
import type { Assignment, ClassSummary } from "@/lib/types";

type ClassList = ClassSummary[] | { classes: ClassSummary[] };
type AssignmentList = Assignment[] | { assignments: Assignment[] } | { items: Assignment[] };
interface ClassWork { classInfo: ClassSummary; assignments: Assignment[]; }

function unwrapClasses(data: ClassList) { return Array.isArray(data) ? data : data.classes; }
function unwrapAssignments(data: AssignmentList) {
  if (Array.isArray(data)) return data;
  return "assignments" in data ? data.assignments : data.items;
}

export default function StudentHomePage() {
  const [work, setWork] = useState<ClassWork[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    async function load() {
      try {
        const classData = await apiRequest<ClassList>("/classes");
        const classes = unwrapClasses(classData);
        const result = await Promise.all(classes.map(async (classInfo) => {
          const data = await apiRequest<AssignmentList>(`/classes/${classInfo.id}/assignments`);
          return { classInfo, assignments: unwrapAssignments(data).filter((item) => item.status === "PUBLISHED") };
        }));
        if (active) setWork(result.filter((item) => item.assignments.length > 0));
      } catch (cause) {
        if (active) setError(cause instanceof ApiError ? cause.message : "暂时无法加载作业，请稍后重试。");
      } finally {
        if (active) setLoading(false);
      }
    }
    void load();
    return () => { active = false; };
  }, []);

  const assignmentCount = work.reduce((count, item) => count + item.assignments.length, 0);

  return <RoleGate role="STUDENT"><div className="mx-auto max-w-6xl space-y-8 p-6">
    <header className="page-heading">
      <div><p className="eyebrow">学生工作台</p><h1>我的作业</h1><p className="muted">查看班级已发布的作业、提交版本与教师反馈。</p></div>
      <div className="summary-chip"><strong>{assignmentCount}</strong><span>项待查看</span></div>
    </header>
    {error && <div className="notice error" role="alert">{error}</div>}
    {loading ? <div className="loading-card"><span className="spinner" />正在加载班级作业…</div> : work.length === 0 ? <section className="empty-state"><span className="empty-icon">✳</span><h2>暂时没有已发布的作业</h2><p>教师发布新作业后，会显示在这里。</p></section> : <div className="work-stack">
      {work.map(({ classInfo, assignments }) => <section key={classInfo.id} className="work-section">
        <div className="section-heading"><div><p className="eyebrow">班级</p><h2>{classInfo.name}</h2></div><span className="timezone-pill">{classInfo.timeZone}</span></div>
        <ul className="assignment-grid">{assignments.map((assignment) => <li key={assignment.id} className="assignment-card">
          <div className="assignment-card-top"><span className="status-dot">已发布</span>{assignment.attachment && <span className="quiet-tag">附件暂不可用</span>}{assignment.closeAfterDeadline && <span className="quiet-tag">截止后关闭</span>}</div>
          <h3>{assignment.title}</h3>
          <p className="assignment-description">{assignment.instructions || "教师尚未添加详细说明。"}</p>
          <div className="assignment-meta"><span>截止</span><strong>{formatInTimeZone(assignment.dueAt, classInfo.timeZone)}</strong></div>
          <p className="timezone-caption">按班级时区展示 · 逾期状态以服务端校验结果为准</p>
          <Link className="button button-primary button-small" href={`/student/assignments/${assignment.id}?timeZone=${encodeURIComponent(classInfo.timeZone)}&className=${encodeURIComponent(classInfo.name)}`}>查看作业详情 <span aria-hidden="true">→</span></Link>
        </li>)}</ul>
      </section>)}
    </div>}
  </div></RoleGate>;
}
