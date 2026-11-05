'use strict';

const TARGET_PR_NUMBER = 8;

/**
 * Skip only the exact merge commit GitHub associates with PR #8.
 * Commit messages and titles are intentionally not inputs to this decision.
 */
function shouldSkipPr8Merge({ commitSha, associatedPullRequests }) {
  if (typeof commitSha !== 'string' || commitSha.length === 0) {
    throw new TypeError('A push commit SHA is required.');
  }
  if (!Array.isArray(associatedPullRequests)) {
    throw new TypeError('Associated pull request metadata is required.');
  }

  const pr8 = associatedPullRequests.find((pr) => pr.number === TARGET_PR_NUMBER);
  if (!pr8) return false;
  if (!pr8.merged_at || !pr8.merge_commit_sha) {
    throw new Error('PR #8 is associated with this commit, but its merged commit SHA is not confirmed.');
  }

  return pr8.merge_commit_sha.toLowerCase() === commitSha.toLowerCase();
}

function shouldDeploy(componentChanged, skipForPr8Merge) {
  return Boolean(componentChanged) && !Boolean(skipForPr8Merge);
}

module.exports = { shouldSkipPr8Merge, shouldDeploy, TARGET_PR_NUMBER };
