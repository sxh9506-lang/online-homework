'use strict';

function directPushEligible(parentShas) {
  if (!Array.isArray(parentShas) || parentShas.some((sha) => typeof sha !== 'string' || !/^[0-9a-f]{40}$/i.test(sha))) {
    throw new TypeError('parentShas must contain full commit SHAs.');
  }
  // Under an enforced merge-commit-only policy, one parent means a direct main push;
  // every PR merge has two parents and is routed to pull_request_target instead.
  return parentShas.length === 1;
}

function mergedPrEligible({ action, merged, baseRef, number }) {
  const prNumber = Number(number);
  return action === 'closed'
    && merged === true
    && baseRef === 'main'
    && Number.isInteger(prNumber)
    && prNumber > 0
    && prNumber !== 8;
}

function mergeCommitOnly(policy) {
  return policy?.allow_merge_commit === true
    && policy?.allow_squash_merge === false
    && policy?.allow_rebase_merge === false;
}

function changedComponents(files) {
  if (!Array.isArray(files)) throw new TypeError('files must be an array.');
  const names = files.map((file) => typeof file === 'string' ? file : file?.filename);
  if (names.some((name) => typeof name !== 'string')) throw new TypeError('Each file must have a filename.');
  return {
    backend: names.some((name) => name.startsWith('backend/')),
    frontend: names.some((name) => name.startsWith('frontend/')),
  };
}

function allComponents() {
  return { backend: true, frontend: true };
}

function countDeployments({ parentShas, pullRequestEvent }) {
  const pushRoute = directPushEligible(parentShas);
  const prRoute = pullRequestEvent ? mergedPrEligible(pullRequestEvent) : false;
  return { pushRoute, prRoute, total: Number(pushRoute) + Number(prRoute) };
}

module.exports = {
  directPushEligible,
  mergedPrEligible,
  mergeCommitOnly,
  changedComponents,
  allComponents,
  countDeployments,
};

if (require.main === module) {
  const [mode, parentText] = process.argv.slice(2);
  if (mode !== 'push' || typeof parentText !== 'string') {
    console.error('Usage: deployment_event_router.js push <space-separated-parent-shas>');
    process.exit(2);
  }
  const parentShas = parentText.trim() === '' ? [] : parentText.trim().split(/\s+/);
  console.log(`direct=${directPushEligible(parentShas) ? 'true' : 'false'}`);
}
