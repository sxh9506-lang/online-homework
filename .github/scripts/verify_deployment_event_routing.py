#!/usr/bin/env python3
"""Validate the single merged-PR deployment route and its no-side-effect matrix."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

WORKFLOW = Path(".github/workflows/deploy-test.yml")
CI_WORKFLOW = Path(".github/workflows/pr-checks.yml")
ROUTER = Path(".github/scripts/deployment_event_router.js")
TEST = Path(".github/scripts/test_deployment_event_router.js")


def job_block(source: str, name: str) -> str:
    match = re.search(rf"^  {re.escape(name)}:\n", source, re.MULTILINE)
    if not match:
        raise ValueError(f"Missing job: {name}")
    tail = source[match.start():]
    after = match.end() - match.start()
    next_job = re.search(r"^  [A-Za-z0-9_-]+:\n", tail[after:], re.MULTILINE)
    return tail if next_job is None else tail[:after + next_job.start()]


def main() -> int:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    ci = CI_WORKFLOW.read_text(encoding="utf-8")
    router = ROUTER.read_text(encoding="utf-8")
    workflow_dir = WORKFLOW.parent
    deploy_files = sorted(path.name for path in workflow_dir.glob("deploy*.y*ml"))
    if deploy_files != [WORKFLOW.name]:
        raise ValueError(f"Only one deploy workflow may exist to prevent duplicate routing: {deploy_files}")
    if not re.search(r"^on:\n  pull_request_target:\n    types: \[closed\]\n    branches: \[main\]", workflow, re.MULTILINE):
        raise ValueError("Deployment must only listen to pull_request_target closed events for main.")
    if re.search(r"^\s+push:", workflow, re.MULTILINE):
        raise ValueError("Deployment workflow must not have a push-triggered route.")
    if "pull-requests: read" not in workflow or "deployments: write" not in workflow:
        raise ValueError("Workflow permissions must be limited to PR metadata read and deployment write.")

    changes = " ".join(job_block(workflow, "changes").split())
    if "github.event.pull_request.merged == true" not in changes or "github.event.pull_request.number != 8" not in changes:
        raise ValueError("Unmerged PRs and PR #8 must be excluded before file metadata lookup.")
    if changes.index("github.event.pull_request.number != 8") > changes.index("github.rest.pulls.listFiles"):
        raise ValueError("PR #8 exclusion must occur before the listFiles API call.")
    if "github.rest.pulls.listFiles" not in changes or "selection = { backend: true, frontend: true }" not in changes:
        raise ValueError("Other merged PRs need file metadata and an all-components API-failure fallback.")
    if changes.index("selection = { backend: true, frontend: true }") < changes.index("catch (error)"):
        raise ValueError("All-components fallback must be inside the API error handler.")
    if "backend: names.some((name) => name.startsWith('backend/'))" not in changes:
        raise ValueError("Backend deployment must be selected from trusted file-name metadata.")
    if "frontend: names.some((name) => name.startsWith('frontend/'))" not in changes:
        raise ValueError("Web deployment must be selected from trusted file-name metadata.")
    if "actions/checkout" in workflow or "pull_request.head.sha" in workflow:
        raise ValueError("pull_request_target must not checkout or reference PR code.")
    for name, component in (("deploy-api", "backend"), ("deploy-web", "frontend")):
        block = " ".join(job_block(workflow, name).split())
        if "needs: changes" not in block or f"needs.changes.outputs.{component} == 'true'" not in block:
            raise ValueError(f"{name} must follow its trusted changed-file output.")

    if "Verify event-level deployment routing" not in ci:
        raise ValueError("PR CI must execute the pure deployment event matrix.")
    if "prNumber !== 8" not in router or "function changedComponents" not in router:
        raise ValueError("Router must use PR identity and support selective component paths.")

    subprocess.run(["node", str(TEST)], check=True)
    print("Single merged-PR deployment wiring verified; no push route or PR-code checkout exists.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Event-level deployment verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
