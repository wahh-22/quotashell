package dev.wahh.quotashell

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import dev.wahh.quotashell.core.PhantomTheme
import java.util.WeakHashMap

/** Restyle existing native controls in place: never rebuild or read credential drafts. */
internal fun Activity.installPhantomShell(
    root: ViewGroup,
    openingGlitch: Boolean,
) {
    val store = (application as QuotaApplication).store
    val sizes = WeakHashMap<TextView, Float>()

    fun apply(
        view: View,
        theme: PhantomTheme,
    ) {
        (view.tag as? PanelStyle)?.let { view.background = PhantomSurface(this, theme, it.code) }
        if (view is TextView) {
            val style = view.tag as? TextStyle
            val base = sizes.getOrPut(view) { style?.size ?: (view.textSize / resources.displayMetrics.scaledDensity) }
            view.textSize = (base + theme.fontSizeOffset).coerceAtLeast(12f)
            view.typeface = PhantomGraphics.typeface(this, view is Button || base >= 18f)
            view.setTextColor(theme.color(style?.slot ?: "text").argb)
            if (view !is EditText) view.setAllCaps(theme.isAllCaps)
            if (view is Button && view !is CompoundButton) {
                view.background = PhantomSurface(this, theme, button = true)
                view.backgroundTintList = null
                view.setTextColor(theme.color("accent").argb)
            }
            if (view is EditText) {
                view.minHeight = dp(48)
                view.setHintTextColor(theme.color("subtext").argb)
                view.backgroundTintList = ColorStateList.valueOf(theme.color("outline").argb)
            }
            if (view is CompoundButton) {
                view.minHeight = dp(48)
                view.buttonTintList = ColorStateList.valueOf(theme.color("accent").argb)
                if (view is android.widget.Switch) {
                    view.thumbTintList = ColorStateList.valueOf(theme.color("accent").argb)
                    view.trackTintList = ColorStateList.valueOf(theme.color("outline").argb)
                }
            }
        }
        if (view is Spinner) {
            view.minimumHeight = dp(48)
            view.backgroundTintList = ColorStateList.valueOf(theme.color("outline").argb)
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) apply(view.getChildAt(index), theme)
        view.invalidate()
    }
    val update: () -> Unit = {
        val theme = store.phantomTheme()
        root.setBackgroundColor(theme.color("background").argb)
        @Suppress("DEPRECATION")
        window.statusBarColor = theme.color("background").argb
        @Suppress("DEPRECATION")
        window.navigationBarColor = theme.color("background").argb
        apply(root, theme)
    }
    if (root.isAttachedToWindow) store.listen(update)
    root.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                store.listen(update)
                update()
                if (openingGlitch) root.post { glitchAppElements() }
            }

            override fun onViewDetachedFromWindow(view: View) {
                store.unlisten(update)
            }
        },
    )
    update()
}

/** Shared widget geometry in dp, not density-dependent pixel chamfers. */
internal class PhantomSurface(
    private val activity: Activity,
    private val theme: PhantomTheme,
    private val code: String = "",
    private val button: Boolean = false,
) : Drawable() {
    override fun draw(canvas: Canvas) {
        val density = activity.resources.displayMetrics.density
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(density, density)
        val width = bounds.width() / density
        val height = bounds.height() / density
        val graphics = PhantomGraphics(activity, canvas, theme)
        val path =
            if (button) {
                graphics.buttonPath(1f, 1f, width - 1, height - 1)
            } else {
                PhantomGraphics.panelPath(1f, 1f, width - 1, height - 1, theme.renderWindowShape)
            }
        graphics.fill(path, theme.color("window").argb)
        graphics.stroke(path, theme.color("outline").argb, 1f)
        if (!button) {
            graphics.text(code, 12f, 19f, 11f, theme.color("subtext2").argb)
            graphics.text("0101 //", width - 86f, 19f, 10f, theme.color("subtext2").argb)
            graphics.text("/////", width - 65f, height - 8f, 10f, theme.color("subtext2").argb)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Drawable compatibility")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
