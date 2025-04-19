package xyz.block.gosling.features.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.block.gosling.features.agent.Agent
import xyz.block.gosling.features.agent.AgentServiceManager
import xyz.block.gosling.features.agent.AgentStatus
import xyz.block.gosling.features.agent.Content
import xyz.block.gosling.features.overlay.OverlayService
import xyz.block.gosling.shared.services.VoiceRecognitionService
import xyz.block.gosling.shared.theme.GoslingTheme

/**
 * Data class to store command results for display
 */
data class CommandResult(
    val command: String,
    val response: String,
    val isError: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * The main launcher screen composable that displays the home screen with a clock
 * and provides access to the app drawer via swipe-up gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen() {
    val context = LocalContext.current
    var appList by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    val coroutineScope = rememberCoroutineScope()

    var showKeyboardDrawer by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }
    var currentTime by remember { mutableStateOf(getCurrentTime()) }
    
    // State for persisted command results
    var lastCommandResult by remember { mutableStateOf<CommandResult?>(null) }

    // Update the clock every minute
    LaunchedEffect(Unit) {
        while (true) {
            delay(60000) // Update every minute
            currentTime = getCurrentTime()
        }
    }

    // Load installed apps
    LaunchedEffect(Unit) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null)
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER)

        val resolveInfoList: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)

        appList = resolveInfoList.map { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo
            val appLabel = resolveInfo.loadLabel(pm).toString()
            val packageName = activityInfo.packageName
            val activityName = activityInfo.name
            val icon = resolveInfo.loadIcon(pm).toBitmap()

            AppInfo(appLabel, packageName, activityName, icon)
        }.sortedBy { it.label }
    }

    val bottomSheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.Hidden,
        skipHiddenState = false
    )

    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = bottomSheetState
    )

    // Draggable state for handling swipe gestures
    val draggableState = rememberDraggableState { delta ->
        // Negative delta means swipe up
        if (delta < -10) {
            coroutineScope.launch {
                bottomSheetState.expand()
            }
        } else if (delta > 10) {
            coroutineScope.launch {
                bottomSheetState.hide()
            }
        }
    }

    BottomSheetScaffold(
        scaffoldState = scaffoldState,
        sheetContent = {
            AppDrawer(
                apps = appList,
                onAppClick = { appInfo ->
                    val launchIntent = Intent()
                    launchIntent.setClassName(appInfo.packageName, appInfo.activityName)
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)

                    // Hide the app drawer after launching an app
                    coroutineScope.launch {
                        bottomSheetState.hide()
                    }
                }
            )
        },
        sheetPeekHeight = 0.dp,
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Vertical,
                    onDragStopped = {
                        // No additional action needed on drag stopped
                    }
                )
                .clickable {
                    // Also allow clicking anywhere to open the drawer
                    coroutineScope.launch {
                        if (bottomSheetState.currentValue == SheetValue.Hidden) {
                            bottomSheetState.expand()
                        } else {
                            bottomSheetState.hide()
                        }
                    }
                },
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                ClockWidget(currentTime = currentTime)
                
                // Display the last command result if available
                lastCommandResult?.let { result ->
                    Spacer(modifier = Modifier.height(32.dp))  // Increased spacing
                    CommandResultCard(
                        commandResult = result,
                        onDismiss = { lastCommandResult = null },
                        modifier = Modifier.clickable { lastCommandResult = null }
                    )
                }

                Spacer(modifier = Modifier.weight(0.6f))  // Reduced weight to give more space to the card

                InputOptions(
                    onMicrophoneClick = {
                        // Start voice recognition for agent commands
                        startVoiceRecognition(context) { command ->
                            processAgentCommand(context, command) { result ->
                                lastCommandResult = result
                            }
                        }
                    },
                    onKeyboardClick = {
                        showKeyboardDrawer = true
                    }
                )

                Spacer(modifier = Modifier.height(32.dp))

                Text(
                    text = "Swipe up or tap to open app drawer",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                )
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
                if (textInput.isNotEmpty()) {
                    processAgentCommand(context, textInput) { result ->
                        lastCommandResult = result
                    }
                }
                showKeyboardDrawer = false
                textInput = ""
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun LauncherScreenPreview() {
    GoslingTheme {
        LauncherScreen()
    }
}

/**
 * Displays a command result card
 */
@Composable
fun CommandResultCard(
    commandResult: CommandResult,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (commandResult.isError)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)  // Increased padding for more vertical space
        ) {
            // Command (question)
            Text(
                text = "\"${commandResult.command}\"",
                style = MaterialTheme.typography.bodyMedium,
                color = if (commandResult.isError)
                    MaterialTheme.colorScheme.onErrorContainer
                else
                    MaterialTheme.colorScheme.onSecondaryContainer
            )
            
            Spacer(modifier = Modifier.height(16.dp))  // Increased spacing
            
            // Response (answer) - with scrolling for long content
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp, max = 350.dp)  // Increased min and max height
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = commandResult.response,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (commandResult.isError)
                        MaterialTheme.colorScheme.onErrorContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))  // Increased spacing
            
            Text(
                text = "Tap to dismiss",
                style = MaterialTheme.typography.bodySmall,
                color = if (commandResult.isError)
                    MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

private fun startVoiceRecognition(
    context: Context,
    onCommandReceived: (String) -> Unit
) {
    val activity = context as? Activity
    if (activity == null) {
        Toast.makeText(context, "Cannot start voice recognition", Toast.LENGTH_SHORT).show()
        return
    }

    val voiceRecognitionManager = VoiceRecognitionService(context)

    if (!voiceRecognitionManager.hasRecordAudioPermission()) {
        voiceRecognitionManager.requestRecordAudioPermission(activity)
        return
    }

    voiceRecognitionManager.startVoiceRecognition(
        object : VoiceRecognitionService.VoiceRecognitionCallback {
            override fun onVoiceCommandReceived(command: String) {
                onCommandReceived(command)
            }
        }
    )
}

private fun processAgentCommand(
    context: Context, 
    command: String,
    onResultReceived: (CommandResult) -> Unit
) {
    val statusToast = Toast.makeText(context.applicationContext, command, Toast.LENGTH_LONG)
    statusToast.show()

    val agentServiceManager = AgentServiceManager(context)
    OverlayService.getInstance()?.setIsPerformingAction(true)

    agentServiceManager.bindAndStartAgent { agent ->
        agent.setStatusListener { status ->
            when (status) {
                is AgentStatus.Processing -> {
                    if (status.message.isEmpty() || status.message == "null") return@setStatusListener
                    android.os.Handler(context.mainLooper).post {
                        statusToast.setText(status.message)
                        statusToast.show()
                    }
                }

                is AgentStatus.Success -> {
                    android.os.Handler(context.mainLooper).post {
                        statusToast.setText(status.message)
                        statusToast.show()
                        
                        // Get the actual answer from the conversation
                        val actualAnswer = agent.conversationManager.currentConversation.value?.let { conversation ->
                            // Find the last assistant message
                            conversation.messages
                                .filter { it.role == "assistant" }
                                .lastOrNull()
                                ?.content
                                ?.filterIsInstance<Content.Text>()
                                ?.firstOrNull()
                                ?.text
                                ?: status.message
                        } ?: status.message
                        
                        // Create and store the command result
                        val result = CommandResult(
                            command = command,
                            response = actualAnswer,
                            isError = false
                        )
                        onResultReceived(result)

                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(homeIntent)
                    }
                }

                is AgentStatus.Error -> {
                    android.os.Handler(context.mainLooper).post {
                        val errorMessage = "Error: ${status.message}"
                        statusToast.setText(errorMessage)
                        statusToast.show()
                        
                        // Create and store the error result
                        val result = CommandResult(
                            command = command,
                            response = errorMessage,
                            isError = true
                        )
                        onResultReceived(result)
                        
                        OverlayService.getInstance()?.setIsPerformingAction(false)
                    }
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                agent.processCommand(
                    userInput = command,
                    context = context,
                    triggerType = Agent.TriggerType.MAIN
                )
            } catch (e: Exception) {
                android.os.Handler(context.mainLooper).post {
                    val errorMessage = "Error: ${e.message}"
                    statusToast.setText(errorMessage)
                    statusToast.show()
                    
                    // Create and store the error result
                    val result = CommandResult(
                        command = command,
                        response = errorMessage,
                        isError = true
                    )
                    onResultReceived(result)
                }
            }
        }
    }
}
