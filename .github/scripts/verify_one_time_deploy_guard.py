#!/usr/bin/env python3
"""Check the one-time PR #8 deployment skip marker without calling deployment hooks."""

from __future__ import annotations

import re
import sys
from pathlib import Path

MARKER = "[skip-test-deploy-pr8-bd179c7]"
WORKFLOW = Path(".github/workflows/deploy-test.yml")
EXPECTED_JOBS = {"deploy-api": "backend", "deploy-web": "frontend"}


def get_job_block(source: str, job_name: str) -> str:
    start_match = re.search(rf"^  {re.escape(job_name)}:\n", source, re.MULTILINE)
    if start_match is None:
        raise ValueError(f"Missing deployment job: {job_name}")
    remainder = source[start_match.start():]
    next_job = re.search(r"^  [A-Za-z0-9_-]+:\n", remainder[start_match.end() - start_match.start():], re.MULTILINE)
    if next_job is None:
        return remainder
    end = start_match.start() + (start_match.end() - start_match.start()) + next_job.start()
    return source[start_match.start():end]


def should_deploy(changed: bool, head_commit_message: str) -> bool:
    return changed and MARKER not in head_commit_message


def main() -> int:
    source = WORKFLOW.read_text(encoding="utf-8")
    if source.count(MARKER) != len(EXPECTED_JOBS):
        raise ValueError("The unique skip marker must appear once in each test deployment job.")

    for job_name, component in EXPECTED_JOBS.items():
        block = " ".join(get_job_block(source, job_name).split())
        expected_guard = (
            f"needs.changes.outputs.{component} == 'true' && "
            f"!contains(github.event.head_commit.message, '{MARKER}')"
        )
        if expected_guard not in block:
            raise ValueError(f"Deployment guard is missing or differs in {job_name}.")

    planned_merge_title = f"Merge PR #8 without test deployment {MARKER}"
    normal_main_title = "Merge pull request #12 from example/ordinary-change"
    if should_deploy(True, planned_merge_title):
        raise ValueError("The planned PR #8 merge title would still trigger deployment.")
    if not should_deploy(True, normal_main_title):
        raise ValueError("An ordinary main push would incorrectly skip deployment.")
    if should_deploy(False, planned_merge_title):
        raise ValueError("Unchanged components must not trigger deployment.")

    print("One-time deploy guard verified: the marked PR #8 merge is skipped; ordinary main pushes remain deployable.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as error:
        print(f"One-time deploy guard validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
