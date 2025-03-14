package xyz.block.gosling.features.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.block.gosling.R
import xyz.block.gosling.features.agent.AgentServiceManager
import xyz.block.gosling.features.agent.AgentStatus
import xyz.block.gosling.features.overlay.OverlayService
import xyz.block.gosling.shared.services.VoiceRecognitionService

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

private val predefinedQueries = listOf(
    "What's the weather like?",
    "Add contact named James Gosling",
    "Show me the best beer garden in Berlin in maps",
    "Turn on flashlight",
    "Take a picture using the camera and attach that to a new email. Save the email in drafts"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    isAccessibilityEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val messages = remember {
        mutableStateListOf<ChatMessage>().apply {
            addAll(activity.loadMessages())
        }
    }
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showPresetQueries by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }

    val pulseAnim = rememberInfiniteTransition()
    val scale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        )
    )

    LaunchedEffect(messages.size) {
        activity.saveMessages(messages.toList())
        listState.scrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                actions = {
                    BadgedBox(
                        badge = {
                            if (!isAccessibilityEnabled) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    modifier = modifier.absoluteOffset((-6).dp, 0.dp)
                                ) {
                                    Text("!")
                                }
                            }
                        }
                    ) {
                        IconButton(
                            onClick = onNavigateToSettings
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
                tonalElevation = 2.dp,
                shadowElevation = 0.dp,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            isRecording = true
                            startVoiceRecognition(context, messages) { isRecording = false }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .scale(if (isRecording) scale else 1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice Input",
                            tint = if (isRecording)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("What can gosling do for you?") },
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.38f
                            ),
                            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.38f
                            ),
                        ),
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true
                    )

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .combinedClickable(
                                onClick = {
                                    if (textInput.isNotEmpty()) {
                                        messages.add(ChatMessage(text = textInput, isUser = true))
                                        processAgentCommand(context, textInput) { message, isUser ->
                                            messages.add(
                                                ChatMessage(
                                                    text = message,
                                                    isUser = isUser
                                                )
                                            )
                                        }
                                        textInput = ""
                                    }
                                },
                                onLongClick = { showPresetQueries = !showPresetQueries }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Message",
                            tint = if (textInput.isNotEmpty())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.goose),
                        contentDescription = "Goose",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(80.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(
                        start = paddingValues.calculateStartPadding(LayoutDirection.Ltr),
                        top = paddingValues.calculateTopPadding(),
                        end = paddingValues.calculateEndPadding(LayoutDirection.Ltr),
                        bottom = paddingValues.calculateBottomPadding() + 8.dp
                    ),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    reverseLayout = true
                ) {
                    items(messages.asReversed()) { message ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = if (message.isUser) 32.dp else 0.dp,
                                    end = if (!message.isUser) 32.dp else 0.dp
                                ),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (message.isUser)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Text(
                                text = message.text,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (message.isUser)
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                else
                                    MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            if (showPresetQueries) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            horizontal = 16.dp,
                        )
                        .padding(bottom = paddingValues.calculateBottomPadding() + 8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        predefinedQueries.forEach { query ->
                            Text(
                                text = query,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showPresetQueries = false
                                        messages.add(ChatMessage(text = query, isUser = true))
                                        processAgentCommand(context, query) { message, isUser ->
                                            messages.add(
                                                ChatMessage(
                                                    text = message,
                                                    isUser = isUser
                                                )
                                            )
                                        }
                                    }
                                    .padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun startVoiceRecognition(
    context: Context,
    messages: MutableList<ChatMessage>,
    onRecordingComplete: () -> Unit
) {
    val activity = context as? Activity
    if (activity == null) {
        Toast.makeText(context, "Cannot start voice recognition", Toast.LENGTH_SHORT).show()
        onRecordingComplete()
        return
    }

    val voiceRecognitionManager = VoiceRecognitionService(context)

    // Check for permission
    if (!voiceRecognitionManager.hasRecordAudioPermission()) {
        voiceRecognitionManager.requestRecordAudioPermission(activity)
        onRecordingComplete()
        return
    }

    // Start voice recognition
    voiceRecognitionManager.startVoiceRecognition(
        object : VoiceRecognitionService.VoiceRecognitionCallback {
            override fun onVoiceCommandReceived(command: String) {
                messages.add(ChatMessage(text = command, isUser = true))
                processAgentCommand(context, command) { message, isUser ->
                    messages.add(ChatMessage(text = message, isUser = isUser))
                }
                onRecordingComplete()
            }

            override fun onSpeechEnd() {
                onRecordingComplete()
            }

            override fun onError(errorMessage: String) {
                super.onError(errorMessage)
                Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                onRecordingComplete()
            }
        }
    )
}

private fun processAgentCommand(
    context: Context,
    command: String,
    onMessageReceived: (String, Boolean) -> Unit
) {
    Log.d("wes", "Starting to process command: $command")
    val agentServiceManager = AgentServiceManager(context)
    val activity = context as MainActivity

    OverlayService.getInstance()?.apply {
        setIsPerformingAction(true)
        setActiveAgentManager(agentServiceManager)
    }

    agentServiceManager.bindAndStartAgent { agent ->
        Log.d("wes", "Agent bound and started, setting status listener")
        agent.setStatusListener { status ->
            Log.d("wes", "Status listener called with: $status")
            when (status) {
                is AgentStatus.Processing -> {
                    if (status.message.isEmpty() || status.message == "null") {
                        Log.d("wes", "Ignoring empty/null processing message")
                        return@setStatusListener
                    }
                    android.os.Handler(context.mainLooper).post {
                        Log.d("wes", "Processing status: ${status.message}")
                        onMessageReceived(status.message, false)
                        Toast.makeText(context, status.message, Toast.LENGTH_SHORT).show()
                        OverlayService.getInstance()?.updateStatus(status)
                    }
                }

                is AgentStatus.Success -> {
                    android.os.Handler(context.mainLooper).post {
                        Log.d("wes", "Success status: ${status.message}")
                        onMessageReceived(status.message, false)
                        Toast.makeText(context, status.message, Toast.LENGTH_SHORT).show()
                        OverlayService.getInstance()?.updateStatus(status)
                        OverlayService.getInstance()?.setIsPerformingAction(false)

                        // Create an intent to bring MainActivity to the foreground
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        }
                        context.startActivity(intent)
                    }
                }

                is AgentStatus.Error -> {
                    android.os.Handler(context.mainLooper).post {
                        Log.d("wes", "Error status: ${status.message}")
                        val errorMessage = "Error: ${status.message}"
                        onMessageReceived(errorMessage, false)
                        Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                        OverlayService.getInstance()?.updateStatus(status)
                        OverlayService.getInstance()?.setIsPerformingAction(false)
                    }
                }
            }
        }

        Log.d("wes", "Starting command processing on IO dispatcher")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("wes", "Calling agent.processCommand")
                agent.processCommand(
                    userInput = command,
                    context = context,
                    isNotificationReply = false
                )
                Log.d("wes", "Finished agent.processCommand")
            } catch (e: Exception) {
                Log.e("wes", "Error processing command", e)
                // Handle exceptions
                android.os.Handler(context.mainLooper).post {
                    val errorMessage = "Error: ${e.message}"
                    onMessageReceived(errorMessage, false)
                    activity.saveMessages(activity.loadMessages())

                    Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

