#!/usr/bin/env python3
"""Fail closed unless CI results cover every changed component on protected main."""

from __future__ import annotations

import os
import sys
from collections.abc import Mapping


def evaluate(values: Mapping[str, str]) -> list[str]:
    errors: list[str] = []

    if values.get("EVENT_NAME", "").strip() != "push":
        errors.append("Release readiness is valid only for a push event.")
    if values.get("GITHUB_REF", "").strip() != "refs/heads/main":
        errors.append("Release readiness is valid only for refs/heads/main.")

    for key, label in (
        ("REPOSITORY_SANITY_RESULT", "repository-sanity"),
        ("CHANGES_RESULT", "changes detection"),
    ):
        result = values.get(key, "").strip().lower()
        if result != "success":
            errors.append(f"Required {label} check must succeed (got {result or 'missing'}).")

    for component in ("BACKEND", "FRONTEND"):
        changed = values.get(f"{component}_CHANGED", "").strip().lower()
        result = values.get(f"{component}_RESULT", "").strip().lower()
        label = component.lower()

        if changed not in {"true", "false"}:
            errors.append(f"{label} changed-path output must be true or false (got {changed or 'missing'}).")
            continue

        if changed == "true" and result != "success":
            errors.append(
                f"Changed {label} requires its complete CI job to succeed; got {result or 'missing'}."
            )
        elif changed == "false" and result not in {"success", "skipped"}:
            errors.append(
                f"Unchanged {label} job may only succeed or be skipped; got {result or 'missing'}."
            )

    return errors


def main() -> int:
    errors = evaluate(os.environ)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print("Release readiness passed: every changed component has a successful required CI job.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
