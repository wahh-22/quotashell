"""Source-level presentation/security wiring guards, not runtime security proof."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/kotlin/dev/wahh/quotashell"


class AppStyleTest(unittest.TestCase):
    def test_shared_outer_spacing(self):
        ui = (SRC / "TerminalUi.kt").read_text()
        self.assertIn("view.setPadding(dp(16), dp(16), dp(16), dp(16))", ui)
        self.assertIn("setPadding(0, 0, 0, 0)", ui)

    def test_dashboard_reuses_local_reset_formatter(self):
        dashboard = (SRC / "MainActivity.kt").read_text()
        self.assertIn("formatLocalReset(", dashboard)
        self.assertIn("ZoneId.systemDefault()", dashboard)
        self.assertNotIn("text(window.reset", dashboard)

    def test_only_selected_views_glitch(self):
        effects = (SRC / "ui/AppGlitch.kt").read_text()
        self.assertIn("glitchElementIndices", effects)
        self.assertIn("selectedViews", effects)
        self.assertIn("getGlobalVisibleRect", effects)
        self.assertIn("view.draw(Canvas(source))", effects)
        drawing = effects.split("override fun onDraw(canvas: Canvas)", 1)[1]
        self.assertNotIn("Bitmap.createBitmap", drawing)
        self.assertNotIn("Canvas(output)", drawing)
        self.assertIn("originalAlphas[view] = view.alpha", effects)
        self.assertIn("view.alpha = 0f", effects)
        self.assertIn("view.alpha = alpha", effects)
        self.assertIn("if (stopped) return", effects)
        self.assertIn("effect.stop()", effects)

    def test_shared_typeface_and_live_theme(self):
        ui = (SRC / "TerminalUi.kt").read_text()
        self.assertIn("PhantomGraphics.typeface", ui)
        self.assertNotIn("Typeface.MONOSPACE", ui)
        shell = (SRC / "ui/PhantomShell.kt").read_text()
        for contract in ("store.listen", "store.unlisten", "phantomTheme()", "EditText", "Spinner"):
            self.assertIn(contract, shell)

    def test_spinner_does_not_capture_unmeasured_parent_width(self):
        editor = (SRC / "ThemeEditor.kt").read_text()
        self.assertNotIn("LayoutParams(parent.width", editor)
        self.assertIn("PhantomGraphics.typeface", editor)

    def test_secure_masked_manual_entry_remains(self):
        connections = (SRC / "ConnectionsActivity.kt").read_text()
        for contract in ("FLAG_SECURE", "TYPE_TEXT_VARIATION_PASSWORD", "isSaveEnabled = false",
                         "IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS", "IME_FLAG_NO_PERSONALIZED_LEARNING"):
            self.assertIn(contract, connections)
        self.assertNotIn("token.setText", connections)
        self.assertNotIn("account?.setText", connections)
        self.assertNotIn("Log.", connections)
        self.assertNotIn("println(", connections)

    def test_dashboard_and_connections_share_panels_and_refresh_effects(self):
        for name in ("MainActivity.kt", "ConnectionsActivity.kt"):
            activity = (SRC / name).read_text()
            self.assertIn("alertGlyph()", activity)
            self.assertIn("snapshotChanged", activity)
            self.assertIn("glitchAppElements()", activity)
        dashboard = (SRC / "MainActivity.kt").read_text()
        self.assertIn("quotaChart(window.progress)", dashboard)
        charts = (SRC / "ui/QuotaChart.kt").read_text()
        for style in ("ChartStyle.CELLS", "ChartStyle.TICKS", "ChartStyle.RING"):
            self.assertIn(style, charts)

    def test_effects_are_bounded_and_only_elements(self):
        effects = (SRC / "ui/AppGlitch.kt").read_text()
        for contract in ("appSignalActive", "ANIMATOR_DURATION_SCALE", "is EditText", "PhantomGraphics.signalLoss"):
            self.assertIn(contract, effects)
        for removed in ("rgbOffset", "jitterX", "PhantomGraphics.slices", "NO SIGNAL", "N O   S I G N A L"):
            self.assertNotIn(removed, effects)
        self.assertIn("providerSyncStatus(snapshot, display, ZoneId.systemDefault())", (SRC / "MainActivity.kt").read_text())
        self.assertNotIn("text(\"${display.state} · ${display.age}\")", (SRC / "MainActivity.kt").read_text())
        self.assertNotIn("drawColor", effects)
        self.assertNotIn("drawRect", effects)
        self.assertIn("glitchAppElements()", (SRC / "MainActivity.kt").read_text())


if __name__ == "__main__":
    unittest.main()
