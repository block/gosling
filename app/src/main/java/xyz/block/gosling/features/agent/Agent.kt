package xyz.block.gosling.features.agent

import android.app.Service
import android.content.Context
import android.content.Intent
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
import org.json.JSONObject
import xyz.block.gosling.features.accessibility.GoslingAccessibilityService
import xyz.block.gosling.features.agent.ToolHandler.callTool
import xyz.block.gosling.features.agent.ToolHandler.getSerializableToolDefinitions
import xyz.block.gosling.features.settings.SettingsStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.pow

open class AgentException(message: String) : Exception(message)

class ApiKeyException(message: String) : AgentException(message)

sealed class AgentStatus {
    data class Processing(val message: String) : AgentStatus()
    data class Success(val message: String, val milliseconds: Double = 0.0) : AgentStatus()
    data class Error(val message: String) : AgentStatus()
}

class Agent : Service() {
    private var job = SupervisorJob()
    private var scope = CoroutineScope(Dispatchers.IO + job)
    private val binder = AgentBinder()
    private var isCancelled = false
    private var statusListener: ((AgentStatus) -> Unit)? = null
    lateinit var conversationManager: ConversationManager

    companion object {
        private var instance: Agent? = null
        fun getInstance(): Agent? = instance
        private const val TAG = "Agent"
        private val jsonFormat = Json {
            prettyPrint = true
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }

    inner class AgentBinder : Binder() {
        fun getService(): Agent = this@Agent
        fun getConversationManager(): ConversationManager = conversationManager
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
        isNotificationReply: Boolean
    ): String {

        try {
            isCancelled = false

            val availableIntents = IntentScanner.getAvailableIntents(context)
            val installedApps = availableIntents.joinToString("\n|") { it.formatForLLM() }

            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)

            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels

            val role =
                if (isNotificationReply) "helping the user process android notifications" else "managing the users android phone"

            val systemMessage = """
                |You are an assistant $role. The user does not have access to the phone. 
                |You will autonomously complete complex tasks on the phone and report back to the 
                |user when done. NEVER ask the user for additional information or choices - you must 
                |decide and act on your own.
                |
                |Your goal is to complete the requested task through any means necessary. If one 
                |approach doesn't work, try alternative methods until you succeed. Be persistent
                |and creative in finding solutions.
                |
                |When you call a tool, tell the user about it. Call getUiHierarchy to see what's on 
                |the screen. In some cases you can call actionView to get something done in one shot -
                |do so only if you are sure about the url to use.
                |
                |The phone has a screen resolution of ${width}x${height} pixels
                |The phone has the following apps installed:
                |
                |$installedApps
                |
                |Before getting started, explicitly state the steps you want to take and which app(s) you want 
                |use to accomplish that task. For example, open the contacts app to find out Joe's phone number. 
                |
                |After each step verify that the step was completed successfully by looking at the screen and 
                |the ui hierarchy dump. If the step was not completed successfully, try to recover by:
                |1. Trying a different approach
                |2. Using a different app
                |3. Looking for alternative UI elements
                |4. Adjusting your interaction method
                |
                |When you start an app, make sure the app is in the state you expect it to be in. If it is not, 
                |try to navigate to the correct state.
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
            val newConversation = Conversation(
                startTime = startTime,
                messages = mutableListOf(
                    Message(
                        role = "system",
                        content = systemMessage
                    ),
                    Message(
                        role = "user",
                        content = userInput
                    )
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
                            Log.e("Agent", "API key error", e)
                            return@withContext errorMsg
                        }

                        retryCount++

                        if (retryCount >= maxRetries) {
                            val errorMsg = "Failed after $maxRetries attempts: ${e.message}"
                            updateStatus(AgentStatus.Error(errorMsg))
                            Log.e("Agent", "Error processing response", e)
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

                        val toolCallsWithIds = toolCalls?.mapIndexed { index, toolCall ->
                            val toolCallId = toolResults[index]["tool_call_id"]
                                ?: "call_${System.currentTimeMillis()}_$index"
                            toolCall to toolCallId
                        } ?: emptyList()

                        val assistantMessage = Message(
                            role = "assistant",
                            content = assistantReply,
                            toolCalls = toolCallsWithIds.map { (toolCall, id) ->
                                ToolCall(
                                    id = id,
                                    function = ToolFunction(
                                        name = toolCall.name,
                                        arguments = toolCall.arguments.toString()
                                    )
                                )
                            }
                        )

                        conversationManager.updateCurrentConversation(
                            conversationManager.currentConversation.value?.copy(
                                messages = conversationManager.currentConversation.value?.messages?.plus(
                                    assistantMessage
                                )
                                    ?: listOf(assistantMessage),
                                annotations = conversationManager.currentConversation.value?.annotations?.plus(
                                    MessageAnnotation(
                                        messageIndex = (conversationManager.currentConversation.value?.messages?.size
                                            ?: 0),
                                        annotations = annotation
                                    )
                                ) ?: listOf(MessageAnnotation(0, annotation))
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
                                content = result["output"].toString(),
                                name = result["name"].toString()
                            )

                            conversationManager.updateCurrentConversation(
                                conversationManager.currentConversation.value?.copy(
                                    messages = conversationManager.currentConversation.value?.messages?.plus(
                                        toolMessage
                                    )
                                        ?: listOf(toolMessage),
                                    annotations = conversationManager.currentConversation.value?.annotations?.plus(
                                        MessageAnnotation(
                                            messageIndex = (conversationManager.currentConversation.value?.messages?.size
                                                ?: 0),
                                            annotations = toolAnnotation
                                        )
                                    ) ?: listOf(MessageAnnotation(0, toolAnnotation))
                                ) ?: newConversation
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("Agent", "Error processing response", e)
                        val errorMsg = "Error processing response: ${e.message}"
                        updateStatus(AgentStatus.Error(errorMsg))
                        return@withContext errorMsg
                    }
                    continue
                }

                val sanitizedCommand = userInput.take(50)
                    .replace(Regex("[^a-zA-Z0-9]"), "_")
                    .lowercase()

                context.getExternalFilesDir(null)?.let { filesDir ->
                    val conversationsDir = File(filesDir, "session_dumps")
                    conversationsDir.mkdirs()

                    val statsMessage = calculateConversationStats(
                        conversationManager.currentConversation.value?.annotations ?: emptyList(),
                        startTime
                    )

                    conversationManager.updateCurrentConversation(
                        conversationManager.currentConversation.value?.copy(
                            endTime = System.currentTimeMillis(),
                            stats = statsMessage,
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
            Log.e("Agent", "Error executing command", e)
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
                // Get message handling preferences from settings
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
                    isNotificationReply = true
                )
            } catch (e: Exception) {
                // Handle any unexpected exceptions
                Log.e("Agent", "Error handling notification", e)

                // If it's a cancellation exception, handle it gracefully
                if (e is kotlinx.coroutines.CancellationException) {
                    // Reset the job and scope to ensure future commands work
                    job = SupervisorJob()
                    scope = CoroutineScope(Dispatchers.IO + job)
                    updateStatus(AgentStatus.Success("Operation cancelled"))
                } else {
                    // For other exceptions, report the error
                    updateStatus(AgentStatus.Error("Error: ${e.message}"))
                }
            }
        }
    }

    private fun filterUiHierarchyMessages(messages: List<Message>): List<Message> {
        val isUiHierarchyCall = { message: Message ->
            message.role == "tool" && message.name == "getUiHierarchy"
        }

        val lastUiHierarchyIndex = messages.indexOfLast(isUiHierarchyCall)

        return messages.mapIndexed { index, message ->
            if (isUiHierarchyCall(message) && index < lastUiHierarchyIndex) {
                message.copy(content = "{UI hierarchy output truncated}")
            } else {
                message
            }
        }
    }

    private fun callLlm(messages: List<Message>, context: Context): JSONObject {
        val settings = SettingsStore(context)
        val model = AiModel.fromIdentifier(settings.llmModel)
        val apiKey = settings.getApiKey(model.provider)

        val processedMessages = filterUiHierarchyMessages(messages)

        val url = when (model.provider) {
            ModelProvider.OPENAI -> URL("https://api.openai.com/v1/chat/completions")
            ModelProvider.GEMINI -> URL("https://generativelanguage.googleapis.com/v1beta/models/${model.identifier}:generateContent?key=$apiKey")
        }

        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")

            if (model.provider == ModelProvider.OPENAI) {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
            }

            connection.doOutput = true

            val json = jsonFormat

            val requestBody = when (model.provider) {
                ModelProvider.OPENAI -> {
                    val toolDefinitions =
                        when (val result =
                            getSerializableToolDefinitions(context, model.provider)) {
                            is SerializableToolDefinitions.OpenAITools -> result.definitions
                            else -> emptyList() // This should never happen for OpenAI
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

                    val tools = when (val result =
                        getSerializableToolDefinitions(context, model.provider)) {
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

            connection.outputStream.write(requestBody.toByteArray())

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: when (responseCode) {
                        HttpURLConnection.HTTP_UNAUTHORIZED -> "Unauthorized - API key may be invalid"
                        HttpURLConnection.HTTP_FORBIDDEN -> "Forbidden - Access denied"
                        HttpURLConnection.HTTP_NOT_FOUND -> "Not found - Invalid API endpoint"
                        HttpURLConnection.HTTP_BAD_REQUEST -> "Bad request"
                        else -> "HTTP Error $responseCode"
                    }

                handleHttpError(responseCode, errorResponse)
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }

            when (model.provider) {
                ModelProvider.OPENAI -> {
                    json.decodeFromString<OpenAIResponse>(response)
                }

                ModelProvider.GEMINI -> {
                    json.decodeFromString<GeminiResponse>(response)
                }
            }

            JSONObject(response)
        } catch (e: Exception) {
            val message = when {
                e.message?.contains("Unable to resolve host") == true -> {
                    // Check network connectivity
                    val connectivityManager =
                        context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val network = connectivityManager.activeNetwork
                    val capabilities =
                        network?.let { connectivityManager.getNetworkCapabilities(it) }

                    if (network == null || capabilities == null) {
                        "Network error: No active network connection"
                    } else {
                        "Network error: DNS resolution failed. Will retry with new connection..."
                    }
                }

                e.message?.contains("timeout") == true -> "Request timed out. Please try again."
                else -> {
                    if (e.message != null && isApiKeyError(
                            HttpURLConnection.HTTP_UNAUTHORIZED,
                            e.message!!
                        )
                    ) {
                        throw ApiKeyException(e.message!!)
                    }
                    "Error calling LLM: ${e.message}"
                }
            }
            throw AgentException(message)
        } finally {
            connection.disconnect()
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

            val toolCallId = "call_${System.currentTimeMillis()}_$index"
            val startTime = System.currentTimeMillis()
            val result = callTool(toolCall, context, GoslingAccessibilityService.getInstance())
            annotations.add(mapOf("duration" to (System.currentTimeMillis() - startTime) / 1000.0))
            mapOf(
                "tool_call_id" to toolCallId,
                "output" to result,
                "name" to toolCall.name
            )
        }
        return Pair(results, annotations)
    }

    private fun calculateConversationStats(
        messageAnnotations: List<MessageAnnotation>,
        startTime: Long
    ): Message {
        val totalInputTokens = messageAnnotations.sumOf { annotation ->
            annotation.annotations["input_tokens"] ?: 0.0
        }
        val totalOutputTokens = messageAnnotations.sumOf { annotation ->
            annotation.annotations["output_tokens"] ?: 0.0
        }
        val totalTime = (System.currentTimeMillis() - startTime) / 1000.0
        val summedAnnotationTime = messageAnnotations.sumOf { annotation ->
            annotation.annotations["duration"] ?: 0.0
        }

        val statsMap = mapOf(
            "total_input_tokens" to totalInputTokens,
            "total_output_tokens" to totalOutputTokens,
            "total_wall_time" to totalTime,
            "total_annotated_time" to summedAnnotationTime,
            "time_coverage_percentage" to (summedAnnotationTime / totalTime * 100)
        )

        return Message(
            role = "stats",
            content = "Conversation Statistics - ${
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            }",
            annotations = Json.encodeToJsonElement(statsMap)
        )
    }

    private fun isApiKeyError(responseCode: Int, errorResponse: String): Boolean {
        return responseCode == HttpURLConnection.HTTP_UNAUTHORIZED
    }

    private fun handleHttpError(responseCode: Int, errorResponse: String): Nothing {
        if (isApiKeyError(responseCode, errorResponse)) {
            throw ApiKeyException(errorResponse)
        }

        throw AgentException(errorResponse)
    }
}
