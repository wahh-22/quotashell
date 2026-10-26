package dev.wahh.quotashell.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PhantomThemeTest {
    private fun blue() = javaClass.getResource("/phantomshell/blue.json")!!.readText()

    @Test fun blueHasExactArgbAndDefaultValues() {
        val theme = PhantomTheme.parse(blue())
        assertEquals(0x81347AFF.toInt(), theme.color("outline").argb)
        assertEquals(0xE6070B1A.toInt(), theme.color("window").argb)
        assertEquals(0xFFDBE9FF.toInt(), theme.color("text").argb)
        assertEquals(0xFF347AFF.toInt(), theme.color("accent").argb)
        assertEquals(0xFF5CE1FF.toInt(), theme.color("notifications").argb)
        assertEquals(0x6705070F, theme.color("background").argb)
        assertEquals(0xA67C5CFF.toInt(), theme.color("subtext2").argb)
        assertEquals(0x0005070F, theme.color("vignette").argb)
        assertEquals(theme.toJson(), PhantomTheme.default().toJson())
        assertFalse(theme.isAllCaps)
        assertEquals(0, theme.fontSizeOffset)
    }

    @Test fun unknownShapesFallBackWithoutLosingOriginalValues() {
        val theme = PhantomTheme.parse(blue()).withOptions("FUTURE WINDOW", "FUTURE BUTTON", "FUTURE ALERT", false, 0)
        assertEquals("SQUARED", theme.renderWindowShape)
        assertEquals("SQUARED", theme.renderButtonShape)
        assertEquals("INVERTED TRIANGLE", theme.renderNotificationShape)
        val exported = PhantomTheme.parse(theme.toJson())
        assertEquals("FUTURE WINDOW", exported.windowShape)
        assertEquals("FUTURE BUTTON", exported.buttonShape)
        assertEquals("FUTURE ALERT", exported.notificationShape)
    }

    @Test fun knownShapesAreRecognizedByField() {
        val theme = PhantomTheme.default()
        for (shape in listOf("TABBED", "BEVEL", "SQUARED")) {
            assertEquals(shape, theme.withOptions(shape, "BEVEL", "NONE", false, 0).renderWindowShape)
        }
        for (shape in listOf("TRIANGLE", "INVERTED TRIANGLE", "CIRCLE", "SQUARE", "NONE")) {
            assertEquals(shape, theme.withOptions("SQUARED", "SQUARED", shape, false, 0).renderNotificationShape)
        }
    }

    @Test fun malformedInputIsRejectedWithUsefulError() {
        val invalid =
            listOf(
                "[]",
                "null",
                "{",
                "{}",
                blue().replace("219.31035", "361"),
                blue().replace("219.31035", "NaN"),
                blue().replace("219.31035", "1e999"),
                blue().replace("\"alpha\":0.9", "\"alpha\":-0.1"),
                blue().replace("\"saturation\":1.0", "\"saturation\":1.1"),
                blue().replace("\"lightness\":0.6019608", "\"lightness\":-1"),
                blue().replace("\"isAllCaps\":false", "\"isAllCaps\":\"false\""),
                blue().replace("\"fontSizeOffset\":0", "\"fontSizeOffset\":0.5"),
                blue().replace("\"windowShape\":\"TABBED\"", "\"windowShape\":null"),
            )
        invalid.forEach { input ->
            try {
                PhantomTheme.parse(input)
                fail("Accepted invalid theme")
            } catch (error: IllegalArgumentException) {
                assertTrue(error.message.orEmpty().startsWith("Invalid PhantomShell theme:"))
            }
        }
    }

    @Test fun exportPreservesLauncherOnlyKeysAndUnknownNestedData() {
        val original = JsonObjectParser.parse(blue())
        assertEquals(original, JsonObjectParser.parse(PhantomTheme.parse(blue()).toJson()))
        val extra = blue().dropLast(1) + ",\"future\":{\"a\":[null,true,\"x\\n\\\"y\",1.25]}}"
        assertEquals(JsonObjectParser.parse(extra), JsonObjectParser.parse(PhantomTheme.parse(extra).toJson()))
    }

    @Test fun nearImportLimitExportCanStillBeReloadedAfterNumberNormalization() {
        val json = blue().dropLast(1) + ",\"future\":[" + List(12500) { "1e-3" }.joinToString(",") + "]}"
        assertTrue(json.toByteArray().size <= PhantomTheme.MAX_BYTES)
        val theme = PhantomTheme.parse(json)
        assertEquals(theme.toJson(), PhantomTheme.parse(theme.toJson()).toJson())
    }

    @Test fun storedThemeCanReloadWhenEditingMakesAnAtLimitDocumentLarger() {
        val prefix = blue().dropLast(1) + ",\"future\":\""
        val suffix = "\"}"
        val json = prefix + "x".repeat(PhantomTheme.MAX_BYTES - prefix.toByteArray().size - suffix.length) + suffix
        assertEquals(PhantomTheme.MAX_BYTES, json.toByteArray().size)
        var edited = PhantomTheme.parse(json)
        PhantomTheme.COLOR_SLOTS.forEach { edited = edited.withColor(it, PhantomColor.fromHex("12ABEF", 35)) }
        assertTrue(edited.toJson().toByteArray().size > PhantomTheme.MAX_BYTES)
        assertEquals(edited.toJson(), PhantomTheme.parse(edited.toJson()).toJson())
    }

    @Test fun textCasingAndFontOffsetAreBoundedAtRenderingOnly() {
        val theme = PhantomTheme.default()
        assertEquals("Sync · just now", theme.displayText("Sync · just now"))
        val upper = theme.withOptions("TABBED", "SQUARED", "NONE", true, 100)
        assertEquals("SYNC · JUST NOW", upper.displayText("Sync · just now"))
        assertEquals(6, upper.fontSizeOffset)
        assertEquals(-4, theme.withOptions("TABBED", "SQUARED", "NONE", false, -100).fontSizeOffset)
        assertEquals(100.0, JsonObjectParser.parse(upper.toJson())["fontSizeOffset"])
        val onlyCapsChanged = upper.withOptions(upper.windowShape, upper.buttonShape, upper.notificationShape, false, null)
        assertEquals(100.0, JsonObjectParser.parse(onlyCapsChanged.toJson())["fontSizeOffset"])
    }

    @Test fun hslBoundariesAndHexEditingRetainAlpha() {
        assertEquals(0x80FF0000.toInt(), PhantomColor(360.0, 1.0, 0.5, 0.5).argb)
        assertEquals(0xFF808080.toInt(), PhantomColor(0.0, 0.0, 0.5, 1.0).argb)
        val color = PhantomColor.fromHex("12ABEF", 35)
        assertEquals(0x5912ABEF, color.argb)
        val edited = PhantomTheme.default().withColor("accent", color)
        assertEquals(color.argb, PhantomTheme.parse(edited.toJson()).color("accent").argb)
    }

    @Test fun manualEditingDoesNotQuantizeUntouchedHslOrAlpha() {
        val theme = PhantomTheme.default()
        var unchanged = theme
        PhantomTheme.COLOR_SLOTS.forEach { slot ->
            val color = theme.color(slot)
            unchanged = unchanged.editColor(slot, "%06X".format(color.argb and 0xFFFFFF), kotlin.math.round(color.alpha * 100).toInt())
        }
        assertEquals(theme.toJson(), unchanged.toJson())
        val rgbOnly = theme.editColor("outline", "FF0000", 50).color("outline")
        assertEquals(theme.color("outline").alpha, rgbOnly.alpha, 0.0)
        val alphaOnly = theme.editColor("outline", "347AFF", 25).color("outline")
        assertEquals(theme.color("outline").copy(alpha = 0.25), alphaOnly)
    }

    @Test fun legacyPreferencesOverrideOnlyAccentAndWindowAlpha() {
        val base = PhantomTheme.default()
        val migrated = PhantomTheme.migrateLegacy("FF0000", 45)
        assertEquals(0xFFFF0000.toInt(), migrated.color("accent").argb)
        assertEquals(0x73070B1A, migrated.color("window").argb)
        assertEquals(base.color("background"), migrated.color("background"))
        assertEquals(base.toJson(), PhantomTheme.migrateLegacy(null, null).toJson())
    }

    @Test fun naturalBoardCasingDoesNotChangeDataOrLegacyUppercaseOutput() {
        val now = Instant.parse("2026-10-26T22:00:00Z")
        val snapshots =
            listOf(
                ProviderSnapshot(
                    ProviderId.CLAUDE,
                    Connection.CONNECTED,
                    now,
                    listOf(QuotaWindow(WindowKind.WEEK, 90.0, now)),
                ),
            )
        val upper = board(snapshots, now, 3600, ZoneId.of("UTC"))
        val natural = board(snapshots, now, 3600, ZoneId.of("UTC"), allCaps = false)
        assertEquals("Claude", natural.cards[1].name)
        assertEquals("Sync · just now", natural.cards[1].status)
        assertEquals(
            "Week",
            natural.cards[1]
                .rows
                .single()
                .label,
        )
        assertEquals(upper, natural.withCasing(true))
    }
}
