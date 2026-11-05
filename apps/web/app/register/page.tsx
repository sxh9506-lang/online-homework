"use client";

import Link from "next/link";
import { FormEvent, useState } from "react";
import { apiRequest } from "@/lib/api";
import type { RegistrationResult } from "@/lib/types";

export default function RegisterPage() {
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState<RegistrationResult | null>(null);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(""); setBusy(true);
    try {
      const result = await apiRequest<RegistrationResult>("/auth/register", { method: "POST", body: JSON.stringify({ email: email.trim(), password, displayName: displayName.trim() }) });
      setSuccess(result);
    } catch (cause) { setError(cause instanceof Error ? cause.message : "注册未完成，请稍后重试。"); } finally { setBusy(false); }
  }

  return <main className="auth-page"><div className="auth-shell"><Link href="/" className="auth-brand brand"><span className="brand-mark">作</span><span>作业伴</span></Link><div className="auth-card"><div className="auth-heading"><p className="eyebrow">学生入口</p><h1>创建你的作业空间</h1><p>注册后，你可以加入老师创建的班级并查看作业。</p></div>{success ? <div className="success-panel" role="status"><div className="success-icon">✓</div><h2>注册信息已提交</h2><p>{success.verificationRequired === false ? "你的邮箱已验证，可以使用账号登录。" : "请查收邮箱并完成验证，再使用账号登录。"}</p><Link href="/login" className="button button-primary button-wide">前往登录</Link></div> : <form className="auth-form" onSubmit={handleSubmit} noValidate><div className="field"><label htmlFor="displayName">你的称呼</label><input id="displayName" name="displayName" type="text" autoComplete="name" value={displayName} onChange={(event) => setDisplayName(event.target.value)} placeholder="例如：小明" required /></div><div className="field"><label htmlFor="register-email">邮箱地址</label><input id="register-email" name="email" type="email" autoComplete="email" value={email} onChange={(event) => setEmail(event.target.value)} placeholder="name@example.com" required /></div><div className="field"><label htmlFor="register-password">设置密码</label><input id="register-password" name="password" type="password" autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} placeholder="至少 6 位字符" minLength={6} required /></div>{error && <div className="notice error" role="alert">{error}</div>}<button className="button button-primary button-wide" type="submit" disabled={busy}>{busy ? <><span className="spinner" />正在创建…</> : "创建学生账号"}</button><p className="form-disclaimer">注册后可能需要通过邮箱验证。学生账号的具体班级与作业权限由老师或系统分配。</p></form>}<p className="auth-switch">已经有账号？<Link href="/login">直接登录</Link></p></div><p className="auth-footer">返回 <Link href="/">作业伴首页</Link></p></div></main>;
}
