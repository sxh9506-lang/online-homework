import { Suspense } from "react";
import AssignmentEditorClient from "./AssignmentEditorClient";

interface PageProps {
  params: Promise<{ classId: string }>;
  searchParams: Promise<{ assignmentId?: string | string[] }>;
}

async function EditorRoute({ params, searchParams }: PageProps) {
  const [{ classId }, query] = await Promise.all([params, searchParams]);
  const rawId = Array.isArray(query.assignmentId) ? query.assignmentId[0] : query.assignmentId;
  return <AssignmentEditorClient classId={classId} assignmentId={rawId || null} />;
}

export default function AssignmentEditorPage(props: PageProps) {
  return <Suspense fallback={<div className="loading-card">正在打开作业编辑器…</div>}><EditorRoute params={props.params} searchParams={props.searchParams} /></Suspense>;
}
