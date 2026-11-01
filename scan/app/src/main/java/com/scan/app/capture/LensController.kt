package com.scan.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import com.scan.app.Lang
import com.scan.app.MainActivity
import com.scan.app.ScanApp
import kotlin.math.hypot

/**
 * Owns every overlay window: the bubble, the selection frame and the long-press menu.
 * All calls must come from the main thread -- they are made by [LensService] and the
 * overlay's own click handlers.
 */
object LensController {

    private const val PREFS = "scan_lens"
    private const val KEY_X = "bubble_x"
    private const val KEY_Y = "bubble_y"

    private var ctx: Context? = null
    private var wm: WindowManager? = null

    private var bubble: LensBubbleView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null

    private var overlay: FrameLayout? = null
    private var selection: SelectionOverlayView? = null

    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null

    private val handler = Handler(Looper.getMainLooper())
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var longPressFired = false
    private var capturing = false

    val isBubbleShown: Boolean get() = bubble != null

    private fun lang(): Lang = ctx?.let { ScanApp.get(it).settings.lang } ?: Lang.AR
    private fun ar(ar: String, en: String): String = if (lang() == Lang.AR) ar else en

    private fun dp(v: Float): Int = (v * (ctx?.resources?.displayMetrics?.density ?: 2f)).toInt()

    fun attach(context: Context) {
        if (bubble != null) return
        ctx = context.applicationContext
        wm = ctx?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        showBubble()
    }

    fun detach() {
        dismissMenu()
        removeOverlay()
        hideBubble()
        ctx = null
        wm = null
    }

    fun showBubble() {
        val c = ctx ?: return
        val manager = wm ?: return
        if (bubble != null) return
        if (!Settings.canDrawOverlays(c)) return

        val (sw, sh) = screenSize(c)
        val size = dp(60f)
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var x = prefs.getInt(KEY_X, sw - size - dp(6f))
        var y = prefs.getInt(KEY_Y, (sh * 0.42f).toInt())
        x = x.coerceIn(0, (sw - size).coerceAtLeast(0))
        y = y.coerceIn(dp(24f), (sh - size - dp(24f)).coerceAtLeast(dp(24f)))

        val view = LensBubbleView(c)
        val lp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            alpha = 0.94f
        }
        view.setOnTouchListener(bubbleTouchListener)
        try {
            manager.addView(view, lp)
        } catch (e: Exception) {
            return
        }
        bubble = view
        bubbleLp = lp
    }

    fun hideBubble() {
        val v = bubble ?: return
        val manager = wm ?: return
        try {
            manager.removeView(v)
        } catch (e: Exception) {
            // already gone
        }
        bubble = null
        bubbleLp = null
    }

    private val bubbleTouchListener = View.OnTouchListener { v, e ->
        val lp = bubbleLp ?: return@OnTouchListener false
        val manager = wm ?: return@OnTouchListener false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dismissMenu()
                downRawX = e.rawX
                downRawY = e.rawY
                startX = lp.x
                startY = lp.y
                dragging = false
                longPressFired = false
                handler.postDelayed(longPressRunnable, 520)
                v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && hypot(dx, dy) > dp(6f)) {
                    dragging = true
                    handler.removeCallbacks(longPressRunnable)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                }
                if (dragging) {
                    val (sw, sh) = screenSize(ctx ?: return@OnTouchListener false)
                    lp.x = (startX + dx).toInt().coerceIn(0, (sw - lp.width).coerceAtLeast(0))
                    lp.y = (startY + dy).toInt().coerceIn(0, (sh - lp.height).coerceAtLeast(0))
                    try {
                        manager.updateViewLayout(v, lp)
                    } catch (ex: Exception) {
                        // view detached
                    }
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                if (dragging) {
                    snapToEdge()
                } else if (!longPressFired) {
                    onBubbleTap()
                }
                true
            }

            else -> false
        }
    }

    private val longPressRunnable = Runnable {
        val v = bubble ?: return@Runnable
        longPressFired = true
        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showMenu()
    }

    private fun snapToEdge() {
        val v = bubble ?: return
        val lp = bubbleLp ?: return
        val manager = wm ?: return
        val (sw, _) = screenSize(ctx ?: return)
        val target = if (lp.x + lp.width / 2 < sw / 2) 0 else (sw - lp.width)
        val start = lp.x
        val anim = android.animation.ValueAnimator.ofInt(start, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
        }
        anim.addUpdateListener { a ->
            lp.x = a.animatedValue as Int
            try {
                manager.updateViewLayout(v, lp)
            } catch (e: Exception) {
                // ignore
            }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                saveBubblePos(lp.x, lp.y)
            }
        })
        anim.start()
    }

    private fun saveBubblePos(x: Int, y: Int) {
        val c = ctx ?: return
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_X, x)
            putInt(KEY_Y, y)
        }
    }

    private fun onBubbleTap() {
        if (overlay != null) return
        hideBubble()
        showSelection()
    }

    // ---------------------------------------------------------------- selection

    fun showSelection() {
        val c = ctx ?: return
        val manager = wm ?: return
        if (overlay != null) return
        capturing = false

        val container = FrameLayout(c)
        val sel = SelectionOverlayView(c)
        container.addView(
            sel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val hint = TextView(c).apply {
            text = ar(
                "اسحب لتحديد المنطقة، ثم اضغط «التقاط»",
                "Drag to select an area, then tap Capture"
            )
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            background = rounded(0xF2121821.toInt(), 22f)
        }
        container.addView(
            hint,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = dp(52f) }
        )

        // Quick "one model / compare" chip before capturing, as asked.
        val store = ScanApp.get(c).settings
        val modeChip = actionButton(
            c,
            if (store.selectionMode == "compare") ar("مقارنة بين موديلين", "Compare two models")
            else ar("موديل واحد", "One model"),
            0xCC121821.toInt(),
            0xFF22D3EE.toInt()
        ).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
            minimumHeight = dp(40f)
            setOnClickListener {
                if (capturing) return@setOnClickListener
                store.selectionMode = if (store.selectionMode == "compare") "single" else "compare"
                text = if (store.selectionMode == "compare") ar("مقارنة بين موديلين", "Compare two models")
                else ar("موديل واحد", "One model")
            }
        }

        val cancel = actionButton(c, ar("إلغاء", "Cancel"), 0x40FFFFFF, Color.WHITE)
        val capture = actionButton(c, ar("التقاط", "Capture"), 0xFF22D3EE.toInt(), 0xFF04202A.toInt())

        val actions = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                capture,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(10f) }
            )
        }

        val panel = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            background = rounded(0xF20E151E.toInt(), 26f)
            addView(
                modeChip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(12f)
                }
            )
            addView(
                actions,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val (screenW, _) = screenSize(c)
        val panelWidth = minOf((screenW * 0.90f).toInt(), dp(460f))
        container.addView(
            panel,
            FrameLayout.LayoutParams(
                panelWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { bottomMargin = dp(56f) }
        )

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        cancel.setOnClickListener {
            if (capturing) return@setOnClickListener
            removeOverlay()
            showBubble()
        }

        capture.setOnClickListener {
            if (capturing) return@setOnClickListener
            capturing = true
            val rect: Rect = sel.selection()
            // Show that the tap landed before any window disappears.
            capture.isEnabled = false
            cancel.isEnabled = false
            modeChip.isEnabled = false
            capture.text = ar("جارٍ الالتقاط…", "Capturing…")
            hint.text = ar(
                "تم استلام الطلب ✔ يفتح الآن إذن الالتقاط",
                "Got it ✔ opening the capture permission now"
            )
            handler.postDelayed({
                removeOverlay()
                hideBubble()
                val i = Intent(c, ConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                i.putExtra(
                    ConsentActivity.EXTRA_RECT,
                    intArrayOf(rect.left, rect.top, rect.right, rect.bottom)
                )
                try {
                    c.startActivity(i)
                } catch (e: Exception) {
                    capturing = false
                    showBubble()
                    android.widget.Toast.makeText(
                        c,
                        ar(
                            "تعذّر فتح شاشة إذن الالتقاط على هذا الجهاز",
                            "The capture consent screen could not open on this device"
                        ),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }, 280)
        }

        try {
            manager.addView(container, lp)
        } catch (e: Exception) {
            showBubble()
            return
        }
        overlay = container
        selection = sel
    }

    fun removeOverlay() {
        val v = overlay ?: return
        try {
            wm?.removeView(v)
        } catch (e: Exception) {
            // ignore
        }
        overlay = null
        selection = null
        capturing = false
    }

    /** Called after a capture (or a denied consent) to bring the bubble back. */
    fun restoreBubble() {
        handler.post { showBubble() }
    }

    /** Hides every window so nothing of ours lands in the screenshot. */
    fun hideAllForCapture() {
        dismissMenu()
        removeOverlay()
        hideBubble()
    }

    // ---------------------------------------------------------------- menu

    private fun showMenu() {
        val c = ctx ?: return
        val manager = wm ?: return
        if (menuView != null) return
        val lp = bubbleLp ?: return

        val container = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            background = rounded(0xF2121821.toInt(), 18f)
        }

        fun item(label: String, onClick: () -> Unit): TextView {
            val tv = TextView(c).apply {
                text = label
                setTextColor(Color.parseColor("#EAF2F8"))
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
                isClickable = true
                setOnClickListener {
                    dismissMenu()
                    onClick()
                }
            }
            container.addView(
                tv,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            return tv
        }

        item(ar("السجل", "History")) { openApp(MainActivity.EXTRA_SCREEN_HISTORY) }
        item(ar("الإعدادات", "Settings")) { openApp(MainActivity.EXTRA_SCREEN_SETTINGS) }
        item(ar("إغلاق العدسة", "Close lens")) {
            val app = ctx
            if (app != null) LensService.stop(app)
        }

        val width = dp(190f)
        val params = WindowManager.LayoutParams(
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (sw, sh) = screenSize(c)
            x = (lp.x + lp.width / 2 - width / 2).coerceIn(dp(8f), (sw - width - dp(8f)).coerceAtLeast(dp(8f)))
            val below = lp.y + lp.height + dp(8f)
            y = if (below + dp(180f) < sh) below else (lp.y - dp(170f)).coerceAtLeast(dp(40f))
        }

        container.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                dismissMenu()
                true
            } else {
                false
            }
        }

        try {
            manager.addView(container, params)
        } catch (e: Exception) {
            return
        }
        menuView = container
        menuParams = params
    }

    fun dismissMenu() {
        val v = menuView ?: return
        try {
            wm?.removeView(v)
        } catch (e: Exception) {
            // ignore
        }
        menuView = null
        menuParams = null
    }

    private fun openApp(screen: String) {
        val c = ctx ?: return
        val i = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_SCREEN, screen)
        try {
            c.startActivity(i)
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Big, readable, always-fits button: fixed paddings, one line, equal width in a row. */
    private fun actionButton(context: Context, label: String, fill: Int, textColor: Int): TextView =
        TextView(context).apply {
            text = label
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10f), dp(17f), dp(10f), dp(17f))
            minimumHeight = dp(54f)
            background = rounded(fill, 18f)
            isClickable = true
            isFocusable = true
        }

    @Suppress("unused")
    private fun pillButton(context: Context, label: String, fill: Int, textColor: Int): TextView =
        TextView(context).apply {
            text = label
            setTextColor(textColor)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(26f), dp(13f), dp(26f), dp(13f))
            background = rounded(fill, 26f)
            isClickable = true
        }

    private fun rounded(fill: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
        }

    private fun screenSize(c: Context): Pair<Int, Int> {
        val manager = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = manager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }
}
