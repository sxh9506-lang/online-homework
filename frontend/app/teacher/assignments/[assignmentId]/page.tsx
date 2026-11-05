import { Suspense } from "react";
import TeacherAssignmentClient from "./TeacherAssignmentClient";

interface PageProps { params: Promise<{ assignmentId: string }>; }

async function AssignmentRoute({ params }: PageProps) {
  const { assignmentId } = await params;
  return <TeacherAssignmentClient assignmentId={assignmentId} />;
}

export default function TeacherAssignmentPage(props: PageProps) {
  return <Suspense fallback={<div className="loading-card">正在打开作业…</div>}><AssignmentRoute params={props.params} /></Suspense>;
}
