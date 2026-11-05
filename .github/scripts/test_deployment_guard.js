'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const { shouldSkipPr8Merge, shouldDeploy } = require('./deployment_guard.js');

const baseBefore = '1'.repeat(40);
const pr8Head = '2'.repeat(40);
const exactMergeCommit = '3'.repeat(40);
const unrelatedHead = '4'.repeat(40);
const unrelatedCommit = '5'.repeat(40);
const reusedMarker = '[skip-test-deploy-pr8-bd179c7]';

function decision({ beforeSha = baseBefore, commitSha, parents, expectedPr8HeadSha = pr8Head, commitMessage }) {
  // commitMessage is deliberately accepted only by the test fixture to prove it has no effect.
  return shouldSkipPr8Merge({ beforeSha, commitSha, parents, expectedPr8HeadSha, commitMessage });
}

// Positive: exactly the expected base-first, PR-head-second merge commit is skipped.
const targetSkip = decision({ commitSha: exactMergeCommit, parents: [baseBefore, pr8Head] });
assert.equal(targetSkip, true);
assert.equal(shouldDeploy(true, targetSkip), false, 'the target PR #8 merge must not deploy');

// Subsequent normal main push: its before SHA is the PR8 merge, but the pushed commit is single-parent.
const followupMainSkip = decision({
  beforeSha: exactMergeCommit,
  commitSha: '6'.repeat(40),
  parents: [exactMergeCommit],
});
assert.equal(followupMainSkip, false);
assert.equal(shouldDeploy(true, followupMainSkip), true, 'later ordinary main pushes remain deployable');

// Other PR merge, including a reused legacy marker, has a different second parent and deploys.
const otherPrSkip = decision({
  commitSha: unrelatedCommit,
  parents: [baseBefore, unrelatedHead],
  commitMessage: reusedMarker,
});
assert.equal(otherPrSkip, false);
assert.equal(shouldDeploy(true, otherPrSkip), true);

// PR #8's head arriving through a non-merge commit (e.g. squash/rebase/direct) is not the designated skip.
const pr8NonMergeSkip = decision({
  commitSha: unrelatedCommit,
  parents: [baseBefore],
  commitMessage: reusedMarker,
});
assert.equal(pr8NonMergeSkip, false);
assert.equal(shouldDeploy(true, pr8NonMergeSkip), true, 'non-merge delivery remains deployable');

// Missing/malformed target variable does not block ordinary one-parent main pushes.
for (const malformedTargetSha of [undefined, '', 'not-a-sha']) {
  const ordinarySkip = shouldSkipPr8Merge({
    beforeSha: baseBefore,
    commitSha: unrelatedCommit,
    parents: [baseBefore],
    expectedPr8HeadSha: malformedTargetSha,
  });
  assert.equal(ordinarySkip, false);
  assert.equal(shouldDeploy(true, ordinarySkip), true);
}

// But missing/malformed target binding on an ambiguous merge commit fails closed; dependent deploy jobs cannot run.
for (const malformedTargetSha of [undefined, '', 'not-a-sha']) {
  assert.throws(
    () => shouldSkipPr8Merge({
      beforeSha: baseBefore,
      commitSha: exactMergeCommit,
      parents: [baseBefore, pr8Head],
      expectedPr8HeadSha: malformedTargetSha,
    }),
    /valid ONLINE_HOMEWORK_PR8_MERGE_HEAD_SHA is required/,
  );
}

// Abnormal event SHA/parent input fails closed rather than treating an unknown merge as ordinary.
assert.throws(
  () => shouldSkipPr8Merge({ beforeSha: 'bad', commitSha: exactMergeCommit, parents: [baseBefore], expectedPr8HeadSha: pr8Head }),
  /beforeSha must be a full/,
);
assert.throws(
  () => shouldSkipPr8Merge({ beforeSha: baseBefore, commitSha: exactMergeCommit, parents: ['bad'], expectedPr8HeadSha: pr8Head }),
  /parents must be an array/,
);

// API-failure case: no PR association/state endpoint is used. A temporary PR API failure therefore cannot
// stop an ordinary push or change target classification when the push graph and pinned SHA are available.
const workflow = fs.readFileSync('.github/workflows/deploy-test.yml', 'utf8');
assert.doesNotMatch(workflow, /listPullRequestsAssociatedWithCommit|github\.rest\.pulls\.get/);
const targetWhilePrApiUnavailable = decision({
  commitSha: exactMergeCommit,
  parents: [baseBefore, pr8Head],
  prAssociationApiError: new Error('simulated HTTP 503'),
});
assert.equal(targetWhilePrApiUnavailable, true);
assert.equal(shouldDeploy(true, targetWhilePrApiUnavailable), false);
const ordinaryWhilePrApiUnavailable = decision({
  commitSha: unrelatedCommit,
  parents: [baseBefore],
  expectedPr8HeadSha: undefined,
  prAssociationApiError: new Error('simulated HTTP 503'),
  commitMessage: reusedMarker,
});
assert.equal(ordinaryWhilePrApiUnavailable, false);
assert.equal(shouldDeploy(true, ordinaryWhilePrApiUnavailable), true);

assert.equal(shouldDeploy(false, false), false, 'unchanged components do not deploy');
console.log('Deployment guard tests passed: exact PR #8 merge skips; later and unrelated pushes deploy; missing config does not block ordinary pushes; PR API is not used.');
