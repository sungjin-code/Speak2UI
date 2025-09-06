package com.example.speak2ui.control

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.example.speak2ui.R
import com.example.speak2ui.data.TipCandidate
import com.example.speak2ui.data.TipSnapshot
import com.example.speak2ui.data.TooltipMap
import com.example.speak2ui.control.Accessibility
import kotlin.math.abs

/**
 * An [AccessibilityService] that automatically displays numbered tooltips (badges)
 * on specific UI elements.
 *
 * This service scans the screen for targetable UI elements (e.g., unlabeled icons, search bars)
 * and overlays a small, numbered view on top of them. It then broadcasts a map of these
 * numbers to the corresponding UI element details ([com.example.speak2ui.data.TooltipMap]) for other services, like
 * [AccessibilityService], to use.
 *
 * This allows the user to refer to UI elements by number in their voice commands (e.g., "tap 3").
 */
class TooltipService : AccessibilityService() {

    private val accessibility = Accessibility()

    private lateinit var windowManager: WindowManager
    private val overlays = mutableMapOf<String, View>()
    private val tooltipMap = mutableListOf<TooltipMap>()

    // 중복 방지 키(정확 동일)
    private val seenTargets = mutableSetOf<String>()
    // 원시 수집 후보(DFS에서 쌓고, 사후 정제)
    private val candidates = mutableListOf<TipCandidate>()
    private var lastSnapshot: List<TipSnapshot> = emptyList()

    // 200ms debounce
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = Runnable { updateTooltips() }

    companion object {
        private const val TAG = "Tooltip"
    }

    /**
     * Called when the system successfully connects to this accessibility service.
     * Initializes the [WindowManager] and triggers the first tooltip update.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateTooltips()
    }

    /**
     * This method is called when the system wants to interrupt the feedback this service is providing.
     * Currently, it does nothing.
     */
    override fun onInterrupt() = Unit

    /**
     * Called when an [AccessibilityEvent] is received.
     *
     * Listens for `TYPE_WINDOW_CONTENT_CHANGED` events to refresh the tooltips.
     * A debounce mechanism is used to avoid excessive updates in rapid succession.
     *
     * @param event The received accessibility event.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            handler.removeCallbacks(updateRunnable)
            handler.postDelayed(updateRunnable, 200)
        }
    }

    /**
     * The core logic for updating the tooltips on the screen.
     *
     * This function performs the following steps:
     * 1. Removes all existing tooltips from the screen.
     * 2. Clears the internal [tooltipMap].
     * 3. Traverses the accessibility node tree of the active window using [dfs] to find target elements.
     * 4. For each found element, it creates a badge view and adds it to the screen.
     * 5. Broadcasts the updated list of [TooltipMap] objects to other components.
     */
    private fun updateTooltips() {
        val root = rootInActiveWindow ?: return

        // 캐시 초기화 (오버레이/브로드캐스트는 '변화가 있을 때만' 갱신)
        seenTargets.clear()
        candidates.clear()

        // DFS로 원시 후보 수집
        dfs(root, depth = 0)

        // ghost/겹침 정제
        val finalTips = dedupeAndFilterTips(candidates)

        // ====== 레이아웃 비교: 기존과 동일하면 갱신/브로드캐스트 전부 스킵 ======
        val newSnapshot = makeSnapshot(finalTips)
        if (isSameLayout(newSnapshot, lastSnapshot)) {
            Log.d(TAG, "No tooltip layout change. Skipping update.")
            return
        }

        // 1) 기존 오버레이 제거
        overlays.values.forEach { view ->
            runCatching { windowManager.removeView(view) } }
        overlays.clear()

        // 2) 브로드캐스트 데이터 재구성
        tooltipMap.clear()
        finalTips.forEachIndexed { idx, c ->
            val badge = makeBadgeView((idx + 1).toString())
            positionOverlay(badge, c.bounds)
            overlays["${idx + 1}"] = badge

            tooltipMap.add(
                TooltipMap(
                    number = idx + 1,
                    description   = c.matchKey,   // MyAccessibilityService가 기대하는 key
                    bounds = c.bounds
                )
            )

            Log.d(
                TAG,
                "[TIP] #${idx + 1} id=${if (c.id.isEmpty()) "no-id" else c.id}, " +
                        "cls=${c.cls}, matchKey='${c.matchKey}', display='${c.displayLabel}', bounds=${c.bounds}"
            )
        }

        // 3) 브로드캐스트
        val broadcastIntent = Intent("com.example.TOOLTIP").apply {
            setPackage(packageName)
            putParcelableArrayListExtra("tooltipMap", ArrayList(tooltipMap))
        }
        sendBroadcast(broadcastIntent)
        Log.d(TAG, "Tooltip Map broadcasted: count=${tooltipMap.size}")

        // 4) 스냅샷 갱신
        lastSnapshot = newSnapshot
    }


    /**
     * Performs a Depth-First Search (DFS) traversal of the accessibility node tree to find
     * UI elements that should receive a tooltip.
     *
     * This is the core logic that determines which elements are important enough to be labeled.
     * The criteria for adding a tooltip are heuristic-based and target elements that are often
     * hard to refer to by voice, such as unlabeled icons or generic search bars.
     *
     * @param n The [AccessibilityNodeInfo] to start the traversal from.
     */
    private fun dfs(n: AccessibilityNodeInfo, depth: Int) {
        val b = Rect().also { n.getBoundsInScreen(it) }
        if (b.width() > 0 && b.height() > 0) {
            val actionable = accessibility.resolveActionableAncestor(n)
            if (actionable != null && actionable.isEnabled && actionable.isVisibleToUser) {
                val key = accessibility.nodeKey(actionable)

                // 하위에 '보이는 text'가 하나라도 있으면 툴팁 배부 X (요구사항)
                if (key !in seenTargets && !hasAnyVisibleText(actionable)) {
                    seenTargets.add(key)

                    val ab   = Rect().also { actionable.getBoundsInScreen(it) }
                    val id   = actionable.viewIdResourceName ?: ""
                    val cls  = actionable.className?.toString().orEmpty()

                    //매칭용 키: contentDescription (없으면 빈 문자열 → 좌표 탭 폴백)
                    val matchKey = actionable.contentDescription?.toString()?.trim().orEmpty()
                    val display = pickDisplayLabel(actionable) // text만, 없으면 "(tap)"

                    candidates.add(
                        TipCandidate(
                            bounds = ab,
                            displayLabel = display,
                            matchKey = matchKey,
                            id = id,
                            cls = cls,
                            depth = depth
                        )
                    )
                }
            }
        }
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { dfs(it, depth + 1) }
        }
    }

    // ---------- 스냅샷/비교 ----------
    private fun makeSnapshot(tips: List<TipCandidate>): List<TipSnapshot> {
        return tips.map { c ->
            TipSnapshot(
                l = c.bounds.left,
                t = c.bounds.top,
                r = c.bounds.right,
                b = c.bounds.bottom,
                key = c.matchKey
            )
        }.sortedWith(compareBy<TipSnapshot> { it.t }.thenBy { it.l }.thenBy { it.r }.thenBy { it.b }.thenBy { it.key })
    }

    // 좌표 오차 tol 픽셀까지 동일로 간주
    private fun isSameLayout(newSnaps: List<TipSnapshot>, oldSnaps: List<TipSnapshot>, tol: Int = 2): Boolean {
        if (newSnaps.size != oldSnaps.size) return false
        for (i in newSnaps.indices) {
            val a = newSnaps[i]
            val b = oldSnaps[i]
            val sameKey = (a.key == b.key) || (a.key.isEmpty() && b.key.isEmpty())
            val sameRect =
                abs(a.l - b.l) <= tol &&
                        abs(a.t - b.t) <= tol &&
                        abs(a.r - b.r) <= tol &&
                        abs(a.b - b.b) <= tol
            if (!(sameKey && sameRect)) return false
        }
        return true
    }

    // 자기/자식에 "보이는 text"가 하나라도 있으면 true (desc/hint 무시)
    private fun hasAnyVisibleText(n: AccessibilityNodeInfo): Boolean {
        val q: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        q.add(n)
        var seen = 0
        while (q.isNotEmpty() && seen < 64) {
            val cur = q.removeFirst(); seen++
            val t = cur.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) {
                val rb = Rect().also { cur.getBoundsInScreen(it) }
                val onScreen = rb.width() > 0 && rb.height() > 0 && cur.isVisibleToUser
                if (onScreen) return true
            }
            for (i in 0 until cur.childCount) cur.getChild(i)?.let { q.add(it) }
        }
        return false
    }

    // 표시에 적합한 문자열: text만 사용(없으면 "(tap)")
    private fun pickDisplayLabel(n: AccessibilityNodeInfo): String {
        val q: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        q.add(n)
        var cnt = 0
        while (q.isNotEmpty() && cnt < 48) {
            val cur = q.removeFirst(); cnt++
            val t = cur.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) return t
            for (i in 0 until cur.childCount) cur.getChild(i)?.let { q.add(it) }
        }
        return "(tap)"
    }

    // ---------- ghost/IoU 기반 정제 ----------
    private fun dedupeAndFilterTips(src: List<TipCandidate>): List<TipCandidate> {
        val banned = listOf(
            "ghost", "skeleton", "scrim", "shimmer",
            "placeholder", "guide", "guideline", "touch_delegate"
        )
        val filtered = src.filter { c ->
            val id = c.id.lowercase()
            val cl = c.cls.lowercase()
            !banned.any { id.contains(it) || cl.contains(it) } &&
                    c.bounds.width() > 0 && c.bounds.height() > 0
        }

        fun isIconish(c: TipCandidate) =
            c.cls.endsWith("ImageButton") || c.cls.endsWith("ImageView")

        fun score(c: TipCandidate): Int {
            val area = c.bounds.width() * c.bounds.height()
            var s = 0
            if (isIconish(c)) s += 1000
            if (c.id.isNotEmpty() && c.id != "no-id") s += 100
            s += c.depth.coerceIn(0, 99)
            s -= (area / 500) // 면적이 작을수록 유리
            return s
        }

        val sorted = filtered.sortedByDescending { score(it) }

        val kept = mutableListOf<TipCandidate>()
        for (c in sorted) {
            val overlap = kept.any { k ->
                iou(k.bounds, c.bounds) > 0.60f ||
                        containsApprox(k.bounds, c.bounds) ||
                        containsApprox(c.bounds, k.bounds)
            }
            if (!overlap) kept.add(c)
        }

        Log.d(TAG, "dedupe: src=${src.size}, afterGhost=${filtered.size}, final=${kept.size}")
        return kept
    }

    private fun iou(a: Rect, b: Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val iw = (right - left).coerceAtLeast(0)
        val ih = (bottom - top).coerceAtLeast(0)
        val inter = iw * ih
        if (inter <= 0) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0) 0f else inter.toFloat() / union.toFloat()
    }

    private fun containsApprox(outer: Rect, inner: Rect, tol: Int = 6): Boolean {
        return inner.left   >= outer.left - tol &&
                inner.top    >= outer.top  - tol &&
                inner.right  <= outer.right + tol &&
                inner.bottom <= outer.bottom + tol
    }


    /**
     * Creates and inflates the tooltip badge view.
     *
     * @param label The text to display inside the badge (e.g., "1", "2").
     * @return The inflated [View] for the tooltip badge.
     */
    @SuppressLint("InflateParams")
    private fun makeBadgeView(label: String): View {
        val root = LayoutInflater.from(this).inflate(R.layout.tooltip_view, null, false)
        val tv = root.findViewById<TextView>(R.id.tooltip)
        tv.text = label
        root.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        return root
    }

    /**
     * Positions the tooltip badge on the screen relative to its target UI element.
     *
     * It calculates the position to be near the center of the target element, with an offset,
     * and ensures the badge does not go off-screen.
     *
     * @param badge The tooltip [View] to position.
     * @param bounds The [Rect] of the target UI element.
     */
    private fun positionOverlay(badge: View, bounds: Rect) {
        val w = badge.measuredWidth
        val h = badge.measuredHeight

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels
        val screenH = metrics.heightPixels

        val offsetX = (metrics.density).toInt()
        val offsetY = (60f * metrics.density).toInt()

        val cx = (bounds.left + bounds.right) / 2
        val cy = (bounds.top + bounds.bottom) / 2

        var x = cx + offsetX - (w / 2)
        var y = cy - offsetY - (h / 2)

        x = x.coerceIn(0, screenW - w)
        y = y.coerceIn(0, screenH - h)

        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        val params = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

        windowManager.addView(badge, params)
    }
}
