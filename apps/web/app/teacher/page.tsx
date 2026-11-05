"use client";

import Link from "next/link";
import { FormEvent, useEffect, useState } from "react";
import { ApiError, apiRequest } from "@/lib/api";
import type { ClassSummary } from "@/lib/types";
import { RoleGate } from "@/components/role-gate";

function messageFor(error: unknown) {
  return error instanceof ApiError ? error.message : "操作失败，请稍后重试。";
}

export default function TeacherHomePage() {
  const [classes, setClasses] = useState<ClassSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const [success, setSuccess] = useState("");

  useEffect(() => {
    apiRequest<ClassSummary[] | { classes: ClassSummary[] }>("/classes")
      .then((data) => setClasses(Array.isArray(data) ? data : data.classes))
      .catch((e) => setError(messageFor(e)))
      .finally(() => setLoading(false));
  }, []);

  async function createClass(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(""); setSuccess(""); setSaving(true);
    const form = new FormData(event.currentTarget);
    const name = String(form.get("name") || "").trim();
    const description = String(form.get("description") || "").trim();
    const timeZone = String(form.get("timeZone") || "").trim();
    if (!name || !timeZone) { setError("请填写班级名称和 IANA 时区，例如 Asia/Shanghai。"); setSaving(false); return; }
    try {
      const created = await apiRequest<ClassSummary>("/classes", { method: "POST", body: JSON.stringify({ name, description, timeZone }) });
      setClasses((current) => [...current, created]); setSuccess("班级已创建。"); event.currentTarget.reset();
    } catch (e) { setError(messageFor(e)); } finally { setSaving(false); }
  }

  return <RoleGate role="TEACHER"><div className="mx-auto max-w-5xl space-y-8 p-6">
    <header><p className="text-sm font-medium text-slate-500">教师工作台</p><h1 className="mt-1 text-3xl font-bold">我的班级</h1></header>
    <section aria-labelledby="create-class" className="rounded-xl border border-slate-200 bg-white p-6 shadow-sm">
      <h2 id="create-class" className="text-xl font-semibold">新建班级</h2>
      <form onSubmit={createClass} className="mt-4 grid gap-4 md:grid-cols-2">
        <label className="grid gap-1 text-sm font-medium">班级名称<input name="name" required className="rounded-md border p-2 font-normal" placeholder="例如：高一数学 1 班" /></label>
        <label className="grid gap-1 text-sm font-medium">IANA 时区<input name="timeZone" required className="rounded-md border p-2 font-normal" placeholder="例如：Asia/Shanghai" /></label>
        <label className="grid gap-1 text-sm font-medium md:col-span-2">描述<textarea name="description" rows={2} className="rounded-md border p-2 font-normal" /></label>
        <button disabled={saving} className="rounded-md bg-slate-900 px-4 py-2 text-white disabled:opacity-50 md:w-fit">{saving ? "创建中…" : "创建班级"}</button>
      </form>
    </section>
    {error && <p role="alert" className="rounded-md bg-red-50 p-3 text-red-700">{error}</p>}
    {success && <p role="status" className="rounded-md bg-green-50 p-3 text-green-700">{success}</p>}
    <section aria-labelledby="class-list"><h2 id="class-list" className="text-xl font-semibold">班级列表</h2>
      {loading ? <p className="mt-4 text-slate-500">正在加载班级…</p> : classes.length === 0 ? <p className="mt-4 rounded-lg border border-dashed p-8 text-center text-slate-500">还没有班级，先创建一个班级吧。</p> : <ul className="mt-4 grid gap-4 md:grid-cols-2">{classes.map((item) => <li key={item.id} className="rounded-xl border p-5"><Link href={`/teacher/${item.id}`} className="text-lg font-semibold underline-offset-4 hover:underline">{item.name}</Link><p className="mt-2 text-sm text-slate-600">{item.description || "暂无描述"}</p><p className="mt-3 text-xs text-slate-500">班级时区：{item.timeZone}</p></li>)}</ul>}
    </section>
  </div></RoleGate>;
}
