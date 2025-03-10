package xyz.block.gosling.features.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import xyz.block.gosling.GoslingApplication
import xyz.block.gosling.OverlayService
import java.util.Locale

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
    val name: String,
    val arguments: JSONObject
)

sealed class SerializableToolDefinitions {
    data class OpenAITools(val definitions: List<ToolDefinition>) : SerializableToolDefinitions()
    data class GeminiTools(val tools: List<GeminiTool>) : SerializableToolDefinitions()
}

object ToolHandler {
    /**
     * Helper function to perform a gesture using the Accessibility API
     */
    private fun performGesture(
        gesture: GestureDescription,
        accessibilityService: AccessibilityService
    ): Boolean {
        var gestureResult = false
        val countDownLatch = java.util.concurrent.CountDownLatch(1)

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
            countDownLatch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            return false
        }

        return gestureResult
    }

    @Tool(
        name = "getUiHierarchy",
        description = "Get the current UI hierarchy in a compact format. " +
                "Shows UI elements with their properties and locations on screen in a hierarchical structure. " +
                "Only includes relevant properties (clickable, focusable, etc. when true; enabled when false).",
        parameters = [],
        requiresAccessibility = true
    )
    fun getUiHierarchy(accessibilityService: AccessibilityService, args: JSONObject): String {
        return try {
            val activeWindow = accessibilityService.rootInActiveWindow 
                ?: return "ERROR: No active window found"

            val appInfo = "App: ${activeWindow.packageName}"
            val hierarchy = buildCompactHierarchy(activeWindow)
            System.out.println("HERE IT IS\n" + hierarchy);
            "$appInfo (coordinates are [left, top, right, bottom])\n$hierarchy"
        } catch (e: Exception) {
            "ERROR: Failed to get UI hierarchy: ${e.message}"
        }
    }
    
    private fun buildCompactHierarchy(node: AccessibilityNodeInfo, depth: Int = 0): String {
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
            if (node.isClickable) attributes.add("clickable")
            if (node.isFocusable) attributes.add("focusable")
            if (node.isScrollable) attributes.add("scrollable")
            if (node.isEditable) attributes.add("editable")
            if (!node.isEnabled) attributes.add("disabled")
            
            // Check if this is a "meaningless" container that should be skipped
            val hasNoAttributes = attributes.isEmpty()
            val hasSingleChild = node.childCount == 1
            
            // Skip this node if it has no attributes and only one child
            if (hasNoAttributes && hasSingleChild && node.getChild(0) != null) {
                // Skip this level and directly return the child's hierarchy with the same depth
                return buildCompactHierarchy(node.getChild(0), depth)
            }
            
            // Format bounds compactly
            val boundsStr = "[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]"
            
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
        return "Started app: $packageName"
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

        val clickPath = Path()
        clickPath.moveTo(x.toFloat(), y.toFloat())

        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))

        val clickResult = performGesture(gestureBuilder.build(), accessibilityService)
        return if (clickResult) "Clicked at coordinates ($x, $y)" else "Failed to click at coordinates ($x, $y)"
    }

    @Tool(
        name = "swipe",
        description = "Swipe from one point to another on the screen for example to scroll",
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
                required = false
            )
        ],
        requiresAccessibility = true
    )
    fun enterText(accessibilityService: AccessibilityService, args: JSONObject): String {
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

        if (!targetNode.isEditable) {
            return "Error: The targeted element is not an editable text field"
        }

        val arguments = Bundle()
        arguments.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        )
        val setTextResult =
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        if (args.optBoolean("submit", false) && setTextResult) {
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_ENTER"))
        }

        return if (setTextResult) {
            "Entered text: \"$text\""
        } else {
            "Failed to enter text"
        }
    }

    @Tool(
        name = "actionView",
        description = "Open a URL using Android's ACTION_VIEW intent. Requires the app " +
                "installed and that you know that app can open the url",
        parameters = [
            ParameterDef(
                name = "package_name",
                type = "string",
                description = "Package name of the app to open the URL in"
            ),
            ParameterDef(
                name = "url",
                type = "string",
                description = "The URL to open"
            )
        ],
        requiresContext = true
    )
    fun actionView(context: Context, args: JSONObject): String {
        val packageName = args.getString("package_name")
        val url = args.getString("url")

        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(packageName)
            }

            context.startActivity(intent)
            "Opened URL '$url' in app: $packageName"
        } catch (e: Exception) {
            "Failed to open URL: ${e.message}"
        }
    }

    fun getSerializableToolDefinitions(provider: ModelProvider): SerializableToolDefinitions {
        val methods = ToolHandler::class.java.methods
            .filter { it.isAnnotationPresent(Tool::class.java) }

        return when (provider) {
            ModelProvider.OPENAI -> {
                val definitions = methods.mapNotNull { method ->
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
                SerializableToolDefinitions.OpenAITools(definitions)
            }

            ModelProvider.GEMINI -> {
                val functionDeclarations = methods.mapNotNull { method ->
                    val tool = method.getAnnotation(Tool::class.java) ?: return@mapNotNull null

                    // For Gemini, we can still use null for empty parameters
                    val toolParameters = if (tool.parameters.isNotEmpty()) {
                        ToolParametersObject(
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
                    } else null

                    GeminiFunctionDeclaration(
                        name = tool.name,
                        description = tool.description,
                        parameters = toolParameters
                    )
                }

                val tools = listOf(
                    GeminiTool(
                        functionDeclarations = functionDeclarations
                    )
                )
                SerializableToolDefinitions.GeminiTools(tools)
            }
        }
    }

    fun callTool(
        toolCall: InternalToolCall,
        context: Context,
        accessibilityService: AccessibilityService?
    ): String {
        // Check if the agent has been cancelled
        if (Agent.getInstance()?.isCancelled() == true) {
            return "Operation cancelled by user"
        }

        val toolMethod = ToolHandler::class.java.methods
            .firstOrNull {
                it.isAnnotationPresent(Tool::class.java) &&
                        it.getAnnotation(Tool::class.java)?.name == toolCall.name
            }
            ?: return "Unknown tool call: ${toolCall.name}"

        val toolAnnotation = toolMethod.getAnnotation(Tool::class.java)
            ?: return "Tool annotation not found for: ${toolCall.name}"

        OverlayService.getInstance()?.setPerformingAction(true)

        if (!GoslingApplication.shouldHideOverlay()) {
            //Delay to let the overlay hide...
            Thread.sleep(100)
        }

        return try {
            // Check again if cancelled after the delay
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
        } finally {
            OverlayService.getInstance()?.setPerformingAction(false)
        }
    }

    fun fromJson(json: JSONObject): InternalToolCall {
        return when {
            json.has("function") -> {
                val functionObject = json.getJSONObject("function")
                InternalToolCall(
                    name = functionObject.getString("name"),
                    arguments = JSONObject(functionObject.optString("arguments", "{}"))
                )
            }

            json.has("functionCall") -> {
                val functionCall = json.getJSONObject("functionCall")
                InternalToolCall(
                    name = functionCall.getString("name"),
                    arguments = functionCall.optJSONObject("args") ?: JSONObject()
                )
            }

            else -> throw IllegalArgumentException("Unknown tool call format")
        }
    }
}
