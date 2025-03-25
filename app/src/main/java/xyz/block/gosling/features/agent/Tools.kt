package xyz.block.gosling.features.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Bundle
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.net.toUri
import org.json.JSONObject
import xyz.block.gosling.features.overlay.OverlayService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong


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

// a lightweight MCP client that will discover apps that can add tools
object MobileMCP {
    // Map to store localId -> (packageName, appName) mappings to keep names short in function calls
    private val mcpRegistry = mutableMapOf<String, Pair<String, String>>()

    // Generate a unique 3-character localId (2 letters + 1 digit)
    private fun generateLocalId(): String {
        val charPool = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val letters = (1..2).map { charPool.filter { it.isLetter() }.random() }.joinToString("")
        val digit = charPool.filter { it.isDigit() }.random()
        val localId = "$letters$digit"

        // Ensure the ID is unique
        return if (mcpRegistry.containsKey(localId)) {
            generateLocalId() // Try again if this ID is already used
        } else {
            localId
        }
    }

    // discover MCPs that are on this device.
    fun discoverMCPs(context: Context): List<Map<String, Any>> {

        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("Don't call this from the main thread!")
        }
        val action = "com.example.ACTION_MMCP_DISCOVERY" // TODO we need a permanent name for this.
        val intent = Intent(action)
        val packageManager = context.packageManager
        val resolveInfos = packageManager.queryBroadcastReceivers(intent, 0)

        val results = mutableListOf<Map<String, Any>>()
        val latch = CountDownLatch(resolveInfos.size) // Wait for all broadcasts to finish

        for (resolveInfo in resolveInfos) {
            val componentName = ComponentName(
                resolveInfo.activityInfo.packageName,
                resolveInfo.activityInfo.name
            )

            val broadcastIntent = Intent(action).apply {
                component = componentName
            }

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    System.out.println("MCP receive from $componentName")
                    val extras = getResultExtras(true)
                    System.out.println("Extras: $extras")
                    if (extras != null) {
                        val packageName = resolveInfo.activityInfo.packageName
                        val appName = resolveInfo.activityInfo.name

                        // Generate or retrieve a localId for this MCP
                        val localId =
                            mcpRegistry.entries.find { it.value == Pair(packageName, appName) }?.key
                                ?: generateLocalId().also {
                                    mcpRegistry[it] = Pair(packageName, appName)
                                }

                        val result = mapOf(
                            "packageName" to packageName,
                            "name" to appName,
                            "localId" to localId,
                            "tools" to (extras.getStringArray("tools")?.toList() ?: emptyList())
                                .associateWith { tool ->
                                    mapOf(
                                        "description" to (extras.getString("$tool.description")
                                            ?: ""),
                                        "parameters" to (extras.getString("$tool.parameters")
                                            ?: "{}")
                                    )
                                }
                        )
                        System.out.println("Results adding: $result")
                        results.add(result)
                    }
                    latch.countDown() // ✅ Signal that this receiver has finished processing
                }
            }

            System.out.println("Sending broadcast to $componentName...")

            context.sendOrderedBroadcast(
                broadcastIntent,
                null, // permission
                receiver, // Attach the receiver here
                null, // scheduler
                0, // initial code
                null, // initial data
                null // No initial extras
            )

            System.out.println("Broadcast finished for $componentName")
        }

        try {
            // Wait for all broadcasts to finish (5-second timeout to avoid hanging forever), these should be fast
            // and this is a one time wait
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            System.err.println("Latch interrupted: ${e.message}")
        }

        System.out.println("Returning results: $results")

        return results
    }


    // invoke a specific tool in an external app
    fun invokeTool(
        context: Context,
        localId: String,
        tool: String,
        params: String
    ): String? {
        // Look up the packageName and appName from the registry
        val (packageName, appName) = mcpRegistry[localId] ?: run {
            System.err.println("Error: Unknown MCP ID: $localId")
            return "Error: Unknown MCP ID: $localId"
        }

        val intent = Intent("com.example.ACTION_MMCP_INVOKE").apply {
            component = ComponentName(
                packageName,
                appName
            )
            putExtra("tool", tool)
            putExtra("params", params)
        }

        var result: String? = null

        val latch = CountDownLatch(1)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                result = resultData
                System.out.println("RESULT FROM MCP ----> " + result)
                latch.countDown()
            }
        }

        context.sendOrderedBroadcast(
            intent,
            null,
            receiver,
            null,
            0,
            null,
            null
        )

        try {
            // Wait for for the app to respond, 10 seconds as why not?
            latch.await(10, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            System.err.println("Latch interrupted: ${e.message}")
        }


        return result
    }


}

private const val appLoadTimeWait: Long = 2500
private const val cordinateHint =
    "(coordinates are of form: [x-coordinate of the left edge, y-coordinate of the top edge, " +
            "x-coordinate of the right edge, y-coordinate of the bottom edge])"

object ToolHandler {
    private val toolCallCounter = AtomicLong(0)


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

    private fun hideKeyboard(context: Context, accessibilityService: AccessibilityService? = null) {
        val imm =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return

        val activity = context as? android.app.Activity ?: return
        val view = activity.currentFocus ?: activity.window?.decorView?.rootView ?: return
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    @Tool(
        name = "recentApps",
        description = "list recently used apps",
        parameters = [],
        requiresContext = true
    )
    fun recentApps(context: Context): String {
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
            "$appInfo $cordinateHint\n$hierarchy"
        } catch (e: Exception) {
            "ERROR: Failed to get UI hierarchy: ${e.message}"
        }
    }

    fun buildCompactHierarchy(node: AccessibilityNodeInfo, depth: Int = 0): String {
        try {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val attributes = mutableListOf<String>()

            // Add key attributes in a compact format
            node.text?.toString()?.takeIf { it.isNotEmpty() }?.let {
                attributes.add("text=\"$it\"")
            }

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

            // Format bounds compactly with midpoint
            val midX = (bounds.left + bounds.right) / 2
            val midY = (bounds.top + bounds.bottom) / 2
            val boundsStr =
                "[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] midpoint=($midX,$midY)"

            // Build the node line
            val indent = "  ".repeat(depth)
            val nodeType = node.className?.toString()?.substringAfterLast('.') ?: "View"
            val attrStr = if (attributes.isNotEmpty()) " " + attributes.joinToString(" ") else ""
            val nodeLine = "$indent$nodeType$attrStr $boundsStr"

            // Process children if any
            val childrenStr = if (node.childCount > 0) {
                val childrenLines = mutableListOf<String>()
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { childNode ->
                        try {
                            childrenLines.add(buildCompactHierarchy(childNode, depth + 1))
                        } catch (e: Exception) {
                            childrenLines.add("${indent}  ERROR: Failed to serialize child: ${e.message}")
                        }
                    }
                }
                "\n" + childrenLines.joinToString("\n")
            } else ""

            return nodeLine + childrenStr

        } catch (e: Exception) {
            return "ERROR: Failed to serialize node: ${e.message}"
        }
    }

    @Tool(
        name = "takeScreenshot",
        description = "Take a screenshot of the current screen and return it as a base64 encoded image. " +
                "This can be used to get a visual representation of the current UI state.",
        parameters = [],
        requiresAccessibility = true,
        requiresContext = true
    )
    fun takeScreenshot(
        accessibilityService: AccessibilityService,
        context: Context,
        args: JSONObject
    ): String {
        Log.d("GOSLING_SCREENSHOT", "Starting takeScreenshot tool")
        val agent = Agent.getInstance()
        if (agent == null) {
            Log.e("GOSLING_SCREENSHOT", "Agent instance is null")
            return "Agent instance is null."
        }
        
        agent.isScreenshotInProgress = true
        try {
            var mediaProjection = agent.mediaProjection
            if (mediaProjection == null) {
                Log.e("GOSLING_SCREENSHOT", "MediaProjection not initialized")
                return "MediaProjection not initialized."
            }
            Log.d("GOSLING_SCREENSHOT", "MediaProjection initialized successfully")

            var imageReader: ImageReader? = null
            var virtualDisplay: android.hardware.display.VirtualDisplay? = null
            
            try {
                Log.d("GOSLING_SCREENSHOT", "Taking a screenshot")
                val metrics = context.resources.displayMetrics
                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val density = metrics.densityDpi
                Log.d("GOSLING_SCREENSHOT", "Screen dimensions: ${width}x${height} @ ${density}dpi")

                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                
                try {
                    virtualDisplay = mediaProjection.createVirtualDisplay(
                        "screenshot",
                        width, height, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader.surface, null, null
                    )
                    Log.d("GOSLING_SCREENSHOT", "Virtual display created")
                } catch (e: SecurityException) {
                    Log.w("GOSLING_SCREENSHOT", "MediaProjection token expired, requesting new permission")
                    imageReader.close()
                    return "MediaProjection token expired. Please grant screen capture permission again."
                }

                if (virtualDisplay == null) {
                    Log.e("GOSLING_SCREENSHOT", "Failed to create virtual display")
                    imageReader.close()
                    return "Failed to create virtual display"
                }

                // Wait briefly for the screenshot to render
                Thread.sleep(300)

                val image = imageReader.acquireLatestImage()
                    ?: run {
                        Log.e("GOSLING_SCREENSHOT", "Failed to acquire image")
                        return "Failed to capture screenshot."
                    }
                Log.d("GOSLING_SCREENSHOT", "Image acquired successfully")

                val plane = image.planes[0]
                val buffer = plane.buffer
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                Log.d("GOSLING_SCREENSHOT", "Bitmap created and populated")

                image.close()

                // Save the original size image to session_dumps
                val timestamp = System.currentTimeMillis()
                val sessionDumpsDir = File(context.getExternalFilesDir(null), "session_dumps")
                if (!sessionDumpsDir.exists()) {
                    sessionDumpsDir.mkdirs()
                }

                val imageFile = File(sessionDumpsDir, "screenshot_${timestamp}.png")
                FileOutputStream(imageFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Log.d("GOSLING_SCREENSHOT", "Full size screenshot saved to ${imageFile.absolutePath}")

                // Resize bitmap for base64 encoding while maintaining aspect ratio
                val maxWidth = 800
                val maxHeight = 1600
                val ratio = Math.min(
                    maxWidth.toFloat() / bitmap.width,
                    maxHeight.toFloat() / bitmap.height
                )

                val newWidth = (bitmap.width * ratio).toInt()
                val newHeight = (bitmap.height * ratio).toInt()

                val resizedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                Log.d("GOSLING_SCREENSHOT", "Resized bitmap to ${newWidth}x${newHeight}")

                val outputStream = ByteArrayOutputStream()
                resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                val base64Image = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)

                // Clean up bitmaps
                resizedBitmap.recycle()
                bitmap.recycle()

                Log.d("GOSLING_SCREENSHOT", "Screenshot captured, resized, and encoded successfully")
                return base64Image
            } finally {
                // Clean up resources but DO NOT stop the MediaProjection
                virtualDisplay?.release()
                imageReader?.close()
            }
        } finally {
            agent.isScreenshotInProgress = false
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
        val currentPackageName =
            accessibilityService.rootInActiveWindow?.packageName?.toString() ?: "Unknown package"


        val text = args.getString("text")

        val targetNode = if (args.has("id")) {
            accessibilityService.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(
                args.getString(
                    "id"
                )
            )?.firstOrNull()
        } else {
            accessibilityService.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } ?: return "Error: No targetable input field found"


        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )

        val setTextResult =
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        if (args.optBoolean("submit") && setTextResult) {
            // Use AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER to click enter on the omnibox
            if (targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                System.err.println("PRESSED ENTER USING ACTION_IME_ENTER")
            } else {
                // Fall back to the previous method if ACTION_IME_ENTER doesn't work
                Runtime.getRuntime().exec(arrayOf("input", "keyevent", "66"))
                System.err.println("PRESSED ENTER USING KEYEVENT")
            }
        }

        return if (setTextResult) {
            "Entered text: \"$text\". IMPORTANT: consider if keyboard is visible, will need to swipe up clicking on next thing."
        } else {
            "Failed to enter text"
        }
    }


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
                ?: return "Error: No active window found"

            val targetNode = when {
                args.has("id") -> {
                    rootNode.findAccessibilityNodeInfosByViewId(args.getString("id"))?.firstOrNull()
                }

                args.has("description") -> {
                    val description = args.getString("description")
                    findNodeByDescription(rootNode, description)
                }

                else -> {
                    rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                }
            } ?: return "Error: Could not find the target text field"


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
                return "Failed to enter text"
            }

            if (args.optBoolean("submit")) {
                if (targetNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                    System.err.println("PRESSED ENTER USING ACTION_IME_ENTER")
                } else {
                    Runtime.getRuntime().exec(arrayOf("input", "keyevent", "66"))
                    System.err.println("PRESSED ENTER USING KEYEVENT")
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
        if (root.contentDescription?.toString()?.contains(description, ignoreCase = true) == true ||
            root.text?.toString()?.contains(description, ignoreCase = true) == true
        ) {
            return root
        }

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findNodeByDescription(child, description)
            if (result != null) {
                return result
            }
        }

        return null
    }

    @Tool(
        name = "webSearch",
        description = "Perform a web search using the default search engine.",
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
                    "(${cordinateHint}):\n\n${hierarchy}"

        } catch (e: Exception) {
            return "Failed to perform web search: ${e.message}"
        }
    }

    @Tool(
        name = "openUrl",
        description = "Open a URL. Can be an app specific url or a general url that you know or " +
                "got from another app or tool (like websearch)",
        parameters = [
            ParameterDef(
                name = "url",
                type = "string",
                description = "The URL to open"
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
                    "(${cordinateHint}):\n\n${hierarchy}"
        } catch (e: Exception) {
            "Failed to open URL: ${e.message}"
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
                System.err.println("Error loading MCP tools: ${e.message}")
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
            System.out.println("TOOL CALL RESULT: " + result)
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
