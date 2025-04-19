package xyz.block.gosling.features.agent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import org.json.JSONObject
import xyz.block.gosling.features.overlay.OverlayService
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import xyz.block.gosling.features.agent.Agent
import xyz.block.gosling.features.agent.Content
import xyz.block.gosling.features.agent.Message
import xyz.block.gosling.features.agent.Conversation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tool(
    val name: String,
    val description: String,
    val parameters: Array<ParameterDef> = [],
    val requiresContext: Boolean = false,
    val requiresAccessibility: Boolean = false
)

@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class ParameterDef(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

data class InternalToolCall(
    val toolId: String,
    val name: String,
    val arguments: JSONObject
)

sealed class SerializableToolDefinitions {
    data class OpenAITools(val definitions: List<ToolDefinition>) : SerializableToolDefinitions()
    data class GeminiTools(val tools: List<GeminiTool>) : SerializableToolDefinitions()
}

private const val appLoadTimeWait: Long = 2500
private const val coordinateHint =
    "(coordinates are of form: [x-coordinate of the left edge, y-coordinate of the top edge, " +
            "x-coordinate of the right edge, y-coordinate of the bottom edge])"

object ToolHandler {
    private val toolCallCounter = AtomicLong(0)
    private const val TAG = "ToolHandler"

    private fun newToolCallId(): String {
        return "call_${toolCallCounter.incrementAndGet()}"
    }

    private fun performGesture(
        gesture: GestureDescription,
        accessibilityService: AccessibilityService
    ): Boolean {
        var gestureResult = false
        val countDownLatch = CountDownLatch(1)

        accessibilityService.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    gestureResult = true
                    countDownLatch.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    gestureResult = false
                    countDownLatch.countDown()
                }
            },
            null
        )

        try {
            countDownLatch.await(2000, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            return false
        }

        return gestureResult
    }

    private fun performClickGesture(
        x: Int,
        y: Int,
        accessibilityService: AccessibilityService
    ): Boolean {
        val clickPath = Path()
        clickPath.moveTo(x.toFloat(), y.toFloat())

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))

        val clickResult = performGesture(gestureBuilder.build(), accessibilityService)
        return clickResult
    }

    private fun hideKeyboard(context: Context) {
        val imm =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return

        val activity = context as? android.app.Activity ?: return
        val view = activity.currentFocus ?: activity.window?.decorView?.rootView ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    /* not operational
    @Tool(
        name = "recentApps",
        description = "list recently used apps",
        parameters = [],
        requiresContext = true
    )
    fun recentApps(context: Context, args: JSONObject): String {
        if (!AppUsageStats.hasPermission(context)) {
            return "Don't have permission to collect app stats, consult app settings to correct this."
        }
        return AppUsageStats.getRecentApps(context, limit = 10).joinToString { ", " }
    }

    @Tool(
        name = "frequentlyUsedApps",
        description = "list apps that are often used",
        parameters = [],
        requiresContext = true
    )
    fun frequentlyUsedApps(context: Context): String {
        if (!AppUsageStats.hasPermission(context)) {
            return "Don't have permission to collect app stats, consult app settings to correct this."
        }
        return AppUsageStats.getFrequentApps(context, limit = 20).joinToString { ", " }
    }
    */


    @Tool(
        name = "getUiHierarchy",
        description = "call this to show UI elements with their properties and locations on screen " +
                "in a hierarchical structure. If the results from this or other tools don't seem" +
                "complete, call getUiHierarchy again to give the system time to finish. But not " +
                "more than twice",
        parameters = [],
        requiresAccessibility = true
    )
    fun getUiHierarchy(accessibilityService: AccessibilityService, args: JSONObject): String {
        return try {
            val activeWindow = accessibilityService.rootInActiveWindow
                ?: return "ERROR: No active window found"

            val appInfo = "App: ${activeWindow.packageName}"
            val hierarchy = buildCompactHierarchy(activeWindow)
            //Log.d(TAG, "build compact: $hierarchy")
            "$appInfo $coordinateHint\n$hierarchy"
        } catch (e: Exception) {
            "ERROR: Failed to get UI hierarchy: ${e.message}"
        }
    }

    fun buildCompactHierarchy(node: AccessibilityNodeInfo, depth: Int = 0): String {
        try {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val attributes = mutableListOf<String>()

            // Add key attributes in a compact format
            val hasText = node.text?.isNotEmpty() == true
            node.text?.toString()?.takeIf { it.isNotEmpty() }?.let {
                attributes.add("text=\"$it\"")
            }

            val hasContentDescription = node.contentDescription?.toString()?.isNotEmpty() == true
            node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let {
                attributes.add("desc=\"$it\"")
            }

            node.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let {
                attributes.add("id=\"$it\"")
            }

            // Add interactive properties only when true
            if (node.isClickable || node.isEnabled) attributes.add("clickable")
            if (node.isFocusable) attributes.add("focusable")
            if (node.isScrollable) attributes.add("scrollable")
            if (node.isEditable) attributes.add("editable")

            // Check if this is a "meaningless" container that should be skipped
            val hasNoAttributes = attributes.isEmpty()
            val hasSingleChild = node.childCount == 1

            if (hasNoAttributes && hasSingleChild && node.getChild(0) != null) {
                return buildCompactHierarchy(node.getChild(0), depth)
            }

            // Get the node type
            val nodeType = node.className?.toString()?.substringAfterLast('.') ?: "View"

            // Check if this node should be filtered out
            val filteredNodeTypes = listOf(
                "FrameLayout",
                "LinearLayout",
                "RelativeLayout",
                "ViewGroup",
                "View",
                "ImageView",
                "TextView",
                "ListView",
                "ComposeView",
            )
            val shouldFilter =
                filteredNodeTypes.contains(nodeType) && !(hasContentDescription || hasText)

            // Format bounds compactly with midpoint
            val midX = (bounds.left + bounds.right) / 2
            val midY = (bounds.top + bounds.bottom) / 2
            val boundsStr =
                "[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] midpoint=($midX,$midY)"

            // Build the node line, or set to empty string if filtered
            val indent = "".repeat(depth)
            val attrStr = if (attributes.isNotEmpty()) " " + attributes.joinToString(" ") else ""
            val nodeLine = if (shouldFilter) "" else "$indent$attrStr $boundsStr"

            // Process children if any
            val childrenStr = if (node.childCount > 0) {
                val childrenLines = mutableListOf<String>()
                for (i in 0 until node.childCount) {
                    val childNode = node.getChild(i)
                    if (childNode != null) {
                        try {
                            val childResult = buildCompactHierarchy(childNode, depth + 1)
                            if (childResult.isNotEmpty()) {
                                childrenLines.add(childResult)
                            }
                        } catch (e: Exception) {
                            childrenLines.add("$indent  ERROR: Failed to serialize child: ${e.message}")
                        }
                    }
                }
                if (childrenLines.isNotEmpty()) "\n" + childrenLines.joinToString("\n") else ""
            } else {
                ""
            }

            return nodeLine + childrenStr

        } catch (e: Exception) {
            return "ERROR: Failed to serialize node: ${e.message}"
        }
    }

    @Tool(
        name = "home",
        description = "Press the home button on the device"
    )
    fun home(args: JSONObject): String {
        Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_HOME"))
        return "Pressed home button"
    }

    @Tool(
        name = "startApp",
        description = "Start an application by its package name",
        parameters = [
            ParameterDef(
                name = "package_name",
                type = "string",
                description = "Full package name of the app to start"
            )
        ],
        requiresContext = true
    )
    fun startApp(context: Context, args: JSONObject): String {
        val packageName = args.getString("package_name")
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "Error: App $packageName not found."

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )

        context.startActivity(launchIntent)
        val appInstruction = AppInstructions.getInstructions(packageName)
        val result = "Starting app: $packageName $appInstruction"
        return result
    }

    @Tool(
        name = "click",
        description = "Click at specific coordinates on the device screen",
        parameters = [
            ParameterDef(
                name = "x",
                type = "integer",
                description = "X coordinate to click"
            ),
            ParameterDef(
                name = "y",
                type = "integer",
                description = "Y coordinate to click"
            )
        ],
        requiresAccessibility = true
    )
    fun click(accessibilityService: AccessibilityService, args: JSONObject): String {
        val x = args.getInt("x")
        val y = args.getInt("y")

        val clickResult = performClickGesture(x, y, accessibilityService)
        return if (clickResult) "Clicked at coordinates ($x, $y)" else "Failed to click at coordinates ($x, $y)"
    }

    @Tool(
        name = "swipe",
        description = "Swipe from one point to another on the screen for example to scroll.",
        parameters = [
            ParameterDef(
                name = "start_x",
                type = "integer",
                description = "Starting X coordinate"
            ),
            ParameterDef(
                name = "start_y",
                type = "integer",
                description = "Starting Y coordinate"
            ),
            ParameterDef(
                name = "end_x",
                type = "integer",
                description = "Ending X coordinate"
            ),
            ParameterDef(
                name = "end_y",
                type = "integer",
                description = "Ending Y coordinate"
            ),
            ParameterDef(
                name = "duration",
                type = "integer",
                description = "Duration of swipe in milliseconds. Default is 300. Use longer duration (500+) for text selection",
                required = false
            )
        ],
        requiresAccessibility = true
    )
    fun swipe(accessibilityService: AccessibilityService, args: JSONObject): String {
        val startX = args.getInt("start_x")
        val startY = args.getInt("start_y")
        val endX = args.getInt("end_x")
        val endY = args.getInt("end_y")
        val duration = if (args.has("duration")) args.getInt("duration") else 300

        val swipePath = Path()
        swipePath.moveTo(startX.toFloat(), startY.toFloat())
        swipePath.lineTo(endX.toFloat(), endY.toFloat())

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(
            GestureDescription.StrokeDescription(
                swipePath,
                0,
                duration.toLong()
            )
        )

        val swipeResult = performGesture(gestureBuilder.build(), accessibilityService)
        return if (swipeResult) {
            "Swiped from ($startX, $startY) to ($endX, $endY) over $duration ms"
        } else {
            "Failed to swipe from ($startX, $startY) to ($endX, $endY)"
        }
    }

    @Tool(
        name = "scrollBrowse",
        description = "Scroll up a screen's worth from the current position and return the UI hierarchy at that new position. " +
                "Use this to navigate through content while examining the UI one screen at a time.",
        parameters = [
            ParameterDef(
                name = "scroll_duration",
                type = "integer",
                description = "Duration of the scroll in milliseconds (default is 300)",
                required = false
            ),
            ParameterDef(
                name = "pause_after_scroll",
                type = "integer",
                description = "Time to pause after scrolling in milliseconds (default is 1000)",
                required = false
            )
        ],
        requiresAccessibility = true
    )
    fun scrollBrowse(accessibilityService: AccessibilityService, args: JSONObject): String {
        val scrollDuration =
            if (args.has("scroll_duration")) args.getInt("scroll_duration") else 300
        val pauseAfterScroll =
            if (args.has("pause_after_scroll")) args.getInt("pause_after_scroll") else 1000

        // Get screen dimensions for calculating scroll coordinates
        val displayMetrics = accessibilityService.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // Center X coordinate
        val centerX = screenWidth / 2

        // Calculate scroll coordinates (from bottom third to top third)
        val startY = (screenHeight * 0.7).toInt()
        val endY = (screenHeight * 0.3).toInt()

        // Perform swipe gesture
        val swipePath = Path()
        swipePath.moveTo(centerX.toFloat(), startY.toFloat())
        swipePath.lineTo(centerX.toFloat(), endY.toFloat())

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(
            GestureDescription.StrokeDescription(
                swipePath,
                0,
                scrollDuration.toLong()
            )
        )

        val swipeResult = performGesture(gestureBuilder.build(), accessibilityService)

        if (!swipeResult) {
            return "Failed to scroll the screen"
        }

        // Wait for content to settle
        Thread.sleep(pauseAfterScroll.toLong())

        // Get UI hierarchy at this position
        return try {
            val activeWindow = accessibilityService.rootInActiveWindow
                ?: return "ERROR: No active window found after scrolling"

            val appInfo = "App: ${activeWindow.packageName}"
            val hierarchyText = buildCompactHierarchy(activeWindow)
            "$appInfo $coordinateHint\n$hierarchyText"
        } catch (e: Exception) {
            "ERROR: Failed to get UI hierarchy after scrolling: ${e.message}"
        }
    }

    private fun findEditableNode(
        root: AccessibilityNodeInfo?,
        maxDepth: Int = 5
    ): AccessibilityNodeInfo? {
        if (root == null || maxDepth <= 0) return null

        // Check if current node is editable
        if (root.isEditable) {
            return root
        }

        // Recursively check children
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val editableNode = findEditableNode(child, maxDepth - 1)
            if (editableNode != null) {
                return editableNode
            }
        }

        return null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Tool(
        name = "enterText",
        description = "Enter text into the a text field. Make sure the field you want the " +
                "text to enter into is focused. Click it if needed, don't assume.",
        parameters = [
            ParameterDef(
                name = "text",
                type = "string",
                description = "Text to enter"
            ),
            ParameterDef(
                name = "submit",
                type = "boolean",
                description = "Whether to submit the text after entering it. " +
                        "This doesn't always work. If there is a button to click directly, use that",
                required = true
            )
        ],
        requiresAccessibility = true
    )
    fun enterText(accessibilityService: AccessibilityService, args: JSONObject): String {
        val text = args.getString("text")

        val targetNode = if (args.has("id")) {
            val id = args.getString("id")
            accessibilityService.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull()
        } else {
            accessibilityService.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        if (targetNode == null) {
            Log.d("Tools", "enterText: No targetable input field found")
            return "Error: No targetable input field found"
        }

        // If it's not already focused and it's clickable, try clicking it first
        if (!targetNode.isFocused && targetNode.isClickable) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(100) // Small delay to allow focus to set
        }

        // If it's not focused after click (or wasn't clickable), try explicit focus
        if (!targetNode.isFocused) {
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(100) // Small delay to ensure focus is set
        }

        // If the node isn't directly editable, try to find an editable node in its hierarchy
        val editableNode = if (!targetNode.isEditable) {
            findEditableNode(targetNode)
        } else {
            targetNode
        }

        if (editableNode == null) {
            Log.d("Tools", "enterText: No editable nodes found in hierarchy")
            return "Error: No editable field found"
        }

        // If we found a different editable node, make sure it's focused
        if (editableNode != targetNode && !editableNode.isFocused) {
            editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(100)
        }

        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )

        val setTextResult =
            editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        if (args.optBoolean("submit") && setTextResult) {
            if (!editableNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                Runtime.getRuntime().exec(arrayOf("input", "keyevent", "66"))
            }
        }

        return if (setTextResult) {
            "Entered text: \"$text\". IMPORTANT: consider if keyboard is visible, will need to swipe up clicking on next thing."
        } else {
            Log.d("Tools", "enterText: Failed to enter text")
            "Failed to enter text"
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Tool(
        name = "enterTextByDescription",
        description = "Enter text into a text field. You must specify either the field's ID or " +
                "provide enough information to find it (like text content or description - " +
                "ensure email goes in email, phone goes in phone, etc.). After entering text, " +
                "focus will be cleared to allow entering text in another field.",
        parameters = [
            ParameterDef(
                name = "text",
                type = "string",
                description = "Text to enter"
            ),
            ParameterDef(
                name = "id",
                type = "string",
                description = "The resource ID of the text field to target. If not provided, will try to find the field by other means.",
                required = false
            ),
            ParameterDef(
                name = "description",
                type = "string",
                description = "The content description or hint text of the field to target. Use this to find fields without IDs.",
                required = true
            ),
            ParameterDef(
                name = "submit",
                type = "boolean",
                description = "Whether to submit the text after entering it. This doesn't always work. If there is a button to click directly, use that",
                required = true
            ),
        ],
        requiresAccessibility = true,
        requiresContext = true
    )
    fun enterTextByDescription(
        accessibilityService: AccessibilityService,
        context: Context,
        args: JSONObject
    ): String {
        // Temporarily disable touch handling on the overlay
        OverlayService.getInstance()?.setTouchDisabled(true)

        try {
            val text = args.getString("text")

            val rootNode = accessibilityService.rootInActiveWindow
            if (rootNode == null) {
                Log.d("Tools", "enterTextByDescription: No active window found")
                return "Error: No active window found"
            }

            val targetNode = when {
                args.has("id") -> {
                    val id = args.getString("id")
                    rootNode.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
                }

                args.has("description") -> {
                    val description = args.getString("description")
                    findNodeByDescription(rootNode, description)
                }

                else -> {
                    rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                }
            }

            if (targetNode == null) {
                Log.d("Tools", "enterTextByDescription: Could not find the target text field")
                return "Error: Could not find the target text field"
            }

            if (!targetNode.isFocused) {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                Thread.sleep(100) // Small delay to ensure focus is set
            }

            val arguments = Bundle()
            arguments.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )

            val setTextResult =
                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

            if (!setTextResult) {
                Log.d("Tools", "enterTextByDescription: Failed to enter text")
                return "Failed to enter text"
            }

            if (args.optBoolean("submit")) {
                if (!targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                    Runtime.getRuntime().exec(arrayOf("input", "keyevent", "66"))
                }
            } else {
                targetNode.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
            }

            hideKeyboard(context)
            return "Entered text: \"$text\""
        } finally {
            // Re-enable touch handling on the overlay
            OverlayService.getInstance()?.setTouchDisabled(false)
        }
    }

    private fun findNodeByDescription(
        root: AccessibilityNodeInfo,
        description: String
    ): AccessibilityNodeInfo? {
        // Function to check if a node matches our criteria
        fun checkNode(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            return node.contentDescription?.toString()
                ?.contains(description, ignoreCase = true) == true ||
                    node.text?.toString()?.contains(description, ignoreCase = true) == true
        }

        // Function to find the best editable ancestor of a node
        fun findBestEditableAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            var current = node
            var bestMatch: AccessibilityNodeInfo? = null

            while (current != null) {
                // If this node is editable, it's our best match so far
                if (current.isEditable) {
                    bestMatch = current
                    break // Stop at the first editable ancestor
                }

                try {
                    current = current.parent
                } catch (e: Exception) {
                    break
                }
            }

            return bestMatch ?: node // Return the original node if no editable ancestor found
        }

        // First check if the current node matches
        if (checkNode(root)) {
            val bestNode = findBestEditableAncestor(root)
            if (bestNode?.isEditable == true) {
                return bestNode
            }
        }

        // If no match at current node, search children
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findNodeByDescription(child, description)
            if (result != null) {
                // If we found a match in a child, check if it or its ancestors are better
                val bestNode = findBestEditableAncestor(result)
                if (bestNode?.isEditable == true) {
                    return bestNode
                }
                return result
            }
        }

        return null
    }

    @Tool(
        name = "checkSetup",
        description = "Check how gosling is currently setup. Start helping the user make the recommendations happen.",
        parameters = [],
        requiresContext = true,
        requiresAccessibility = true
    )
    fun checkSetup(
        accessibilityService: AccessibilityService,
        context: Context,
        args: JSONObject
    ): String {
        val appKinds = IntentAppKinds.allCategories.toString()
        return "IMPORTANT: you can check on the following on behalf of the user: Please check that gosling has a variety of apps, and be logged in to them. eg that the calendar is configured, email is setup and accounts logged in, messaging and more. " +
                "Check if there are applications installed for things like ecommerce, " +
                " Can suggest just a few apps if not there, eg amazon, ebay, afterpay and so on. Suggest other apps if you can sense what the user may benefit from. Some ideas to compare with what is available:" + appKinds ;

    }

    @Tool(
        name = "webSearch",
        description = "Perform a web search using the default search engine." +
                "If you don't see a clear result, use the click or scrollBrowser tools along with getUIHierarchy to look further.",
        parameters = [
            ParameterDef(
                name = "query",
                type = "string",
                description = "What to search for"
            )
        ],
        requiresContext = true,
        requiresAccessibility = true
    )
    fun webSearch(
        accessibilityService: AccessibilityService,
        context: Context,
        args: JSONObject
    ): String {
        val query = args.getString("query")

        try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH)
            intent.putExtra(SearchManager.QUERY, query)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            Thread.sleep(appLoadTimeWait)

            val activeWindow = accessibilityService.rootInActiveWindow
                ?: return "The search is done, but no active window. " +
                        "Check the UI hierarchy to see what happened."

            val hierarchy = buildCompactHierarchy(activeWindow)

            return "The search is done. What follows are the results " +
                    "(${coordinateHint}):\n\n${hierarchy}"

        } catch (e: Exception) {
            return "Failed to perform web search: ${e.message}"
        }
    }

    @Tool(
        name = "openUrl",
        description = "Open a URL. IMPORTANT: When opening URLs in specific apps, you MUST use the app's URL scheme format, not a regular web URL. " +
                "For example, for Google Maps, use 'geo:0,0?q=LOCATION' instead of 'https://www.google.com/maps/search/...'. " +
                "If you don't know the correct URL scheme, first try without a package_name to open in the default browser. " +
                "Common URL schemes:\n" +
                "- Maps: 'geo:0,0?q=LOCATION' for searching locations\n" +
                "- Navigation: 'google.navigation:q=DESTINATION' for directions\n" +
                "- Street View: 'google.streetview:cbll=LAT,LONG' for street view\n" +
                "When in doubt, check the error message which will list valid URL schemes for the specified app.",
        parameters = [
            ParameterDef(
                name = "url",
                type = "string",
                description = "The URL to open. Must use the correct URL scheme if package_name is specified."
            ),
            ParameterDef(
                name = "package_name",
                type = "string",
                description = "The package name of the app to open the URL in. If provided, the URL scheme will be validated against known schemes for that app.",
                required = false
            )
        ],
        requiresContext = true,
        requiresAccessibility = true
    )
    fun openUrl(
        accessibilityService: AccessibilityService,
        context: Context,
        args: JSONObject
    ): String {
        val url = args.getString("url")
        val packageName = args.optString("package_name", "")

        Log.d("Tools", "openUrl called with url: $url, packageName: $packageName")
        // If a package name is provided, validate the URL scheme
        if (packageName.isNotEmpty()) {
            val validSchemes = AppInstructions.getUrlSchemes(packageName)
            if (validSchemes.isNotEmpty()) {
                val urlScheme = url.substringBefore(":")

                val isValid =
                    validSchemes.any { scheme -> urlScheme == scheme.substringBefore(":") }

                if (!isValid) {
                    val error =
                        "Error: Invalid URL scheme for app $packageName. Valid schemes are: ${
                            validSchemes.joinToString(", ")
                        }"
                    Log.e("Tools", error)
                    return error
                }
            }
        }

        return try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            Thread.sleep(appLoadTimeWait)

            val activeWindow = accessibilityService.rootInActiveWindow
                ?: return "URL opened, but no active window. " +
                        "Check the UI hierarchy to see what happened."

            val appInfo = "App: ${activeWindow.packageName}"
            val hierarchy = buildCompactHierarchy(activeWindow)

            return "The URL has been opened. ${appInfo}. What follows is the contents. " +
                    "(${coordinateHint}):\n\n${hierarchy}"
        } catch (e: Exception) {
            Log.e("Tools", "Failed to open URL", e)
            "Failed to open URL: ${e.message}"
        }
    }

    @Tool(
        name = "getCalendarEvents",
        description = "Simply retrieves upcoming calendar events from the user's calendar. " +
                "Can filter by date range and/or search terms." +
                "If this doesn't work use open the calendar app and control it (and use the app for more sophisticated control)",
        parameters = [
            ParameterDef(
                name = "days_ahead",
                type = "integer",
                description = "Number of days ahead to look for events (default: 7)",
                required = false
            ),
            ParameterDef(
                name = "search_term",
                type = "string",
                description = "Optional search term to filter events by title or description",
                required = false
            )
        ],
        requiresContext = true
    )
    fun getCalendarEvents(context: Context, args: JSONObject): String {
        // Check if we have calendar permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "Calendar permission not granted. Please grant the Calendar permission in the app settings."
        }

        try {
            val daysAhead = args.optInt("days_ahead", 7)
            val searchTerm = args.optString("search_term", "").lowercase()

            // Define time range
            val startMillis = System.currentTimeMillis()
            val endMillis = startMillis + (daysAhead * 24 * 60 * 60 * 1000L)

            // Event projection
            val projection = arrayOf(
                android.provider.CalendarContract.Events._ID,
                android.provider.CalendarContract.Events.TITLE,
                android.provider.CalendarContract.Events.DESCRIPTION,
                android.provider.CalendarContract.Events.DTSTART,
                android.provider.CalendarContract.Events.DTEND,
                android.provider.CalendarContract.Events.EVENT_LOCATION,
                android.provider.CalendarContract.Events.ALL_DAY
            )

            // Query conditions
            val selection = "${android.provider.CalendarContract.Events.DTSTART} >= ? AND " +
                    "${android.provider.CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())

            // Query the calendar
            val uri = android.provider.CalendarContract.Events.CONTENT_URI
            val cursor = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${android.provider.CalendarContract.Events.DTSTART} ASC"
            )

            if (cursor == null) {
                return "Unable to access calendar data."
            }

            val events = mutableListOf<Map<String, Any>>()

            // Column indices
            val idIdx = cursor.getColumnIndex(android.provider.CalendarContract.Events._ID)
            val titleIdx = cursor.getColumnIndex(android.provider.CalendarContract.Events.TITLE)
            val descIdx = cursor.getColumnIndex(android.provider.CalendarContract.Events.DESCRIPTION)
            val startIdx = cursor.getColumnIndex(android.provider.CalendarContract.Events.DTSTART)
            val endIdx = cursor.getColumnIndex(android.provider.CalendarContract.Events.DTEND)
            val locIdx = cursor.getColumnIndex(android.provider.CalendarContract.Events.EVENT_LOCATION)
            val allDayIdx = cursor.getColumnIndex(android.provider.CalendarContract.Events.ALL_DAY)

            // Process results
            while (cursor.moveToNext()) {
                val title = cursor.getString(titleIdx) ?: "Untitled Event"
                val description = cursor.getString(descIdx) ?: ""
                
                // Apply search filter if provided
                if (searchTerm.isNotEmpty() &&
                    !title.lowercase().contains(searchTerm) &&
                    !description.lowercase().contains(searchTerm)) {
                    continue
                }
                
                val startTime = cursor.getLong(startIdx)
                val endTime = cursor.getLong(endIdx)
                val location = cursor.getString(locIdx) ?: ""
                val isAllDay = cursor.getInt(allDayIdx) == 1
                
                events.add(
                    mapOf(
                        "title" to title,
                        "description" to description,
                        "startTime" to startTime,
                        "endTime" to endTime,
                        "location" to location,
                        "isAllDay" to isAllDay
                    )
                )
            }
            
            cursor.close()
            
            // Format the results
            val sdf = java.text.SimpleDateFormat("EEE, MMM d, yyyy 'at' h:mm a", java.util.Locale.getDefault())
            val resultBuilder = StringBuilder()
            
            if (events.isEmpty()) {
                resultBuilder.append("No events found")
                if (searchTerm.isNotEmpty()) {
                    resultBuilder.append(" matching '${searchTerm}'")
                }
                resultBuilder.append(" in the next $daysAhead days.")
            } else {
                resultBuilder.append("Found ${events.size} events")
                if (searchTerm.isNotEmpty()) {
                    resultBuilder.append(" matching '${searchTerm}'")
                }
                resultBuilder.append(" in the next $daysAhead days:\n\n")
                
                var currentDate = ""
                
                events.forEach { event ->
                    val startDate = java.util.Date(event["startTime"] as Long)
                    val dateStr = sdf.format(startDate).substringBefore("at").trim()
                    
                    // Add date header if it's a new date
                    if (dateStr != currentDate) {
                        currentDate = dateStr
                        resultBuilder.append("=== $currentDate ===\n")
                    }
                    
                    // Format time
                    val timeStr = if (event["isAllDay"] as Boolean) {
                        "All day"
                    } else {
                        val startTimeStr = sdf.format(startDate).substringAfter("at").trim()
                        val endTimeStr = sdf.format(java.util.Date(event["endTime"] as Long)).substringAfter("at").trim()
                        "$startTimeStr - $endTimeStr"
                    }
                    
                    // Add event details
                    resultBuilder.append("• ${event["title"]}\n")
                    resultBuilder.append("  Time: $timeStr\n")
                    
                    if ((event["location"] as String).isNotEmpty()) {
                        resultBuilder.append("  Location: ${event["location"]}\n")
                    }
                    
                    if ((event["description"] as String).isNotEmpty()) {
                        val desc = event["description"] as String
                        val shortDesc = if (desc.length > 100) desc.substring(0, 97) + "..." else desc
                        resultBuilder.append("  Description: $shortDesc\n")
                    }
                    
                    resultBuilder.append("\n")
                }
            }
            
            return resultBuilder.toString().trim()
            
        } catch (e: Exception) {
            Log.e("CalendarTool", "Error accessing calendar: ${e.message}")
            return "Error accessing calendar: ${e.message}"
        }
    }

    @Tool(
        name = "searchContacts",
        description = "simply searches the user's contacts by name, phone number, or email. " +
                "Requires contacts permission. If this doesn't work you can open the contacts app and control it for more access",
        parameters = [
            ParameterDef(
                name = "query",
                type = "string",
                description = "Search term to find in contacts (name, phone, email)"
            ),
            ParameterDef(
                name = "limit",
                type = "integer",
                description = "Maximum number of contacts to return (default: 10)",
                required = false
            )
        ],
        requiresContext = true
    )
    fun searchContacts(context: Context, args: JSONObject): String {
        // Check if we have contacts permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "Contacts permission not granted. Please grant the Contacts permission in the app settings."
        }

        try {
            val query = args.getString("query")
            val limit = args.optInt("limit", 10)
            
            if (query.isBlank()) {
                return "Please provide a search term to find contacts."
            }
            
            // Define the columns we want
            val projection = arrayOf(
                android.provider.ContactsContract.Contacts._ID,
                android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER
            )
            
            // Query conditions - search by display name
            val selection = "${android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?"
            val selectionArgs = arrayOf("%$query%")
            
            // Query the contacts
            val cursor = context.contentResolver.query(
                android.provider.ContactsContract.Contacts.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC LIMIT $limit"
            )
            
            if (cursor == null) {
                return "Unable to access contacts data."
            }
            
            val contacts = mutableListOf<Map<String, Any>>()
            
            // Column indices
            val idIdx = cursor.getColumnIndex(android.provider.ContactsContract.Contacts._ID)
            val nameIdx = cursor.getColumnIndex(android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val hasPhoneIdx = cursor.getColumnIndex(android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER)
            
            // Process results
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx)
                val name = cursor.getString(nameIdx) ?: "Unknown"
                val hasPhone = cursor.getInt(hasPhoneIdx) > 0
                
                val phoneNumbers = mutableListOf<String>()
                val emails = mutableListOf<String>()
                
                // Get phone numbers if available
                if (hasPhone) {
                    val phoneCursor = context.contentResolver.query(
                        android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(id),
                        null
                    )
                    
                    phoneCursor?.use { pc ->
                        val phoneIdx = pc.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                        while (pc.moveToNext()) {
                            pc.getString(phoneIdx)?.let { phoneNumbers.add(it) }
                        }
                    }
                }
                
                // Get email addresses
                val emailCursor = context.contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Email.DATA),
                    "${android.provider.ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                    arrayOf(id),
                    null
                )
                
                emailCursor?.use { ec ->
                    val emailIdx = ec.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Email.DATA)
                    while (ec.moveToNext()) {
                        ec.getString(emailIdx)?.let { emails.add(it) }
                    }
                }
                
                // Only add contact if it matches the query in name, phone, or email
                val matchesPhone = phoneNumbers.any { it.contains(query) }
                val matchesEmail = emails.any { it.lowercase().contains(query.lowercase()) }
                
                if (name.lowercase().contains(query.lowercase()) || matchesPhone || matchesEmail) {
                    contacts.add(
                        mapOf(
                            "name" to name,
                            "phones" to phoneNumbers,
                            "emails" to emails
                        )
                    )
                }
                
                // Stop if we've reached the limit
                if (contacts.size >= limit) {
                    break
                }
            }
            
            cursor.close()
            
            // Format the results
            val resultBuilder = StringBuilder()
            
            if (contacts.isEmpty()) {
                resultBuilder.append("No contacts found matching '$query'.")
            } else {
                resultBuilder.append("Found ${contacts.size} contacts matching '$query':\n\n")
                
                contacts.forEachIndexed { index, contact ->
                    resultBuilder.append("${index + 1}. ${contact["name"]}\n")
                    
                    val phones = contact["phones"] as List<String>
                    if (phones.isNotEmpty()) {
                        resultBuilder.append("   Phone: ${phones.first()}")
                        if (phones.size > 1) {
                            resultBuilder.append(" (+${phones.size - 1} more)")
                        }
                        resultBuilder.append("\n")
                    }
                    
                    val emails = contact["emails"] as List<String>
                    if (emails.isNotEmpty()) {
                        resultBuilder.append("   Email: ${emails.first()}")
                        if (emails.size > 1) {
                            resultBuilder.append(" (+${emails.size - 1} more)")
                        }
                        resultBuilder.append("\n")
                    }
                    
                    resultBuilder.append("\n")
                }
            }
            
            return resultBuilder.toString().trim()
            
        } catch (e: Exception) {
            Log.e("ContactsTool", "Error accessing contacts: ${e.message}")
            return "Error accessing contacts: ${e.message}"
        }
    }

    @Tool(
        name = "searchPastConversations",
        description = "Search through past conversations to retrieve conversation history. When query is provided, searches for specific text. When query is omitted, returns recent conversation history, only use if getLastConversationContext didn't help.",
        parameters = [
            ParameterDef(
                name = "query",
                type = "string",
                description = "Optional search term to look for in past conversations. If omitted, returns recent conversation history without searching.",
                required = false
            ),
            ParameterDef(
                name = "max_conversations",
                type = "integer",
                description = "Maximum number of conversations to retrieve or search through (default: 4)",
                required = false
            ),
            ParameterDef(
                name = "max_messages_per_conversation",
                type = "integer",
                description = "Maximum number of messages to retrieve per conversation (default: 5)",
                required = false
            )
        ],
        requiresContext = true
    )
    fun searchPastConversations(context: Context, args: JSONObject): String {
        val hasQuery = args.has("query")
        val query = if (hasQuery) args.getString("query").lowercase() else ""
        val maxConversations = args.optInt("max_conversations", 4)
        val maxMessagesPerConversation = args.optInt("max_messages_per_conversation", 5)
        
        val agent = Agent.getInstance() ?: return "Error: Agent not available"
        val conversationManager = agent.conversationManager
        val allConversations = conversationManager.conversations.value
        
        if (allConversations.isEmpty()) {
            return "No past conversations found."
        }
        
        // Get conversations - either search by query or just get recent ones
        val selectedConversations = if (hasQuery) {
            // Search for conversations containing the query
            allConversations
                .filter { conversation ->
                    conversation.messages.any { message ->
                        // Only search in user and assistant messages
                        (message.role == "user" || message.role == "assistant") &&
                        message.content?.filterIsInstance<Content.Text>()?.any { 
                            it.text.lowercase().contains(query) 
                        } == true
                    }
                }
                .take(maxConversations)
        } else {
            // Just get the most recent conversations
            allConversations
                .sortedByDescending { it.startTime }
                .take(maxConversations)
        }
        
        if (selectedConversations.isEmpty()) {
            return if (hasQuery) {
                "No conversations found matching query: \"$query\""
            } else {
                "No conversations found. Consider using no query or a different query or looking further."
            }
        }
        
        val resultBuilder = StringBuilder()
        if (hasQuery) {
            resultBuilder.append("Found ${selectedConversations.size} conversations matching \"$query\":\n\n")
        } else {
            resultBuilder.append("Retrieved ${selectedConversations.size} most recent conversations:\n\n")
        }
        
        // Process each selected conversation
        selectedConversations.forEachIndexed { index, conversation ->
            // Get conversation title
            val title = xyz.block.gosling.features.agent.getConversationTitle(conversation)
            val formattedDate = java.text.SimpleDateFormat(
                "MMM d, yyyy 'at' h:mm a", 
                java.util.Locale.getDefault()
            ).format(java.util.Date(conversation.startTime))
            
            resultBuilder.append("--- Conversation ${index + 1}: $title ($formattedDate) ---\n")
            
            // Get relevant messages (filter out system and tool messages, focus on user-assistant exchange)
            val relevantMessages = conversation.messages
                .filter { it.role == "user" || it.role == "assistant" }
                .filter { message ->
                    message.content?.filterIsInstance<Content.Text>()?.isNotEmpty() == true
                }
                .takeLast(maxMessagesPerConversation)
            
            if (relevantMessages.isEmpty()) {
                resultBuilder.append("No relevant messages found in this conversation.\n\n")
                return@forEachIndexed
            }
            
            // Format and add messages
            relevantMessages.forEach { message ->
                val role = if (message.role == "user") "User" else "Assistant"
                val messageText = xyz.block.gosling.features.agent.firstText(message)
                
                // Highlight query matches in the text if we're searching
                val highlightedText = if (hasQuery && messageText.lowercase().contains(query)) {
                    val startIndex = messageText.lowercase().indexOf(query)
                    val endIndex = startIndex + query.length
                    val before = messageText.substring(0, startIndex)
                    val match = messageText.substring(startIndex, endIndex)
                    val after = messageText.substring(endIndex)
                    "$before[$match]$after"
                } else {
                    messageText
                }
                
                resultBuilder.append("$role: $highlightedText\n")
            }
            
            resultBuilder.append("\n")
        }
        
        return resultBuilder.toString().trim()
    }

    @Tool(
        name = "getLastConversationContext",
        description = "Use this if the user is asking something which implies past context or continuing on, this will retrieve the last user message and assistant reply from the previous conversation for context. Useful when continuing a conversation, and you may often want to use it to be sure.",
        parameters = [],
        requiresContext = true
    )
    fun getLastConversationContext(context: Context, args: JSONObject): String {
        val agent = Agent.getInstance() ?: return "Error: Agent not available"
        val conversationManager = agent.conversationManager
        val allConversations = conversationManager.conversations.value
        
        if (allConversations.isEmpty()) {
            return "No previous conversations found."
        }
        
        // Get the most recent conversation (excluding current one if possible)
        val sortedConversations = allConversations.sortedByDescending { it.startTime }
        val lastConversation = if (sortedConversations.size > 1) {
            // If there's more than one conversation, get the second most recent
            // (assuming the first is the current one)
            sortedConversations[1]
        } else {
            // Otherwise just get the most recent one
            sortedConversations[0]
        }
        
        // Get conversation details
        val title = xyz.block.gosling.features.agent.getConversationTitle(lastConversation)
        val formattedDate = java.text.SimpleDateFormat(
            "MMM d, yyyy 'at' h:mm a", 
            java.util.Locale.getDefault()
        ).format(java.util.Date(lastConversation.startTime))
        
        // Find the last user and assistant messages
        val userMessages = lastConversation.messages.filter { it.role == "user" }
        val assistantMessages = lastConversation.messages.filter { it.role == "assistant" }
        
        if (userMessages.isEmpty() && assistantMessages.isEmpty()) {
            return "Previous conversation found ($title, $formattedDate) but it contains no user or assistant messages."
        }
        
        val resultBuilder = StringBuilder()
        resultBuilder.append("Context from previous conversation ($title, $formattedDate):\n\n")
        
        // Get the last user message if available
        val lastUserMessage = userMessages.lastOrNull()
        if (lastUserMessage != null) {
            val userText = xyz.block.gosling.features.agent.firstText(lastUserMessage)
            resultBuilder.append("Last user message: $userText\n\n")
        } else {
            resultBuilder.append("No user message in the previous conversation.\n\n")
        }
        
        // Get the last assistant message if available
        val lastAssistantMessage = assistantMessages.lastOrNull()
        if (lastAssistantMessage != null) {
            val assistantText = xyz.block.gosling.features.agent.firstText(lastAssistantMessage)
            resultBuilder.append("Last assistant response: $assistantText")
        } else {
            resultBuilder.append("No assistant response in the previous conversation.")
        }
        
        return resultBuilder.toString().trim() + "\n Also consider using searchPastConversations if more context needed."
    }

    @Tool(
        name = "notificationHandler",
        description = "Use this when user wants to configure automatic notification/message handling to take actions when events happen. Enable or disable notification processing and set rules for how notifications (such as messages, calendar events, app notifications) should be handled.",
        parameters = [
            ParameterDef(
                name = "enable",
                type = "boolean",
                description = "Whether to enable or disable automatic notification processing"
            ),
            ParameterDef(
                name = "rules",
                type = "string",
                description = "Rules for handling notifications. These are instructions for how to process different types of notifications/events as they come in.",
                required = false
            ),
            ParameterDef(
                name = "replaceRules",
                type = "boolean",
                description = "If true, the provided rules will replace existing rules. If false, the rules will be appended to existing rules.",
                required = false
            )
        ],
        requiresContext = true
    )
    fun notificationHandler(context: Context, args: JSONObject): String {
        val enable = args.getBoolean("enable")
        val rules = if (args.has("rules")) args.getString("rules") else null
        val replaceRules = args.optBoolean("replaceRules", true) // Default to replacing rules
        
        val settings = xyz.block.gosling.features.settings.SettingsStore(context)
        
        // Update notification processing setting
        settings.shouldProcessNotifications = enable
        
        // Update rules if provided
        if (rules != null) {
            if (replaceRules) {
                // Replace existing rules
                settings.messageHandlingPreferences = rules
            } else {
                // Append to existing rules
                val existingRules = settings.messageHandlingPreferences
                val updatedRules = if (existingRules.isBlank()) {
                    rules
                } else {
                    "$existingRules\n\n$rules"
                }
                settings.messageHandlingPreferences = updatedRules
            }
        }
        
        val currentRules = settings.messageHandlingPreferences
        val rulesStatus = if (currentRules.isBlank()) {
            "No specific handling rules are configured."
        } else {
            "Current rules: $currentRules"
        }
        
        val actionTaken = if (rules != null) {
            if (replaceRules) "Rules have been replaced." else "Rules have been appended."
        } else {
            "Rules were not modified."
        }
        
        return if (enable) {
            "Notification handling has been enabled. $actionTaken $rulesStatus"
        } else {
            "Notification handling has been disabled. $actionTaken Rules are preserved but will not be applied."
        }
    }

    fun getSerializableToolDefinitions(
        context: Context,
        provider: ModelProvider
    ): SerializableToolDefinitions {
        val methods = ToolHandler::class.java.methods
            .filter { it.isAnnotationPresent(Tool::class.java) }

        // Get the regular tool definitions first
        val regularToolDefinitions = when (provider) {
            ModelProvider.OPENAI -> {
                methods.mapNotNull { method ->
                    val tool = method.getAnnotation(Tool::class.java) ?: return@mapNotNull null

                    // Always create a ToolParametersObject, even for tools with no parameters
                    val toolParameters = ToolParametersObject(
                        properties = tool.parameters.associate { param ->
                            param.name to ToolParameter(
                                type = param.type,
                                description = param.description
                            )
                        },
                        required = tool.parameters
                            .filter { it.required }
                            .map { it.name }
                    )

                    ToolDefinition(
                        function = ToolFunctionDefinition(
                            name = tool.name,
                            description = tool.description,
                            parameters = toolParameters
                        )
                    )
                }
            }

            ModelProvider.GEMINI -> {
                methods.mapNotNull { method ->
                    val tool = method.getAnnotation(Tool::class.java) ?: return@mapNotNull null
                    val toolParameters = ToolParametersObject(
                        properties = tool.parameters.associate { param ->
                            param.name to ToolParameter(
                                type = when (param.type.lowercase(Locale.getDefault())) {
                                    "integer" -> "string" // Use string for integers
                                    "boolean" -> "boolean"
                                    "string" -> "string"
                                    "double", "float" -> "number"
                                    else -> "string"
                                },
                                description = param.description
                            )
                        },
                        required = tool.parameters
                            .filter { it.required }
                            .map { it.name }
                    )

                    ToolDefinition(
                        function = ToolFunctionDefinition(
                            name = tool.name,
                            description = tool.description,
                            parameters = toolParameters
                        )
                    )
                }
            }
        }

        val settings = xyz.block.gosling.features.settings.SettingsStore(context)
        val enableAppExtensions = settings.enableAppExtensions

        val mcpTools = mutableListOf<ToolDefinition>()
        if (enableAppExtensions) {
            try {
                val mcps = MobileMCP.discoverMCPs(context)

                for (mcp in mcps) {
                    val localId = mcp["localId"] as String

                    @Suppress("UNCHECKED_CAST")
                    val tools = mcp["tools"] as Map<String, Map<String, String>>

                    for ((toolName, toolInfo) in tools) {
                        val toolDescription = toolInfo["description"] ?: ""

                        // Parse the parameters JSON string into a proper structure
                        val parametersJson = toolInfo["parameters"] ?: "{}"
                        val parametersObj = JSONObject(parametersJson)
                        val paramProperties = mutableMapOf<String, ToolParameter>()
                        val requiredParams = mutableListOf<String>()

                        // Extract parameters from the JSON
                        parametersObj.keys().forEach { paramName ->
                            val paramType = "string" // Default to string type for simplicity

                            paramProperties[paramName] = ToolParameter(
                                type = paramType,
                                description = "Parameter for $toolName"
                            )

                            // Assume all parameters are required for now
                            requiredParams.add(paramName)
                        }

                        // Create the tool parameters object
                        val toolParameters = ToolParametersObject(
                            properties = paramProperties,
                            required = requiredParams
                        )

                        // Create the tool definition with a special name format to identify it as an MCP tool
                        // we use a localId which is compact to save on space for toolName as there are limits
                        val mcpToolName = "mcp_${localId}_${toolName}"

                        mcpTools.add(
                            ToolDefinition(
                                function = ToolFunctionDefinition(
                                    name = mcpToolName,
                                    description = toolDescription,
                                    parameters = toolParameters
                                )
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading MCP tools: ${e.message}")
            }
        }

        // Combine regular tools and MCP tools
        val allTools = regularToolDefinitions + mcpTools

        return when (provider) {
            ModelProvider.OPENAI -> SerializableToolDefinitions.OpenAITools(allTools)
            ModelProvider.GEMINI -> {
                // For Gemini, we need to convert the tool definitions to Gemini format
                val functionDeclarations = allTools.map { toolDef ->
                    GeminiFunctionDeclaration(
                        name = toolDef.function.name,
                        description = toolDef.function.description,
                        parameters = if (toolDef.function.parameters.properties.isEmpty()) null else toolDef.function.parameters
                    )
                }

                SerializableToolDefinitions.GeminiTools(
                    listOf(
                        GeminiTool(
                            functionDeclarations = functionDeclarations
                        )
                    )
                )
            }
        }
    }

    fun callTool(
        toolCall: InternalToolCall,
        context: Context,
        accessibilityService: AccessibilityService?
    ): String {
        if (Agent.getInstance()?.isCancelled() == true) {
            return "Operation cancelled by user"
        }

        if (!toolCall.name.startsWith("mcp_")) {
            val toolMethod = ToolHandler::class.java.methods
                .firstOrNull {
                    it.isAnnotationPresent(Tool::class.java) &&
                            it.getAnnotation(Tool::class.java)?.name == toolCall.name
                }
                ?: return "Unknown tool call: ${toolCall.name}"

            val toolAnnotation = toolMethod.getAnnotation(Tool::class.java)
                ?: return "Tool annotation not found for: ${toolCall.name}"

            return try {
                if (Agent.getInstance()?.isCancelled() == true) {
                    return "Operation cancelled by user"
                }

                if (toolAnnotation.requiresAccessibility) {
                    if (accessibilityService == null) {
                        return "Accessibility service not available."
                    }
                    if (toolAnnotation.requiresContext) {
                        return toolMethod.invoke(
                            ToolHandler,
                            accessibilityService,
                            context,
                            toolCall.arguments
                        ) as String
                    }
                    return toolMethod.invoke(
                        ToolHandler,
                        accessibilityService,
                        toolCall.arguments
                    ) as String
                }
                if (toolAnnotation.requiresContext) {
                    return toolMethod.invoke(ToolHandler, context, toolCall.arguments) as String
                }
                return toolMethod.invoke(ToolHandler, toolCall.arguments) as String
            } catch (e: Exception) {
                "Error executing ${toolCall.name}: ${e.message}"
            }

        } else {
            val nameParts = toolCall.name.split("_", limit = 3)
            val result = MobileMCP.invokeTool(
                context,
                nameParts[1],
                nameParts[2],
                toolCall.arguments.toString()
            )
            Log.d(TAG, "TOOL CALL RESULT: $result")
            return "" + result

        }

    }

    fun fromJson(json: JSONObject): InternalToolCall {
        return when {
            json.has("function") -> {
                val functionObject = json.getJSONObject("function")
                InternalToolCall(
                    name = functionObject.getString("name"),
                    arguments = JSONObject(functionObject.optString("arguments", "{}")),
                    toolId = json.optString("id", newToolCallId())
                )
            }

            json.has("functionCall") -> {
                val functionCall = json.getJSONObject("functionCall")
                InternalToolCall(
                    name = functionCall.getString("name"),
                    arguments = functionCall.optJSONObject("args") ?: JSONObject(),
                    toolId = json.optString("id", newToolCallId())
                )
            }

            else -> throw IllegalArgumentException("Unknown tool call format")
        }
    }
}
