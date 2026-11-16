package app.grove.browser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.PanZoomController
import kotlin.math.abs

/** The web page view, plus pull-to-refresh: pull down when the page is at its top and the page isn't using the drag itself. */
@SuppressLint("ViewConstructor")
class GroveGeckoView(ctx: Context) : GeckoView(ctx) {
    /** 0..1 while pulling (1 = let go to refresh), -1 when the pull ends. */
    var onPull: ((Float) -> Unit)? = null
    var onRefresh: (() -> Unit)? = null
    var pullEnabled = true

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val distance = 120 * resources.displayMetrics.density
    private var startX = 0f
    private var startY = 0f
    private var gesture = 0
    private var canPull = false
    private var pulling = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                canPull = false; pulling = false
                val id = ++gesture
                if (!pullEnabled) return super.onTouchEvent(event)
                requestFocus()
                // Ask the page what it does with this touch: only pull when the page is at its top
                // and the page itself (a map, a slider, an inner list) isn't handling the drag.
                val r = runCatching { super.onTouchEventForDetailResult(event) }.getOrNull() ?: return super.onTouchEvent(event)
                r.accept({ d ->
                    if (id == gesture && d != null) {
                        canPull = d.handledResult() != PanZoomController.INPUT_RESULT_HANDLED_CONTENT &&
                            (d.scrollableDirections() and PanZoomController.SCROLLABLE_FLAG_TOP) == 0
                    }
                }, { })
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> { canPull = false; if (pulling) { pulling = false; onPull?.invoke(-1f) } }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - startY
                val dx = event.x - startX
                if (canPull && event.pointerCount == 1 && dy > slop * 3 && abs(dx) < dy * 0.7f) {
                    pulling = true
                    onPull?.invoke((dy / distance).coerceIn(0f, 1f))
                } else if (pulling) { onPull?.invoke(0f) }
            }
            MotionEvent.ACTION_UP -> {
                if (pulling) {
                    val go = canPull && event.y - startY >= distance
                    pulling = false
                    onPull?.invoke(-1f)
                    if (go) onRefresh?.invoke()
                }
                canPull = false
            }
            MotionEvent.ACTION_CANCEL -> { if (pulling) { pulling = false; onPull?.invoke(-1f) }; canPull = false }
        }
        return super.onTouchEvent(event)
    }
}
