package xyz.block.gosling.features.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.block.gosling.R
import xyz.block.gosling.features.agent.Agent
import xyz.block.gosling.features.agent.AgentStatus
import xyz.block.gosling.features.agent.Conversation
import xyz.block.gosling.features.agent.getConversationTitle
import xyz.block.gosling.features.overlay.OverlayService
import xyz.block.gosling.features.settings.SettingsStore
import xyz.block.gosling.shared.services.VoiceRecognitionService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "MainScreen"

private val predefinedQueries = listOf(
    "What's the weather like?",
    "Add contact named James Gosling",
    "Show me the best beer garden in Berlin in maps",
    "Turn on flashlight",
    "Take a picture using the camera and attach that to a new email. Save the email in drafts"
)

private val suggestionBubbles = listOf(
    "What can you help me with?",
    "Can you find the nearest pharmacy and make a vaccination appointment",
    "Find me some new tyres I can afford and when I can pick them up",
    "can you always reply to messages that ask about my availability",
    "Do I have any time next week when the weather is good to go surfing?",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToConversation: (String) -> Unit,
    isAccessibilityEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as MainActivity
    var conversations by remember { mutableStateOf<List<Conversation>>(emptyList()) }
    var textInput by remember { mutableStateOf("") }
    var currentConversation by remember { mutableStateOf<Conversation?>(null) }
    val listState = rememberLazyListState()
    var showPresetQueries by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }

    val pulseAnim = rememberInfiniteTransition()
    val scale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        )
    )

    fun createImageUri(): Uri? {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val imageFileName = "JPEG_${timeStamp}_"
        val storageDir = context.getExternalFilesDir("Photos")
        val imageFile = File.createTempFile(imageFileName, ".jpg", storageDir)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { selectedUri ->
            val settings = SettingsStore(context)
            val instructions = settings.screenshotHandlingPreferences

            processAgentCommand(
                context,
                "The user selected a photo from their gallery, see the attached image. " +
                        "Use the following instructions take take action or " +
                        "if nothing is applicable, leave it be: $instructions",
                selectedUri
            )
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            photoUri?.let { uri ->
                val settings = SettingsStore(context)
                val instructions = settings.screenshotHandlingPreferences

                processAgentCommand(
                    context,
                    "The user took a photo with their camera, see the attached image. " +
                            "Use the following instructions take take action or " +
                            "if nothing is applicable, leave it be: $instructions",
                    uri
                )
            }
        }
    }

    // Permission states
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasGalleryPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    // Permission launchers
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            photoUri = createImageUri()
            photoUri?.let { uri ->
                cameraLauncher.launch(uri)
            }
        } else {
            Toast.makeText(
                context,
                "Camera permission is required to take photos",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val galleryPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasGalleryPermission = isGranted
        if (isGranted) {
            imagePickerLauncher.launch("image/*")
        } else {
            Toast.makeText(
                context,
                "Storage permission is required to access gallery",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun showImageOptions() {
        val contextActivity = context as? Activity
        if (contextActivity != null) {
            val options = arrayOf("Take Photo", "Choose from Gallery")
            android.app.AlertDialog.Builder(contextActivity)
                .setTitle("Select Photo")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            if (hasCameraPermission) {
                                photoUri = createImageUri()
                                photoUri?.let { uri ->
                                    cameraLauncher.launch(uri)
                                }
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        }

                        1 -> {
                            if (hasGalleryPermission) {
                                imagePickerLauncher.launch("image/*")
                            } else {
                                val permission =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        Manifest.permission.READ_MEDIA_IMAGES
                                    } else {
                                        Manifest.permission.READ_EXTERNAL_STORAGE
                                    }
                                galleryPermissionLauncher.launch(permission)
                            }
                        }
                    }
                }
                .show()
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { 
                            Text("What can gosling do for you?")
                        },
                        colors = TextFieldDefaults.colors(
                            unfocusedContainerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent,
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
                        maxLines = 3,
                        minLines = 1
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { showImageOptions() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoCamera,
                                contentDescription = "Take or select photo",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        IconButton(
                            onClick = {
                                isRecording = true
                                startVoiceRecognition(context) { isRecording = false }
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

                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .combinedClickable(
                                    onClick = {
                                        if (textInput.isNotEmpty()) {
                                            processAgentCommand(
                                                context,
                                                textInput
                                            )
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
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize()) {
            if (conversations.isEmpty()) {
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
                    Text(
                        text = "No conversations yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(32.dp))
                    
                    // Suggestion bubbles
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        suggestionBubbles.forEach { suggestion ->
                            SuggestionBubble(
                                text = suggestion,
                                onClick = {
                                    processAgentCommand(context, suggestion)
                                },
                                modifier = Modifier.fillMaxWidth(0.8f)
                            )
                        }
                    }
                }
            } else {
                var showAllConversations by remember { mutableStateOf(false) }
                
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Content padding for the column
                    val topPadding = paddingValues.calculateTopPadding()
                    val startPadding = paddingValues.calculateStartPadding(LayoutDirection.Ltr)
                    val endPadding = paddingValues.calculateEndPadding(LayoutDirection.Ltr)
                    val bottomPadding = paddingValues.calculateBottomPadding() + 8.dp
                    
                    // Show current conversation if it exists
                    if (currentConversation != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = topPadding, start = startPadding, end = endPadding)
                        ) {
                            ConversationCard(
                                conversation = currentConversation!!,
                                onClick = { onNavigateToConversation(currentConversation!!.id) },
                                isCurrentConversation = true
                            )
                        }
                    }
                    
                    // Show More button
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (showAllConversations) 0.dp else bottomPadding)
                            .clickable { showAllConversations = !showAllConversations },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!showAllConversations) {
                                Icon(
                                    painter = painterResource(id = R.drawable.goose),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(24.dp)
                                        .padding(end = 8.dp)
                                )
                            }
                            Text(
                                text = if (showAllConversations) "Hide Past Conversations" else "View Past Conversations",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    // Show other conversations if expanded
                    if (showAllConversations) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = bottomPadding),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(conversations.filter { it.id != currentConversation?.id }) { conversation ->
                                ConversationCard(
                                    conversation = conversation,
                                    onClick = { onNavigateToConversation(conversation.id) },
                                    isCurrentConversation = false
                                )
                            }
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
                                        processAgentCommand(context, query)
                                    }
                                    .padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Collect conversations from the agent
    LaunchedEffect(Unit) {
        val agent = activity.currentAgent
        if (agent != null) {
            CoroutineScope(Dispatchers.Main).launch {
                conversations = agent.conversationManager.recentConversations()
            }
        }
    }

    // Watch for agent changes
    LaunchedEffect(activity.currentAgent) {
        val agent = activity.currentAgent
        if (agent != null) {
            CoroutineScope(Dispatchers.Main).launch {
                conversations = agent.conversationManager.recentConversations()
            }
            
            CoroutineScope(Dispatchers.Main).launch {
                agent.conversationManager.currentConversation.collect { updatedCurrentConversation ->
                    currentConversation = updatedCurrentConversation
                }
            }
        }
    }
}

private fun startVoiceRecognition(
    context: Context,
    onRecordingComplete: () -> Unit
) {
    val activity = context as? Activity
    // Create a single Toast instance that will be reused
    val voiceToast = Toast.makeText(context, "", Toast.LENGTH_SHORT)

    if (activity == null) {
        voiceToast.setText("Cannot start voice recognition")
        voiceToast.show()
        onRecordingComplete()
        return
    }

    val voiceRecognitionManager = VoiceRecognitionService(context)

    if (!voiceRecognitionManager.hasRecordAudioPermission()) {
        voiceRecognitionManager.requestRecordAudioPermission(activity)
        onRecordingComplete()
        return
    }

    voiceRecognitionManager.startVoiceRecognition(
        object : VoiceRecognitionService.VoiceRecognitionCallback {
            override fun onVoiceCommandReceived(command: String) {
                processAgentCommand(context, command)
                onRecordingComplete()
            }

            override fun onSpeechEnd() {
                onRecordingComplete()
            }

            override fun onError(errorMessage: String) {
                super.onError(errorMessage)
                voiceToast.setText(errorMessage)
                voiceToast.show()
                onRecordingComplete()
            }
        }
    )
}

private fun processAgentCommand(
    context: Context,
    command: String,
    imageUri: Uri? = null,
    onMessageReceived: ((String, Boolean) -> Unit)? = null
) {
    val activity = context as MainActivity
    val statusToast = Toast.makeText(context, "", Toast.LENGTH_SHORT)
    val agent = activity.currentAgent

    if (agent == null) {
        statusToast.setText("Agent service not available")
        statusToast.show()
        return
    }

    OverlayService.getInstance()?.apply {
        setIsPerformingAction(true)
        setActiveAgentManager(activity.agentServiceManager)
    }

    agent.setStatusListener { status ->
        when (status) {
            is AgentStatus.Processing -> {
                if (status.message.isEmpty() || status.message == "null") {
                    Log.d(TAG, "Ignoring empty/null processing message")
                    return@setStatusListener
                }
                android.os.Handler(context.mainLooper).post {
                    onMessageReceived?.invoke(status.message, false)

                    statusToast.setText(status.message)
                    statusToast.show()

                    OverlayService.getInstance()?.updateStatus(status)
                }
            }

            is AgentStatus.Success -> {
                android.os.Handler(context.mainLooper).post {
                    onMessageReceived?.invoke(status.message, false)

                    statusToast.setText(status.message)
                    statusToast.show()

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
                    val errorMessage = "Error: ${status.message}"
                    onMessageReceived?.invoke(errorMessage, false)

                    // Show error message in toast - use LENGTH_LONG for API key errors
                    if (status.message.contains("API key", ignoreCase = true)) {
                        Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                    } else {
                        statusToast.setText(errorMessage)
                        statusToast.show()
                    }

                    OverlayService.getInstance()?.updateStatus(status)
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
                triggerType = Agent.TriggerType.MAIN,
                imageUri = imageUri
            )
        } catch (e: Exception) {
            android.os.Handler(context.mainLooper).post {
                val errorMessage = "Error: ${e.message}"
                onMessageReceived?.invoke(errorMessage, false)
                statusToast.setText(errorMessage)
                statusToast.show()
                
                OverlayService.getInstance()?.updateStatus(AgentStatus.Error(e.message ?: "Unknown error"))
                OverlayService.getInstance()?.setIsPerformingAction(false)
            }
        }
    }
}

@Composable
private fun SuggestionBubble(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
