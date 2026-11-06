#!/usr/bin/env python3
"""Validate PR-close deployment routing, target-SHA idempotency, and safe hook handling."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

WORKFLOW = Path(".github/workflows/deploy-test.yml")
CI_WORKFLOW = Path(".github/workflows/pr-checks.yml")
TEST = Path(".github/scripts/test_deployment_event_router.js")
LEDGER_SERVICE = Path("backend/src/main/java/com/onlinehomework/deployment/application/DeploymentLedgerService.java")
LEDGER_ENTITY = Path("backend/src/main/java/com/onlinehomework/deployment/domain/DeploymentDelivery.java")
LEDGER_CONTROLLER = Path("backend/src/main/java/com/onlinehomework/deployment/api/DeploymentLedgerController.java")
LEDGER_MIGRATION = Path("backend/src/main/resources/db/migration/V2__deployment_delivery_ledger.sql")
API_VERSION_CONTROLLER = Path("backend/src/main/java/com/onlinehomework/common/api/DeploymentVersionController.java")
API_VERSION_TEST = Path("backend/src/test/java/com/onlinehomework/common/api/DeploymentVersionControllerTest.java")
API_VERSION_API_TEST = Path("backend/src/test/java/com/onlinehomework/deployment/DeploymentLedgerApiTest.java")
WEB_VERSION_ROUTE = Path("frontend/app/api/deployment-version/route.ts")
SECURITY_CONFIGURATION = Path("backend/src/main/java/com/onlinehomework/security/SecurityConfiguration.java")


def job_block(source: str, name: str) -> str:
    match = re.search(rf"^  {re.escape(name)}:\n", source, re.MULTILINE)
    if not match:
        raise ValueError(f"Missing job: {name}")
    tail = source[match.start():]
    after = match.end() - match.start()
    next_job = re.search(r"^  [A-Za-z0-9_-]+:\n", tail[after:], re.MULTILINE)
    return tail if next_job is None else tail[:after + next_job.start()]


def verify_deploy_job(workflow: str, name: str, component: str, target_environment: str, version_path: str) -> None:
    block = " ".join(job_block(workflow, name).split())
    if "github.event.pull_request.merged == true" not in block:
        raise ValueError(f"{name} must run only for merged pull requests.")
    if "github.event.pull_request.number != 8" not in block:
        raise ValueError(f"{name} must explicitly skip PR #8.")
    if f"needs.changes.outputs.{component} == 'true'" not in block:
        raise ValueError(f"{name} must be selected using trusted changed-file metadata.")
    group = component.replace("backend", "api").replace("frontend", "web")
    if f"group: deploy-test-{group}" not in block or "queue: max" not in block:
        raise ValueError(f"{name} must serialize jobs and retain pending runs using queue:max.")
    if "cancel-in-progress: true" in block or "github.run_attempt" in block:
        raise ValueError(f"{name} must not cancel work or bypass the persistent ledger on rerun.")
    if "github.rest.repos.getBranch" not in block or "branch: 'main'" not in block:
        raise ValueError(f"{name} must resolve the current main deployment SHA at job execution time.")
    if "! /^[0-9a-f]{40}$/.test(sha)" in block or "core.setOutput('sha', sha)" not in block:
        # Normalized block removes whitespace around the regex; check a whitespace-insensitive pattern below.
        if not re.search(r"!\s*/\^\[0-9a-f\]\{40\}\$/\.test\(sha\)", block):
            raise ValueError(f"{name} must validate a complete lowercase 40-character deployment SHA.")
        if "core.setOutput('sha', sha)" not in block:
            raise ValueError(f"{name} must export the pinned deployment SHA.")
    if "DEPLOYMENT_SHA: ${{ steps.resolve-target.outputs.sha }}" not in block:
        raise ValueError(f"{name} ledger and hook must use the resolved main deployment SHA.")
    if "--arg deploymentSha \"$DEPLOYMENT_SHA\"" not in block or "deploymentSha:$deploymentSha" not in block:
        raise ValueError(f"{name} request must send a deploymentSha, not PR event merge SHA.")
    if "TARGET_ENVIRONMENT: " + target_environment not in block:
        raise ValueError(f"{name} uses the wrong API/Web ledger environment key.")
    if f"VERSION_PATH: {version_path}" not in block or "VERSION_URL=" not in block:
        raise ValueError(f"{name} must derive the service version URL without a new repository Variable.")
    if 'select(test("^[0-9a-f]{40}$"))' not in block or '[[ "$served_sha" == "$DEPLOYMENT_SHA" ]]' not in block:
        raise ValueError(f"{name} must require the running service to report the exact target commit SHA.")
    if "urlsplit" not in block or 'parsed.scheme == "https"' not in block:
        raise ValueError(f"{name} must reject non-HTTPS ledger, hook, and health URLs before deployment.")

    claim = block.find("id: claim")
    requesting = block.find("id: mark-requesting")
    hook = block.find("id: request-hook")
    requested = block.find("id: mark-requested")
    health = block.find("id: wait-health")
    succeeded = block.find("Persist " + component.replace("backend", "API").replace("frontend", "web") + " deployment success")
    unknown = block.find("Mark " + component.replace("backend", "API").replace("frontend", "web") + " result UNKNOWN")
    positions = [claim, requesting, hook, requested, health, unknown, succeeded]
    if any(position < 0 for position in positions) or positions[:5] != sorted(positions[:5]):
        raise ValueError(f"{name} ledger claim/REQUESTING/hook/REQUESTED/health sequence is incomplete or misordered.")
    if unknown < hook or succeeded < health:
        raise ValueError(f"{name} must persist UNKNOWN on ambiguous failure and SUCCEEDED only after health passes.")
    if "steps.claim.outputs.acquired == 'true'" not in block:
        raise ValueError(f"{name} must gate all hook/state writes on atomic claim acquisition.")
    if '--retry 0 --get' not in block or '--data-urlencode "ref=$DEPLOYMENT_SHA"' not in block:
        raise ValueError(f"{name} hook must receive the fixed SHA ref once, with no automatic retry.")
    if "--write-out '%{http_code}'" not in block or "[[ \"$status\" != '200' ]]" not in block:
        raise ValueError(f"{name} must treat HTTP 202/non-200 responses as ambiguous, not as a new confirmed deployment.")
    if "ledger claim failed closed; no " not in block:
        raise ValueError(f"{name} must fail closed if the persistent ledger cannot be reached.")


def main() -> int:
    workflow = WORKFLOW.read_text(encoding="utf-8")
    ci = CI_WORKFLOW.read_text(encoding="utf-8")
    service = LEDGER_SERVICE.read_text(encoding="utf-8")
    entity = LEDGER_ENTITY.read_text(encoding="utf-8")
    controller = LEDGER_CONTROLLER.read_text(encoding="utf-8")
    migration = LEDGER_MIGRATION.read_text(encoding="utf-8").upper()
    api_version = API_VERSION_CONTROLLER.read_text(encoding="utf-8")
    api_version_test = API_VERSION_TEST.read_text(encoding="utf-8")
    api_version_api_test = API_VERSION_API_TEST.read_text(encoding="utf-8")
    web_version = WEB_VERSION_ROUTE.read_text(encoding="utf-8")
    security = SECURITY_CONFIGURATION.read_text(encoding="utf-8")
    deploy_files = sorted(path.name for path in WORKFLOW.parent.glob("deploy*.y*ml"))
    if deploy_files != [WORKFLOW.name]:
        raise ValueError(f"Only one deploy workflow may exist: {deploy_files}")
    if not re.search(r"^on:\n  pull_request_target:\n    types: \[closed\]\n    branches: \[main\]", workflow, re.MULTILINE):
        raise ValueError("Deployment must only listen to pull_request_target closed events for main.")
    if re.search(r"^\s+push:", workflow, re.MULTILINE):
        raise ValueError("Deployment must not have a push-triggered route.")
    if "pull-requests: read" not in workflow or "contents: read" not in workflow:
        raise ValueError("Workflow permissions must be limited to PR and main-branch metadata read.")
    if "deployments: write" in workflow:
        raise ValueError("Workflow must not request unused GitHub deployment write permission.")
    if "X-Deployment-Ledger-Operator-Token" in workflow or "/reconcile" in workflow or "/safe-failure" in workflow:
        raise ValueError("Automation must not receive the operator credential or invoke manual ledger recovery.")

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
        raise ValueError("API deployment must be selected from trusted file-name metadata.")
    if "frontend: names.some((name) => name.startsWith('frontend/'))" not in changes:
        raise ValueError("Web deployment must be selected from trusted file-name metadata.")
    if "actions/checkout" in workflow or "pull_request.head.sha" in workflow:
        raise ValueError("pull_request_target must not checkout or reference PR code.")

    verify_deploy_job(workflow, "deploy-api", "backend", "test-api", "/internal/deployment-version")
    verify_deploy_job(workflow, "deploy-web", "frontend", "test-web", "/api/deployment-version")
    if workflow.count("uses: actions/github-script@f28e40c7f34bde8b3046d885e986cb6290c5673b") != 3:
        raise ValueError("Every actions/github-script use must be pinned to the verified immutable commit.")
    if "TEST_API_VERSION_URL" in workflow or "TEST_WEB_VERSION_URL" in workflow:
        raise ValueError("Version URLs must not require newly created GitHub Variables.")
    if '${RENDER_GIT_COMMIT:unknown}' not in api_version or '"/internal/deployment-version"' not in api_version:
        raise ValueError("The API runtime endpoint must expose only the Render commit SHA or unknown.")
    if "unknown" not in api_version_test or "not-a-full-sha" not in api_version_test:
        raise ValueError("API version tests must fail closed for missing or malformed commit metadata.")
    if 'get("/internal/deployment-version")' not in api_version_api_test:
        raise ValueError("The API endpoint must have a public integration test through Spring Security.")
    if '"/internal/deployment-version").permitAll()' not in security:
        raise ValueError("The read-only API version endpoint must be reachable by deploy health checks.")
    if 'process.env.RENDER_GIT_COMMIT' not in web_version or '"Cache-Control": "no-store"' not in web_version:
        raise ValueError("The Web endpoint must read the runtime Render SHA and disable caching.")
    if "request.url" not in web_version or '"Cache-Control": "no-store"' not in web_version:
        raise ValueError("The Web version endpoint must read the incoming request at runtime and disable caching.")
    if 'export const dynamic' in web_version or 'export const runtime' in web_version:
        raise ValueError("The Web version endpoint must not use segment config incompatible with Cache Components.")
    if not WEB_VERSION_ROUTE.exists():
        raise ValueError("The Web version endpoint must exist at frontend/app/api/deployment-version/route.ts.")

    if "Verify event-level deployment routing" not in ci:
        raise ValueError("PR CI must execute the route, ledger-key, and fake-hook matrix.")
    if "UNIQUE (REPOSITORY_NAME, TARGET_ENVIRONMENT, DEPLOYMENT_SHA)" not in migration:
        raise ValueError("The database must enforce one persistent key per repository/environment/deployment SHA.")
    if 'columnNames = {"repository_name", "target_environment", "deployment_sha"}' not in entity:
        raise ValueError("The JPA entity must use the deployment SHA as the unique component key.")
    if "findByKeyForUpdate" not in service or "saveAndFlush" not in service:
        raise ValueError("The ledger must serialize same-key claims with a DB lock and flushed unique insert.")
    if 'String deploymentSha' not in controller or 'String mergeSha' in controller:
        raise ValueError("The internal ledger API must call the key deploymentSha, not PR mergeSha.")
    if 'app.deployment-ledger.workflow-token' not in controller or 'app.deployment-ledger.operator-token' not in controller:
        raise ValueError("Workflow and operator credentials must be separately configured.")
    if 'authorizeOperator(token, repositoryHeader)' not in controller or 'authorizeWorkflow(token, repositoryHeader)' not in controller:
        raise ValueError("Controller must separate automatic status transitions from operator-only recovery.")

    subprocess.run(["node", str(TEST)], check=True)
    print("PR-close routing and main deployment-SHA idempotency verified; fake curl only, no deploy hook invoked.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Deployment-routing verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
