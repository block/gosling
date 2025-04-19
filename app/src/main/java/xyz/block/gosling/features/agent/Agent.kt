package xyz.block.gosling.features.agent

import android.app.Service
import android.content.Context
import android.content.Intent
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
        
        // Update the current conversation to mark it as complete when cancelled
        conversationManager.currentConversation.value?.let { currentConv ->
            conversationManager.updateCurrentConversation(
                currentConv.copy(
                    endTime = System.currentTimeMillis(),
                    isComplete = true
                )
            )
        }
        
        updateStatus(AgentStatus.Success("Agent cancelled", 0.0))
    }

    fun isCancelled(): Boolean {
        return isCancelled
    }

    suspend fun processCommand(
        userInput: String,
        context: Context,
        triggerType: TriggerType,
        imageUri: Uri? = null,
    ): String {

        // TODO: decide if we want long running sessions or not.
        // possibly not as we can just have it look at past converstations when needed.
        val continueSession = false;

        try {
            isCancelled = false

            val availableIntents = IntentScanner.getAvailableIntents(
                context,
                GoslingAccessibilityService.getInstance()
            )
            val installedApps = IntentAppKinds.groupIntentsByCategory(availableIntents)

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
                |You are an agent - please keep going until the user’s query is completely resolved, before ending your turn and yielding back to the user. Only terminate your turn when you are sure that the problem is solved.
                |You will be asked to solve every day problems and take actions making use of what is at your disposal.
                |
                |Your goal is to complete the requested task through any means necessary. If one 
                |approach doesn't work, try alternative methods until you succeed. Be persistent
                |and creative in finding solutions.
                |
                |When you call a tool, tell the user about it. Call getUiHierarchy to see what's on 
                |the screen. In some cases you can call actionView to get something done in one shot -
                |do so only if you are sure about the url to use.
                |
                |If you are not sure about content or apps pertaining to the user’s request, use your tools to control or gather the relevant information: do NOT guess or make up an answer.
                |If you get stuck, you MUST re-plan extensively before each function call, and reflect extensively on the outcomes of the previous function calls. DO NOT do this entire process by making function calls only, as this can impair your ability to solve the problem and think insightfully.
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
                |The phone has the following apps installed:
                |
                |$installedApps
                |
                |Before getting started, explicitly state the steps you want to take and which app(s) you want 
                |use to accomplish that task. For example, open the contacts app to find out Joe's phone number.
                |This may require the use of multiple apps in sequence. 
                |For example, check the calendar for free time and then check the maps that there is enough time to get between appointments. 
                |
                |If after taking a step and getting the ui hierarchy you don't what you find, don't
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
                |Some tasks will be one shot, but CRITICALLY some will require multiple steps and iteration and checking if you are done.
                | for example, adding to a shopping cart will require multiple steps, as will planning a trip.
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

            val newConversation = if (continueSession && conversationManager.currentConversation.value != null) {
                val conv = conversationManager.currentConversation.value!!
                Conversation(
                    startTime = startTime,
                    fileName = conversationManager.fileNameFor(userInput),
                    messages = conv.messages.filter { it.role != "stats" } + userMessage
                )
            } else {
                Conversation(
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
            }
            
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
                            // Make sure we're properly reporting this to the UI
                            CoroutineScope(Dispatchers.Main).launch {
                                statusListener?.invoke(AgentStatus.Error(errorMsg))
                            }
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
                
                // Update the current conversation to mark it as complete when cancelled
                conversationManager.currentConversation.value?.let { currentConv ->
                    conversationManager.updateCurrentConversation(
                        currentConv.copy(
                            endTime = System.currentTimeMillis(),
                            isComplete = true
                        )
                    )
                }
                
                updateStatus(AgentStatus.Success("Operation cancelled"))
                return "Operation cancelled by user"
            }
            
            // Special handling for API key exceptions
            if (e is ApiKeyException) {
                val errorMsg = "API key error: ${e.message}"
                updateStatus(AgentStatus.Error(errorMsg))
                Log.e(tag, "API key error", e)
                // Make sure we're properly reporting this to the UI
                CoroutineScope(Dispatchers.Main).launch {
                    statusListener?.invoke(AgentStatus.Error(errorMsg))
                }
                return errorMsg
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
                    triggerType = TriggerType.NOTIFICATION
                )
            } catch (e: Exception) {
                Log.e(tag, "Error handling notification", e)

                // If it's a cancellation exception, handle it gracefully
                if (e is kotlinx.coroutines.CancellationException) {
                    // Reset the job and scope to ensure future commands work
                    job = SupervisorJob()
                    scope = CoroutineScope(Dispatchers.IO + job)
                    
                    // Update the current conversation to mark it as complete when cancelled
                    conversationManager.currentConversation.value?.let { currentConv ->
                        conversationManager.updateCurrentConversation(
                            currentConv.copy(
                                endTime = System.currentTimeMillis(),
                                isComplete = true
                            )
                        )
                    }
                    
                    updateStatus(AgentStatus.Success("Operation cancelled"))
                } else {
                    val errorMsg = "Error: ${e.message}"
                    updateStatus(AgentStatus.Error(errorMsg))
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
        // Check for missing API key before making the request
        if (apiKey.isNullOrBlank()) {
            val errorMsg = "API key is missing for ${model.provider}. Please add your API key in settings."
            Log.e(tag, errorMsg)
            throw ApiKeyException(errorMsg)
        }
        
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
        
        // Check for empty API key early
        if (apiKey.isNullOrBlank()) {
            updateStatus(AgentStatus.Error("API key is missing. Please add your API key in settings."))
            throw ApiKeyException("API key is missing. Please add your API key in settings.")
        }

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
            val errorMessage = "Invalid API key. Please check your API key in settings."
            Log.e(tag, "API key error: $errorResponse")
            updateStatus(AgentStatus.Error(errorMessage))
            throw ApiKeyException(errorMessage)
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
                Log.e(tag, "Error handling screenshot", e)

                // If it's a cancellation exception, handle it gracefully
                if (e is kotlinx.coroutines.CancellationException) {
                    // Reset the job and scope to ensure future commands work
                    job = SupervisorJob()
                    scope = CoroutineScope(Dispatchers.IO + job)
                    
                    // Update the current conversation to mark it as complete when cancelled
                    conversationManager.currentConversation.value?.let { currentConv ->
                        conversationManager.updateCurrentConversation(
                            currentConv.copy(
                                endTime = System.currentTimeMillis(),
                                isComplete = true
                            )
                        )
                    }
                    
                    updateStatus(AgentStatus.Success("Operation cancelled"))
                } else {
                    val errorMsg = "Error: ${e.message}"
                    updateStatus(AgentStatus.Error(errorMsg))
                }
            }
        }
    }
}
