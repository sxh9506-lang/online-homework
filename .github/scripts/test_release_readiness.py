#!/usr/bin/env python3
"""Unit and static checks for the Render checksPass release-readiness gate."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

from release_readiness import evaluate


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = ROOT / ".github/workflows/pr-checks.yml"
WORKFLOW = WORKFLOW_PATH.read_text(encoding="utf-8")


def job_block(job_id: str) -> str:
    pattern = rf"(?ms)^  {re.escape(job_id)}:\n(.*?)(?=^  [A-Za-z0-9_-]+:|\Z)"
    match = re.search(pattern, WORKFLOW)
    if not match:
        raise AssertionError(f"Missing workflow job: {job_id}")
    return match.group(1)


def readiness_values(
    *,
    backend_changed: str = "false",
    backend_result: str = "skipped",
    frontend_changed: str = "false",
    frontend_result: str = "skipped",
    repository_sanity: str = "success",
    changes: str = "success",
    event: str = "push",
    ref: str = "refs/heads/main",
) -> dict[str, str]:
    return {
        "EVENT_NAME": event,
        "GITHUB_REF": ref,
        "REPOSITORY_SANITY_RESULT": repository_sanity,
        "CHANGES_RESULT": changes,
        "BACKEND_CHANGED": backend_changed,
        "BACKEND_RESULT": backend_result,
        "FRONTEND_CHANGED": frontend_changed,
        "FRONTEND_RESULT": frontend_result,
    }


class ReleaseReadinessDecisionTests(unittest.TestCase):
    def test_docs_only_change_passes_without_component_builds(self) -> None:
        self.assertEqual([], evaluate(readiness_values()))

    def test_no_code_change_passes_without_component_builds(self) -> None:
        self.assertEqual([], evaluate(readiness_values()))

    def test_backend_change_requires_backend_verify_and_image_job_success(self) -> None:
        self.assertEqual(
            [],
            evaluate(readiness_values(backend_changed="true", backend_result="success")),
        )
        for result in ("skipped", "cancelled", "failure", ""):
            with self.subTest(result=result):
                errors = evaluate(readiness_values(backend_changed="true", backend_result=result))
                self.assertTrue(any("Changed backend" in error for error in errors))

    def test_frontend_change_requires_lint_and_production_build_job_success(self) -> None:
        self.assertEqual(
            [],
            evaluate(readiness_values(frontend_changed="true", frontend_result="success")),
        )
        for result in ("skipped", "cancelled", "failure", ""):
            with self.subTest(result=result):
                errors = evaluate(readiness_values(frontend_changed="true", frontend_result=result))
                self.assertTrue(any("Changed frontend" in error for error in errors))

    def test_mixed_change_requires_both_component_jobs(self) -> None:
        both_success = readiness_values(
            backend_changed="true",
            backend_result="success",
            frontend_changed="true",
            frontend_result="success",
        )
        self.assertEqual([], evaluate(both_success))

        backend_missing = dict(both_success, FRONTEND_RESULT="skipped")
        self.assertTrue(any("Changed frontend" in error for error in evaluate(backend_missing)))

        frontend_missing = dict(both_success, BACKEND_RESULT="cancelled")
        self.assertTrue(any("Changed backend" in error for error in evaluate(frontend_missing)))

    def test_required_sanity_and_path_detection_must_succeed(self) -> None:
        for values in (
            readiness_values(repository_sanity="failure"),
            readiness_values(repository_sanity="cancelled"),
            readiness_values(changes="failure"),
            readiness_values(changes="skipped"),
        ):
            with self.subTest(values=values):
                self.assertTrue(evaluate(values))

    def test_only_main_push_can_be_release_ready(self) -> None:
        self.assertTrue(evaluate(readiness_values(event="pull_request")))
        self.assertTrue(evaluate(readiness_values(ref="refs/heads/feature")))

    def test_unknown_path_outputs_fail_closed(self) -> None:
        self.assertTrue(evaluate(readiness_values(backend_changed="", frontend_changed="false")))


class WorkflowStructureTests(unittest.TestCase):
    def test_pr_and_main_push_ci_triggers_are_preserved(self) -> None:
        trigger_header = WORKFLOW.split("permissions:", 1)[0]
        self.assertIn(
            "on:\n  pull_request:\n    branches: [main]\n  push:\n    branches: [main]",
            trigger_header,
        )

    def test_no_github_deployment_workflow_or_secrets_remain(self) -> None:
        workflows = ROOT / ".github/workflows"
        names = sorted(path.name for path in workflows.glob("*") if path.is_file())
        self.assertEqual(["pr-checks.yml"], names)
        lowered = WORKFLOW.lower()
        for forbidden in (
            "pull_request_target",
            "workflow_dispatch",
            "deploy-test.yml",
            "deploy_hook_url",
            "test_deployment_ledger_token",
            "actions/github-script",
        ):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, lowered)

    def test_release_ready_always_runs_for_main_push_and_checks_all_dependencies(self) -> None:
        gate = job_block("release-ready")
        normalized = " ".join(gate.split())
        self.assertIn("always()", normalized)
        self.assertIn("github.event_name == 'push'", normalized)
        self.assertIn("github.ref == 'refs/heads/main'", normalized)
        for dependency in ("repository-sanity", "changes", "backend-tests", "frontend-tests"):
            self.assertIn(dependency, gate)
        for result_name in (
            "REPOSITORY_SANITY_RESULT",
            "CHANGES_RESULT",
            "BACKEND_CHANGED",
            "BACKEND_RESULT",
            "FRONTEND_CHANGED",
            "FRONTEND_RESULT",
        ):
            self.assertIn(result_name, gate)
        self.assertIn("python3 .github/scripts/release_readiness.py", gate)

    def test_path_mapping_and_component_job_contents_match_the_gate(self) -> None:
        changes_job = job_block("changes")
        self.assertIn("grep -qE '^backend/'", changes_job)
        self.assertIn("grep -qE '^frontend/'", changes_job)

        backend_job = job_block("backend-tests")
        self.assertIn(".github/scripts/verify_ci_backend.py", backend_job)
        self.assertIn("docker build --file backend/Dockerfile", backend_job)

        frontend_job = job_block("frontend-tests")
        self.assertIn("npm --prefix frontend run lint --if-present", frontend_job)
        self.assertIn("npm --prefix frontend run build", frontend_job)
        self.assertNotIn("npm --prefix frontend test", frontend_job)

    def test_render_api_filter_includes_root_dockerignore_for_root_context_build(self) -> None:
        self.assertTrue((ROOT / ".dockerignore").is_file())
        backend_job = job_block("backend-tests")
        self.assertIn("docker build --file backend/Dockerfile --tag online-homework-api:ci .", backend_job)

        for runbook_path in (
            ROOT / "ops/ci-test-deploy.md",
            ROOT / "docs/ops/TEST_DEPLOYMENT_IDEMPOTENCY.md",
        ):
            with self.subTest(runbook=runbook_path):
                lines = runbook_path.read_text(encoding="utf-8").splitlines()
                api_filter_line = next(line for line in lines if "API include build filter" in line)
                web_filter_line = next(line for line in lines if "Web include build filter" in line)
                self.assertIn("`backend/**`", api_filter_line)
                self.assertIn("`.dockerignore`", api_filter_line)
                self.assertIn("`frontend/**`", web_filter_line)

    def test_repository_sanity_runs_this_static_test(self) -> None:
        sanity = job_block("repository-sanity")
        self.assertIn("python3 .github/scripts/test_release_readiness.py", sanity)


if __name__ == "__main__":
    unittest.main()
