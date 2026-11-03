package com.example.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.CommonItemDivider
import com.example.ui.components.IconContainerBox
import com.example.ui.components.standardSwitchColors
import com.example.ui.theme.EmeraldGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import java.lang.ref.WeakReference

/**
 * Temporary read-only Screen Inspector diagnostic tool.
 * Shows a floating button over other apps, intercepts one tap when armed,
 * and displays an info card with accessibility node details.
 */
object TempInspector {

    const val ENABLED = true

    // In-memory switch state
    var isSwitchOn by mutableStateOf(false)
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    private var floatingButtonView: View? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null

    private var captureView: View? = null
    private var infoCardView: View? = null

    private var isArmed = false
    private var savedServiceInfoFlags: Int? = null

    private var lastButtonX = 30
    private var lastButtonY = 400

    private val autoCancelRunnable = Runnable {
        try {
            if (isArmed) {
                val service = FocusAccessibilityService.getInstance()
                disarmToIdle(service)
                if (service != null) {
                    Toast.makeText(service, "Screen inspection cancelled (timeout)", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (t: Throwable) {
            // ignored intentionally
        }
    }

    /**
     * Composable switch row to be placed inside HomeScreen.kt.
     */
    @Composable
    fun InspectorSwitchRow() {
        if (!ENABLED) return
        val context = LocalContext.current

        Column(modifier = Modifier.fillMaxWidth()) {
            CommonItemDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { toggleSwitch(context) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconContainerBox(
                        icon = Icons.Default.Search,
                        iconTint = EmeraldGreen,
                        containerColor = androidx.compose.ui.graphics.Color(0xFF10B981).copy(alpha = 0.15f)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "Screen Inspector",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Tap floating button to inspect any UI element",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = TextMuted,
                            lineHeight = 14.sp
                        )
                    }
                }

                Switch(
                    checked = isSwitchOn,
                    onCheckedChange = { toggleSwitch(context) },
                    colors = standardSwitchColors(),
                    modifier = Modifier.testTag("toggle_screen_inspector")
                )
            }
        }
    }

    /**
     * Toggles the inspector on/off in-memory.
     */
    fun toggleSwitch(context: Context) {
        if (!ENABLED) return
        try {
            if (!isSwitchOn) {
                val service = FocusAccessibilityService.getInstance()
                if (service == null || !FocusAccessibilityService.isServiceConnected()) {
                    Toast.makeText(context, "Accessibility service is off", Toast.LENGTH_SHORT).show()
                    isSwitchOn = false
                    return
                }
                isSwitchOn = true
                showFloatingButton(service)
            } else {
                isSwitchOn = false
                cleanUp()
            }
        } catch (t: Throwable) {
            cleanUp()
            isSwitchOn = false
        }
    }

    /**
     * Completely removes all inspector overlay windows and restores any modified flags.
     */
    fun cleanUp() {
        try {
            mainHandler.removeCallbacks(autoCancelRunnable)
            isSwitchOn = false
            isArmed = false

            val service = FocusAccessibilityService.getInstance()
            if (service != null) {
                val wm = try { service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager } catch (t: Throwable) { null }
                if (wm != null) {
                    captureView?.let { try { wm.removeView(it) } catch (t: Throwable) {} }
                    infoCardView?.let { try { wm.removeView(it) } catch (t: Throwable) {} }
                    floatingButtonView?.let { try { wm.removeView(it) } catch (t: Throwable) {} }
                }
                restoreServiceInfoFlags(service)
            }

            captureView = null
            infoCardView = null
            floatingButtonView = null
            floatingButtonParams = null
        } catch (t: Throwable) {
            // ignored intentionally
        }
    }

    private fun showFloatingButton(service: FocusAccessibilityService) {
        try {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

            if (floatingButtonView != null) {
                try { wm.removeView(floatingButtonView) } catch (t: Throwable) {}
                floatingButtonView = null
            }

            val sizePx = dpToPx(service, 48)
            val params = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                format = PixelFormat.TRANSLUCENT
                width = sizePx
                height = sizePx
                gravity = Gravity.TOP or Gravity.START
                x = lastButtonX
                y = lastButtonY
            }
            floatingButtonParams = params

            val button = FrameLayout(service).apply {
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFF10B981.toInt()) // Emerald Green
                    setStroke(dpToPx(service, 2), 0xFFFFFFFF.toInt())
                }
                background = bg
                elevation = dpToPx(service, 6).toFloat()

                val textView = TextView(service).apply {
                    text = "🎯"
                    textSize = 20f
                    gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                addView(textView)
            }

            val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
            var downRawX = 0f
            var downRawY = 0f
            var startParamsX = 0
            var startParamsY = 0
            var isDragging = false

            button.setOnTouchListener { _, event ->
                try {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = event.rawX
                            downRawY = event.rawY
                            startParamsX = params.x
                            startParamsY = params.y
                            isDragging = false
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - downRawX).toInt()
                            val dy = (event.rawY - downRawY).toInt()
                            if (!isDragging && (Math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop)) {
                                isDragging = true
                            }
                            if (isDragging) {
                                params.x = startParamsX + dx
                                params.y = startParamsY + dy
                                lastButtonX = params.x
                                lastButtonY = params.y
                                try {
                                    wm.updateViewLayout(button, params)
                                } catch (t: Throwable) {}
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            if (!isDragging) {
                                onButtonClicked(service)
                            }
                            true
                        }
                        else -> false
                    }
                } catch (t: Throwable) {
                    false
                }
            }

            floatingButtonView = button
            wm.addView(button, params)
        } catch (t: Throwable) {
            // ignored intentionally
        }
    }

    private fun onButtonClicked(service: FocusAccessibilityService) {
        if (isArmed) {
            // Tapping while armed cancels back to IDLE
            disarmToIdle(service)
            Toast.makeText(service, "Screen inspection cancelled", Toast.LENGTH_SHORT).show()
        } else {
            // Tapping while idle arms the inspector
            armForNextTap(service)
        }
    }

    private fun armForNextTap(service: FocusAccessibilityService) {
        try {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

            // 1. Dismiss any existing info card
            removeInfoCard(service)

            isArmed = true

            // 2. Enable FLAG_INCLUDE_NOT_IMPORTANT_VIEWS on serviceInfo
            try {
                val info = service.serviceInfo
                if (info != null) {
                    savedServiceInfoFlags = info.flags
                    info.flags = info.flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    service.serviceInfo = info
                }
            } catch (t: Throwable) {}

            // 3. Create full-screen transparent capture layer (slight dim)
            val capture = FrameLayout(service).apply {
                setBackgroundColor(0x33000000) // slight dark dim

                // Small banner pill at the top
                val bannerPill = TextView(service).apply {
                    text = "Tap the item to inspect (tap button to cancel)"
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    val pillBg = GradientDrawable().apply {
                        setColor(0xFF1E293B.toInt())
                        cornerRadius = dpToPx(service, 20).toFloat()
                        setStroke(dpToPx(service, 1), 0xFF475569.toInt())
                    }
                    background = pillBg
                    setPadding(dpToPx(service, 16), dpToPx(service, 8), dpToPx(service, 16), dpToPx(service, 8))
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        topMargin = dpToPx(service, 48)
                    }
                }
                addView(bannerPill)

                // Intercept exactly the next tap
                setOnTouchListener { _, event ->
                    try {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            val tapX = event.rawX.toInt()
                            val tapY = event.rawY.toInt()

                            // Immediately disarm to IDLE so user never gets stuck
                            disarmToIdle(service)

                            // Inspect the tapped location and show card
                            mainHandler.postDelayed({
                                inspectAndShowCard(service, tapX, tapY)
                            }, 60L)
                            true
                        } else {
                            true
                        }
                    } catch (t: Throwable) {
                        disarmToIdle(service)
                        false
                    }
                }
            }

            val captureParams = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                format = PixelFormat.TRANSLUCENT
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                gravity = Gravity.TOP or Gravity.START
            }

            captureView = capture
            wm.addView(capture, captureParams)

            // 4. Ensure floating button stays on top of capture layer so it can be tapped to cancel
            floatingButtonView?.let { btn ->
                try {
                    wm.removeView(btn)
                    floatingButtonParams?.let { wm.addView(btn, it) }
                } catch (t: Throwable) {}
            }

            // 5. Schedule 15-second auto-cancel
            mainHandler.removeCallbacks(autoCancelRunnable)
            mainHandler.postDelayed(autoCancelRunnable, 15000L)

        } catch (t: Throwable) {
            disarmToIdle(service)
        }
    }

    private fun disarmToIdle(service: FocusAccessibilityService?) {
        try {
            isArmed = false
            mainHandler.removeCallbacks(autoCancelRunnable)

            if (service != null) {
                val wm = try { service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager } catch (t: Throwable) { null }
                if (wm != null && captureView != null) {
                    try { wm.removeView(captureView) } catch (t: Throwable) {}
                    captureView = null
                }
                restoreServiceInfoFlags(service)
            }
        } catch (t: Throwable) {
            // ignored intentionally
        }
    }

    private fun restoreServiceInfoFlags(service: FocusAccessibilityService) {
        try {
            val flags = savedServiceInfoFlags
            if (flags != null) {
                val info = service.serviceInfo
                if (info != null) {
                    info.flags = flags
                    service.serviceInfo = info
                }
                savedServiceInfoFlags = null
            }
        } catch (t: Throwable) {
            // ignored intentionally
        }
    }

    private fun inspectAndShowCard(service: FocusAccessibilityService, tapX: Int, tapY: Int) {
        try {
            val root = try {
                service.rootInActiveWindow ?: service.windows?.find { it.isActive }?.root ?: service.windows?.firstOrNull()?.root
            } catch (t: Throwable) {
                null
            }

            val tappedNode = if (root != null) findDeepestNodeAt(root, tapX, tapY) else null
            val formattedInfo = formatInspectionInfo(service, tappedNode)

            showInfoCard(service, formattedInfo)
        } catch (t: Throwable) {
            showInfoCard(service, "No info (protected or empty screen)")
        }
    }

    private data class MatchedNode(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val area: Long
    )

    private fun findDeepestNodeAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        var bestMatch: MatchedNode? = null
        val bounds = Rect()

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            try {
                node.getBoundsInScreen(bounds)
                if (bounds.contains(x, y) && node.isVisibleToUser) {
                    val w = bounds.width().toLong()
                    val h = bounds.height().toLong()
                    val area = if (w > 0 && h > 0) w * h else Long.MAX_VALUE
                    val current = bestMatch
                    if (current == null || depth > current.depth || (depth == current.depth && area < current.area)) {
                        bestMatch = MatchedNode(node, depth, area)
                    }
                }
                val count = node.childCount
                for (i in 0 until count) {
                    val child = try { node.getChild(i) } catch (t: Throwable) { null }
                    if (child != null) {
                        traverse(child, depth + 1)
                    }
                }
            } catch (t: Throwable) {
                // ignored intentionally
            }
        }

        traverse(root, 0)
        return bestMatch?.node
    }

    private fun formatInspectionInfo(service: FocusAccessibilityService, node: AccessibilityNodeInfo?): String {
        if (node == null) {
            return "No info (protected or empty screen)"
        }

        val sb = StringBuilder()

        // 1. App name & Package name
        val pkg = node.packageName?.toString() ?: ""
        val appName = try {
            val pm = service.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            pkg
        }
        sb.append("App: ").append(trunc(appName)).append("\n")
        sb.append("Package: ").append(trunc(pkg)).append("\n")

        // 2. LOCKED: YES or NO (reuses FocusAccessibilityService.isAppLocked)
        val isLocked = service.isAppLocked(pkg)
        val lockedStr = if (isLocked) "YES" else "NO"
        sb.append("LOCKED: ").append(lockedStr).append("\n")

        // 3. Window title
        val winTitle = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                node.window?.title?.toString()
            } else null
        } catch (t: Throwable) {
            null
        }
        sb.append("Window Title: ").append(trunc(winTitle ?: "N/A")).append("\n\n")

        // 4. Tapped node details
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val nodeText = if (node.isPassword) "[hidden]" else trunc(node.text)

        sb.append("--- TAPPED NODE ---\n")
        sb.append("Class: ").append(trunc(node.className)).append("\n")
        sb.append("Text: ").append(nodeText).append("\n")
        sb.append("ContentDesc: ").append(trunc(node.contentDescription)).append("\n")
        sb.append("ViewId: ").append(trunc(node.viewIdResourceName)).append("\n")
        sb.append("Clickable: ").append(node.isClickable).append("\n")
        sb.append("Bounds: [${rect.left}, ${rect.top} - ${rect.right}, ${rect.bottom}] (${rect.width()}x${rect.height()})\n\n")

        // 5. Nearest clickable ancestor (or node itself if clickable)
        var clickableNode: AccessibilityNodeInfo? = if (node.isClickable) node else null
        var ancestor: AccessibilityNodeInfo? = try { node.parent } catch (t: Throwable) { null }
        while (clickableNode == null && ancestor != null) {
            if (ancestor.isClickable) {
                clickableNode = ancestor
                break
            }
            ancestor = try { ancestor.parent } catch (t: Throwable) { null }
        }

        sb.append("--- NEAREST CLICKABLE ---\n")
        if (clickableNode != null) {
            val cRect = Rect()
            clickableNode.getBoundsInScreen(cRect)
            val cText = if (clickableNode.isPassword) "[hidden]" else trunc(clickableNode.text)
            sb.append("Class: ").append(trunc(clickableNode.className)).append("\n")
            sb.append("Text: ").append(cText).append("\n")
            sb.append("ContentDesc: ").append(trunc(clickableNode.contentDescription)).append("\n")
            sb.append("ViewId: ").append(trunc(clickableNode.viewIdResourceName)).append("\n")
            sb.append("Clickable: true\n")
            sb.append("Bounds: [${cRect.left}, ${cRect.top} - ${cRect.right}, ${cRect.bottom}] (${cRect.width()}x${cRect.height()})\n\n")
        } else {
            sb.append("None\n\n")
        }

        // 6. Up to 3 parent levels
        sb.append("--- PARENTS (UP TO 3) ---\n")
        var parentLevel = 1
        var currParent: AccessibilityNodeInfo? = try { node.parent } catch (t: Throwable) { null }
        while (currParent != null && parentLevel <= 3) {
            val pText = if (currParent.isPassword) "[hidden]" else trunc(currParent.text)
            val pDesc = trunc(currParent.contentDescription)
            val pClass = trunc(currParent.className)
            sb.append("L$parentLevel: class=$pClass | text=$pText | desc=$pDesc\n")
            currParent = try { currParent.parent } catch (t: Throwable) { null }
            parentLevel++
        }
        if (parentLevel == 1) {
            sb.append("No parent nodes\n")
        }

        return sb.toString().trimEnd()
    }

    private fun trunc(s: CharSequence?): String {
        if (s == null) return "null"
        val str = s.toString()
        return if (str.length > 80) str.take(80) + "…" else str
    }

    private fun showInfoCard(service: FocusAccessibilityService, content: String) {
        try {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

            removeInfoCard(service)

            val dm = service.resources.displayMetrics
            val cardWidth = (dm.widthPixels * 0.92).toInt()
            val maxScrollHeight = (dm.heightPixels * 0.50).toInt()

            val card = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                val bg = GradientDrawable().apply {
                    setColor(0xFF0F172A.toInt()) // Slate 900
                    cornerRadius = dpToPx(service, 16).toFloat()
                    setStroke(dpToPx(service, 1), 0xFF334155.toInt())
                }
                background = bg
                elevation = dpToPx(service, 12).toFloat()
                setPadding(dpToPx(service, 16), dpToPx(service, 16), dpToPx(service, 16), dpToPx(service, 16))

                // Title bar
                val title = TextView(service).apply {
                    text = "Screen Inspector"
                    textSize = 16f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF10B981.toInt()) // Emerald Green
                    setPadding(0, 0, 0, dpToPx(service, 8))
                }
                addView(title)

                // Scrollable content area (max height ~60% of screen)
                val scroll = object : ScrollView(service) {
                    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                        val customHeight = MeasureSpec.makeMeasureSpec(maxScrollHeight, MeasureSpec.AT_MOST)
                        super.onMeasure(widthMeasureSpec, customHeight)
                    }
                }.apply {
                    isFillViewport = true
                }

                val textView = TextView(service).apply {
                    text = content
                    textSize = 11.5f
                    typeface = Typeface.MONOSPACE
                    setTextColor(0xFFE2E8F0.toInt())
                    setTextIsSelectable(true)
                    setLineSpacing(dpToPx(service, 2).toFloat(), 1f)
                }
                scroll.addView(textView)
                addView(scroll, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))

                // Action buttons: Copy & Close
                val buttonRow = LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, dpToPx(service, 12), 0, 0)

                    val copyBtn = Button(service).apply {
                        text = "Copy"
                        setTextColor(0xFFFFFFFF.toInt())
                        val copyBg = GradientDrawable().apply {
                            setColor(0xFF059669.toInt()) // Emerald 600
                            cornerRadius = dpToPx(service, 8).toFloat()
                        }
                        background = copyBg
                        setOnClickListener {
                            try {
                                val cm = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("Screen Inspector", content))
                                Toast.makeText(service, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            } catch (t: Throwable) {}
                        }
                    }
                    val copyParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dpToPx(service, 40)
                    ).apply {
                        marginEnd = dpToPx(service, 8)
                    }
                    addView(copyBtn, copyParams)

                    val closeBtn = Button(service).apply {
                        text = "Close"
                        setTextColor(0xFFFFFFFF.toInt())
                        val closeBg = GradientDrawable().apply {
                            setColor(0xFF475569.toInt()) // Slate 600
                            cornerRadius = dpToPx(service, 8).toFloat()
                        }
                        background = closeBg
                        setOnClickListener {
                            removeInfoCard(service)
                        }
                    }
                    val closeParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dpToPx(service, 40)
                    )
                    addView(closeBtn, closeParams)
                }
                addView(buttonRow)
            }

            val cardParams = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                format = PixelFormat.TRANSLUCENT
                width = cardWidth
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.CENTER
            }

            infoCardView = card
            wm.addView(card, cardParams)
        } catch (t: Throwable) {
            // ignored intentionally
        }
    }

    private fun removeInfoCard(service: FocusAccessibilityService? = null) {
        try {
            val s = service ?: FocusAccessibilityService.getInstance()
            if (s != null && infoCardView != null) {
                val wm = s.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.removeView(infoCardView)
            }
        } catch (t: Throwable) {
            // ignored intentionally
        } finally {
            infoCardView = null
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
