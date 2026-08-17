#!/usr/bin/env python3
"""Focused tests for the design-phase documentation validator."""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("validate_docs.py")
SPEC = importlib.util.spec_from_file_location("validate_docs", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("Could not load validate_docs.py")
validator = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(validator)


class DocumentValidatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary_directory.name)
        self.original_root = validator.ROOT
        validator.ROOT = self.root

    def tearDown(self) -> None:
        validator.ROOT = self.original_root
        self.temporary_directory.cleanup()

    def write(self, relative_path: str, content: str) -> Path:
        path = self.root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def test_given_valid_relative_link_when_checked_then_no_failure(self) -> None:
        document = self.write("docs/guide.md", "[other](other.md)\n")
        self.write("docs/other.md", "# Other\n")

        self.assertEqual([], validator.check_links([document]))

    def test_given_missing_relative_link_when_checked_then_reports_source_line(self) -> None:
        document = self.write("docs/guide.md", "[missing](missing.md)\n")

        self.assertEqual(
            ["docs/guide.md:1: missing link missing.md"],
            validator.check_links([document]),
        )

    def test_given_json_examples_when_checked_then_rejects_only_invalid_document(self) -> None:
        valid = self.write("docs/valid.md", "```json\n{\"state\": \"ready\"}\n```\n")
        invalid = self.write("docs/invalid.md", "```json\n{not-json}\n```\n")

        self.assertEqual([], validator.check_json_fences([valid]))
        self.assertEqual(
            ["docs/invalid.md:1: invalid JSON example: Expecting property name enclosed in double quotes"],
            validator.check_json_fences([invalid]),
        )

    def test_given_credential_signature_when_checked_then_reports_exact_line(self) -> None:
        document = self.write("docs/secret.md", "safe\nsk-ant_ignored\nsk-ant-abcdefghijabcdefghij\n")

        failure = validator.check_credentials([document])

        self.assertEqual(1, len(failure))
        self.assertTrue(failure[0].startswith("docs/secret.md:3:"))


if __name__ == "__main__":
    unittest.main()
