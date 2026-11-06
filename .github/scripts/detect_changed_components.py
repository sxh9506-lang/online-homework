#!/usr/bin/env python3
"""Map changed repository paths to the CI component jobs that must run."""

from __future__ import annotations

import sys
from collections.abc import Iterable


def classify_changed_files(paths: Iterable[str]) -> dict[str, bool]:
    changed = {path.strip() for path in paths if path.strip()}
    return {
        # The API Docker build uses the repository root as context, so root
        # .dockerignore affects the image even though it is outside backend/.
        "backend": ".dockerignore" in changed or any(path.startswith("backend/") for path in changed),
        "frontend": any(path.startswith("frontend/") for path in changed),
    }


def main() -> int:
    components = classify_changed_files(sys.stdin.read().splitlines())
    for component, changed in components.items():
        print(f"{component}={'true' if changed else 'false'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
