#!/usr/bin/env python3
"""Regression checks for the audit's resource-table resolution; fictional data only."""

import unittest

from verify_apk import xml_resource_path, audit_accessibility_flags


class AccessibilityScopeTest(unittest.TestCase):
    def test_scoped_flag_and_pinned_previous_configuration(self):
        for flags in ("2", "0x2", "0x00000002", "flagIncludeNotImportantViews"):
            audit_accessibility_flags(flags, "current")
            audit_accessibility_flags(flags, "previous")
        audit_accessibility_flags("0", "previous")
        with self.assertRaises(ValueError):
            audit_accessibility_flags("0", "current")

    def test_expanded_window_or_unexpected_flags_are_rejected(self):
        for role in ("current", "previous"):
            for flags in ("64", "66", "0x42", "flagRetrieveInteractiveWindows", "flagIncludeNotImportantViews|flagReportViewIds", "-1"):
                with self.assertRaises(ValueError):
                    audit_accessibility_flags(flags, role)


class XmlResourceResolutionTest(unittest.TestCase):
    def table(self, values: str) -> str:
        return (
            "Package name=example id=7f\n"
            "  type xml id=0b entryCount=2\n"
            "    resource 0x7f0b0000 xml/data_extraction_rules\n"
            + values
            + "    resource 0x7f0b0001 xml/other_resource\n"
            "      () (file) res/other.xml type=XML\n"
        )

    def test_debug_path(self):
        self.assertEqual(
            xml_resource_path("data_extraction_rules", self.table(
                "      () (file) res/xml/data_extraction_rules.xml type=XML\n")),
            "res/xml/data_extraction_rules.xml",
        )

    def test_optimized_release_path(self):
        self.assertEqual(
            xml_resource_path("data_extraction_rules", self.table(
                "      () (file) res/4j.xml type=XML\n")),
            "res/4j.xml",
        )

    def test_last_resource_in_table(self):
        self.assertEqual(
            xml_resource_path("other_resource", self.table(
                "      () (file) res/4j.xml type=XML\n")),
            "res/other.xml",
        )

    def test_rejects_qualified_override_even_if_file_shared(self):
        with self.assertRaises(ValueError):
            xml_resource_path("data_extraction_rules", self.table(
                "      () (file) res/4j.xml type=XML\n"
                "      (v31) (file) res/4j.xml type=XML\n"))

    def test_rejects_only_qualified_value(self):
        with self.assertRaises(ValueError):
            xml_resource_path("data_extraction_rules", self.table(
                "      (v31) (file) res/4j.xml type=XML\n"))

    def test_rejects_alias(self):
        with self.assertRaises(ValueError):
            xml_resource_path("data_extraction_rules", self.table("      () @xml/other_resource\n"))

    def test_rejects_duplicate_resource_entry(self):
        with self.assertRaises(ValueError):
            xml_resource_path("data_extraction_rules", self.table(
                "      () (file) res/4j.xml type=XML\n")
                + "    resource 0x7f0b0002 xml/data_extraction_rules\n"
                "      () (file) res/second.xml type=XML\n")

    def test_rejects_non_resource_path(self):
        with self.assertRaises(ValueError):
            xml_resource_path("data_extraction_rules", self.table(
                "      () (file) res/../assets/data.xml type=XML\n"))


if __name__ == "__main__":
    unittest.main()
