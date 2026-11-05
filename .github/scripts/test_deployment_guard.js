'use strict';

const assert = require('node:assert/strict');
const { shouldSkipPr8Merge, shouldDeploy, TARGET_PR_NUMBER } = require('./deployment_guard.js');

const pr8MergeSha = 'a'.repeat(40);
const unrelatedMainSha = 'b'.repeat(40);
const pr8Merged = {
  number: TARGET_PR_NUMBER,
  merged_at: '2026-11-05T00:00:00Z',
  merge_commit_sha: pr8MergeSha,
};
const reusedOldMarker = '[skip-test-deploy-pr8-bd179c7]';

// Positive: only the exact merge_commit_sha associated with merged PR #8 is skipped.
const targetMergeSkip = shouldSkipPr8Merge({
  commitSha: pr8MergeSha,
  associatedPullRequests: [pr8Merged],
});
assert.equal(targetMergeSkip, true, 'the exact PR #8 merge commit must be recognized');
assert.equal(shouldDeploy(true, targetMergeSkip), false, 'the target PR #8 merge must not deploy');

// Negative: an unrelated main commit remains deployable even when its message reuses the old marker.
const copiedMarkerSkip = shouldSkipPr8Merge({
  commitSha: unrelatedMainSha,
  associatedPullRequests: [{ number: 12, merged_at: '2026-11-05T00:00:00Z', merge_commit_sha: unrelatedMainSha }],
  commitMessage: reusedOldMarker,
});
assert.equal(copiedMarkerSkip, false, 'a different PR with the old marker must not be skipped');
assert.equal(shouldDeploy(true, copiedMarkerSkip), true, 'an unrelated main commit with the old marker must deploy');

// Negative: a commit associated with PR #8 but not equal to its merge commit is not the one-time exception.
const otherPr8CommitSkip = shouldSkipPr8Merge({
  commitSha: unrelatedMainSha,
  associatedPullRequests: [pr8Merged],
  commitMessage: reusedOldMarker,
});
assert.equal(otherPr8CommitSkip, false, 'only PR #8 merge_commit_sha itself may be skipped');
assert.equal(shouldDeploy(true, otherPr8CommitSkip), true, 'a different PR #8-associated commit remains deployable');

// Negative: the same old marker without any PR #8 association has no effect.
const unassociatedCopiedMarkerSkip = shouldSkipPr8Merge({
  commitSha: unrelatedMainSha,
  associatedPullRequests: [],
  commitMessage: reusedOldMarker,
});
assert.equal(unassociatedCopiedMarkerSkip, false, 'commit-message text alone must never suppress deployment');
assert.equal(shouldDeploy(true, unassociatedCopiedMarkerSkip), true);

// Fail closed if GitHub associates a commit with PR #8 but cannot confirm its merged SHA.
assert.throws(
  () => shouldSkipPr8Merge({ commitSha: unrelatedMainSha, associatedPullRequests: [{ number: TARGET_PR_NUMBER }] }),
  /merged commit SHA is not confirmed/,
);
assert.equal(shouldDeploy(false, false), false, 'unchanged components do not deploy');

console.log('Deployment guard tests passed: exact PR #8 merge skips; other main commits, including copied-marker commits, deploy.');
