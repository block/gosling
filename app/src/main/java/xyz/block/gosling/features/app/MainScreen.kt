package xyz.block.gosling.features.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import xyz.block.gosling.R
import xyz.block.gosling.features.agent.AgentServiceManager
import xyz.block.gosling.features.agent.AgentStatus
import xyz.block.gosling.features.launcher.InputOptions
import xyz.block.gosling.features.launcher.KeyboardInputDrawer
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var showKeyboardDrawer by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showPresetQueries by remember { mutableStateOf(false) }

    fun sendMessage() {


        // Step 0: discover mMCPs and what tools they offer
        val availableMCPs = context.packageManager.queryIntentActivities(
            Intent("com.example.mMCP.ACTION_TOOL_ADVERTISE"),
            0
        )
        availableMCPs.forEach { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo
            println("\nFound mMCP provider:")
            println("- Package: ${activityInfo.packageName}")
            println("- Activity: ${activityInfo.name}")

            val ai = context.packageManager.getActivityInfo(
                android.content.ComponentName(activityInfo.packageName, activityInfo.name),
                android.content.pm.PackageManager.GET_META_DATA
            )

            println("- Metadata bundle: ${ai.metaData}")
            val manifest = ai.metaData?.getString("mMCP_manifest")
            println("MCP Manifest ${manifest}")
            val jsonObject = org.json.JSONObject(manifest)
            println("instructions ${jsonObject.getString("instructions")}")
            println("tools ${jsonObject.getString("tools")}")

        }

        // Step 1: Trigger mMCP action when settings page is shown
        val params = JSONObject(mapOf("name" to "Mic"))
        val intent = Intent("com.example.mMCP.ACTION_TOOL_CALL").apply {
            setPackage("org.breezyweather.debug")
            putExtra("tool_name", "say_hi")
            putExtra("parameters", params.toString())
        }
        context.startActivityForResult(intent, 1001)
    }




    LaunchedEffect(messages.size) {
        activity.saveMessages(messages.toList())
        // Scroll to the latest message
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
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
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Chat messages
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    if (messages.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
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
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(
                            8.dp,
                            alignment = Alignment.Bottom
                        ),
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

                // Input options
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    tonalElevation = 2.dp,
                    shadowElevation = 0.dp,
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .navigationBarsPadding(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        InputOptions(
                            onMicrophoneClick = {
                                startVoiceRecognition(context, messages)
                            },
                            onKeyboardClick = {
                                showKeyboardDrawer = true
                            },
                            onKeyboardLongPress = {
                                showPresetQueries = true
                            }
                        )
                    }
                }
            }

            if (showPresetQueries) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 180.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        predefinedQueries.forEach { query ->
                            Text(
                                text = query,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showPresetQueries = false
                                        messages.add(ChatMessage(text = query, isUser = true))
                                        processAgentCommand(
                                            context,
                                            query
                                        ) { message, isUser ->
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

            if (showKeyboardDrawer) {
                KeyboardInputDrawer(
                    value = textInput,
                    onValueChange = { textInput = it },
                    onDismiss = {
                        showKeyboardDrawer = false
                        textInput = ""
                    },
                    onSubmit = {
                        //sendMessage()
                        if (textInput.isNotEmpty()) {
                            messages.add(ChatMessage(text = textInput, isUser = true))
                            processAgentCommand(
                                context,
                                textInput
                            ) { message, isUser ->
                                messages.add(ChatMessage(text = message, isUser = isUser))
                            }
                        }
                        showKeyboardDrawer = false
                        textInput = ""
                    }
                )
            }
        }
    }
}

private fun startVoiceRecognition(context: Context, messages: MutableList<ChatMessage>) {
    val activity = context as? Activity
    if (activity == null) {
        Toast.makeText(context, "Cannot start voice recognition", Toast.LENGTH_SHORT).show()
        return
    }

    val voiceRecognitionManager = VoiceRecognitionService(context)

    // Check for permission
    if (!voiceRecognitionManager.hasRecordAudioPermission()) {
        voiceRecognitionManager.requestRecordAudioPermission(activity)
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
            }
        }
    )
}

private fun processAgentCommand(
    context: Context,
    command: String,
    onMessageReceived: (String, Boolean) -> Unit
) {
    val agentServiceManager = AgentServiceManager(context)
    val activity = context as MainActivity

    agentServiceManager.bindAndStartAgent { agent ->
        agent.setStatusListener { status ->
            when (status) {
                is AgentStatus.Processing -> {
                    if (status.message.isEmpty() || status.message == "null") return@setStatusListener
                    android.os.Handler(context.mainLooper).post {
                        Toast.makeText(
                            context,
                            status.message,
                            Toast.LENGTH_SHORT
                        ).show()
                        if (status.message != "Thinking..." && status.message != "Processing...") {
                            onMessageReceived(status.message, false)
                        }
                    }
                }

                is AgentStatus.Success -> {
                    android.os.Handler(context.mainLooper).post {
                        onMessageReceived(status.message, false)

                        // Create an intent to bring MainActivity to the foreground
                        val intent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                                   Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        }
                        context.startActivity(intent)

                        Toast.makeText(
                            context,
                            status.message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                is AgentStatus.Error -> {
                    android.os.Handler(context.mainLooper).post {
                        val errorMessage = "Error: ${status.message}"
                        onMessageReceived(errorMessage, false)

                        Toast.makeText(
                            context,
                            errorMessage,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                agent.processCommand(
                    userInput = command,
                    context = context,
                    isNotificationReply = false
                )
            } catch (e: Exception) {
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

