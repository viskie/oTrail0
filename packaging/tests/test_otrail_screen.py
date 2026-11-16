from pathlib import Path
import sys
import unittest


SCRIPT = Path(__file__).parents[1] / "debian12" / "otrail_screen.py"
sys.path.insert(0, str(SCRIPT.parent))
import otrail_screen as screen


class WindowSelectionTests(unittest.TestCase):
    def test_parses_titles_with_spaces_and_keeps_wmctrl_order(self):
        output = (
            "0x04200007  0  0  0  1280  720 laptop  Browser - Project Docs\n"
            "0x03600004  0  10 20 900 600 laptop Terminal — shell\n"
            "invalid row\n"
        )
        windows = screen.parse_windows(output)
        self.assertEqual(
            [("0x04200007", "Browser - Project Docs"), ("0x03600004", "Terminal — shell")],
            [(window.window_id, window.title) for window in windows],
        )

    def test_selects_one_based_number_in_displayed_order(self):
        windows = [screen.Window("0x1", "first"), screen.Window("0x2", "second")]
        self.assertEqual(windows[1], screen.select_window(windows, "2"))

    def test_rejects_non_numeric_and_out_of_range_selections(self):
        windows = [screen.Window("0x1", "only")]
        for value in ("0", "2", "one"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                screen.select_window(windows, value)


if __name__ == "__main__":
    unittest.main()
