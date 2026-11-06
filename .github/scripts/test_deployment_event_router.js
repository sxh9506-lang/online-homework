'use strict';

const assert = require('node:assert/strict');
const childProcess = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {
  mergedPrEligible,
  changedComponents,
  allComponents,
  deploymentCount,
} = require('./deployment_event_router.js');

const closed = (number, merged = true, baseRef = 'main', extra = {}) => ({
  action: 'closed', merged, baseRef, number, ...extra,
});

function extractInlineScript(source) {
  const lines = source.split(/\r?\n/);
  const marker = lines.findIndex((line) => /^\s*script: \|\s*$/.test(line));
  assert.notEqual(marker, -1, 'workflow must contain the inline actions/github-script program');
  let bodyIndent = null;
  const body = [];
  for (let i = marker + 1; i < lines.length; i += 1) {
    const line = lines[i];
    if (line.trim() === '') {
      if (bodyIndent !== null) body.push('');
      continue;
    }
    const indent = line.match(/^\s*/)[0].length;
    if (bodyIndent === null) bodyIndent = indent;
    if (indent < bodyIndent) break;
    body.push(line.slice(bodyIndent));
  }
  assert.ok(body.length > 0, 'inline GitHub script must not be empty');
  return body.join('\n');
}

function extractRunScriptByStepName(source, stepName) {
  const lines = source.split(/\r?\n/);
  const nameLine = lines.findIndex((line) => line.trim() === `name: ${stepName}`);
  assert.notEqual(nameLine, -1, `workflow must contain step ${stepName}`);
  let runLine = -1;
  for (let i = nameLine + 1; i < lines.length; i += 1) {
    if (/^\s{6}-\s/.test(lines[i])) break;
    if (/^\s*run: \|\s*$/.test(lines[i])) {
      runLine = i;
      break;
    }
  }
  assert.notEqual(runLine, -1, `${stepName} must use a shell script body`);
  let bodyIndent = null;
  const body = [];
  for (let i = runLine + 1; i < lines.length; i += 1) {
    const line = lines[i];
    if (line.trim() === '') {
      if (bodyIndent !== null) body.push('');
      continue;
    }
    const indent = line.match(/^\s*/)[0].length;
    if (bodyIndent === null) bodyIndent = indent;
    if (indent < bodyIndent) break;
    body.push(line.slice(bodyIndent));
  }
  assert.ok(body.length > 0, `${stepName} must not be empty`);
  return body.join('\n');
}

async function runInlinePathScript({ files, expectedFiles, error } = {}) {
  const outputs = {};
  const warnings = [];
  const infos = [];
  let apiCalls = 0;
  const listFilesMethod = async () => {};
  const github = {
    rest: { pulls: { listFiles: listFilesMethod } },
    paginate: async (method, args) => {
      apiCalls += 1;
      assert.equal(method, listFilesMethod);
      assert.deepEqual(args, { owner: 'example', repo: 'homework', pull_number: 42, per_page: 100 });
      if (error) throw error;
      return files;
    },
  };
  const context = { repo: { owner: 'example', repo: 'homework' } };
  const core = {
    info: (message) => infos.push(message),
    warning: (message) => warnings.push(message),
    setOutput: (name, value) => { outputs[name] = value; },
  };
  const mockedProcess = { env: { PR_NUMBER: '42', EXPECTED_FILES: String(expectedFiles) } };
  const workflow = fs.readFileSync('.github/workflows/deploy-test.yml', 'utf8');
  const program = extractInlineScript(workflow);
  const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
  await new AsyncFunction('github', 'context', 'core', 'process', program)(github, context, core, mockedProcess);
  return { outputs, warnings, infos, apiCalls };
}

function runHookWithFakeCurl(stepScript, tempDir, label, httpStatus) {
  const capturePath = path.join(tempDir, `${label}-${httpStatus}.json`);
  const sha = '0123456789abcdef0123456789abcdef01234567';
  const hookUrl = 'https://render-hook.invalid/fake';
  const env = {
    ...process.env,
    PATH: `${path.join(tempDir, 'bin')}${path.delimiter}${process.env.PATH}`,
    HOOK_CAPTURE_FILE: capturePath,
    MOCK_HTTP_STATUS: String(httpStatus),
    DEPLOY_HOOK_URL: hookUrl,
    DEPLOYMENT_SHA: sha,
  };
  const result = childProcess.spawnSync('/bin/bash', ['-c', stepScript], { env, encoding: 'utf8' });
  assert.equal(result.error, undefined, `${label} fake-hook script must start`);
  const args = JSON.parse(fs.readFileSync(capturePath, 'utf8'));
  assert.ok(args.includes('--get'), `${label} must send ref as a query parameter`);
  const refIndex = args.indexOf('--data-urlencode');
  assert.ok(refIndex >= 0, `${label} must URL-encode the ref parameter`);
  assert.equal(args[refIndex + 1], `ref=${sha}`, `${label} must send the exact pinned SHA, not the main branch name`);
  assert.equal(args.at(-1), hookUrl, `${label} must pass only the fake hook URL in the test`);
  assert.equal(args.includes('main'), false, `${label} must not ask Render to deploy the moving main branch`);
  if (httpStatus === 200) {
    assert.equal(result.status, 0, `${label} may continue after a confirmed HTTP 200`);
  } else {
    assert.notEqual(result.status, 0, `${label} must fail closed for HTTP ${httpStatus}`);
  }
}

function runVersionHealthWithFakeCurl(stepScript, tempDir, label, options = {}) {
  const targetSha = options.targetSha || '0123456789abcdef0123456789abcdef01234567';
  const endpoint = label === 'api' ? '/internal/deployment-version' : '/api/deployment-version';
  const healthUrl = 'https://service.example.invalid/actuator/health';
  const versionUrl = `https://service.example.invalid${endpoint}`;
  const env = {
    ...process.env,
    PATH: `${path.join(tempDir, 'bin')}${path.delimiter}${process.env.PATH}`,
    HEALTH_URL: healthUrl,
    VERSION_PATH: endpoint,
    VERSION_URL: versionUrl,
    DEPLOYMENT_SHA: targetSha,
    MOCK_HEALTH_STATUS: String(options.healthStatus ?? 200),
    MOCK_VERSION_STATUS: String(options.versionStatus ?? 200),
    MOCK_VERSION_BODY: options.versionBody ?? JSON.stringify({ commit: targetSha }),
  };
  const result = childProcess.spawnSync('/bin/bash', ['-c', stepScript], { env, encoding: 'utf8' });
  assert.equal(result.error, undefined, `${label} fake health/version script must start`);
  return result;
}

async function main() {
  // One PR-close route handles all merge strategies; PR identity is its event number, not any head SHA.
  for (const mergeMethod of ['merge-commit', 'squash', 'rebase']) {
    assert.equal(deploymentCount(closed(8, true, 'main', { mergeMethod })), 0, `PR #8 skipped for ${mergeMethod}`);
    assert.equal(deploymentCount(closed(42, true, 'main', { mergeMethod })), 1, `other PR deploys once for ${mergeMethod}`);
  }
  assert.equal(mergedPrEligible(closed(42, true, 'main', { headSha: '8'.repeat(40) })), true,
    'another PR reusing PR #8 head SHA is still routed by its own event number');
  assert.equal(deploymentCount({ action: 'push', merged: true, baseRef: 'main', number: 42 }), 0,
    'there is no push-triggered deployment path');

  // Unmerged, wrong-base, wrong-action and invalid PR number events never deploy.
  for (const event of [
    closed(42, false),
    closed(42, true, 'develop'),
    { ...closed(42), action: 'opened' },
    { ...closed('not-a-number') },
  ]) {
    assert.equal(mergedPrEligible(event), false);
  }
  assert.deepEqual(changedComponents([{ filename: 'backend/src/App.java' }]), { backend: true, frontend: false });
  assert.deepEqual(changedComponents([{ filename: 'frontend/src/page.tsx' }]), { backend: false, frontend: true });
  assert.deepEqual(changedComponents([{ filename: 'docs/README.md' }]), { backend: false, frontend: false });
  assert.deepEqual(changedComponents([{ filename: 'backend/App.java' }, { filename: 'frontend/app.tsx' }]), { backend: true, frontend: true });
  assert.deepEqual(allComponents(), { backend: true, frontend: true });
  assert.throws(() => changedComponents(null), /files must be an array/);

  // Execute the exact workflow inline script with a mocked GitHub API; no network calls or deploy hooks occur.
  assert.deepEqual((await runInlinePathScript({ files: [{ filename: 'backend/src/App.java' }], expectedFiles: 1 })).outputs,
    { backend: 'true', frontend: 'false' });
  assert.deepEqual((await runInlinePathScript({ files: [{ filename: 'frontend/app.tsx' }], expectedFiles: 1 })).outputs,
    { backend: 'false', frontend: 'true' });
  assert.deepEqual((await runInlinePathScript({ files: [{ filename: 'docs/README.md' }], expectedFiles: 1 })).outputs,
    { backend: 'false', frontend: 'false' });
  assert.deepEqual((await runInlinePathScript({ files: [{ filename: 'backend/App.java' }, { filename: 'frontend/app.tsx' }], expectedFiles: 2 })).outputs,
    { backend: 'true', frontend: 'true' });

  const apiError = await runInlinePathScript({ files: [], expectedFiles: 0, error: Object.assign(new Error('mock API outage'), { status: 503 }) });
  assert.deepEqual(apiError.outputs, { backend: 'true', frontend: 'true' });
  assert.equal(apiError.apiCalls, 1);
  assert.equal(apiError.warnings.length, 1);

  const incomplete = await runInlinePathScript({ files: [{ filename: 'backend/App.java' }], expectedFiles: 2 });
  assert.deepEqual(incomplete.outputs, { backend: 'true', frontend: 'true' });
  assert.equal(incomplete.warnings.length, 1);

  const malformed = await runInlinePathScript({ files: [{ filename: null }], expectedFiles: 1 });
  assert.deepEqual(malformed.outputs, { backend: 'true', frontend: 'true' });
  assert.equal(malformed.warnings.length, 1);

  // Run the actual API/Web shell steps with a fake curl executable, never a real Render endpoint.
  const workflowsDir = '.github/workflows';
  const workflow = fs.readFileSync(path.join(workflowsDir, 'deploy-test.yml'), 'utf8');
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'homework-fake-hook-'));
  fs.mkdirSync(path.join(tempDir, 'bin'));
  const fakeCurl = path.join(tempDir, 'bin', 'curl');
  fs.writeFileSync(fakeCurl, `#!/usr/bin/env node
const fs = require('node:fs');
const args = process.argv.slice(2);
const url = args.at(-1);
if (url === process.env.HEALTH_URL) {
  process.exit(Number(process.env.MOCK_HEALTH_STATUS || '200') === 200 ? 0 : 22);
}
if (url === process.env.VERSION_URL) {
  const body = process.env.MOCK_VERSION_BODY || '';
  const code = Number(process.env.MOCK_VERSION_STATUS || '200') === 200 ? 0 : 22;
  process.stdout.write(body, () => process.exit(code));
} else {
  fs.writeFileSync(process.env.HOOK_CAPTURE_FILE, JSON.stringify(args));
  process.stdout.write(process.env.MOCK_HTTP_STATUS);
}
`, { mode: 0o700 });
  fs.writeFileSync(path.join(tempDir, 'bin', 'seq'), '#!/bin/sh\nprintf "1\\n"\n', { mode: 0o700 });
  fs.writeFileSync(path.join(tempDir, 'bin', 'sleep'), '#!/bin/sh\nexit 0\n', { mode: 0o700 });
  try {
    for (const [label, stepName] of [
      ['api', 'Request API deployment once (no automatic retry)'],
      ['web', 'Request web deployment once (no automatic retry)'],
    ]) {
      const script = extractRunScriptByStepName(workflow, stepName);
      runHookWithFakeCurl(script, tempDir, label, 200);
      runHookWithFakeCurl(script, tempDir, label, 202);
    }
    for (const [label, stepName] of [
      ['api', 'Wait for API health and target commit'],
      ['web', 'Wait for web health and target commit'],
    ]) {
      const script = extractRunScriptByStepName(workflow, stepName);
      const matchingVersion = runVersionHealthWithFakeCurl(script, tempDir, label);
      assert.equal(matchingVersion.status, 0,
        `${label} succeeds only when the running SHA matches the requested SHA: ${matchingVersion.stderr}${matchingVersion.stdout}`);
      assert.notEqual(runVersionHealthWithFakeCurl(script, tempDir, label, {
        versionBody: JSON.stringify({ commit: 'f'.repeat(40) }),
      }).status, 0, `${label} must not report success while an old commit is serving`);
      assert.notEqual(runVersionHealthWithFakeCurl(script, tempDir, label, {
        versionBody: JSON.stringify({ commit: 'unknown' }),
      }).status, 0, `${label} must fail closed when commit metadata is unavailable`);
      assert.notEqual(runVersionHealthWithFakeCurl(script, tempDir, label, {
        healthStatus: 503,
      }).status, 0, `${label} must remain unhealthy when its health endpoint fails`);
    }
  } finally {
    fs.rmSync(tempDir, { recursive: true, force: true });
  }

  // Structural proof: one deploy workflow, merged PR-close only; PR #8 guard precedes API and no PR-code checkout.
  const deployFiles = fs.readdirSync(workflowsDir).filter((name) => /^deploy.*\.ya?ml$/i.test(name)).sort();
  assert.deepEqual(deployFiles, ['deploy-test.yml']);
  const pr8GuardIndex = workflow.indexOf('github.event.pull_request.number != 8');
  const listFilesIndex = workflow.indexOf('github.rest.pulls.listFiles');
  assert.ok(pr8GuardIndex >= 0 && pr8GuardIndex < listFilesIndex, 'PR #8 must be skipped before listFiles is called');
  assert.match(workflow, /pull_request_target:\n\s+types: \[closed\]\n\s+branches: \[main\]/);
  assert.doesNotMatch(workflow, /^\s+push:/m);
  assert.match(workflow, /github.event.pull_request.merged == true/);
  assert.match(workflow, /pull-requests: read/);
  assert.match(workflow, /contents: read/);
  assert.equal((workflow.match(/queue: max/g) || []).length, 2, 'API and Web must retain pending deploy events, within the documented cap');
  assert.equal((workflow.match(/github\.rest\.repos\.getBranch/g) || []).length, 2, 'each component resolves main at job execution time');
  assert.equal((workflow.match(/branch: 'main'/g) || []).length, 2);
  assert.equal((workflow.match(/core\.setOutput\('sha', sha\)/g) || []).length, 2);
  assert.equal((workflow.match(/--data-urlencode "ref=\$DEPLOYMENT_SHA"/g) || []).length, 2);
  assert.equal((workflow.match(/VERSION_PATH: \/(?:internal\/deployment-version|api\/deployment-version)/g) || []).length, 2,
    'API and web jobs derive version URLs without adding configuration variables');
  assert.equal((workflow.match(/select\(test\("\^\[0-9a-f\]\{40\}\$"\)\)/g) || []).length, 2,
    'both health gates require a full lowercase SHA from the service');
  assert.equal((workflow.match(/uses: actions\/github-script@f28e40c7f34bde8b3046d885e986cb6290c5673b/g) || []).length, 3,
    'workflow actions/github-script references are pinned to the verified immutable commit');
  assert.doesNotMatch(workflow, /TEST_(?:API|WEB)_VERSION_URL/,
    'deployment must not require newly added repository variables');
  assert.doesNotMatch(workflow, /merge_commit_sha|MERGE_SHA/,
    'PR event merge SHA must not be confused with the pinned main deployment SHA');
  assert.doesNotMatch(workflow, /actions\/checkout|pull_request\.head\.sha|require\(['"]\.\/\.github\/scripts/);
  const publishCommand = /(?:^|\s)(?:curl\b.*(?:DEPLOY_HOOK_URL|deploy)|docker push\b|npm publish\b|wrangler deploy\b|vercel deploy\b|fly deploy\b|railway up\b|gh release create\b|render deploy\b)/im;
  for (const name of fs.readdirSync(workflowsDir).filter((entry) => /\.ya?ml$/i.test(entry) && entry !== 'deploy-test.yml')) {
    const otherWorkflow = fs.readFileSync(path.join(workflowsDir, name), 'utf8');
    assert.doesNotMatch(otherWorkflow, publishCommand, `${name} must not contain another publishing path`);
  }

  console.log('Single-route matrix passed; fake curl verified fixed main deployment SHA ref and HTTP 202 fail-closed; no real deploy hook invoked.');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
