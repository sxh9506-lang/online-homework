"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiError, apiRequest } from "@/lib/api";
import type { CurrentUser, Role } from "@/lib/types";
import { AppShell } from "@/components/app-shell";

export function RoleGate({ role, children }: { role: Role; children: React.ReactNode }) {
  const router = useRouter();
  const [user, setUser] = useState<CurrentUser | null>(null);
  const [problem, setProblem] = useState("");

  useEffect(() => {
    let active = true;
    apiRequest<CurrentUser>("/me")
      .then((current) => {
        if (!active) return;
        if (current.role !== role) {
          router.replace(current.role === "TEACHER" ? "/teacher" : "/student");
          return;
        }
        setUser(current);
      })
      .catch((cause) => {
        if (!active) return;
        if (cause instanceof ApiError && cause.status === 401) router.replace("/login");
        else setProblem(cause instanceof Error ? cause.message : "无法确认账号权限，请重试。");
      });
    return () => { active = false; };
  }, [role, router]);

  if (problem) return <main className="page-wrap"><div className="notice error" role="alert">{problem}</div><button className="button button-secondary" onClick={() => window.location.reload()}>重新加载</button></main>;
  if (!user) return <main className="page-wrap"><div className="loading-card"><span className="spinner" />正在确认账号权限…</div></main>;
  return <AppShell user={user}>{children}</AppShell>;
}
