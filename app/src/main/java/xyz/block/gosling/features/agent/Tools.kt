package xyz.block.gosling.features.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import xyz.block.gosling.GoslingApplication
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
                        val localId = mcpRegistry.entries.find { it.value == Pair(packageName, appName) }?.key
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
                                        "description" to (extras.getString("$tool.description") ?: ""),
                                        "parameters" to (extras.getString("$tool.parameters") ?: "{}")
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
                result = getResultData()
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
        description = "call this to show UI elements with their properties and locations on screen in a hierarchical structure.",
        parameters = [],
        requiresAccessibility = true
    )
    fun getUiHierarchy(accessibilityService: AccessibilityService, args: JSONObject): String {
        return try {
            val activeWindow = accessibilityService.rootInActiveWindow
                ?: return "ERROR: No active window found"

            val appInfo = "App: ${activeWindow.packageName}"
            val hierarchy = buildCompactHierarchy(activeWindow)
            System.out.println("HERE IT IS\n" + hierarchy)
            "$appInfo (coordinates are of form: [x-coordinate of the left edge, y-coordinate of the top edge, x-coordinate of the right edge, y-coordinate of the bottom edge])\n$hierarchy"
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

        val text = if (args.optBoolean("submit", false)) {
            args.getString("text")
        } else {
            args.getString("text") + "\n"
        }

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


    fun getSerializableToolDefinitions(context: Context, provider: ModelProvider): SerializableToolDefinitions {
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

                    // Always create a ToolParametersObject, even for tools with no parameters
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
        
        // Check if app extensions are enabled
        val settings = xyz.block.gosling.features.settings.SettingsStore(context)
        val enableAppExtensions = settings.enableAppExtensions
        
        // Only add MCP tools if app extensions are enabled
        val mcpTools = mutableListOf<ToolDefinition>()
        if (enableAppExtensions) {
            try {
                val mcps = MobileMCP.discoverMCPs(context)
                
                for (mcp in mcps) {
                    val localId = mcp["localId"] as String

                    @Suppress("UNCHECKED_CAST")
                    val tools = mcp["tools"] as Map<String, Map<String, String>>
                    
                    // For each tool in this MCP, create a ToolDefinition
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
        // Check if the agent has been cancelled
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
            }

        } else { // handle mcp calls
            val nameParts = toolCall.name.split("_", limit = 3)
            if (!GoslingApplication.shouldHideOverlay()) {
                //Delay to let the overlay hide...
                Thread.sleep(100)
            }

            val result = MobileMCP.invokeTool(context, nameParts[1], nameParts[2], toolCall.arguments.toString() )
            System.out.println("TOOL CALL RESULT: " + result)
            return "" + result;

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
