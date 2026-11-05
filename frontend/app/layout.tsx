import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "作业伴｜让作业成为更好的连接",
  description: "作业伴帮助老师和学生更清晰地完成作业布置、提交与反馈。",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="zh-CN"><body>{children}</body></html>;
}
