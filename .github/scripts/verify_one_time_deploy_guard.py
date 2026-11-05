#!/usr/bin/env python3
"""Validate the API-independent, exact PR #8 merge-parent deployment guard."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

WORKFLOW = Path(".github/workflows/deploy-test.yml")
PR_WORKFLOW = Path(".github/workflows/pr-checks.yml")
GUARD_MODULE = Path(".github/scripts/deployment_guard.js")
TEST_SCRIPT = Path(".github/scripts/test_deployment_guard.js")
EXPECTED_JOBS = {"deploy-api": "backend", "deploy-web": "frontend"}


def get_job_block(source: str, job_name: str) -> str:
    start = re.search(rf"^  {re.escape(job_name)}:\n", source, re.MULTILINE)
    if start is None:
        raise ValueError(f"Missing workflow job: {job_name}")
    remainder = source[start.start():]
    relative_start = start.end() - start.start()
    next_job = re.search(r"^  [A-Za-z0-9_-]+:\n", remainder[relative_start:], re.MULTILINE)
    if next_job is None:
        return remainder
    return remainder[:relative_start + next_job.start()]


def main() -> int:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    pr_workflow = PR_WORKFLOW.read_text(encoding="utf-8")
    guard_module = GUARD_MODULE.read_text(encoding="utf-8")
    if "listPullRequestsAssociatedWithCommit" in workflow or "github.rest.pulls.get" in workflow:
        raise ValueError("The deployment decision must not depend on Pull Requests API availability.")
    for event_field in ("github.event.before", "github.sha", "vars.ONLINE_HOMEWORK_PR8_MERGE_HEAD_SHA"):
        if event_field not in workflow:
            raise ValueError(f"Deployment guard is missing required push/config input: {event_field}.")
    if "git show -s --format=%P" not in workflow or "parents[0]" not in guard_module or "parents[1]" not in guard_module:
        raise ValueError("The exact base/PR-head merge parent topology is not checked.")
    if "head_commit.message" in workflow:
        raise ValueError("Commit messages must not participate in deployment suppression.")

    decision_job = " ".join(get_job_block(workflow, "deployment-decision").split())
    if "skip: ${{ steps.decision.outputs.skip }}" not in decision_job:
        raise ValueError("The decision job must expose the exact topology result.")
    if "python3 .github/scripts/verify_pr8_merge_target.py" not in pr_workflow:
        raise ValueError("PR CI must verify the one-time target SHA matches the reviewed head.")

    for job_name, component in EXPECTED_JOBS.items():
        block = " ".join(get_job_block(workflow, job_name).split())
        if "needs: [changes, deployment-decision]" not in block:
            raise ValueError(f"{job_name} must depend on the fail-closed decision job.")
        if f"needs.changes.outputs.{component} == 'true'" not in block:
            raise ValueError(f"{job_name} must remain conditional on its component changing.")
        if "needs.deployment-decision.outputs.skip != 'true'" not in block:
            raise ValueError(f"{job_name} must skip only for the exact PR #8 merge topology.")

    subprocess.run(["node", str(TEST_SCRIPT)], check=True)
    print("PR #8 parent-topology guard wiring verified; no PR association API dependency remains.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"PR #8 deployment guard validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
