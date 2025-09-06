package com.example.speak2ui.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import com.example.speak2ui.data.ParsedCommand
import com.example.speak2ui.data.TooltipMap

/**
 * Executes actions on the UI based on a parsed command.
 *
 * This class takes a [ParsedCommand] object and performs the corresponding accessibility action,
 * such as clicking, swiping, or entering text. It uses the provided lists of visible and
 * clickable nodes to identify targets for these actions.
 *
 * @param service The instance of the running [AccessibilityService].
 * @param visibleNodes A list of all currently visible [AccessibilityNodeInfo]s on the screen.
 * @param clickableNodes A list of all currently clickable [AccessibilityNodeInfo]s on the screen.
 * @param availableApps A list of all available apps.
 * @param tooltipMap A list of mappings from a number to a UI element, used for commands like "tap 3".
 */
class ActionExecutor(
    private val service: AccessibilityService,
    private val visibleNodes: List<AccessibilityNodeInfo>,
    private val clickableNodes: List<AccessibilityNodeInfo>,
    private val availableApps: List<AccessibilityNodeInfo>,
    private val tooltipMap: List<TooltipMap>
) {

    private val homePackage by lazy { resolveDefaultLauncherPackage() }
    private val accessibility = Accessibility()

    companion object {
        private const val TAG = "ActionExecutor"
    }

    /**
     * Handles the given [ParsedCommand] by executing the corresponding UI action.
     *
     * This is the main entry point for the class. It interprets the intent from the command
     * and dispatches the action to the appropriate helper method. It handles actions like
     * PRESS, SWIPE, ENTER, etc.
     *
     * @param cmd The [ParsedCommand] object containing the intent and value to be executed.
     * @return Always returns `true` after attempting to execute the command. The success of the
     *         action itself is logged and broadcasted separately.
     */
    fun handleParsedCommand(cmd: ParsedCommand): Boolean {
        val intent = cmd.intent
        val value = cmd.value
        var isCompleted = false
        var message = ""

        // For any press-like actions (include LONG_PRESS and DOUBLE_PRESS),
        // try to identify the target node beforehand.
        // The target can be identified by its label (text) or by a tooltip number.
        var tooltip: TooltipMap? = null
        var targetNode: AccessibilityNodeInfo? = null
        var isLabeled = false

        if (intent.contains("PRESS")) {
            if (value.isEmpty()) {
                Log.e(TAG, "PRESS received without target values")
            } else {
                val pressTarget = value[0]
                // 숫자 우선
                val tooltipNumber = pressTarget.takeWhile { it.isDigit() }
                if (tooltipNumber.isNotEmpty()) {
                    tooltip = tooltipMap.find { it.number == tooltipNumber.toInt() }
                    if (tooltip == null) Log.e(TAG, "No tooltip ($pressTarget) executable")
                }
                // 라벨 매칭은 그다음
                if (tooltip == null) {
                    targetNode = findNodeByLabel(pressTarget)
                    isLabeled = (targetNode != null)
                }
            }
        }

        when (intent) {
            "PRESS" -> {
                val ok = if (isLabeled) {
                    val actionable = accessibility.resolveActionableAncestor(targetNode) ?: targetNode
                    actionable?.let { node ->
                        val cls = node.className?.toString().orEmpty()
                        if (cls.endsWith("EditText")) {
                            focusOrTapEditText(node, tooltip?.bounds)
                        } else {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                                    Rect().apply { node.getBoundsInScreen(this) }.let { tapRect(it) }
                        }
                    } == true
                } else {
                    val desc = tooltip?.description?.trim()
                    val node = clickableNodes.find { it.contentDescription?.toString()?.trim() == desc }
                    when {
                        node != null -> {
                            val actionable = accessibility.resolveActionableAncestor(node) ?: node
                            val cls = actionable.className?.toString().orEmpty()
                            if (cls.endsWith("EditText")) focusOrTapEditText(actionable, tooltip?.bounds)
                            else actionable.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                                    Rect().apply { actionable.getBoundsInScreen(this) }
                                        .let { r -> tapRect(if (r.width() > 0 && r.height() > 0) r else (tooltip?.bounds ?: Rect())) }
                        }
                        tooltip?.bounds != null -> tapRect(tooltip.bounds)
                        else -> false
                    }
                }
                isCompleted = ok                      // 🔑
                if (!ok) message += "PRESS click/tap failed | "
            }

            "DOUBLE_PRESS" -> {
                if (isLabeled) {
                    val actionable = accessibility.resolveActionableAncestor(targetNode) ?: targetNode
                    val ok1 = actionable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    val ok2 = actionable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    isCompleted = ok1 && ok2
                    if (!isCompleted) message += "DOUBLE_PRESS labeled failed | "
                } else {
                    val desc = tooltip?.description
                    val node = clickableNodes.find { it.contentDescription?.toString()?.trim() == desc?.trim() }
                    val actionable = accessibility.resolveActionableAncestor(node) ?: node
                    val ok1 = actionable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    val ok2 = actionable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    isCompleted = ok1 && ok2
                    if (!isCompleted) message += "DOUBLE_PRESS tooltip failed | "
                }
            }

            "LONG_PRESS" -> {
                if (isLabeled) {
                    val actionable = accessibility.resolveActionableAncestor(targetNode) ?: targetNode
                    val ok = actionable?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
                    isCompleted = ok
                    if (!ok) message += "LONG_PRESS labeled failed | "
                } else {
                    val desc = tooltip?.description
                    val node = clickableNodes.find { it.contentDescription?.toString()?.trim() == desc?.trim() }
                    val actionable = accessibility.resolveActionableAncestor(node) ?: node
                    val ok = actionable?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
                    isCompleted = ok
                    if (!ok) message += "LONG_PRESS tooltip failed | "
                }
            }

            "ENTER" -> {
                val (completed, msg) = handleEnterAction(cmd)
                isCompleted = completed
                message += msg
            }

            "SWIPE" -> {
                val (completed, msg) = handleSwipeAction(cmd)
                isCompleted = completed
                message += msg
            }

            "HOME" -> {
                isCompleted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                if (!isCompleted) message += "GLOBAL_ACTION_HOME failed | "
            }

            "BACK" -> {
                isCompleted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                if (!isCompleted) message += "GLOBAL_ACTION_BACK failed | "
            }

            "OVERVIEW_BUTTON" -> {
                isCompleted =
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                if (!isCompleted) message += "GLOBAL_ACTION_RECENTS failed | "
            }

            "NONE" -> {
                isCompleted = false
                message += "Input is unclear, not actionable, or lacks required information | "
            }

            else -> {
                isCompleted = false
            }
        }

        broadcastToStt(intent, isCompleted, message)
        return true
    }

    private fun findNodeByLabel(label: String): AccessibilityNodeInfo? {
        if (label.isBlank()) return null
        accessibility.refreshNodeCaches() // 항상 최신 스냅샷

        val key = normalizeLabel(label)
        val appPool = availableApps.toList()
        val clickPool = clickableNodes.toList()

        fun primary(n: AccessibilityNodeInfo) = normalizeLabel(accessibility.labelForActionable(n))
        fun alt(n: AccessibilityNodeInfo)     = normalizeLabel(extractLabel(n))
        fun idTok(n: AccessibilityNodeInfo) = normalizeLabel((n.viewIdResourceName ?: "").substringAfterLast("/"))

        Log.d("STTProcessLog", "available_app count=${appPool.size}, labels=${appPool.map { accessibility.labelForActionable(it) }.filter { it.isNotBlank() }}")
        Log.d("STTProcessLog", "clickable_nodes count=${clickPool.size}, labels=${clickPool.map { accessibility.labelForActionable(it) }.filter { it.isNotBlank() }}")

        fun resolve(list: List<AccessibilityNodeInfo>): AccessibilityNodeInfo? {
            list.firstOrNull { primary(it) == key }?.let { return it }
            list.firstOrNull {
                val c = primary(it); c.isNotBlank() && (c.contains(key) || key.contains(c))
            }?.let { return it }
            list.firstOrNull { alt(it) == key }?.let { return it }
            list.firstOrNull {
                val c = alt(it); c.isNotBlank() && (c.contains(key) || key.contains(c))
            }?.let { return it }
            list.firstOrNull {
                val c = idTok(it); c.isNotBlank() && (c == key || c.contains(key) || key.contains(c))
            }?.let { return it }
            return null
        }

        return resolve(appPool) ?: resolve(clickPool).also {
            if (it == null) Log.d("STTProcessLog", "findNodeByLabel: no match for '$label'")
        }
    }

    // 라벨 정규화
    private fun normalizeLabel(s: String): String =
        s.lowercase().trim().replace("\\s+".toRegex(), " ")

    private fun extractLabel(n: AccessibilityNodeInfo): String {
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

    private fun handleSwipeAction(cmd: ParsedCommand): Pair<Boolean, String> {
        val direction = cmd.value.getOrNull(0)?.lowercase().orEmpty()
        if (direction.isEmpty()) {
            return Pair(false, "No direction parsed | ")
        }

        val onHome = isOnHomeScreen()
        val isPageSwiped = direction == "left" || direction == "right"
        val isScrolled = direction == "up" || direction == "down"

        // Differentiate between scrolling a list and swiping between pages on the home screen.
        val ok = if (onHome && isPageSwiped) {
            performHomePageSwipe(direction)
        } else {
            // Try to find a scrollable container first.
            val mainScroller = pickMainScrollableFromVisible()
            if (mainScroller != null && isScrolled) {
                // Use accessibility scroll action if possible, otherwise fallback to gesture.
                performScrollAction(mainScroller, direction) || performSwipeGesture(direction)
            } else {
                // If no scrollable container, perform a generic swipe gesture.
                performSwipeGesture(direction)
            }
        }

        val message = if (!ok) "Scroll/Gesture failed | " else ""
        return Pair(ok, message)
    }

    private fun handleEnterAction(cmd: ParsedCommand): Pair<Boolean, String> {
        val valueText = cmd.value.getOrNull(0).orEmpty()
        var message = ""

        val (editableNode, wasFocused) = findEditableNode()

        if (editableNode == null) {
            message += "ENTER: no editable field | "
            return Pair(false, message)
        }

        focusAndShowKeyboard(editableNode, wasFocused)

        if (valueText.isNotEmpty()) {
            val isPasted = setText(editableNode, valueText)
            if (!isPasted) message += "ENTER: setText failed | "
        }

        val focusedNode = (service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: editableNode)
        val (submitted, submitMessage) = submitText(focusedNode, valueText, wasFocused)
        message += submitMessage

        return Pair(submitted, message)
    }

    private fun findEditableNode(): Pair<AccessibilityNodeInfo?, Boolean> {
        val focusedEditableNode =
            service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { it.isEditable }
        if (focusedEditableNode != null) {
            return Pair(focusedEditableNode, true)
        }

        val editableNode = visibleNodes.firstOrNull { it.isEditable }
            ?: visibleNodes.firstOrNull {
                it.className?.toString()?.contains("EditText") == true
            }
        return Pair(editableNode, false)
    }

    private fun focusAndShowKeyboard(editableNode: AccessibilityNodeInfo, wasFocused: Boolean) {
        if (wasFocused) {
            editableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK) // Reinforce focus
            return
        }

        editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        // Also perform a quick tap gesture to help reveal the soft keyboard.
        runCatching {
            val rect = Rect().apply { editableNode.getBoundsInScreen(this) }
            val x = (rect.left + rect.right) / 2f
            val y = (rect.top + rect.bottom) / 2f
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 40)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            service.dispatchGesture(gesture, null, null) // Induce keyboard to show
        }
    }

    private fun submitText(
        focusedNode: AccessibilityNodeInfo,
        valueText: String,
        wasInitiallyFocused: Boolean
    ): Pair<Boolean, String> {
        var submitted = false
        var message = ""

        // Try to submit using the IME action.
        val imeEnterActionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
        if (focusedNode.isEditable && focusedNode.actionList.any { it.id == imeEnterActionId }) {
            val args = Bundle().apply {
                putInt("ACTION_ARGUMENT_IME_ACTION", EditorInfo.IME_ACTION_SEARCH)
            }
            submitted = focusedNode.performAction(imeEnterActionId, args) ||
                    focusedNode.performAction(imeEnterActionId, null)
            if (submitted) {
                message += "ENTER: IME_ENTER | "
                return Pair(true, message)
            }
        }

        // Heuristic to detect if we are in a browser-like context.
        val pkg = service.rootInActiveWindow?.packageName?.toString()?.lowercase().orEmpty()
        val browserPackages = listOf("chrome", "browser", "web", "webview")
        val isBrowser = browserPackages.any { pkg.contains(it) } ||
                (pkg.contains("samsung") && pkg.contains("internet"))

        // If standard submission failed, try more robust methods via our custom IME.
        val delayMs =
            if (!wasInitiallyFocused) 180L else 0L // Add delay if we just focused the field.
        if (isBrowser) {
            if (wasInitiallyFocused) {
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            // In browsers, it's often more reliable to ask the IME to commit text and then perform the "Go" action.
            if (valueText.isNotBlank()) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    askImeToCommitAndGo(valueText)
                }, delayMs)
                submitted = true
                message += "ENTER: IME commit+GO scheduled | "
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    askImeToSubmit(true)
                }, delayMs)
                submitted = true
                message += "ENTER: IME submit scheduled | "
            }
        } else {
            // For non-browser apps, try a newline fallback or ask the IME to submit.
            val currentText = (focusedNode.text ?: "").toString()
            val setArgs = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    currentText + "\n"
                )
            }
            if (focusedNode.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    setArgs
                )
            ) {
                submitted = true
                message += "ENTER: newline fallback | "
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    askImeToSubmit(true)
                }, delayMs)
                submitted = true
                message += "ENTER: IME submit scheduled | "
            }
        }
        return Pair(submitted, message)
    }

//    private fun findNodeByTooltip(tooltip: TooltipMap?): AccessibilityNodeInfo? {
//        if (tooltip == null) return null
//        return clickableNodes.find {
//            it.contentDescription?.toString()?.trim() == tooltip.description.trim()
//        }
//    }

    /**
     * Performs a 'PRESS' action on the given node.
     * EditText nodes are handled as a special case. For regular nodes, it first
     * attempts a click action and falls back to tapping the node's bounds.
     *
     * @param node The node on which to perform the action.
     * @param fallbackBounds The bounds to use as a fallback if the node's own bounds are unavailable.
     * @return `true` if the action was successful, `false` otherwise.
     */
    private fun performPressAction(node: AccessibilityNodeInfo, fallbackBounds: Rect?): Boolean {
        // 1. Handle EditText
        val className = node.className?.toString().orEmpty()
        if (className.endsWith("EditText")) {
            // Assuming focusOrTapEditText returns a Boolean
            return focusOrTapEditText(node, fallbackBounds)
        }

        // 2. Attempt to click a regular node
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // 3. If click fails, tap directly using its bounds
        val nodeBounds = Rect().also { node.getBoundsInScreen(it) }

        // Use the node's bounds if they are valid; otherwise, use the fallbackBounds
        val targetBounds = if (nodeBounds.width() > 0 && nodeBounds.height() > 0) {
            nodeBounds
        } else {
            fallbackBounds
        }

        return targetBounds?.let { tapRect(it) } ?: false
    }

    /**
     * Attempts to focus on an EditText node, falling back to a simple tap if focus fails.
     * This ensures the field is selected and the keyboard is likely to appear.
     *
     * @param node The EditText node to focus or tap.
     * @param fallbackBounds The bounds to tap as a fallback if the node's own bounds are invalid.
     * @return `true` if an action was successfully performed, `false` otherwise.
     */
    private fun focusOrTapEditText(
        node: AccessibilityNodeInfo,
        fallbackBounds: Rect? = null
    ): Boolean {
        // Ensure the node is visible on screen before interacting.
        val showOnScreenActionId =
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
        node.performAction(showOnScreenActionId)

        // Try standard focus actions first.
        if (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) return true
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

        // If focus fails, try a click action.
        if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK } &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // As a last resort, perform a tap gesture at the node's location.
        val bound = Rect().also { node.getBoundsInScreen(it) }
        val target = if (bound.width() > 0 && bound.height() > 0) bound else fallbackBounds
        return target?.let { tapRect(it) } ?: false
    }

    /**
     * Performs a tap gesture at the center of the given [Rect].
     *
     * @param rect The screen coordinates to tap.
     * @return `true` if the gesture was successfully dispatched, `false` otherwise.
     */
    private fun tapRect(rect: Rect): Boolean {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        val path = Path().apply {
            moveTo(cx, cy); lineTo(
            cx + 0.1f,
            cy + 0.1f
        )
        } // A minimal path for a tap
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    /**
     * Sends a broadcast to the custom [ImeService] to commit text and perform a "GO" action.
     * This is useful for submitting forms in web views.
     *
     * @param text The text to commit.
     */
    private fun askImeToCommitAndGo(text: String) {
        val pkg = service.rootInActiveWindow?.packageName?.toString()
        service.sendBroadcast(
            Intent("com.example.speak2ui.IME_CMD")
                .putExtra("do", "COMMIT_AND_GO")
                .putExtra("text", text)
                .putExtra("targetPkg", pkg)
        )
    }

    /**
     * Sends a broadcast to the custom [ImeService] to perform a submit action (e.g., Search or Enter).
     *
     * @param searchPreferred If `true`, prioritizes the "Search" action over "Enter".
     */
    private fun askImeToSubmit(searchPreferred: Boolean = true) {
        val pkg = service.rootInActiveWindow?.packageName?.toString()
        service.sendBroadcast(
            Intent("com.example.speak2ui.IME_CMD")
                .putExtra("do", if (searchPreferred) "SEARCH_OR_ENTER" else "ENTER")
                .putExtra("targetPkg", pkg)
        )
    }

    /**
     * Sets the text of a given [AccessibilityNodeInfo] using the `ACTION_SET_TEXT` action.
     *
     * @param node The target node.
     * @param text The text to set.
     * @return `true` if the action was successful, `false` otherwise.
     */
    private fun setText(node: AccessibilityNodeInfo?, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return node?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) ?: false
    }

    /**
     * Performs a scroll action on a given node in a specified direction.
     * It tries multiple platform-standard scroll action IDs to maximize compatibility.
     *
     * @param node The scrollable node.
     * @param direction The direction to scroll ("up", "down", "left", or "right").
     * @return `true` if any scroll action was successful, `false` otherwise.
     */
    private fun performScrollAction(node: AccessibilityNodeInfo, direction: String): Boolean {
        val actionSet = node.actionList.map { it.id }.toSet()

        // Standard accessibility actions for scrolling
        val scrollForward = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val scrollBackward = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        // Hidden but often functional action constants
        val scrollLeft = 16908345
        val scrollRight = 16908346
        val scrollUp = 16908358
        val scrollDown = 16908359

        // Determine which action IDs to try based on the direction.
        val tryIds = when (direction.lowercase()) {
            "up" -> listOf(scrollBackward, scrollUp)
            "down" -> listOf(scrollForward, scrollDown)
            "left" -> listOf(scrollLeft, scrollBackward)
            "right" -> listOf(scrollRight, scrollForward)
            else -> emptyList()
        }.filter { actionSet.contains(it) }

        // Attempt each valid action until one succeeds.
        for (id in tryIds) {
            if (node.performAction(id)) {
                return true
            }
        }
        return false
    }

    /**
     * Performs a swipe gesture on the screen in the given direction.
     * This is used as a fallback when accessibility actions are not available or fail.
     * Also handles global actions mapped to swipe-like commands.
     *
     * @param direction The direction to swipe ("up", "down", "left", "right") or a global action name.
     * @return `true` if the gesture was dispatched successfully, `false` otherwise.
     */
    private fun performSwipeGesture(direction: String): Boolean {
        // Handle global actions that might be phrased as swipes.
        val globalActions = mapOf(
            "home" to AccessibilityService.GLOBAL_ACTION_HOME,
            "recents" to AccessibilityService.GLOBAL_ACTION_RECENTS,
            "back" to AccessibilityService.GLOBAL_ACTION_BACK,
            "notifications" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
            "quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
        )
        globalActions[direction]?.let { action ->
            return service.performGlobalAction(action)
        }

        val displayMetrics = Resources.getSystem().displayMetrics
        val w = displayMetrics.widthPixels.toFloat()
        val h = displayMetrics.heightPixels.toFloat()

        // Define start/end points and duration for the swipe gesture based on direction.
        val (start, end, duration) = when (direction) {
            "left" -> Triple(
                android.graphics.PointF(w * 0.85f, h * 0.5f),
                android.graphics.PointF(w * 0.15f, h * 0.5f), 280L
            )

            "right" -> Triple(
                android.graphics.PointF(w * 0.15f, h * 0.5f),
                android.graphics.PointF(w * 0.85f, h * 0.5f), 280L
            )

            "up" -> Triple(
                android.graphics.PointF(w * 0.5f, h * 0.85f),
                android.graphics.PointF(w * 0.5f, h * 0.35f), 260L
            )

            "down" -> Triple(
                android.graphics.PointF(w * 0.5f, h * 0.25f),
                android.graphics.PointF(w * 0.5f, h * 0.90f), 260L
            )

            else -> return false
        }

        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(end.x, end.y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val ok = service.dispatchGesture(gesture, null, null)
        return ok
    }

    /**
     * Finds the largest scrollable UI element currently visible on the screen.
     *
     * @return The [AccessibilityNodeInfo] of the largest scrollable element, or `null` if none is found.
     */
    private fun pickMainScrollableFromVisible(): AccessibilityNodeInfo? =
        visibleNodes
            .asSequence()
            .filter { it.isVisibleToUser && it.isEnabled }
            .filter { node ->
                node.isScrollable ||
                        node.actionList.any { actionId ->
                            actionId.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                                    actionId.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ||
                                    actionId.id == 16908345 || actionId.id == 16908346 || // LEFT/RIGHT
                                    actionId.id == 16908358 || actionId.id == 16908359    // UP/DOWN
                        }
            }
            .maxByOrNull { node ->
                val rect = Rect(); node.getBoundsInScreen(rect); rect.width() * rect.height()
            }

    /**
     * Resolves the package name of the default home screen launcher application.
     *
     * @return The package name of the default launcher, or `null` if it cannot be determined.
     */
    private fun resolveDefaultLauncherPackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolvedInfo = service.packageManager.resolveActivity(intent, 0) ?: return null
        return if (resolvedInfo.activityInfo?.packageName == "android") null else resolvedInfo.activityInfo?.packageName
    }

    /**
     * Checks if the currently active window belongs to the default home screen launcher.
     *
     * @return `true` if the user is on the home screen, `false` otherwise.
     */
    private fun isOnHomeScreen(): Boolean {
        val current = service.rootInActiveWindow?.packageName?.toString()
        return current != null && current == homePackage
    }

    /**
     * Performs a horizontal swipe gesture, specifically tuned for navigating home screen pages.
     * It tries swiping at multiple vertical positions to increase reliability.
     *
     * @param direction The direction to swipe ("left" or "right").
     * @return `true` if the gesture was successfully dispatched, `false` otherwise.
     */
    private fun performHomePageSwipe(direction: String): Boolean {
        val displayMetrics = Resources.getSystem().displayMetrics
        val w = displayMetrics.widthPixels.toFloat()
        val h = displayMetrics.heightPixels.toFloat()

        val (startX, endX) = if (direction.lowercase() == "left")
            w * 0.88f to w * 0.12f
        else
            w * 0.12f to w * 0.88f

        // Try swiping at a few different vertical positions to avoid system navigation bars.
        val ys = floatArrayOf(h * 0.40f, h * 0.52f, h * 0.64f)
        val duration = 300L

        for (y in ys) {
            val path = Path().apply {
                moveTo(startX, y); lineTo(endX, y)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, duration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val accepted = service.dispatchGesture(gesture, null, null)
            if (accepted) return true
        }
        return false
    }

    /**
     * Broadcasts the result of an action execution back to the STT service.
     * This signals whether the STT service can resume listening for the next command.
     *
     * @param intent The name of the intent that was executed (e.g., "PRESS").
     * @param isCompleted `true` if the action was considered successful, `false` otherwise.
     * @param message An optional string with extra details about the execution for logging.
     */
    private fun broadcastToStt(intent: String, isCompleted: Boolean, message: String) {
        Intent("com.example.SCREEN_CONTROL_COMPLETE").apply {
            setPackage(service.packageName)
            if (!isCompleted) {
                if (intent == "NONE") {
                    Log.e(
                        TAG,
                        "Action: $intent (Input is unclear, not actionable, or lacks required information)"
                    )
                } else {
                    Log.e(TAG, "Action: $intent FAILED!")
                }
            }
            if (message.isNotEmpty()) {
                Log.d(TAG, message)
            }
        }.also { service.sendBroadcast(it) }
    }
}