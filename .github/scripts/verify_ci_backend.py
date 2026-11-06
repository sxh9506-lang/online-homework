#!/usr/bin/env python3
"""Run backend verification without emitting CI demo credentials or password hashes."""

from __future__ import annotations

import argparse
import base64
import html
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote, quote_plus

SECRET_NAMES = ("DEMO_TEACHER_PASSWORD", "DEMO_STUDENT_PASSWORD")
MIN_CHARACTERS = 16
MAX_UTF8_BYTES = 72
BCRYPT_HASH = re.compile(rb"\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}")
RANDOM_TEST_SECRET = re.compile(
    rb"(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[0-9a-f]{32}(?![0-9a-f])"
)
REPORT_DIRECTORIES = (
    Path("backend/target/surefire-reports"),
    Path("backend/target/failsafe-reports"),
)
REPORT_SUFFIXES = {".xml", ".txt", ".dump", ".dumpstream"}


def credential_variants(values: list[str]) -> list[bytes]:
    variants: set[bytes] = set()
    for value in values:
        if not value:
            continue
        encoded = (
            value,
            html.escape(value, quote=True),
            json.dumps(value, ensure_ascii=True)[1:-1],
            quote(value, safe=""),
            quote_plus(value),
            base64.b64encode(value.encode("utf-8")).decode("ascii"),
        )
        variants.update(item.encode("utf-8") for item in encoded if item)
    return sorted(variants, key=len, reverse=True)


def contains_sensitive_data(content: bytes, variants: list[bytes]) -> bool:
    return (
        any(value in content for value in variants)
        or BCRYPT_HASH.search(content) is not None
        or RANDOM_TEST_SECRET.search(content) is not None
    )


def sanitize_log(content: bytes, variants: list[bytes]) -> str:
    text = content.decode("utf-8", errors="replace")
    for value in variants:
        text = text.replace(value.decode("utf-8", errors="replace"), "[REDACTED]")
    safe_bytes = text.encode("utf-8")
    safe_bytes = BCRYPT_HASH.sub(b"[BCRYPT_HASH_REDACTED]", safe_bytes)
    safe_bytes = RANDOM_TEST_SECRET.sub(b"[RANDOM_TEST_SECRET_REDACTED]", safe_bytes)
    return safe_bytes.decode("utf-8", errors="replace")


def inspect_and_remove_reports(variants: list[bytes]) -> bool:
    found_sensitive_data = False
    for report_dir in REPORT_DIRECTORIES:
        if not report_dir.exists():
            continue
        for path in report_dir.rglob("*"):
            if path.is_file() and path.suffix.lower() in REPORT_SUFFIXES:
                try:
                    found_sensitive_data |= contains_sensitive_data(path.read_bytes(), variants)
                except OSError:
                    found_sensitive_data = True
        try:
            shutil.rmtree(report_dir)
        except OSError:
            found_sensitive_data = True
    return found_sensitive_data


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--without-ci-demo-secrets",
        action="store_true",
        help="run the fork-safe test path and require the CI demo Secret environment to be empty",
    )
    args = parser.parse_args()

    values = [os.environ.get(name, "") for name in SECRET_NAMES]
    if args.without_ci_demo_secrets:
        if any(values):
            print("Fork-safe verification unexpectedly received a CI demo Secret.", file=sys.stderr)
            return 1
        values = []
    else:
        for name, value in zip(SECRET_NAMES, values, strict=True):
            if len(value) < MIN_CHARACTERS or len(value.encode("utf-8")) > MAX_UTF8_BYTES:
                print(f"{name} is missing or outside the allowed length bounds.", file=sys.stderr)
                return 1

    variants = credential_variants(values)
    wrapper = Path("backend/mvnw")
    if wrapper.is_file() and os.access(wrapper, os.X_OK):
        command = ["./mvnw", "--batch-mode", "verify"]
        cwd = "backend"
    else:
        command = ["mvn", "--batch-mode", "--file", "backend/pom.xml", "verify"]
        cwd = None

    try:
        result = subprocess.run(
            command,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        raw_output = result.stdout or b""
    except OSError:
        print("Backend verification could not start; command output was withheld.", file=sys.stderr)
        inspect_and_remove_reports(variants)
        return 1

    log_leak = contains_sensitive_data(raw_output, variants)
    report_leak = inspect_and_remove_reports(variants)
    safe_output = sanitize_log(raw_output, variants)
    if safe_output:
        sys.stdout.write(safe_output)
        if not safe_output.endswith("\n"):
            sys.stdout.write("\n")

    if log_leak or report_leak:
        print("Sensitive demo credential material was detected and redacted; test reports were removed.",
              file=sys.stderr)
        return 1
    if result.returncode != 0:
        print(f"Backend verification failed (Maven exit code {result.returncode}); output above was scanned.",
              file=sys.stderr)
        return result.returncode
    print("Backend verification passed; Maven output was scanned and test reports were removed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
