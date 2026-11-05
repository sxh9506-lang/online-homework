"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { apiRequest, setAccessToken } from "@/lib/api";
import type { CurrentUser } from "@/lib/types";

interface LoginResponse { accessToken?: string; }

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setBusy(true);
    try {
      const result = await apiRequest<LoginResponse>("/auth/login", { method: "POST", body: JSON.stringify({ email: email.trim(), password }) });
      if (result.accessToken) setAccessToken(result.accessToken);
      const user = await apiRequest<CurrentUser>("/me");
      router.replace(user.role === "TEACHER" ? "/teacher" : "/student");
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "登录未完成，请稍后重试。");
    } finally { setBusy(false); }
  }

  return <main className="auth-page"><div className="auth-shell"><Link href="/" className="auth-brand brand"><span className="brand-mark">作</span><span>作业伴</span></Link><div className="auth-card"><div className="auth-heading"><p className="eyebrow">欢迎回来</p><h1>登录你的作业空间</h1><p>继续查看课程、作业与最新反馈。</p></div><form className="auth-form" onSubmit={handleSubmit} noValidate><div className="field"><label htmlFor="email">邮箱地址</label><input id="email" name="email" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} placeholder="name@example.com" required /></div><div className="field"><div className="field-label-row"><label htmlFor="password">密码</label><span className="field-hint">请使用注册时的密码</span></div><input id="password" name="password" type="password" autoComplete="current-password" value={password} onChange={(event) => setPassword(event.target.value)} placeholder="请输入密码" minLength={6} required /></div>{error && <div className="notice error" role="alert">{error}</div>}<button className="button button-primary button-wide" type="submit" disabled={busy}>{busy ? <><span className="spinner" />正在登录…</> : "登录"}</button></form><p className="auth-switch">还没有账号？<Link href="/register">注册学生账号</Link></p></div><p className="auth-footer">返回 <Link href="/">作业伴首页</Link></p></div></main>;
}
