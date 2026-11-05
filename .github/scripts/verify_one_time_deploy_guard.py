#!/usr/bin/env python3
"""Validate the exact PR #8 merge-SHA deployment guard and its positive/negative tests."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

WORKFLOW = Path(".github/workflows/deploy-test.yml")
GUARD_MODULE = Path(".github/scripts/deployment_guard.js")
TEST_SCRIPT = Path(".github/scripts/test_deployment_guard.js")
EXPECTED_JOBS = {"deploy-api": "backend", "deploy-web": "frontend"}


def get_job_block(source: str, job_name: str) -> str:
    start = re.search(rf"^  {re.escape(job_name)}:\n", source, re.MULTILINE)
    if start is None:
        raise ValueError(f"Missing deployment workflow job: {job_name}")
    remainder = source[start.start():]
    relative_start = start.end() - start.start()
    next_job = re.search(r"^  [A-Za-z0-9_-]+:\n", remainder[relative_start:], re.MULTILINE)
    if next_job is None:
        return remainder
    return remainder[:relative_start + next_job.start()]


def main() -> int:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    guard_module = GUARD_MODULE.read_text(encoding="utf-8")
    if "listPullRequestsAssociatedWithCommit" not in workflow or "commitSha = context.sha" not in workflow:
        raise ValueError("The deployment decision must use GitHub's commit-to-PR association for the pushed SHA.")
    if "shouldSkipPr8Merge" not in workflow or "merge_commit_sha" not in guard_module:
        raise ValueError("The workflow must compare the pushed SHA against PR #8's merge_commit_sha.")
    if "head_commit.message" in workflow or "contains(github.event.head_commit.message" in workflow:
        raise ValueError("Deployment suppression must not depend on a reusable commit-message marker.")
    if "skip-test-deploy-pr8-bd179c7" in workflow:
        raise ValueError("The old reusable commit-message marker must not appear in deployment conditions.")

    decision_job = " ".join(get_job_block(workflow, "deployment-decision").split())
    if "pull-requests: read" not in decision_job or "TARGET_PR_NUMBER = 8" not in guard_module:
        raise ValueError("The decision job must read PR #8 association metadata with least-privilege permissions.")
    if "skip: ${{ steps.association.outputs.skip }}" not in decision_job:
        raise ValueError("The decision job must expose its exact-merge skip result to deployment jobs.")

    for job_name, component in EXPECTED_JOBS.items():
        block = " ".join(get_job_block(workflow, job_name).split())
        if "needs: [changes, deployment-decision]" not in block:
            raise ValueError(f"{job_name} must depend on the deployment decision job.")
        if f"needs.changes.outputs.{component} == 'true'" not in block:
            raise ValueError(f"{job_name} must remain conditional on its component changing.")
        if "needs.deployment-decision.outputs.skip != 'true'" not in block:
            raise ValueError(f"{job_name} must skip only when the exact PR #8 merge SHA is confirmed.")

    if not TEST_SCRIPT.is_file():
        raise ValueError("Positive and negative deployment guard tests are missing.")
    subprocess.run(["node", str(TEST_SCRIPT)], check=True)
    print("PR #8 merge-SHA guard wiring verified; copied-marker main commits remain deployable.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"PR #8 deployment guard validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
