"""SDK-independent regression check for the home-screen widget footprint.

Launchers such as PhantomShell honor targetCell* and min* literally, so an
oversized declaration fills the screen. Run from the checkout:
python3 app/checks/check_widget_size.py
"""

import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parents[1] / "src" / "main" / "res"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def dp(value):
    if value is None or not value.endswith("dp"):
        raise AssertionError("Expected a dp dimension, got " + repr(value))
    return int(value[:-2])


class WidgetSizeTest(unittest.TestCase):
    def setUp(self):
        # Only checked-in XML is parsed here, never untrusted input.
        self.info = ET.parse(RES / "xml" / "quota_widget.xml").getroot()  # noqa: S314

    def attr(self, name):
        value = self.info.get(ANDROID + name)
        if value is None:
            self.fail("Missing widget attribute: " + name)
        return value

    def test_default_footprint_is_compact(self):
        self.assertLessEqual(int(self.attr("targetCellWidth")), 4)
        self.assertLessEqual(int(self.attr("targetCellHeight")), 2)
        self.assertLessEqual(dp(self.attr("minWidth")), 180)
        self.assertLessEqual(dp(self.attr("minHeight")), 110)

    def test_user_can_shrink_below_default(self):
        self.assertEqual("horizontal|vertical", self.attr("resizeMode"))
        self.assertLess(dp(self.attr("minResizeWidth")), dp(self.attr("minWidth")))
        self.assertLess(dp(self.attr("minResizeHeight")), dp(self.attr("minHeight")))

    def test_layout_has_no_tall_fixed_rows(self):
        for name in ("widget_quota.xml",):
            layout = ET.parse(RES / "layout" / name).getroot()  # noqa: S314
            for node in layout.iter():
                height = node.get(ANDROID + "layout_height")
                if height and height.endswith("dp"):
                    with self.subTest(layout=name, view=node.get(ANDROID + "id")):
                        self.assertLessEqual(dp(height), 32)


if __name__ == "__main__":
    unittest.main()
