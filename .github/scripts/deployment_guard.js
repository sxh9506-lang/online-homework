'use strict';

const SHA_PATTERN = /^[0-9a-f]{40}$/i;

/**
 * Recognize only PR #8's explicitly selected GitHub merge-commit topology.
 * The PR association API and commit message are intentionally not inputs.
 */
function shouldSkipPr8Merge({ beforeSha, commitSha, parents, expectedPr8HeadSha }) {
  if (typeof beforeSha !== 'string' || !SHA_PATTERN.test(beforeSha)) {
    throw new TypeError('beforeSha must be a full 40-character commit SHA.');
  }
  if (typeof commitSha !== 'string' || !SHA_PATTERN.test(commitSha)) {
    throw new TypeError('commitSha must be a full 40-character commit SHA.');
  }
  if (!Array.isArray(parents) || parents.some((parent) => typeof parent !== 'string' || !SHA_PATTERN.test(parent))) {
    throw new TypeError('parents must be an array of full commit SHAs.');
  }

  // Ordinary, squash, and rebase commits are not the designated two-parent merge commit.
  // They remain deployable even if the one-time target variable is absent or malformed.
  if (parents.length !== 2 || parents[0].toLowerCase() !== beforeSha.toLowerCase()) {
    return false;
  }

  // An ambiguous two-parent merge must fail the decision job closed if the target binding is unavailable.
  // Deploy jobs depend on that decision job, so they cannot run on this error path.
  if (typeof expectedPr8HeadSha !== 'string' || !SHA_PATTERN.test(expectedPr8HeadSha)) {
    throw new TypeError('A valid ONLINE_HOMEWORK_PR8_MERGE_HEAD_SHA is required for a two-parent main merge.');
  }

  return parents[1].toLowerCase() === expectedPr8HeadSha.toLowerCase();
}

function shouldDeploy(componentChanged, skipForPr8Merge) {
  return Boolean(componentChanged) && !Boolean(skipForPr8Merge);
}

module.exports = { shouldSkipPr8Merge, shouldDeploy, SHA_PATTERN };
