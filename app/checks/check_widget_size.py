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
        self.assertLessEqual(dp(self.attr("minHeight")), 90)

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


class ThemeWiringTest(unittest.TestCase):
    """Source integration guards, not Android runtime or pixel-rendering tests."""

    def source(self, name):
        return (RES.parent / "kotlin" / "dev" / "wahh" / "quotashell" / name).read_text()

    def test_board_uses_phantom_theme_and_every_rendered_slot(self):
        renderer = self.source("PhantomBoardRenderer.kt")
        self.assertIn("theme: PhantomTheme", renderer)
        for slot in ("outline", "window", "text", "subtext", "subtext2", "accent", "notifications", "background"):
            self.assertIn('theme.color("' + slot + '")', renderer)
        for option in ("renderWindowShape", "renderButtonShape", "renderNotificationShape", "fontSizeOffset", "displayText"):
            self.assertIn("theme." + option, renderer)
        self.assertIn("store.phantomTheme()", self.source("QuotaWidget.kt"))
        self.assertIn("allCaps = false", self.source("QuotaWidget.kt"))

    def test_compact_donuts_and_deterministic_glitch_are_wired(self):
        renderer = self.source("PhantomBoardRenderer.kt")
        for symbol in ("donutSweep", "glitchSlices", "glitchSeed", "drawArc", "scanlines", "columnWidth"):
            self.assertIn(symbol, renderer)
        self.assertNotIn("private fun segments", renderer)

    def test_burst_uses_separate_updates_and_respects_animation_preferences(self):
        widget = self.source("QuotaWidget.kt")
        for symbol in ("shouldGlitchBurst", "ANIMATOR_DURATION_SCALE", "Handler(Looper.getMainLooper())", "postDelayed", "notificationAnimation"):
            self.assertIn(symbol, widget)
        self.assertIn("NOTIFICATION_ANIMATIONS", self.source("ThemeEditor.kt"))
        self.assertIn("withNotificationAnimation", self.source("ThemeEditor.kt"))

    def test_theme_document_actions_and_nonsecret_storage_are_wired(self):
        settings = self.source("SettingsActivity.kt") + self.source("ThemeDocumentIo.kt")
        for action in ("ACTION_OPEN_DOCUMENT", "ACTION_CREATE_DOCUMENT"):
            self.assertIn(action, settings)
        self.assertIn("application/json", settings)
        self.assertIn("PhantomTheme.MAX_BYTES", settings)
        self.assertIn("PhantomTheme.parse", settings)
        self.assertIn("Thread", settings)
        store = self.source("SnapshotStore.kt")
        self.assertIn('putString("phantomTheme"', store)
        self.assertIn("PhantomTheme.migrateLegacy", store)


if __name__ == "__main__":
    unittest.main()
