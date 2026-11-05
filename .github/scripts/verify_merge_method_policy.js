'use strict';

async function verifyMergeMethodPolicy(github, owner, repo, core) {
  const { data } = await github.rest.repos.get({ owner, repo });
  const policy = {
    allow_merge_commit: data.allow_merge_commit,
    allow_squash_merge: data.allow_squash_merge,
    allow_rebase_merge: data.allow_rebase_merge,
  };
  core.info(`Merge-method policy: merge=${policy.allow_merge_commit}, squash=${policy.allow_squash_merge}, rebase=${policy.allow_rebase_merge}`);
  const { mergeCommitOnly } = require('./deployment_event_router.js');
  if (!mergeCommitOnly(policy)) {
    core.setFailed('Event-level deployment routing requires merge commits only: enable merge commits and disable squash/rebase. No repository settings were changed by this check.');
    return false;
  }
  return true;
}

module.exports = { verifyMergeMethodPolicy };
