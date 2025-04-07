package xyz.block.gosling.features.agent

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import xyz.block.gosling.features.accessibility.GoslingAccessibilityService
import xyz.block.gosling.features.agent.ToolHandler.callTool
import xyz.block.gosling.features.agent.ToolHandler.getSerializableToolDefinitions
import xyz.block.gosling.features.settings.SettingsStore
import java.io.File
import java.net.HttpURLConnection
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.math.pow

open class AgentException(message: String) : Exception(message)

class ApiKeyException(message: String) : AgentException(message)

sealed class AgentStatus {
    data class Processing(val message: String) : AgentStatus()
    data class Success(val message: String, val milliseconds: Double = 0.0) : AgentStatus()
    data class Error(val message: String) : AgentStatus()
}

class Agent : Service() {
    private val tag = "Agent"

    private var job = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + job)
    private val binder = AgentBinder()
    private var isCancelled = false
    private var statusListener: ((AgentStatus) -> Unit)? = null
    lateinit var conversationManager: ConversationManager

    enum class TriggerType {
        MAIN,
        NOTIFICATION,
        IMAGE,
        ASSISTANT
    }

    companion object {
        private var instance: Agent? = null
        fun getInstance(): Agent? = instance
        private const val TAG = "Agent"
        private val jsonFormat = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        private val okHttpClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    inner class AgentBinder : Binder() {
        fun getService(): Agent = this@Agent
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        conversationManager = ConversationManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        instance = null
    }

    fun setStatusListener(listener: (AgentStatus) -> Unit) {
        statusListener = listener
    }

    private fun updateStatus(status: AgentStatus) {
        statusListener?.invoke(status)
    }

    /**
     * Determines the device type and returns the appropriate system message first paragraph.
     *
     * @param context The application context
     * @param role The role of the assistant (from TriggerType)
     * @return The system message first paragraph based on device type
     */
    private fun getDeviceSpecificSystemMessage(context: Context, role: String): String {
        return when {
            isChromebook(context) -> {
                Log.d(tag, "THIS IS A CHROMEBOOK!!")
                "You are an assistant $role. The user may not have access to the ChromeOS device. " +
                        "You will autonomously complete complex tasks on the ChromeOS device and report back to the " +
                        "user when done. NEVER ask the user for additional information or choices - you must " +
                        "decide and act on your own. IMPORTANT: you must be aware of what application you are opening, browser, contacts and so on, take note and don't accidentally open the wrong app"
            }

            else -> {
                "You are an assistant $role. The user does not have access to the phone. " +
                        "You will autonomously complete complex tasks on the phone and report back to the " +
                        "user when done. NEVER ask the user for additional information or choices - you must " +
                        "decide and act on your own."
            }
        }
    }

    /**
     * Detects if the device is running ChromeOS.
     *
     * According to Android documentation, ChromeOS devices can be detected using the
     * "ro.boot.hardware.context" system property which is set to "u-boot" on ChromeOS.
     * Additionally, the "android.hardware.type.pc" feature is present on ChromeOS devices.
     *
     * @param context The application context
     * @return true if the device is running ChromeOS, false otherwise
     */
    private fun isChromebook(context: Context): Boolean {
        val pm = context.packageManager
        return pm.hasSystemFeature("android.hardware.type.pc") ||
                System.getProperty("ro.boot.hardware.context") == "u-boot"
    }

    fun cancel() {
        isCancelled = true
        job.cancel()
        job = SupervisorJob()
        scope = CoroutineScope(Dispatchers.IO + job)
        updateStatus(AgentStatus.Success("Agent cancelled", 0.0))
    }

    fun isCancelled(): Boolean {
        return isCancelled
    }

    suspend fun processCommand(
        userInput: String,
        context: Context,
        triggerType: TriggerType,
        imageUri: Uri? = null
    ): String {

        try {
            isCancelled = false

            // Get a complete list of all installed apps using PackageManager directly
            val packageManager = context.packageManager
            val allPackages = packageManager.getInstalledPackages(PackageManager.GET_META_DATA)
            
            // Also get intent-based apps for better categorization
            val availableIntents = IntentScanner.getAvailableIntents(
                context,
                GoslingAccessibilityService.getInstance()
            )
            
            // Combine both approaches for a more comprehensive list
            val intentAppMap = availableIntents.associateBy { it.packageName }
            val categorizedApps = mutableMapOf<String, MutableList<String>>()
            
            allPackages.forEach { packageInfo ->
                val packageName = packageInfo.packageName
                packageInfo.applicationInfo?.let { applicationInfo ->
                    val appLabel = packageManager.getApplicationLabel(applicationInfo).toString()
                    val isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    
                    // Include non-system apps and selected system apps
                    if (!isSystemApp || packageName.startsWith("com.android.vending")) { // Include Play Store
                        // Determine category using existing logic when possible
                        val category = intentAppMap[packageName]?.kind 
                            ?: IntentAppKinds.getCategoryForPackage(packageName)?.name 
                            ?: "other"
                        
                        // Add to category list
                        categorizedApps.getOrPut(category) { mutableListOf() }
                            .add("- $appLabel: $packageName")
                    }
                }
            }
            
            // Format output
            val installedApps = buildString {
                categorizedApps.forEach { (category, apps) ->
                    appendLine("## ${category.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }}")
                    appendLine()
                    apps.sorted().forEach { app ->
                        appendLine(app)
                    }
                    appendLine()
                }
            }

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)

            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels

            val role = when (triggerType) {
                TriggerType.NOTIFICATION -> "helping the user process android notifications"
                TriggerType.MAIN -> "managing the users android phone"
                TriggerType.IMAGE -> "analyzing images on the users android phone"
                TriggerType.ASSISTANT -> "providing assistant services on the users android phone"
            }

            val systemMessage = """
                |${getDeviceSpecificSystemMessage(context, role)}
                |
                |You are a personal shopping assistant that helps users research products, find services, and make purchases. Your goal is to complete shopping-related tasks through any means necessary, using multiple apps in sequence to gather information and take actions.
                |
                |PLANNING APPROACH:
                |1. First, create a clear multi-step plan for completing the shopping task
                |2. Identify which apps you'll need to use in sequence (e.g., personal info app → web browser for research → shopping app → calendar → payment app)
                |3. BEFORE STARTING, EXPLICITLY STATE the steps you will take and WHICH SPECIFIC APPS you will use to accomplish the task
                |4. Be prepared to revise your plan if you discover new information during execution
                |5. For complex tasks, break them down into smaller sub-tasks
                |6. Be flexible and creative in finding solutions - try alternative apps if your first choice doesn't work
                |7. Use multiple apps in combination - don't limit yourself to a single app for the entire task
                |
                |APP USAGE GUIDELINES:
                |• UTILIZE ALL RELEVANT INSTALLED APPS - not just web browsers
                |• Check the installed apps list below and use specialized apps when appropriate
                |• Shopping apps (Amazon, eBay, etc.) often provide better information than general web searches
                |• Use dedicated apps for specific domains (travel apps for trips, food apps for restaurants, etc.)
                |• Review apps often have more detailed ratings than websites
                |• Payment apps may offer special deals or financing options
                |• Local service apps may have exclusive listings not found on the web
                |• Social media apps can provide user experiences and recommendations
                |• Don't hesitate to switch between multiple apps to gather comprehensive information
                |
                |RESEARCH EXPECTATIONS:
                |• DEEP RESEARCH IS REQUIRED - a single search is NEVER enough
                | THIS MEANS ALWAYS do a google search or similar as part of your work, never skip this. This provides important context.
                |• For product research, visit AT LEAST 3 different sources (websites, apps, review platforms)
                |• Always check multiple retailers, marketplaces, and review sites
                |• Don't just search once - try different search terms and approaches
                |• Look beyond the first page of search results
                |• For important decisions, verify information across multiple sources
                |• Read actual product reviews, not just summary ratings
                |• Compare prices across multiple vendors
                |• A task is NOT complete until thorough research has been conducted
                |
                |SHOPPING CAPABILITIES:
                |• Research products and services based on user preferences and needs. Always do a web search as well as in app search. Never do in app without also a web search.
                |• Use browsers and shopping apps to compare options across different platforms
                |• Check user's personal information (car details, clothing sizes, preferences) when relevant
                |• Find local stores or service providers with specific features (payment options, delivery, etc.)
                |• Check calendar for availability before scheduling appointments
                |• Compare prices and options across different providers
                |• Book appointments and services
                |• Complete purchases when authorized
                |• Monitor for sales, deals, and price drops over time
                |• Check notes, messages, and emails for user preferences and past interests
                |• Plan travel itineraries including flights, hotels, and activities
                |• Organize events with themed decorations, food, and gifts
                |• Compare payment options across vendors (Afterpay, CashApp, etc.)
                |• Create shopping lists for upcoming events or trips
                |• Monitor calendars for birthdays, events, and deadlines
                |• Shop across multiple platforms (online marketplaces and local businesses)
                |• Balance budget considerations with product quality and preferences
                |
                |When you call a tool, tell the user about it. Call getUiHierarchy to see what's on 
                |the screen. In some cases you can call actionView to get something done in one shot -
                |do so only if you are sure about the url to use.
                |
                |When filling out forms:
                |1. Always use enterTextByDescription and provide the exact field label as shown in the UI hierarchy
                |   For example, use "First name" not "first" or "firstname"
                |2. Some fields may not be immediately visible and require clicking buttons like "Add address" first
                |3. After clicking such buttons, always get the UI hierarchy again to see the new fields
                |4. Handle each form section in sequence (e.g., basic info first, then address)
                |5. Verify the form state after each major section is complete
                |6. If a field is near the bottom of the screen (y-coordinate > ${height * 0.8}), swipe up slightly before interacting
                |
                |The phone has a screen resolution of ${width}x${height} pixels 
                |When a field is near the bottom (y > ${height * 0.7}), swipe up slightly before interacting.
                |
                |EXAMPLE SCENARIOS YOU CAN HANDLE:
                |1. "Do I need new tires?"
                |   • Check personal info for car details
                |   • Research tire options using dedicated auto parts apps and web browsers
                |   • Compare prices and reviews across at least 3 different tire retailers
                |   • Use specialized automotive apps to check compatibility with your vehicle
                |   • Read specific customer reviews about durability and performance
                |   • Find local tire stores with preferred payment options
                |   • Check calendar for available appointment times
                |   • Book tire installation appointment
                |
                |2. "I need a new outfit for Friday's dinner"
                |   • Check calendar for Friday's event details
                |   • Look up user's clothing sizes and preferences
                |   • Search across multiple dedicated shopping apps (not just websites)
                |   • Compare styles, materials, and prices across different retailers
                |   • Use fashion apps to check current trends and recommendations
                |   • Find appropriate clothing options at nearby stores
                |   • Check store hours and availability
                |   • Add items to cart or create shopping list
                |
                |3. "Find me a plumber that takes Afterpay"
                |   • Search for local plumbers using service apps like Yelp, Angi, and HomeAdvisor
                |   • Verify payment options on their websites and through service platforms
                |   • Filter for those accepting Afterpay
                |   • Use payment apps to confirm Afterpay availability
                |   • Read detailed customer reviews about quality and reliability
                |   • Check reviews and availability
                |   • Check calendar for free time slots
                |   • Prepare booking information
                |
                |4. "Plan my Europe trip for this summer"
                |   • Check calendar for available time off
                |   • Research flight and hotel deals using dedicated travel apps (Kayak, Expedia, etc.)
                |   • Compare prices and options from different airlines and hotel chains
                |   • Use travel planning apps to organize itinerary options
                |   • Read traveler reviews about neighborhoods and accommodations
                |   • Create itinerary based on user interests
                |   • Generate packing list based on weather forecast
                |   • Identify items needed before trip
                |
                |5. "Find a birthday gift for my sister"
                |   • Check calendar for upcoming birthday
                |   • Review messages/notes for mentioned interests
                |   • Research gift options using dedicated shopping apps (Amazon, Etsy, etc.)
                |   • Check social media apps for wishlists or interests
                |   • Read product reviews and ratings from multiple sources
                |   • Compare prices, quality, and delivery times across at least 3 retailers
                |   • Purchase from preferred vendor with appropriate timing
                |
                |IMPORTANT: Before taking any action, you MUST explicitly state which apps you plan to use and in what order.
                |For example: "I'll use Contacts to find your car details, then Chrome to search for tire options, 
                |then Calendar to check your availability, and finally Maps to locate nearby tire shops."
                |
                |If after taking a step and getting the UI hierarchy you don't find what you expect, don't
                |immediately give up. Try asking for the hierarchy again to give the app more time
                |to finalize rendering.
                |
                |When you start an app, make sure the app is in the state you expect it to be in. If it is not, 
                |try to navigate to the correct state (for example, getting back to the home page or start screen).
                |
                |After each tool call and before the next step, write down what you see on the screen that helped 
                |you resolve this step. Keep iterating until you complete the task or have exhausted all possible approaches.
                |
                |When you think you are finished, double check to make sure you are done (sometimes you need to click more to continue).
                |Use a screenshot if necessary to check.
                |
                |Remember: DO NOT ask the user for help or additional information - you must solve the problem autonomously.
                """.trimMargin()

            val startTime = System.currentTimeMillis()
            val userMessage = if (imageUri != null) {
                val contentResolver = applicationContext.contentResolver

                val imageBytes = contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                    ?: throw AgentException("Failed to read screenshot")

                val base64Image =
                    android.util.Base64.encodeToString(imageBytes, android.util.Base64.DEFAULT)
                val mimeType = contentResolver.getType(imageUri) ?: "image/jpeg"

                Message(
                    role = "user",
                    content = listOf(
                        Content.Text(text = userInput),
                        Content.ImageUrl(
                            imageUrl = Image(url = "data:$mimeType;base64,$base64Image")
                        )
                    )
                )
            } else {
                Message(
                    role = "user",
                    content = contentWithText(userInput)
                )
            }

            val newConversation = Conversation(
                startTime = startTime,
                fileName = conversationManager.fileNameFor(userInput),
                messages = mutableListOf(
                    Message(
                        role = "system",
                        content = contentWithText(systemMessage)
                    ),
                    userMessage
                )
            )
            conversationManager.updateCurrentConversation(newConversation)

            updateStatus(AgentStatus.Processing("Thinking..."))

            return withContext(scope.coroutineContext) {
                var retryCount = 0
                val maxRetries = 3

                while (true) {
                    if (isCancelled) {
                        updateStatus(AgentStatus.Success("Operation cancelled"))
                        return@withContext "Operation cancelled by user"
                    }

                    val startTimeLLMCall = System.currentTimeMillis()
                    var response: JSONObject?
                    try {
                        if (retryCount > 0) {
                            val delayMs = (2.0.pow(retryCount.toDouble()) * 1000).toLong()
                            delay(delayMs)
                            updateStatus(AgentStatus.Processing("Retrying... (attempt ${retryCount + 1})"))
                        }

                        response = callLlm(
                            conversationManager.currentConversation.value?.messages ?: emptyList(),
                            context
                        )
                        retryCount = 0
                    } catch (e: AgentException) {
                        if (isCancelled) {
                            updateStatus(AgentStatus.Success("Operation cancelled"))
                            return@withContext "Operation cancelled by user"
                        }

                        // Don't retry for API key errors
                        if (e is ApiKeyException) {
                            val errorMsg = "API key error: ${e.message}"
                            updateStatus(AgentStatus.Error(errorMsg))
                            Log.e(tag, "API key error", e)
                            return@withContext errorMsg
                        }

                        retryCount++

                        if (retryCount >= maxRetries) {
                            val errorMsg = "Failed after $maxRetries attempts: ${e.message}"
                            updateStatus(AgentStatus.Error(errorMsg))
                            Log.e(tag, "Error processing response", e)
                            return@withContext errorMsg
                        }
                        continue
                    }
                    val llmDuration = (System.currentTimeMillis() - startTimeLLMCall) / 1000.0

                    try {
                        val (assistantReply, toolCalls, annotation) = when {
                            response.has("choices") -> {
                                val assistantMessage =
                                    response.getJSONArray("choices").getJSONObject(0)
                                        .getJSONObject("message")
                                val content = assistantMessage.optString("content", "Ok")
                                val tools = assistantMessage.optJSONArray("tool_calls")?.let {
                                    List(it.length()) { i -> ToolHandler.fromJson(it.getJSONObject(i)) }
                                }
                                val usage = response.getJSONObject("usage")
                                val annotation = mapOf(
                                    "duration" to llmDuration,
                                    "input_tokens" to usage.getInt("prompt_tokens").toDouble(),
                                    "output_tokens" to usage.getInt("completion_tokens").toDouble()
                                )
                                Triple(content, tools, annotation)
                            }

                            response.has("candidates") -> {
                                val candidate = response.getJSONArray("candidates").getJSONObject(0)
                                val content = candidate.getJSONObject("content")
                                val text = content.getJSONArray("parts").getJSONObject(0)
                                    .optString("text", "Ok")

                                val tools = content.optJSONArray("parts")?.let { parts ->
                                    List(parts.length()) { i ->
                                        val part = parts.getJSONObject(i)
                                        if (part.has("functionCall")) {
                                            ToolHandler.fromJson(part)
                                        } else null
                                    }.filterNotNull()
                                }
                                val annotation = mapOf(
                                    "duration" to llmDuration
                                )
                                Triple(text, tools, annotation)
                            }

                            else -> Triple("Unknown response format", null, emptyMap())
                        }

                        if (isCancelled) {
                            updateStatus(AgentStatus.Success("Operation cancelled"))
                            return@withContext "Operation cancelled by user"
                        }

                        updateStatus(AgentStatus.Processing(assistantReply))

                        val (toolResults, toolAnnotations) = executeTools(toolCalls, context)

                        val assistantMessage = Message(
                            role = "assistant",
                            content = contentWithText(assistantReply),
                            toolCalls = toolCalls?.map { toolCall ->
                                ToolCall(
                                    id = toolCall.toolId,
                                    function = ToolFunction(
                                        name = toolCall.name,
                                        arguments = toolCall.arguments.toString()
                                    )
                                )
                            },
                            stats = annotation
                        )

                        conversationManager.updateCurrentConversation(
                            conversationManager.currentConversation.value?.copy(
                                messages = conversationManager.currentConversation.value?.messages?.plus(
                                    assistantMessage
                                )
                                    ?: listOf(assistantMessage)
                            ) ?: newConversation
                        )

                        if (toolResults.isEmpty() || isCancelled) {
                            if (isCancelled) {
                                updateStatus(AgentStatus.Success("Operation cancelled"))
                                return@withContext "Operation cancelled by user"
                            } else {
                                updateStatus(AgentStatus.Success(assistantReply))
                                break
                            }
                        }

                        for ((result, toolAnnotation) in toolResults.zip(toolAnnotations)) {
                            val toolMessage = Message(
                                role = "tool",
                                toolCallId = result["tool_call_id"].toString(),
                                content = listOf(Content.Text(text = result["output"].toString())),
                                name = result["name"].toString(),
                                stats = toolAnnotation
                            )

                            conversationManager.updateCurrentConversation(
                                conversationManager.currentConversation.value?.copy(
                                    messages = conversationManager.currentConversation.value?.messages?.plus(
                                        toolMessage
                                    )
                                        ?: listOf(toolMessage)
                                ) ?: newConversation
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error processing response", e)
                        val errorMsg = "Error processing response: ${e.message}"
                        updateStatus(AgentStatus.Error(errorMsg))
                        return@withContext errorMsg
                    }
                    continue
                }

                val explanationPrompt = "" // change to something if you want an explanation
                if (explanationPrompt != "") {
                    val internetQuery = Message(
                        role = "user",
                        content = contentWithText(explanationPrompt)
                    )
                    val explainConversation = conversationManager.currentConversation.value?.copy(
                        messages = conversationManager.currentConversation.value?.messages?.plus(
                            internetQuery
                        ) ?: listOf(internetQuery)
                    )

                    if (explainConversation != null) {
                        val response = callLlm(
                            explainConversation.messages,
                            context
                        )
                        val assistantMessage =
                            response.getJSONArray("choices").getJSONObject(0)
                                .getJSONObject("message")
                        val content = assistantMessage.optString("content", "Ok")
                        Log.d(tag, "Explanation response: $content")
                    }
                }

                context.getExternalFilesDir(null)?.let { filesDir ->
                    val conversationsDir = File(filesDir, "session_dumps")
                    conversationsDir.mkdirs()

                    val statsMessage = calculateConversationStats(
                        conversationManager.currentConversation.value,
                        startTime
                    )

                    conversationManager.updateCurrentConversation(
                        conversationManager.currentConversation.value?.copy(
                            messages = statsMessage?.let { stats ->
                                conversationManager.currentConversation.value?.messages?.let { existingMessages ->
                                    listOf(stats) + existingMessages
                                }
                            } ?: conversationManager.currentConversation.value?.messages
                            ?: emptyList(),
                            endTime = System.currentTimeMillis(),
                            isComplete = true
                        ) ?: newConversation
                    )
                }

                val completionTime = (System.currentTimeMillis() - startTime) / 1000.0
                val completionMessage =
                    "Task completed successfully in %.1f seconds".format(completionTime)

                updateStatus(AgentStatus.Success(completionMessage, completionTime))
                return@withContext completionMessage
            }
        } catch (e: Exception) {
            Log.e(tag, "Error executing command", e)
            if (e is kotlinx.coroutines.CancellationException) {
                // Reset the job and scope to ensure future commands work
                job = SupervisorJob()
                scope = CoroutineScope(Dispatchers.IO + job)
                updateStatus(AgentStatus.Success("Operation cancelled"))
                return "Operation cancelled by user"
            }

            val errorMsg = "Error: ${e.message}"
            updateStatus(AgentStatus.Error(errorMsg))
            return errorMsg
        }
    }

    fun handleNotification(
        packageName: String,
        title: String,
        content: String,
        category: String,
    ) {
        scope.launch {
            try {
                val settings = SettingsStore(this@Agent)
                val messageHandlingPreferences = settings.messageHandlingPreferences

                val prompt = buildString {
                    append(
                        """
                        Here's the notification:
                        App: $packageName
                        Title: $title
                        Content: $content
                        Category: $category
                        
                        Please analyze this notification and take appropriate action if needed.
                    """.trimIndent()
                    )

                    // Add handling rules if they exist
                    if (messageHandlingPreferences.isNotEmpty()) {
                        append(messageHandlingPreferences)
                    }
                }

                processCommand(
                    prompt,
                    this@Agent,
                    triggerType = TriggerType.NOTIFICATION,
                )
            } catch (e: Exception) {
                Log.e(tag, "Error handling notification", e)

                // If it's a cancellation exception, handle it gracefully
                if (e is kotlinx.coroutines.CancellationException) {
                    // Reset the job and scope to ensure future commands work
                    job = SupervisorJob()
                    scope = CoroutineScope(Dispatchers.IO + job)
                    updateStatus(AgentStatus.Success("Operation cancelled"))
                } else {
                    updateStatus(AgentStatus.Error("Error: ${e.message}"))
                }
            }
        }
    }

    private fun removeOutdatedPayloads(messages: List<Message>): List<Message> {
        val isUiHierarchyCall = { message: Message ->
            message.role == "tool" && message.name == "getUiHierarchy"
        }

        val lastUiHierarchyIndex = messages.indexOfLast(isUiHierarchyCall)

        return messages.mapIndexed { index, message ->
            when {
                index >= lastUiHierarchyIndex -> message
                isUiHierarchyCall(message) ->
                    message.copy(content = contentWithText("{UI hierarchy output truncated}"))

                message.role == "user" && message.content?.any { it is Content.ImageUrl } == true ->
                    message.copy(content = message.content.filterNot { it is Content.ImageUrl })

                else -> message
            }
        }
    }

    private fun makeHttpCall(
        urlString: String,
        requestBody: String,
        apiKey: String?,
        model: AiModel
    ): JSONObject {
        val request = Request.Builder()
            .url(urlString)
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .apply {
                if (model.provider == ModelProvider.OPENAI) {
                    addHeader("Authorization", "Bearer $apiKey")
                }
            }
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                val errorResponse = errorBody.ifEmpty {
                    when (response.code) {
                        HttpURLConnection.HTTP_UNAUTHORIZED -> "Unauthorized - API key may be invalid"
                        HttpURLConnection.HTTP_FORBIDDEN -> "Forbidden - Access denied"
                        HttpURLConnection.HTTP_NOT_FOUND -> "Not found - Invalid API endpoint"
                        HttpURLConnection.HTTP_BAD_REQUEST -> "Bad request"
                        else -> "HTTP Error ${response.code}"
                    }
                }
                handleHttpError(response.code, errorResponse)
            }

            val responseBody = response.body?.string()
                ?: throw AgentException("Empty response body")

            return JSONObject(responseBody)
        }
    }

    private suspend fun callLlm(messages: List<Message>, context: Context): JSONObject {
        val settings = SettingsStore(context)
        val model = AiModel.fromIdentifier(settings.llmModel)
        val apiKey = settings.getApiKey(model.provider)

        val processedMessages = removeOutdatedPayloads(messages)

        val urlString = when (model.provider) {
            ModelProvider.OPENAI -> "https://api.openai.com/v1/chat/completions"
            ModelProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models/${model.identifier}:generateContent?key=$apiKey"
        }

        val json = jsonFormat

        val requestBody = when (model.provider) {
            ModelProvider.OPENAI -> {
                val toolDefinitions =
                    when (val result = getSerializableToolDefinitions(context, model.provider)) {
                        is SerializableToolDefinitions.OpenAITools -> result.definitions
                        else -> emptyList()
                    }

                val openAIRequest = OpenAIRequest(
                    model = model.identifier,
                    messages = processedMessages,
                    temperature = if (model.identifier != "o3-mini") 0.1 else null,
                    tools = toolDefinitions
                )

                json.encodeToString(openAIRequest)
            }

            ModelProvider.GEMINI -> {
                val combinedText = processedMessages.joinToString("\n") {
                    "${it.role}: ${it.content}"
                }

                val tools =
                    when (val result = getSerializableToolDefinitions(context, model.provider)) {
                        is SerializableToolDefinitions.GeminiTools -> result.tools
                        else -> emptyList()
                    }

                val geminiRequest = GeminiRequest(
                    contents = GeminiContent(
                        parts = listOf(GeminiPart(text = combinedText))
                    ),
                    tools = tools
                )

                json.encodeToString(geminiRequest)
            }
        }

        return withContext(Dispatchers.IO) {
            makeHttpCall(urlString, requestBody, apiKey, model)
        }
    }

    private fun executeTools(
        toolCalls: List<InternalToolCall>?,
        context: Context
    ): Pair<List<Map<String, String>>, List<Map<String, Double>>> {
        if (toolCalls == null || isCancelled) return Pair(emptyList(), emptyList())

        val annotations: MutableList<Map<String, Double>> = mutableListOf()

        val results = toolCalls.mapIndexed { index, toolCall ->
            if (isCancelled) {
                annotations.add(emptyMap())
                return@mapIndexed mapOf(
                    "tool_call_id" to "cancelled_${System.currentTimeMillis()}_$index",
                    "output" to "Operation cancelled by user",
                    "name" to "cancelled"
                )
            }

            val startTime = System.currentTimeMillis()
            val result = callTool(toolCall, context, GoslingAccessibilityService.getInstance())
            annotations.add(mapOf("duration" to (System.currentTimeMillis() - startTime) / 1000.0))
            mapOf(
                "tool_call_id" to toolCall.toolId,
                "output" to result,
                "name" to toolCall.name
            )
        }
        return Pair(results, annotations)
    }

    private fun calculateConversationStats(
        conversation: Conversation?,
        startTime: Long
    ): Message? {
        fun sumStats(key: String): Double =
            conversation?.messages?.sumOf { msg -> msg.stats?.get(key) ?: 0.0 } ?: 0.0

        return conversation?.let {
            val totalTime = (System.currentTimeMillis() - startTime) / 1000.0
            val annotationTime = sumStats("duration")

            Message(
                role = "stats",
                content =
                    contentWithText(
                        "Conversation Statistics - ${
                            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                        }"
                    ),
                annotations = Json.encodeToJsonElement(
                    mapOf(
                        "total_input_tokens" to sumStats("input_tokens"),
                        "total_output_tokens" to sumStats("output_tokens"),
                        "total_wall_time" to totalTime,
                        "total_annotated_time" to annotationTime,
                        "time_coverage_percentage" to (annotationTime / totalTime * 100)
                    )
                )
            )
        }
    }

    private fun isApiKeyError(responseCode: Int): Boolean {
        return responseCode == HttpURLConnection.HTTP_UNAUTHORIZED
    }

    private fun handleHttpError(responseCode: Int, errorResponse: String): Nothing {
        if (isApiKeyError(responseCode)) {
            throw ApiKeyException(errorResponse)
        }

        throw AgentException(errorResponse)
    }

    fun processScreenshot(uri: Uri, instructions: String) {
        scope.launch {
            try {
                val prompt = "The user took a screenshot, see the attached image. " +
                        "Use the following instructions take take action or " +
                        "if nothing is applicable, leave it be: $instructions"

                processCommand(
                    prompt,
                    this@Agent,
                    triggerType = TriggerType.IMAGE,
                    imageUri = uri
                )
            } catch (e: Exception) {
                Log.e(tag, "Error handling notification", e)

                // If it's a cancellation exception, handle it gracefully
                if (e is kotlinx.coroutines.CancellationException) {
                    // Reset the job and scope to ensure future commands work
                    job = SupervisorJob()
                    scope = CoroutineScope(Dispatchers.IO + job)
                    updateStatus(AgentStatus.Success("Operation cancelled"))
                } else {
                    updateStatus(AgentStatus.Error("Error: ${e.message}"))
                }
            }
        }
    }
}
