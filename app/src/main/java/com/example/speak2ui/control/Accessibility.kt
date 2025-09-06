package com.example.speak2ui.control

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.example.speak2ui.data.InteractiveNode
import com.example.speak2ui.data.ParsedCommand
import com.example.speak2ui.data.TooltipMap
import com.example.speak2ui.ui.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.set

/**
 * The main accessibility service that listens for user commands and controls the screen.
 *
 * This service is the core of the application's functionality. It performs three main tasks in a loop:
 * 1.  **Screen Reading**: It captures the current state of the UI, identifying all visible and
 *     interactive elements.
 * 2.  **Command Parsing**: It receives voice commands (as text) from the [SttService],
 *     sends them to the [CommandParser] along with the screen context to get a structured command.
 * 3.  **Action Execution**: It uses the [ActionExecutor] to perform the parsed command on the screen.
 *
 * It also listens for tooltip data from the [TooltipService] to help identify UI elements.
 */
class Accessibility : AccessibilityService() {

    private var commandActionReceiver: BroadcastReceiver? = null
    private var tooltipMapReceiver: BroadcastReceiver? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screenReader = ScreenReader()
    private lateinit var commandParser: CommandParser

    private var visibleNodes = mutableListOf<AccessibilityNodeInfo>()
    private val clickableNodes = mutableListOf<AccessibilityNodeInfo>()
    private val availableApps = mutableListOf<AccessibilityNodeInfo>()
    private var tooltipMap = mutableListOf<TooltipMap>()

    companion object {
        private const val TAG = "ActionExecutor"
    }

    override fun onCreate() {
        super.onCreate()
        commandParser = CommandParser()
    }

    /**
     * Called when the system connects to the service.
     *
     * Sets up broadcast receivers to listen for:
     * - Commands from the [SttService] (`com.example.COMMAND_ACTION`).
     * - Tooltip map updates from the [TooltipService] (`com.example.TOOLTIP_MAP`).
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()

        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, OverlayService::class.java))
        } else {
            Log.w(TAG, "OverlayService permission missing")
        }

        // Receiver for commands from the STT service
        commandActionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val command = intent.getStringExtra("command") ?: return
                handleCommand(command)
            }
        }
        val commandFilter = IntentFilter("com.example.COMMAND_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandActionReceiver, commandFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandActionReceiver, commandFilter)
        }

        // Receiver for the map of numbered tooltips from the TooltipService
        tooltipMapReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != "com.example.TOOLTIP") return
                val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra("tooltipMap", TooltipMap::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra("tooltipMap")
                }
                tooltipMap = list?.toMutableList() ?: mutableListOf()
            }
        }
        val tooltipFilter = IntentFilter("com.example.TOOLTIP")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                tooltipMapReceiver,
                tooltipFilter,
                null,
                Handler(Looper.getMainLooper()),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(tooltipMapReceiver, tooltipFilter)
        }
    }

    /**
     * The main workflow for handling a single voice command.
     *
     * This function is launched in a coroutine and performs the entire sequence of reading the screen,
     * parsing the command, and executing the resulting action.
     *
     * @param command The raw text command received from the STT service.
     */
    private fun handleCommand(command: String) {
        serviceScope.launch {
            // 1. Read screen to get current UI context
            visibleNodes.clear()
            clickableNodes.clear()
            availableApps.clear()

            refreshNodeCaches()

//            rootInActiveWindow?.let { root ->
//                screenReader.collectVisibleNodes(root, visibleNodes)
//            }
//            clickableNodes.addAll(visibleNodes.filter { it.isClickable })
//
//            val activePackage = rootInActiveWindow?.packageName?.toString()
//            availableApps.addAll(
//                clickableNodes.filter { node ->
//                    val nodePackage = node.packageName?.toString()
//                    node.window?.type == AccessibilityWindowInfo.TYPE_APPLICATION && nodePackage == activePackage
//                }
//            )

            // 2. Parse the command using the screen context
            val allInteractiveNodes = getInteractiveNodes()
            val parseStartMs = SystemClock.elapsedRealtime()
            val parsed: ParsedCommand = commandParser.parseCommand(command, allInteractiveNodes)
            Log.d(TAG, "Parsed: ${parsed.intent} ${parsed.value} (${SystemClock.elapsedRealtime() - parseStartMs} ms)")

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    applicationContext,
                    "${parsed.intent} (${parsed.value})",
                    Toast.LENGTH_SHORT
                ).show()
            }

            // 3. Execute the parsed action
            withContext(Dispatchers.Main) {
                val actionStartMs = SystemClock.elapsedRealtime()
                val executor = ActionExecutor(
                    this@Accessibility,
                    visibleNodes,
                    clickableNodes,
                    availableApps,
                    tooltipMap
                )
                executor.handleParsedCommand(parsed)
                Log.d(
                    TAG,
                    "Command completed (${SystemClock.elapsedRealtime() - actionStartMs} ms)"
                )
            }
        }
    }

    fun refreshNodeCaches() {
        visibleNodes.clear()
        clickableNodes.clear()
        availableApps.clear()

        val root = rootInActiveWindow ?: return
        screenReader.collectVisibleNodes(root, visibleNodes)

        val activePkg = rootInActiveWindow?.packageName?.toString()
        val dm = Resources.getSystem().displayMetrics
        val screenRect = Rect(0, 0, dm.widthPixels, dm.heightPixels)
        val seen = mutableSetOf<String>()

        visibleNodes.forEach { v ->
            val vb = Rect().also { v.getBoundsInScreen(it) }
            val onScreen = Rect.intersects(screenRect, vb) && vb.width() > 0 && vb.height() > 0
            if (!onScreen) return@forEach

            // 자식(=텍스트 노드 등)에서 조상 클릭 타깃으로 승격
            val actionable = if (v.isClickable) v else resolveActionableAncestor(v)
            if (actionable == null /*|| !actionable.isEnabled*/) return@forEach // 필요 시 enabled만 체크

            val key = nodeKey(actionable)
            if (!seen.add(key)) return@forEach

            val pkg = actionable.packageName?.toString()
            val isApp = (actionable.window?.type == AccessibilityWindowInfo.TYPE_APPLICATION && pkg == activePkg)

            if (isApp) availableApps.add(actionable) else clickableNodes.add(actionable)
        }
    }

    // 노드 식별용 키
    fun nodeKey(n: AccessibilityNodeInfo): String {
        val b = Rect().also { n.getBoundsInScreen(it) }
        val id = n.viewIdResourceName ?: "no-id"
        val cls = n.className?.toString().orEmpty()
        return "$id|$cls|${b.left},${b.top},${b.right},${b.bottom}"
    }

    fun resolveActionableAncestor(from: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = from ?: return null
        var steps = 0
        while (true) {
            val hasClick = cur.isClickable || cur.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
            if (hasClick /* && cur.isEnabled */) { // enabled만 체크(visibleToUser는 refresh에서 자식 bounds로 보장)
                return cur
            }
            cur = cur.parent ?: return null
            if (++steps > 20) return null
        }
    }

    /**
     * Aggregates all available clickable elements into a single list of [InteractiveNode]s.
     *
     * This list is passed to the [CommandParser] to provide context about what is currently
     * interactive on the screen. It includes elements identified by text from apps, general
     * clickable nodes, and numbered tooltips.
     *
     * @return A consolidated list of [InteractiveNode]s.
     */
    private fun getInteractiveNodes(): List<InteractiveNode> {
        val allInteractiveNodes = mutableListOf<InteractiveNode>()

        val availableList = availableApps
            .mapNotNull { labelForActionable(it).trim().takeIf { t -> t.isNotEmpty() } }
            .distinctBy { normalizeLabel(it) }
            .map { InteractiveNode(it, true) }
        allInteractiveNodes.addAll(availableList)

        val clickableList = clickableNodes
            .mapNotNull { labelForActionable(it).trim().takeIf { t -> t.isNotEmpty() } }
            .distinctBy { normalizeLabel(it) }
            .map { InteractiveNode(it, false) }
        allInteractiveNodes.addAll(clickableList)

        val tooltipList = tooltipMap
            .mapNotNull { it.number?.toString()?.trim()?.takeIf { t -> t.isNotEmpty() } }
            .distinct()
            .map { InteractiveNode(it, false) }
        allInteractiveNodes.addAll(tooltipList)

        return allInteractiveNodes
    }

    // 액션 가능한 노드에 라벨 부여: 본인 라벨 없으면 자식(최대 depth 3)에서 찾아옴
    // actionable 자신에 text/desc/hint 없으면 자식들에서 제목/설명 텍스트를 끌어온다.
    fun labelForActionable(n: AccessibilityNodeInfo): String {
        fun selfLabel(): String {
            val t = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            val h = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString().orEmpty() else ""
            return when {
                t.isNotBlank() -> t
                d.isNotBlank() -> d
                h.isNotBlank() -> h
                else -> ""
            }
        }

        val self = selfLabel()
        if (self.isNotBlank()) return self

        // 제목/채널명 같은 자식 텍스트를 BFS로 검색 (최대 48개 노드)
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(n)
        var visited = 0
        var fallback = "" // 첫 번째 발견 텍스트(없으면 빈 문자열)

        while (q.isNotEmpty() && visited < 48) {
            val cur = q.removeFirst()
            visited++

            val t = cur.text?.toString()?.trim().orEmpty()
            val d = cur.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotBlank() || d.isNotBlank()) {
                val lbl = if (t.isNotBlank()) t else d
                val idName = (cur.viewIdResourceName ?: "").substringAfterLast("/")
                // 우선순위: title, channel/author 같은 필드명을 가급적 먼저 사용
                if (idName.contains("title", ignoreCase = true) ||
                    idName.contains("author", ignoreCase = true) ||
                    idName.contains("channel", ignoreCase = true)
                ) {
                    return lbl
                }
                if (fallback.isBlank()) fallback = lbl
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { q.add(it) }
            }
        }
        return fallback // 있으면 반환, 없으면 빈 문자열
    }

    fun normalizeLabel(s: String): String =
        s.lowercase().trim().replace("\\s+".toRegex(), " ")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /**
     * Called when the service is being destroyed.
     *
     * Cleans up resources by canceling the coroutine scope and unregistering broadcast receivers.
     */
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        runCatching { unregisterReceiver(commandActionReceiver) }
        runCatching { unregisterReceiver(tooltipMapReceiver) }
    }
}