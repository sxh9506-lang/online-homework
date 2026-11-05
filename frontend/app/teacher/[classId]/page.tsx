import { Suspense } from "react";
import TeacherClassClient from "./TeacherClassClient";

interface PageProps { params: Promise<{ classId: string }>; }

async function ClassRoute({ params }: PageProps) {
  const { classId } = await params;
  return <TeacherClassClient classId={classId} />;
}

export default function TeacherClassPage(props: PageProps) {
  return <Suspense fallback={<div className="loading-card">正在打开班级…</div>}><ClassRoute params={props.params} /></Suspense>;
}
