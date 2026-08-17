#!/usr/bin/env python3
"""Validate Hearth specification documents without requiring runtime code."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DRIFT_TERMS = (
    "hearth_dispatch_a2a",
    "hearth_checkpoint_done",
    "完整 V001",
    "六个模块",
    "M1.*MCP server",
    "Pi.*不使用自身 Provider",
    "Pi.*工具调用.*权限检查",
)
CREDENTIAL_PATTERNS = (
    re.compile(r"sk-ant-[A-Za-z0-9_-]{20,}"),
    re.compile(r"(?:^|[^A-Za-z])sk-[A-Za-z0-9]{20,}"),
    re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}"),
    re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
    re.compile(r"xox[baprs]-[A-Za-z0-9-]{20,}"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
)
LINK_PATTERN = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
FENCE_PATTERN = re.compile(r"^```(?P<language>[^\s`]*)\s*$")


def markdown_files() -> list[Path]:
    return sorted(
        path
        for path in ROOT.rglob("*.md")
        if ".git" not in path.parts and ".claude/worktrees" not in path.parts
    )


def check_links(files: list[Path]) -> list[str]:
    failures: list[str] = []
    for path in files:
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            for target in LINK_PATTERN.findall(line):
                target = target.split("#", 1)[0].strip().strip("<>")
                if not target or "://" in target or target.startswith("mailto:"):
                    continue
                resolved = (path.parent / target).resolve()
                if not resolved.exists():
                    failures.append(f"{path.relative_to(ROOT)}:{line_number}: missing link {target}")
    return failures


def check_json_fences(files: list[Path]) -> list[str]:
    failures: list[str] = []
    for path in files:
        lines = path.read_text(encoding="utf-8").splitlines()
        in_json = False
        start = 0
        payload: list[str] = []
        for line_number, line in enumerate(lines, 1):
            match = FENCE_PATTERN.match(line)
            if match and not in_json:
                in_json = match.group("language").lower() == "json"
                start = line_number
                payload = []
            elif line == "```" and in_json:
                try:
                    json.loads("\n".join(payload))
                except json.JSONDecodeError as error:
                    failures.append(
                        f"{path.relative_to(ROOT)}:{start}: invalid JSON example: {error.msg}"
                    )
                in_json = False
            elif in_json:
                payload.append(line)
        if in_json:
            failures.append(f"{path.relative_to(ROOT)}:{start}: unterminated JSON fence")
    return failures


def check_credentials(files: list[Path]) -> list[str]:
    failures: list[str] = []
    for path in files:
        text = path.read_text(encoding="utf-8")
        for pattern in CREDENTIAL_PATTERNS:
            match = pattern.search(text)
            if match:
                line_number = text.count("\n", 0, match.start()) + 1
                failures.append(
                    f"{path.relative_to(ROOT)}:{line_number}: credential-like value matches {pattern.pattern}"
                )
    return failures


def check_drift() -> list[str]:
    command = [
        "rg",
        "-n",
        "--glob",
        "!16-spec-governance.md",
        "|".join(DRIFT_TERMS),
        "AGENTS.md",
        "CLAUDE.md",
        "README.md",
        "docs",
        ".claude",
    ]
    result = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, check=False)
    return result.stdout.splitlines() if result.returncode == 0 else []


def main() -> int:
    failures: list[str] = []
    files = markdown_files()
    failures.extend(check_links(files))
    failures.extend(check_json_fences(files))
    failures.extend(check_credentials(files))
    drift = check_drift()
    if drift:
        failures.append("drift check matched old terminology:")
        failures.extend(f"  {line}" for line in drift)

    if failures:
        print("Document validation failed:")
        print("\n".join(failures))
        return 1

    print(f"Document validation passed ({len(files)} Markdown files checked).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
