'use strict';

const { shouldSkipPr8Merge } = require('./deployment_guard.js');

const [beforeSha, commitSha, expectedPr8HeadSha, parentsRaw] = process.argv.slice(2);
if ([beforeSha, commitSha, expectedPr8HeadSha, parentsRaw].some((value) => typeof value !== 'string')) {
  console.error('Usage: evaluate_deployment_guard.js <before-sha> <push-sha> <expected-pr8-head-sha> <parent-shas>');
  process.exit(2);
}

const parents = parentsRaw.trim() === '' ? [] : parentsRaw.trim().split(/\s+/);
const skip = shouldSkipPr8Merge({ beforeSha, commitSha, parents, expectedPr8HeadSha });
console.log(`skip=${skip ? 'true' : 'false'}`);
