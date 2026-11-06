'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
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

  // Structural proof: one deploy workflow, merged PR-close only; PR #8 guard precedes API and no PR-code checkout.
  const workflowsDir = '.github/workflows';
  const deployFiles = fs.readdirSync(workflowsDir).filter((name) => /^deploy.*\.ya?ml$/i.test(name)).sort();
  assert.deepEqual(deployFiles, ['deploy-test.yml']);
  const workflow = fs.readFileSync(path.join(workflowsDir, 'deploy-test.yml'), 'utf8');
  const pr8GuardIndex = workflow.indexOf('github.event.pull_request.number != 8');
  const listFilesIndex = workflow.indexOf('github.rest.pulls.listFiles');
  assert.ok(pr8GuardIndex >= 0 && pr8GuardIndex < listFilesIndex, 'PR #8 must be skipped before listFiles is called');
  assert.match(workflow, /pull_request_target:\n\s+types: \[closed\]\n\s+branches: \[main\]/);
  assert.doesNotMatch(workflow, /^\s+push:/m);
  assert.match(workflow, /github.event.pull_request.merged == true/);
  assert.match(workflow, /pull-requests: read/);
  assert.doesNotMatch(workflow, /actions\/checkout|pull_request\.head\.sha|require\(['"]\.\/\.github\/scripts/);
  const publishCommand = /(?:^|\s)(?:curl\b.*(?:DEPLOY_HOOK_URL|deploy)|docker push\b|npm publish\b|wrangler deploy\b|vercel deploy\b|fly deploy\b|railway up\b|gh release create\b|render deploy\b)/im;
  for (const name of fs.readdirSync(workflowsDir).filter((entry) => /\.ya?ml$/i.test(entry) && entry !== 'deploy-test.yml')) {
    const otherWorkflow = fs.readFileSync(path.join(workflowsDir, name), 'utf8');
    assert.doesNotMatch(otherWorkflow, publishCommand, `${name} must not contain another publishing path`);
  }

  console.log('Single-route runtime matrix passed: PR #8=0, other PR=1 for merge/squash/rebase; non-merged=0; exact inline API script covers paths and error fallbacks.');
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
