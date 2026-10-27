"""Source-level presentation/security wiring guards, not runtime security proof."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/kotlin/dev/wahh/quotashell"


class AppStyleTest(unittest.TestCase):
    def test_recognizable_secure_input_component(self):
        path = SRC / "ui/ThemedInput.kt"
        self.assertTrue(path.exists(), "Reusable outlined input component is missing")
        source = path.read_text()
        for contract in ("TYPE_TEXT_VARIATION_PASSWORD", "TYPE_TEXT_FLAG_NO_SUGGESTIONS",
                         "IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS", "IME_FLAG_NO_PERSONALIZED_LEARNING",
                         "labelFor", "contentDescription", "dp(48)", "setStroke", "hideSecret"):
            self.assertIn(contract, source)
        self.assertIn("hideSecret()", (SRC / "ConnectionsActivity.kt").read_text())

    def test_segments_and_toggle_rows_have_real_view_minimum_height(self):
        controls = (SRC / "ui/InputControls.kt").read_text()
        self.assertIn("class SegmentButton", controls)
        self.assertIn("minimumHeight = activity.dp(48)", controls)
        self.assertIn("options.size in 2..4", controls)
        self.assertIn("buttonDrawable = null", controls)
        self.assertIn("isSelected = isChecked", controls)
        self.assertIn('theme.color("accent")', controls)
        shell = (SRC / "ui/PhantomShell.kt").read_text()
        self.assertIn("view.minimumHeight = dp(48)", shell)
        self.assertIn("view is SegmentButton", shell)

    def test_input_has_one_helper_replaced_by_validation(self):
        inputs = (SRC / "ui/ThemedInput.kt").read_text()
        self.assertNotIn("addView(activity.text(helper, 12f))", inputs)
        self.assertEqual(1, inputs.count("addView(support)"))
        self.assertIn("result?.valid == false", inputs)
        self.assertIn("else helper", inputs)
        editor = (SRC / "ThemeEditor.kt").read_text()
        self.assertIn("showLabel = false", editor)
        self.assertIn("inputLine.addView(swatch", editor)
        self.assertIn("inputLine.addView(field", editor)

    def test_interactive_controls_never_glitch(self):
        effects = (SRC / "ui/AppGlitch.kt").read_text()
        self.assertIn("view is EditText", effects)
        self.assertIn("view.isClickable", effects)
        self.assertIn("view.isFocused", effects)

    def test_claude_session_wiring_and_sensitive_boundary(self):
        connections = (SRC / "ConnectionsActivity.kt").read_text()
        for contract in ("ClaudeCredentialKind.SESSION_KEY", "full claude.ai web account access",
                         "Application/Storage", "organizationPicker", "FLAG_SECURE"):
            self.assertIn(contract, connections)
        application = (SRC / "QuotaApplication.kt").read_text()
        for contract in ("rotateClaudeSession", "capturedVersions", "ClaudeSessionFetcher", "selectClaudeOrganization"):
            self.assertIn(contract, application)
        # Cookie values must never enter general UI, logs, widgets, intents or snapshots.
        for path in SRC.rglob("*.kt"):
            source = path.read_text()
            self.assertNotIn("Log.", source)
            self.assertNotIn("println(", source)
            if path.name not in ("ConnectionsActivity.kt", "QuotaApplication.kt", "SecureCredentialStore.kt"):
                self.assertNotIn("sessionKey", source)
        snapshot = (ROOT / "app/core/src/main/kotlin/dev/wahh/quotashell/core/SnapshotCodec.kt").read_text()
        for forbidden in ("sessionKey", "organizationId", "ManualCredential"):
            self.assertNotIn(forbidden, snapshot)

    def test_hud_font_is_app_only(self):
        font = (SRC / "ui/HudFont.kt").read_text() if (SRC / "ui/HudFont.kt").exists() else ""
        self.assertIn("KodeMono[ROND,wdth,wght].ttf", font)
        self.assertIn("setFontVariationSettings", font)
        self.assertIn("SIL OPEN FONT LICENSE", (ROOT / "app/src/main/assets/fonts/KodeMono-OFL.txt").read_text())
        widget = (SRC / "PhantomBoardRenderer.kt").read_text()
        self.assertNotIn("HudFont", widget)
        self.assertIn("PhantomGraphics", widget)
        for path in ("TerminalUi.kt", "ThemeEditor.kt", "ui/PhantomShell.kt"):
            self.assertIn("HudFont.typeface", (SRC / path).read_text())
        graphics = (SRC / "PhantomGraphics.kt").read_text()
        self.assertIn("AdwaitaMono", graphics)
        for path in ("MainActivity.kt", "ConnectionsActivity.kt", "SettingsActivity.kt", "ui/AppGlitch.kt"):
            source = (SRC / path).read_text()
            for forbidden in ("NO SIGNAL", "N O   S I G N A L", "rgbOffset"):
                self.assertNotIn(forbidden, source)

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
        drawing = effects.split("override fun onDraw(canvas: Canvas)", 1)[1].split("}.apply {", 1)[0]
        self.assertNotIn("Bitmap.createBitmap", drawing)
        self.assertNotIn("Canvas(output)", drawing)
        self.assertIn("originalAlphas[view] = view.alpha", effects)
        self.assertIn("view.alpha = 0f", effects)
        self.assertIn("view.alpha = alpha", effects)
        self.assertIn("if (stopped) return", effects)
        self.assertIn("cancel()", effects)
        self.assertIn("root.removeCallbacks(frame)", effects)
        self.assertIn("root.removeCallbacks(periodic)", effects)
        self.assertIn("if (hadFrames) effect.invalidate()", effects)

    def test_shared_typeface_and_live_theme(self):
        ui = (SRC / "TerminalUi.kt").read_text()
        self.assertIn("HudFont.typeface", ui)
        self.assertNotIn("Typeface.MONOSPACE", ui)
        shell = (SRC / "ui/PhantomShell.kt").read_text()
        for contract in ("store.listen", "store.unlisten", "phantomTheme()", "EditText", "Spinner"):
            self.assertIn(contract, shell)

    def test_selectors_do_not_capture_unmeasured_parent_width(self):
        editor = (SRC / "ThemeEditor.kt").read_text()
        self.assertNotIn("LayoutParams(parent.width", editor)
        self.assertIn("HudFont.typeface", editor)
        self.assertNotIn("Spinner", editor)
        self.assertIn("ChoiceControl", editor)
        self.assertIn("RadioGroup", (SRC / "ui/InputControls.kt").read_text())

    def test_secure_masked_manual_entry_remains(self):
        connections = (SRC / "ConnectionsActivity.kt").read_text()
        self.assertIn("FLAG_SECURE", connections)
        self.assertIn("secret = true", connections)
        inputs = (SRC / "ui/ThemedInput.kt").read_text()
        for contract in ("TYPE_TEXT_VARIATION_PASSWORD", "isSaveEnabled = false",
                         "IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS", "IME_FLAG_NO_PERSONALIZED_LEARNING"):
            self.assertIn(contract, inputs)
        self.assertIn("PasswordTransformationMethod.getInstance()", inputs)
        self.assertIn("it.hideSecret()", connections)
        self.assertIn("it.edit.text.clear()", connections)
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
        # The app is now a terminal HUD; widget chart-style options remain unchanged.
        for contract in ("filledSegments(percent, 12)", "Paint.Style.STROKE", "percent used"):
            self.assertIn(contract, charts)
        for name in ("MainActivity.kt", "ConnectionsActivity.kt", "SettingsActivity.kt"):
            self.assertIn("hudHeader(", (SRC / name).read_text())

    def test_effects_are_bounded_and_only_elements(self):
        effects = (SRC / "ui/AppGlitch.kt").read_text()
        for contract in ("periodicGlitchEnabled", "ANIMATOR_DURATION_SCALE", "is EditText", "PhantomGraphics.signalLoss",
                         "decodeReveal", "duration = 900L", "if (loss) 67 else 50", "hudBurstDuration", "hudBurstDelay",
                         "root.hasWindowFocus()", "OnWindowFocusChangeListener", "font = view.typeface"):
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
