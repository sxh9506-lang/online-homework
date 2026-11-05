import { Suspense } from "react";
import { StudentAssignmentClient } from "./StudentAssignmentClient";

interface PageProps {
  params: Promise<{ assignmentId: string }>;
  searchParams: Promise<{ timeZone?: string | string[]; className?: string | string[] }>;
}

async function StudentAssignmentRoute({ params, searchParams }: PageProps) {
  const [{ assignmentId }, query] = await Promise.all([params, searchParams]);
  const timeZone = Array.isArray(query.timeZone) ? query.timeZone[0] : query.timeZone;
  const className = Array.isArray(query.className) ? query.className[0] : query.className;
  return <StudentAssignmentClient assignmentId={assignmentId} timeZone={timeZone || "UTC"} className={className} />;
}

export default function StudentAssignmentPage(props: PageProps) {
  return <Suspense fallback={<div className="loading-card">正在打开作业…</div>}><StudentAssignmentRoute params={props.params} searchParams={props.searchParams} /></Suspense>;
}
