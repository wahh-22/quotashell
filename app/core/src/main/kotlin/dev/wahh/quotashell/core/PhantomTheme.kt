package dev.wahh.quotashell.core

import java.util.Locale
import kotlin.math.roundToInt

/** Immutable export document; untouched launcher fields survive edits and re-export. */
class PhantomTheme private constructor(
    private val fields: Map<String, Any?>,
) {
    fun color(slot: String): PhantomColor {
        require(slot in COLOR_SLOTS)
        val value = fields[slot] as? Map<*, *> ?: error("Missing color $slot")

        fun number(key: String) = (value[key] as? Number)?.toDouble() ?: error("$slot.$key must be a number")
        return PhantomColor(number("hue"), number("saturation"), number("lightness"), number("alpha"))
    }

    val windowShape: String get() = fields.getValue("windowShape") as String
    val buttonShape: String get() = fields.getValue("buttonShape") as String
    val notificationShape: String get() = fields.getValue("notificationShape") as String
    val notificationAnimation: String get() = fields["notificationAnimation"] as? String ?: "DEFAULT"

    fun withNotificationAnimation(value: String): PhantomTheme = PhantomTheme(fields + ("notificationAnimation" to value))

    val isAllCaps: Boolean get() = fields.getValue("isAllCaps") as Boolean
    val fontSizeOffset: Int get() = (fields.getValue("fontSizeOffset") as Number).toDouble().coerceIn(-4.0, 6.0).toInt()

    // Field-specific support is deliberate: launcher enum membership alone proves no mapping.
    val renderWindowShape: String get() = windowShape.takeIf { it in listOf("TABBED", "BEVEL") } ?: "SQUARED"
    val renderButtonShape: String get() = buttonShape.takeIf { it == "BEVEL" } ?: "SQUARED"
    val renderNotificationShape: String get() = notificationShape.takeIf { it in NOTIFICATION_SHAPES } ?: "INVERTED TRIANGLE"

    fun displayText(value: String): String = if (isAllCaps) value.uppercase(Locale.US) else value

    fun withColor(
        slot: String,
        color: PhantomColor,
    ): PhantomTheme {
        require(slot in COLOR_SLOTS)
        // Preserve additive fields inside color objects too.
        val old = fields[slot] as? Map<*, *> ?: emptyMap<String, Any?>()
        return PhantomTheme(fields + (slot to (old + color.fields())))
    }

    /** Hex and whole-percent editor values must not quantize fields the user did not change. */
    fun editColor(
        slot: String,
        hex: String,
        alphaPercent: Int,
    ): PhantomTheme {
        val old = color(slot)
        val initialHex = "%06X".format(old.argb and 0xFFFFFF)
        val initialAlpha = (old.alpha * 100).roundToInt()
        val edited = PhantomColor.fromHex(hex, alphaPercent)
        val sameRgb = hex.equals(initialHex, ignoreCase = true)
        val sameAlpha = alphaPercent == initialAlpha
        if (sameRgb && sameAlpha) return this
        val updated = if (sameRgb) old.copy(alpha = edited.alpha) else edited.copy(alpha = if (sameAlpha) old.alpha else edited.alpha)
        return withColor(slot, updated)
    }

    fun withOptions(
        window: String,
        button: String,
        notification: String,
        allCaps: Boolean,
        fontOffset: Int?,
    ): PhantomTheme =
        PhantomTheme(
            fields +
                mapOf(
                    "windowShape" to window,
                    "buttonShape" to button,
                    "notificationShape" to notification,
                    "isAllCaps" to allCaps,
                    "fontSizeOffset" to (fontOffset ?: fields.getValue("fontSizeOffset")),
                ),
        )

    fun toJson(): String = encodeJson(fields)

    companion object {
        /** Document import limit; internal reloads use JsonObjectParser's larger 256 KiB bound. */
        const val MAX_BYTES = 64 * 1024
        val COLOR_SLOTS = listOf("outline", "window", "text", "subtext", "subtext2", "accent", "notifications", "background", "vignette")
        val WINDOW_SHAPES = listOf("TABBED", "BEVEL", "SQUARED")
        val BUTTON_SHAPES = listOf("SQUARED", "BEVEL")
        val NOTIFICATION_ANIMATIONS = listOf("GLITCH", "FADE", "DEFAULT", "NONE")
        val NOTIFICATION_SHAPES = listOf("INVERTED TRIANGLE", "TRIANGLE", "CIRCLE", "SQUARE", "NONE")

        fun parse(json: String): PhantomTheme =
            try {
                val fields = JsonObjectParser.parse(json)
                val theme = PhantomTheme(fields)
                COLOR_SLOTS.filter { it != "vignette" || fields.containsKey(it) }.forEach { theme.color(it) }
                listOf("windowShape", "buttonShape", "notificationShape").forEach {
                    require(fields[it] is String) { "$it must be a string" }
                }
                require(fields["isAllCaps"] is Boolean) { "isAllCaps must be a boolean" }
                val offset = (fields["fontSizeOffset"] as? Number)?.toDouble()
                require(offset != null && offset.isFinite() && offset % 1 == 0.0) { "fontSizeOffset must be an integer" }
                theme
            } catch (error: RuntimeException) {
                throw IllegalArgumentException("Invalid PhantomShell theme: ${error.message ?: "malformed JSON"}")
            }

        fun default(): PhantomTheme = parse(PHANTOM_BLUE_JSON)

        /** Legacy explicit accent is retained; panel opacity becomes window alpha only. */
        fun migrateLegacy(
            accentHex: String?,
            opacityPercent: Int?,
        ): PhantomTheme {
            var theme = default()
            if (accentHex != null && accentHex.removePrefix("#").matches(Regex("[0-9a-fA-F]{6}"))) {
                theme = theme.withColor("accent", PhantomColor.fromHex(accentHex.removePrefix("#"), 100))
            }
            if (opacityPercent != null) {
                theme = theme.withColor("window", theme.color("window").copy(alpha = opacityPercent.coerceIn(0, 100) / 100.0))
            }
            return theme
        }
    }
}

/** JSON semantic round-trip, not byte/whitespace identity. */
private fun encodeJson(value: Any?): String =
    when (value) {
        null -> {
            "null"
        }

        is String -> {
            buildString {
                append('"')
                value.forEach { c ->
                    when (c) {
                        '"' -> append("\\\"")
                        '\\' -> append("\\\\")
                        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                    }
                }
                append('"')
            }
        }

        is Boolean -> {
            value.toString()
        }

        is Number -> {
            // Avoid inflating a near-limit import (e.g. 1e-3 -> 0.001) beyond the import cap.
            val decimal = java.math.BigDecimal(value.toString()).stripTrailingZeros()
            val ordinary = decimal.toString().replace("E+", "e").replace("E", "e")
            val scientific = decimal.unscaledValue().toString() + "e" + -decimal.scale()
            if (scientific.length < ordinary.length) scientific else ordinary
        }

        is Map<*, *> -> {
            value.entries.joinToString(",", "{", "}") { encodeJson(it.key) + ":" + encodeJson(it.value) }
        }

        is List<*> -> {
            value.joinToString(",", "[", "]") { encodeJson(it) }
        }

        else -> {
            error("Unsupported JSON value")
        }
    }
