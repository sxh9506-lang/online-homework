'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const {
  directPushEligible,
  mergedPrEligible,
  mergeCommitOnly,
  countDeployments,
} = require('./deployment_event_router.js');

const base = '1'.repeat(40);
const pr8Head = '2'.repeat(40);
const otherPrHead = '3'.repeat(40);
const directCommit = '4'.repeat(40);
const reusedMarker = '[skip-test-deploy-pr8-bd179c7]';
const closed = (number, merged = true, baseRef = 'main') => ({ action: 'closed', merged, baseRef, number });

// Target PR #8 merge-commit event: push router suppresses every merge commit; closed event skips #8.
const pr8Merge = countDeployments({ parentShas: [base, pr8Head], pullRequestEvent: closed(8) });
assert.deepEqual(pr8Merge, { pushRoute: false, prRoute: false, total: 0 });

// Unrelated PR merge: push route suppresses the merge commit; trusted closed-PR route deploys exactly once.
const otherPrMerge = countDeployments({ parentShas: [base, otherPrHead], pullRequestEvent: closed(42) });
assert.deepEqual(otherPrMerge, { pushRoute: false, prRoute: true, total: 1 });
assert.equal(mergedPrEligible({ ...closed(42), commitMessage: reusedMarker }), true, 'a copied marker is irrelevant');

// Direct main push deploys exactly once and has no PR closed event.
const directPush = countDeployments({ parentShas: [base], pullRequestEvent: null });
assert.deepEqual(directPush, { pushRoute: true, prRoute: false, total: 1 });
assert.equal(directPushEligible([directCommit]), true);

// Unmerged, wrong-base, wrong-action and invalid PR number events never deploy.
for (const event of [
  closed(42, false),
  closed(42, true, 'develop'),
  { ...closed(42), action: 'opened' },
  { ...closed('not-a-number') },
]) {
  assert.equal(mergedPrEligible(event), false);
}

// If PR #8 is squashed/rebased, its single-parent push would deploy before the closed event skips it.
const pr8NonMerge = countDeployments({ parentShas: [base], pullRequestEvent: closed(8) });
assert.deepEqual(pr8NonMerge, { pushRoute: true, prRoute: false, total: 1 });

// If another PR is squashed/rebased, both workflows would deploy; the repository must prohibit this mode.
const otherPrNonMerge = countDeployments({ parentShas: [base], pullRequestEvent: closed(42) });
assert.deepEqual(otherPrNonMerge, { pushRoute: true, prRoute: true, total: 2 });

// Policy fixtures: only merge commits enabled is safe; unsafe combinations are rejected by the matrix.
assert.equal(mergeCommitOnly({ allow_merge_commit: true, allow_squash_merge: false, allow_rebase_merge: false }), true);
assert.equal(mergeCommitOnly({ allow_merge_commit: true, allow_squash_merge: true, allow_rebase_merge: true }), false);
assert.equal(mergeCommitOnly({ allow_merge_commit: false, allow_squash_merge: false, allow_rebase_merge: false }), false);
assert.equal(mergeCommitOnly({ allow_merge_commit: true, allow_squash_merge: false, allow_rebase_merge: true }), false);

// GitHub PR association/API failures cannot affect direct-push routing: no such API is called.
const pushWorkflow = fs.readFileSync('.github/workflows/deploy-test.yml', 'utf8');
const prWorkflow = fs.readFileSync('.github/workflows/deploy-test-pr.yml', 'utf8');
assert.doesNotMatch(pushWorkflow, /listPullRequestsAssociatedWithCommit|github\.rest\.pulls\./);
assert.doesNotMatch(prWorkflow, /github\.rest\.pulls\./);
assert.doesNotMatch(prWorkflow, /actions\/checkout|pull_request\.head\.sha/);
assert.match(pushWorkflow, /deployment_event_router\.js/);
assert.match(prWorkflow, /github\.event\.pull_request\.number != 8/);
assert.doesNotMatch(pushWorkflow + prWorkflow, /skip-test-deploy-pr8-bd179c7|head_commit\.message/);

// Pure router tests cannot trigger any deployment hook.
console.log('Event-router matrix passed under merge-only: PR #8=0, other merge=1, direct push=1; squash/rebase duplicates are detected and rejected.');
