# Contributing

## Workflow

- Work in a focused branch and open a pull request; do not push feature work directly to `main`.
- Keep PRs small and scoped to one layer/module. Avoid editing another PR's files; coordinate contract changes first.
- Treat `docs/openapi.yaml` and `docs/mvp-contract.md` as the v1 source of truth. If implementation reveals a necessary change, update the contract in the same PR and explain why.
- Do not add parent/mobile/import/notifications/reporting/automatic-grading scope or Redis/queues without a concrete approved need.
- Never commit credentials, signed URLs, or local environment files. Do not make object-storage buckets public.

## Required before merge

- Build and tests pass for changed modules.
- Backend authorization is enforced server-side for each object and file operation.
- Add or update an acceptance test for every behavior change, especially tenant/ownership boundaries, version retention, late/closed deadlines, published-grade visibility, and file limits.
- State any unresolved product or deployment decision in the PR description; do not silently pick an account-onboarding model.
