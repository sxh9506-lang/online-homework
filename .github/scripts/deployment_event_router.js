'use strict';

function mergedPrEligible({ action, merged, baseRef, number }) {
  const prNumber = Number(number);
  return action === 'closed'
    && merged === true
    && baseRef === 'main'
    && Number.isInteger(prNumber)
    && prNumber > 0
    && prNumber !== 8;
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

function deploymentCount(event) {
  return mergedPrEligible(event) ? 1 : 0;
}

module.exports = { mergedPrEligible, changedComponents, allComponents, deploymentCount };
