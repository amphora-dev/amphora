package app.amphora.sdk.internal.window

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import kotlin.math.floor
import kotlin.math.min

/** How the Wine desktop is put on the screen ("display.scale"). */
internal enum class ScaleMode(val key: String) {
    /** Largest size that keeps the aspect ratio; bars on two sides. */
    Fit("fit"),

    /** Fills the screen; distorts when aspect ratios differ. */
    Stretch("stretch"),

    /** Largest whole multiple: sharpest for pixel art. Falls back to [Fit] when the desktop is larger than the screen. */
    Integer("integer"),
    ;

    companion object {
        fun of(key: String): ScaleMode = entries.firstOrNull { it.key == key } ?: Fit
    }
}

/** Scale and offset that place content of one size inside a container of another. */
internal data class SurfaceTransform(val scaleX: Float, val scaleY: Float, val offsetX: Float, val offsetY: Float)

internal object GameSurfaceLayout {
    fun transform(
        contentWidth: Int,
        contentHeight: Int,
        containerWidth: Int,
        containerHeight: Int,
        mode: ScaleMode,
    ): SurfaceTransform {
        if (contentWidth <= 0 || contentHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) {
            return SurfaceTransform(1f, 1f, 0f, 0f)
        }
        val sx = containerWidth.toFloat() / contentWidth
        val sy = containerHeight.toFloat() / contentHeight
        val fit = min(sx, sy)
        val (scaleX, scaleY) =
            when (mode) {
                ScaleMode.Stretch -> sx to sy
                ScaleMode.Fit -> fit to fit
                ScaleMode.Integer -> floor(fit).let { if (it >= 1f) it to it else fit to fit }
            }
        return SurfaceTransform(
            scaleX = scaleX,
            scaleY = scaleY,
            offsetX = ((containerWidth - contentWidth * scaleX) / 2f),
            offsetY = ((containerHeight - contentHeight * scaleY) / 2f),
        )
    }
}

/** Holds the desktop view at its own size and scales it onto the screen. */
internal class SurfaceHost(context: Context) : ViewGroup(context) {
    var mode: ScaleMode = ScaleMode.Fit
        set(value) {
            field = value
            requestLayout()
        }

    private var content: View? = null

    /** Last applied transform, for the session menu. */
    var transform: SurfaceTransform? = null
        private set

    init {
        setBackgroundColor(Color.BLACK)
    }

    fun show(view: View) {
        if (content === view) return
        (view.parent as? ViewGroup)?.removeView(view)
        removeAllViews()
        addView(view)
        content = view
    }

    fun clear() {
        removeAllViews()
        content = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        content?.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val view = content ?: return
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val t = GameSurfaceLayout.transform(view.measuredWidth, view.measuredHeight, right - left, bottom - top, mode)
        view.pivotX = 0f
        view.pivotY = 0f
        view.scaleX = t.scaleX
        view.scaleY = t.scaleY
        view.translationX = t.offsetX
        view.translationY = t.offsetY
        transform = t
    }
}
