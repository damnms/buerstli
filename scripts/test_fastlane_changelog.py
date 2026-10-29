"""Tests for fastlane_changelog.py. Run: python3 -m unittest discover scripts"""
import unittest

from fastlane_changelog import LIMIT, MORE, to_store_text


class StoreTextTest(unittest.TestCase):
    def test_headings_become_labels_and_wrapped_lines_are_joined(self):
        md = (
            "### Added\n"
            "- Install and stay up to date with Obtainium, straight from the GitHub releases\n"
            "  (see README).\n"
            "\n"
            "### Fixed\n"
            "- Saving works.\n"
        )
        self.assertEqual(
            to_store_text(md),
            "Added:\n"
            "- Install and stay up to date with Obtainium, straight from the GitHub releases (see README).\n"
            "\n"
            "Fixed:\n"
            "- Saving works.",
        )

    def test_markdown_is_removed(self):
        md = "- **Bold** and `code` and a [link](https://example.org).\n"
        self.assertEqual(to_store_text(md), "- Bold and code and a link.")

    def test_long_notes_are_cut_at_a_line_and_point_to_the_full_changelog(self):
        md = "### Added\n" + "".join(f"- Feature number {i} with a fairly long description.\n" for i in range(30))
        text = to_store_text(md)
        self.assertLessEqual(len(text), LIMIT)
        self.assertTrue(text.endswith(MORE))
        self.assertNotIn("Feature number 29", text)
        # no half lines
        for line in text.splitlines()[:-1]:
            self.assertTrue(line == "" or line.endswith((".", ":")), line)


if __name__ == "__main__":
    unittest.main()
