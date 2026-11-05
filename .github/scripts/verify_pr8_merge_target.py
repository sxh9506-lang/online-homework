#!/usr/bin/env python3
"""Ensure the one-time repository variable binds to the reviewed PR #8 head SHA."""

from __future__ import annotations

import os
import re
import sys

SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$", re.IGNORECASE)


def main() -> int:
    expected = os.environ.get("PR8_MERGE_HEAD_SHA", "").strip()
    reviewed = os.environ.get("PR_HEAD_SHA", "").strip()
    if not SHA_PATTERN.fullmatch(expected):
        raise ValueError("Repository variable ONLINE_HOMEWORK_PR8_MERGE_HEAD_SHA must contain a full commit SHA.")
    if not SHA_PATTERN.fullmatch(reviewed):
        raise ValueError("The current PR head SHA is missing or invalid.")
    if expected.lower() != reviewed.lower():
        raise ValueError("The configured one-time target SHA differs from PR #8's current reviewed head.")
    print("One-time merge target matches the current PR #8 head SHA.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as error:
        print(f"PR #8 merge target validation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
