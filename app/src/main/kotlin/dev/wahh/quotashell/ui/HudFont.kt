package dev.wahh.quotashell

import android.content.Context
import android.graphics.Typeface

/** Separate from PhantomGraphics: the widget deliberately retains Adwaita Mono. API 26+. */
internal object HudFont {
    private val fonts = mutableMapOf<Boolean, Typeface>()

    fun typeface(
        context: Context,
        bold: Boolean = false,
    ): Typeface =
        fonts.getOrPut(bold) {
            Typeface
                .Builder(context.assets, "fonts/KodeMono[ROND,wdth,wght].ttf")
                .setFontVariationSettings("'wght' ${if (bold) 700 else 400}, 'wdth' 100, 'ROND' 0")
                .build()
        }
}
