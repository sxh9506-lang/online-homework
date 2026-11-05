#!/usr/bin/env python3
"""Validate event-level deployment routing and its no-side-effect CI matrix."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

PUSH_WORKFLOW = Path(".github/workflows/deploy-test.yml")
PR_WORKFLOW = Path(".github/workflows/deploy-test-pr.yml")
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
    push = PUSH_WORKFLOW.read_text(encoding="utf-8")
    pr = PR_WORKFLOW.read_text(encoding="utf-8")
    ci = CI_WORKFLOW.read_text(encoding="utf-8")
    router = ROUTER.read_text(encoding="utf-8")

    if not re.search(r"^on:\n  push:\n    branches: \[main\]", push, re.MULTILINE):
        raise ValueError("Direct deployment workflow must be scoped to pushes on main.")
    if "pull_request_target:" in push or "deployment_event_router.js push" not in push:
        raise ValueError("Push workflow must only route commit parents and not handle PR-close events.")
    for name in ("deploy-api", "deploy-web"):
        block = " ".join(job_block(push, name).split())
        if "needs.deployment-route.outputs.direct == 'true'" not in block:
            raise ValueError(f"{name} must deploy only through the direct-push route.")

    if "pull_request_target:" not in pr or "types: [closed]" not in pr or "branches: [main]" not in pr:
        raise ValueError("Merged-PR deployment must use pull_request_target closed for base main.")
    changes = " ".join(job_block(pr, "changes").split())
    if "github.event.pull_request.merged == true" not in changes or "github.event.pull_request.number != 8" not in changes:
        raise ValueError("PR #8 must be excluded before the PR file-list API is called.")
    if "github.rest.pulls.listFiles" not in changes or "selection = { backend: true, frontend: true }" not in changes:
        raise ValueError("Other merged PRs must use file metadata with an all-components API-failure fallback.")
    if "backend: names.some((name) => name.startsWith('backend/'))" not in changes:
        raise ValueError("PR backend changes must be selected from trusted file-name metadata.")
    if "frontend: names.some((name) => name.startsWith('frontend/'))" not in changes:
        raise ValueError("PR frontend changes must be selected from trusted file-name metadata.")
    if "actions/checkout" in pr or "pull_request.head.sha" in pr:
        raise ValueError("pull_request_target must not checkout or reference PR code.")
    for name, component in (("deploy-api", "backend"), ("deploy-web", "frontend")):
        block = " ".join(job_block(pr, name).split())
        if "needs: changes" not in block or f"needs.changes.outputs.{component} == 'true'" not in block:
            raise ValueError(f"{name} must follow the trusted changed-file output.")

    if "Verify event-level deployment routing" not in ci:
        raise ValueError("PR CI must execute the pure event-routing matrix.")
    if "parentShas.length === 1" not in router or "prNumber !== 8" not in router:
        raise ValueError("Router must classify single-parent pushes and skip only PR #8 closed events.")
    if "function changedComponents" not in router or "function allComponents" not in router:
        raise ValueError("Router must cover selective PR paths and API-failure fallback.")

    subprocess.run(["node", str(TEST)], check=True)
    print("Event-level deployment wiring verified; no PR-code checkout or PR-association lookup is used.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Event-level deployment verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
