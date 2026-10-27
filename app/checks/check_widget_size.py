"""SDK-independent regression check for the home-screen widget footprint.

Launchers such as PhantomShell honor targetCell* and min* literally, so an
oversized declaration fills the screen. Run from the checkout:
python3 app/checks/check_widget_size.py
"""

import struct
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

    def test_periodic_overlay_autostarts_and_does_not_intercept_taps(self):
        layout = ET.parse(RES / "layout" / "widget_quota.xml").getroot()  # noqa: S314
        flipper = layout.find("ViewFlipper")
        if flipper is None:
            self.fail("Missing periodic glitch ViewFlipper")
        self.assertEqual("true", flipper.get(ANDROID + "autoStart"))
        self.assertEqual("200", flipper.get(ANDROID + "flipInterval"))
        self.assertEqual("false", flipper.get(ANDROID + "clickable"))

    def test_refresh_is_explicit_background_broadcast(self):
        source = (RES.parent / "kotlin" / "dev" / "wahh" / "quotashell" / "QuotaWidget.kt").read_text()
        self.assertIn("PendingIntent.getBroadcast", source)
        self.assertIn("RefreshJob.requestWidgetRefresh", source)
        self.assertIn("syncing = RefreshJob.widgetRefreshPending", source)
        job = (RES.parent / "kotlin" / "dev" / "wahh" / "quotashell" / "RefreshJob.kt").read_text()
        for symbol in ("shouldEnqueueWidgetRefresh", "NETWORK_TYPE_ANY", "jobFinished", "WIDGET_JOB_ID = 101"):
            self.assertIn(symbol, job)
        manifest = ET.parse(RES.parent / "AndroidManifest.xml").getroot()  # noqa: S314
        receiver = manifest.find("application/receiver")
        if receiver is None:
            self.fail("Missing widget receiver")
        self.assertEqual("false", receiver.get(ANDROID + "exported"))

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
        renderer = self.source("PhantomBoardRenderer.kt") + self.source("PhantomGraphics.kt")
        self.assertIn("theme: PhantomTheme", renderer)
        for slot in ("outline", "window", "text", "subtext", "subtext2", "accent", "notifications", "background"):
            self.assertIn('theme.color("' + slot + '")', renderer)
        for option in ("renderWindowShape", "renderButtonShape", "renderNotificationShape", "fontSizeOffset", "displayText"):
            self.assertIn("theme." + option, renderer)
        self.assertIn("store.phantomTheme()", self.source("QuotaWidget.kt"))
        self.assertIn("allCaps = false", self.source("QuotaWidget.kt"))

    def test_compact_donuts_and_deterministic_glitch_are_wired(self):
        renderer = self.source("PhantomBoardRenderer.kt")
        for symbol in ("donutSweep", "signalLoss", "glitchSeed", "drawArc", "columnWidth", "boardFit", "quotaItemWidth"):
            self.assertIn(symbol, renderer)
        self.assertNotIn("scanlines", renderer)
        graphics = self.source("PhantomGraphics.kt")
        for symbol in ("signalLossPlan", "decodeReveal", "signalMix", "canvas.translate(dx", "PorterDuff.Mode.SRC", "canvas.clipRect(region)"):
            self.assertIn(symbol, graphics)
        for removed in ("rgbOffset", "jitterX", "glitchFrame", "GlitchFrame", "glitchSlices", "substituteGlitchGlyphs"):
            self.assertNotIn(removed, graphics + renderer + self.source("ui/AppGlitch.kt"))
        self.assertIn("metrics.header + metrics.cardTop", renderer)
        self.assertIn("metrics.footer - 13", renderer)
        core = RES.parents[2] / "core/src/main/kotlin/dev/wahh/quotashell/core"
        plan = (core / "SignalGlitch.kt").read_text()
        for removed in ("NO SIGNAL", "N O   S I G N A L", "noSignal", "signRows"):
            self.assertNotIn(removed, plan + renderer + graphics + self.source("ui/AppGlitch.kt"))
        self.assertIn("MAX_FRAME_BYTES = 2 * 1024 * 1024", renderer)
        self.assertNotIn("private fun segments", renderer)
        for symbol in ("allocateColumns", "requiredWidths", "graphics.measure", "ChartStyle.CELLS", "ChartStyle.TICKS", "filledSegments"):
            self.assertIn(symbol, renderer)
        editor = self.source("ThemeEditor.kt")
        self.assertIn("Quota chart style", editor)
        self.assertIn("withChartStyle", editor)

    def test_burst_uses_separate_updates_and_respects_animation_preferences(self):
        widget = self.source("QuotaWidget.kt")
        for symbol in ("shouldGlitchBurst", "ANIMATOR_DURATION_SCALE", "Handler(Looper.getMainLooper())", "postDelayed", "notificationAnimation"):
            self.assertIn(symbol, widget)
        self.assertIn("NOTIFICATION_ANIMATIONS", self.source("ThemeEditor.kt"))
        self.assertIn("withNotificationAnimation", self.source("ThemeEditor.kt"))
        overlay = self.source("WidgetGlitchOverlay.kt")
        for symbol in ("periodicGlitchEnabled", "GlitchSchedule", "View.GONE", "removeAllViews", "glitchIndices"):
            self.assertIn(symbol, overlay)
        self.assertNotIn("drawRect", overlay)
        self.assertNotIn("Bitmap.createBitmap", overlay)
        self.assertIn("renderFrame(index)", overlay)
        self.assertIn("else cleanFrame", overlay)
        self.assertIn("R.id.widget_board, if (enabled) View.INVISIBLE else View.VISIBLE", overlay)
        self.assertNotIn("0xff shl 24", self.source("PhantomBoardRenderer.kt"))
        self.assertIn("setOnClickPendingIntent(R.id.widget_glitch", widget)
        self.assertIn("enabled && frame == 0", widget)
        for symbol in ("geometry.sync.widthPixels", "geometry.sync.heightPixels", "geometry.sync.topPixels", "geometry.sync.endPixels", "setViewPadding"):
            self.assertIn(symbol, widget)
        renderer = self.source("PhantomBoardRenderer.kt")
        for symbol in ("widgetSyncGeometry(", "geometry.sync.endDp / geometry.fit", "geometry.sync.widthDp / geometry.fit", "geometry.sync.topDp / geometry.fit", "geometry.sync.heightDp / geometry.fit"):
            self.assertIn(symbol, renderer)
        self.assertIn("burstFrame == 0", renderer)
        self.assertIn("revealProgress = if (frame == 0) 1.0", widget)
        self.assertIn("frame <= 4", widget)
        self.assertIn("225L", widget)
        layout = ET.parse(RES / "layout/widget_quota.xml").getroot()  # noqa: S314
        self.assertEqual("ltr", layout.get(ANDROID + "layoutDirection"))
        self.assertIn('android:layout_gravity="top|end"', (RES / "layout/widget_quota.xml").read_text())

    def test_startup_reconciles_cached_syncing_and_jobs_use_execution_tokens(self):
        app = self.source("QuotaApplication.kt")
        startup = app.split("override fun onCreate()", 1)[1].split("fun replaceCredential", 1)[0]
        self.assertIn("QuotaWidget.updateAll(this)", startup)
        job = self.source("RefreshJob.kt")
        for symbol in ("JobExecutions()", "executions.start(params.jobId)", "executions.stop(params.jobId)",
                       "executions.complete(params.jobId, token)"):
            self.assertIn(symbol, job)
        self.assertNotIn("mutableSetOf<JobParameters>", job)

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


def font_measure(font_path):
    """Nominal advances from the bundled sfnt, without SDK or third-party dependencies.

    This measures the default zero-offset Latin fixture, not rasterizer hinting or
    a device screenshot. Android Paint uses these same font advances.
    """
    data = font_path.read_bytes()
    def u16(offset):
        return struct.unpack_from(">H", data, offset)[0]

    def u32(offset):
        return struct.unpack_from(">I", data, offset)[0]
    tables = {}
    for index in range(u16(4)):
        offset = 12 + index * 16
        tables[data[offset:offset + 4].decode("ascii")] = u32(offset + 8)
    units = u16(tables["head"] + 18)
    metrics = u16(tables["hhea"] + 34)
    cmap = tables["cmap"]
    subtables = [cmap + u32(cmap + 4 + i * 8 + 4) for i in range(u16(cmap + 2))]
    mapping = next(offset for offset in subtables if u16(offset) == 4)
    segments = u16(mapping + 6) // 2
    ends = mapping + 14
    starts = ends + segments * 2 + 2
    deltas = starts + segments * 2
    ranges = deltas + segments * 2

    def glyph(char):
        code = ord(char)
        for index in range(segments):
            if u16(starts + index * 2) <= code <= u16(ends + index * 2):
                delta = u16(deltas + index * 2)
                address = ranges + index * 2
                distance = u16(address)
                if not distance:
                    return (code + delta) & 0xFFFF
                result = u16(address + distance + 2 * (code - u16(starts + index * 2)))
                return (result + delta) & 0xFFFF if result else 0
        return 0

    def measure(text, size):
        return sum(u16(tables["hmtx"] + 4 * min(glyph(char), metrics - 1)) for char in text) * size / units
    return measure


class WidgetMetricsTest(unittest.TestCase):
    def test_default_six_window_metrics_and_remoteviews_budget(self):
        src = RES.parent / "kotlin/dev/wahh/quotashell"
        renderer = (src / "PhantomBoardRenderer.kt").read_text()
        core = RES.parents[2] / "core/src/main/kotlin/dev/wahh/quotashell/core"
        geometry = (core / "Glitch.kt").read_text()
        schedule = (core / "CyberWidget.kt").read_text()
        self.assertIn("const val PAD = 3f", renderer)
        self.assertIn("const val GAP = 2f", renderer)
        for metric in ("val header = 38f", "val footer = 9f", "val cardHeight = 90f"):
            self.assertIn(metric, renderer)
        self.assertIn("pixels(34 + extra * 2)", geometry)
        self.assertIn("children = cleanChildren + 4", schedule)
        self.assertIn("MAX_FRAME_BYTES = 2 * 1024 * 1024", renderer)
        fonts = RES.parent / "assets/fonts"
        regular = font_measure(fonts / "AdwaitaMono-Regular.ttf")
        bold = font_measure(fonts / "AdwaitaMono-Bold.ttf")
        # Representative 1/2/3-window connected board, natural case, default theme.
        providers = [("Codex", "CDX-01", ["Week"], "Resets · 0"),
                     ("Claude", "CLD-02", ["5h", "Week"], ""),
                     ("OpenCode Go", "OCG-03", ["5h", "Week", "Month"], "")]

        def required(old):
            widths = []
            for name, code, labels, note in providers:
                items = sum(10 + (5 if old else 3) + max(
                    bold("35%", 13), bold(label, 9), regular("02 Nov", 7), regular("08:21", 8)
                ) for label in labels)
                padding, gap = (10, 5) if old else (6, 2)
                widths.append(max(64 if old else 56, items + padding + (len(labels) - 1) * gap,
                                  bold(name, 10) + 17, regular("Sync · just now" if old else "Sync 08:21", 7) + padding,
                                  regular(code, 6) + 14, regular(note, 7) + padding))
            return sum(widths) + (22 if old else 10)
        before = min(1.8, 405 / required(True), 248 / 156)
        after = min(1.8, 405 / required(False), 248 / 143)
        width = int(66 * after * 2.25 + 0.5) / 2.25
        height = int(34 * after * 2.25 + 0.5) / 2.25
        self.assertGreater(after, before)
        self.assertGreaterEqual(height, 48)
        frame_bytes = int(405 * 2.25) * int(248 * 2.25) * 4
        total_bytes = frame_bytes * 5
        self.assertLessEqual(total_bytes, 12_000_000)
        self.assertLessEqual(5 * 2 * 1024 * 1024, 12_000_000)
        print(f"Widget metrics (405x248dp, density 2.25, 1/2/3-window fixture): "
              f"required width {required(True):.3f}->{required(False):.3f}; "
              f"scale {before:.6f}->{after:.6f}; Sync {width:.3f}x{height:.3f}dp; "
              f"bitmap {frame_bytes} bytes x 5 = {total_bytes} bytes")


if __name__ == "__main__":
    unittest.main()
