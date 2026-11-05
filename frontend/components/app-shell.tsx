"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import type { ReactNode } from "react";
import { apiRequest, setAccessToken } from "@/lib/api";
import type { CurrentUser } from "@/lib/types";

export function AppShell({ user, children }: { user: CurrentUser; children: ReactNode }) {
  const router = useRouter();
  const isTeacher = user.role === "TEACHER";
  const home = isTeacher ? "/teacher" : "/student";

  async function signOut() {
    try { await apiRequest<void>("/auth/logout", { method: "POST" }); } catch { /* clear local session either way */ }
    setAccessToken(null);
    router.replace("/login");
  }

  return (
    <div className="app-frame">
      <header className="topbar">
        <Link href={home} className="brand" aria-label="作业伴首页">
          <span className="brand-mark">作</span>
          <span>作业伴</span>
        </Link>
        <nav className="main-nav" aria-label="主导航">
          {isTeacher ? <Link href="/teacher">我的班级</Link> : <Link href="/student">我的作业</Link>}
        </nav>
        <div className="user-area">
          <div className="user-chip">
            <span className="avatar">{(user.displayName || user.email || "用").slice(0, 1).toUpperCase()}</span>
            <span className="user-label">{user.displayName || user.email || (isTeacher ? "教师" : "学生")}</span>
          </div>
          <button className="button button-quiet button-small" onClick={signOut}>退出</button>
        </div>
      </header>
      <main className="app-main">{children}</main>
      <footer className="footer-note">在线作业 · 文件仅对有权限的班级成员开放</footer>
    </div>
  );
}
