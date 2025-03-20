package xyz.block.gosling.features.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import xyz.block.gosling.features.agent.AgentServiceManager
import xyz.block.gosling.features.agent.AiModel
import xyz.block.gosling.features.agent.AppUsageStats

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsStore: SettingsStore,
    onBack: () -> Unit,
    openAccessibilitySettings: () -> Unit,
    isAccessibilityEnabled: Boolean,
) {
    val context = LocalContext.current
    var isAssistantEnabled by remember { mutableStateOf(false) }
    var llmModel by remember { mutableStateOf(settingsStore.llmModel) }
    var currentModel by remember { mutableStateOf(AiModel.fromIdentifier(llmModel)) }
    var apiKey by remember { mutableStateOf(settingsStore.getApiKey(currentModel.provider)) }
    var enableAppExtensions by remember { mutableStateOf(settingsStore.enableAppExtensions) }
    var shouldProcessNotifications by remember { mutableStateOf(settingsStore.shouldProcessNotifications) }
    var messageHandlingPreferences by remember { mutableStateOf(settingsStore.messageHandlingPreferences) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showClearConversationsDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var shouldHandleScreenshots by remember { mutableStateOf(settingsStore.handleScreenshots) }
    var screenshotHandlingPreferences by remember { mutableStateOf(settingsStore.screenshotHandlingPreferences) }
    val scope = rememberCoroutineScope()
    val agentServiceManager = remember { AgentServiceManager(context) }

    // Permission launcher for screenshots
    val screenshotPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            shouldHandleScreenshots = true
            settingsStore.handleScreenshots = true
        } else {
            shouldHandleScreenshots = false
            settingsStore.handleScreenshots = false
        }
    }

    // Function to check and request screenshot permissions
    fun checkAndRequestScreenshotPermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        when {
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) == PackageManager.PERMISSION_GRANTED -> {
                shouldHandleScreenshots = true
                settingsStore.handleScreenshots = true
            }

            else -> {
                screenshotPermissionLauncher.launch(permission)
            }
        }
    }

    fun checkAssistantStatus() {
        val settingSecure = Settings.Secure.getString(
            context.contentResolver,
            "assistant"
        )
        isAssistantEnabled = settingSecure?.contains(context.packageName) == true
    }

    fun showStatsSettings(): () -> Unit {
        return { AppUsageStats.requestPermission(context) }
    }

    // Check on initial launch
    LaunchedEffect(Unit) {
        checkAssistantStatus()
    }

    // Check when app regains focus
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        val lifecycleObserver = object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) {
                if (activity == context) {
                    checkAssistantStatus()
                }
            }

            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStarted(activity: android.app.Activity) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(
                activity: android.app.Activity,
                outState: android.os.Bundle
            ) {
            }

            override fun onActivityStopped(activity: android.app.Activity) {}
            override fun onActivityCreated(
                activity: android.app.Activity,
                savedInstanceState: android.os.Bundle?
            ) {
            }
        }

        activity?.application?.registerActivityLifecycleCallbacks(lifecycleObserver)

        onDispose {
            activity?.application?.unregisterActivityLifecycleCallbacks(lifecycleObserver)
        }
    }

    // Update API key when model changes
    LaunchedEffect(llmModel) {
        currentModel = AiModel.fromIdentifier(llmModel)
        apiKey = settingsStore.getApiKey(currentModel.provider)
    }

    val models = AiModel.AVAILABLE_MODELS.map {
        it.identifier to it.displayName
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "LLM Model")
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = it }
                        ) {
                            OutlinedTextField(
                                value = models.find { it.first == llmModel }?.second ?: llmModel,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                            )

                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                models.forEach { (modelId, displayName) ->
                                    DropdownMenuItem(
                                        text = { Text(displayName) },
                                        onClick = {
                                            llmModel = modelId
                                            settingsStore.llmModel = modelId
                                            currentModel = AiModel.fromIdentifier(modelId)
                                            apiKey = settingsStore.getApiKey(currentModel.provider)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // API Key
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "API Key")
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = {
                                apiKey = it
                                settingsStore.setApiKey(currentModel.provider, it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                        )
                    }
                }

                // Accessibility/Notifications Section
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Enable other apps to provide extensions",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(
                            checked = enableAppExtensions,
                            onCheckedChange = {
                                enableAppExtensions = it
                                settingsStore.enableAppExtensions = it
                            }
                        )
                    }

                    if (isAccessibilityEnabled) {
                        Button(
                            onClick = showStatsSettings(),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = true,
                        ) {
                            Text("Go to app stats settings")
                        }

                        Text(
                            text = "Notification Processing",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "Allow Gosling to analyze and respond to notifications from other apps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Process notifications",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Switch(
                                checked = shouldProcessNotifications,
                                onCheckedChange = {
                                    shouldProcessNotifications = it
                                    settingsStore.shouldProcessNotifications = it
                                }
                            )
                        }
                        if (shouldProcessNotifications) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Message handling preferences",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                OutlinedTextField(
                                    value = messageHandlingPreferences,
                                    onValueChange = {
                                        messageHandlingPreferences = it
                                        settingsStore.messageHandlingPreferences = it
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    minLines = 3,
                                    maxLines = 5
                                )
                            }
                        }

                        // Screenshot handling option
                        Text(
                            text = "Screenshot Processing",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "Allow Gosling to look at screenshots and take " +
                                    "action based on your preferences and the contents " +
                                    "of the screenshot.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Handle screenshots",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Switch(
                                checked = shouldHandleScreenshots,
                                onCheckedChange = { handleScreenshots ->
                                    if (handleScreenshots) {
                                        checkAndRequestScreenshotPermission()
                                    } else {
                                        shouldHandleScreenshots = false
                                        settingsStore.handleScreenshots = false
                                    }
                                }
                            )
                        }

                        // Screenshot handling preferences
                        AnimatedVisibility(
                            visible = shouldHandleScreenshots,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                OutlinedTextField(
                                    value = screenshotHandlingPreferences,
                                    onValueChange = { preferences ->
                                        screenshotHandlingPreferences = preferences
                                        settingsStore.screenshotHandlingPreferences = preferences
                                    },
                                    label = { Text("Screenshot Handling Rules") },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp),
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    placeholder = { Text("Enter rules for handling screenshots...") }
                                )

                                Text(
                                    text = "Specify how you want screenshots to be processed. For example:\n" +
                                            "When a screenshot comes in and it is a receipt, extract " +
                                            "the total amount and other details and send it to my accountant.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Accessibility Permissions",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = "Gosling needs accessibility permissions to interact with other apps and help you with tasks.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = openAccessibilitySettings,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = true,
                        ) {
                            Text("Enable Accessibility")
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showClearConversationsDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear all conversations")
                }

                Button(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Clear saved settings")
                }
            }
        }
    }

    if (showClearConversationsDialog) {
        AlertDialog(
            onDismissRequest = { showClearConversationsDialog = false },
            title = { Text("Clear all conversations?") },
            text = { Text("This will permanently delete all conversations. This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            agentServiceManager.bindAndStartAgent { agent ->
                                agent.conversationManager.clearConversations()
                            }
                        }
                        showClearConversationsDialog = false
                    },
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConversationsDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset Setup") },
            text = { Text("This will reset all settings and show the setup wizard again. Are you sure?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        settingsStore.isFirstTime = true
                        showResetDialog = false
                    }
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
