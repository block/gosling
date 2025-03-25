package xyz.block.gosling.features.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.navigation.compose.rememberNavController
import xyz.block.gosling.GoslingApplication
import xyz.block.gosling.features.agent.Agent
import xyz.block.gosling.features.agent.AgentServiceManager
import xyz.block.gosling.features.overlay.OverlayService
import xyz.block.gosling.features.settings.SettingsStore
import xyz.block.gosling.shared.navigation.NavGraph
import xyz.block.gosling.shared.theme.GoslingTheme

class MainActivity : ComponentActivity() {
    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1234
    }

    private lateinit var settingsStore: SettingsStore
    private lateinit var accessibilitySettingsLauncher: ActivityResultLauncher<Intent>
    private var isAccessibilityEnabled by mutableStateOf(false)
    lateinit var agentServiceManager: AgentServiceManager
    var currentAgent by mutableStateOf<Agent?>(null)
        private set

    // Add this property to track if we're waiting for a new MediaProjection
    private var isWaitingForMediaProjection = false

    @SuppressLint("UseKtx")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsStore = SettingsStore(this)
        isAccessibilityEnabled = settingsStore.isAccessibilityEnabled
        agentServiceManager = AgentServiceManager(this)

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
        } else {
            agentServiceManager.bindAndStartAgent { agent ->
                currentAgent = agent
                // Mark any stale active conversations as completed
                val currentTime = System.currentTimeMillis()
                agent.conversationManager.conversations.value
                    .filter { it.endTime == null }
                    .forEach { conversation ->
                        agent.conversationManager.updateCurrentConversation(
                            conversation.copy(endTime = currentTime)
                        )
                    }
                Log.d("MainActivity", "Agent service started successfully")

                if (agent.mediaProjection == null) {
                    requestMediaProjectionPermission()
                }
            }

            startService(Intent(this, OverlayService::class.java))
        }

        accessibilitySettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            // Check accessibility permission when returning from settings
            val isEnabled = checkAccessibilityPermission(this)
            settingsStore.isAccessibilityEnabled = isEnabled
            isAccessibilityEnabled = isEnabled
            Log.d("Gosling", "MainActivity: Updated accessibility state after settings: $isEnabled")
        }

        enableEdgeToEdge()
        setContent {
            GoslingTheme {
                val navController = rememberNavController()
                NavGraph(
                    navController = navController,
                    settingsStore = settingsStore,
                    openAccessibilitySettings = { openAccessibilitySettings() },
                    isAccessibilityEnabled = isAccessibilityEnabled
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GoslingApplication.isMainActivityRunning = true
        OverlayService.getInstance()?.updateOverlayVisibility()

        val isEnabled = checkAccessibilityPermission(this)
        settingsStore.isAccessibilityEnabled = isEnabled
        isAccessibilityEnabled = isEnabled
        Log.d("Gosling", "MainActivity: Updated accessibility state on resume: $isEnabled")

        if (Settings.canDrawOverlays(this)) {
            if (OverlayService.getInstance() == null) {
                startService(Intent(this, OverlayService::class.java))
            }

            if (currentAgent == null) {
                agentServiceManager.bindAndStartAgent { agent ->
                    currentAgent = agent
                    Log.d("MainActivity", "Agent service started successfully")
                    checkAndRequestMediaProjection()
                }
            } else {
                checkAndRequestMediaProjection()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        GoslingApplication.isMainActivityRunning = false
        OverlayService.getInstance()?.updateOverlayVisibility()
    }

    override fun onDestroy() {
        super.onDestroy()
        GoslingApplication.isMainActivityRunning = false
        OverlayService.getInstance()?.updateOverlayVisibility()

        // Mark any active conversations as completed
        currentAgent?.let { agent ->
            val currentTime = System.currentTimeMillis()
            agent.conversationManager.currentConversation.value?.let { conversation ->
                if (conversation.endTime == null) {
                    agent.conversationManager.updateCurrentConversation(
                        conversation.copy(endTime = currentTime)
                    )
                }
            }
            // Stop MediaProjection when the activity is destroyed
            agent.mediaProjection?.stop()
            agent.mediaProjection = null
        }

        currentAgent = null
        agentServiceManager.unbindAgent()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        accessibilitySettingsLauncher.launch(intent)
    }

    private val screenshotLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            Log.d("MainActivity", "MediaProjection permission granted")
            try {
                val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val mediaProjection = projectionManager.getMediaProjection(result.resultCode, result.data!!)
                
                mediaProjection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d("MainActivity", "MediaProjection stopped")
                        Handler(Looper.getMainLooper()).post {
                            // Only request new permission if we're not taking a screenshot and the app is active
                            Agent.getInstance()?.let { agent ->
                                if (!agent.isScreenshotInProgress && !isFinishing && !isDestroyed) {
                                    agent.mediaProjection = null
                                    if (!isWaitingForMediaProjection) {
                                        requestMediaProjectionPermission()
                                    }
                                }
                            }
                        }
                    }
                }, Handler(Looper.getMainLooper()))

                Agent.getInstance()?.mediaProjection = mediaProjection
                Log.d("MainActivity", "MediaProjection set on Agent instance")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error setting up MediaProjection", e)
            }
        } else {
            Log.e("MainActivity", "MediaProjection permission denied or cancelled")
        }
        isWaitingForMediaProjection = false
    }

    private fun requestMediaProjectionPermission() {
        Log.d("MainActivity", "Requesting MediaProjection permission")
        if (isWaitingForMediaProjection) {
            Log.d("MainActivity", "Already waiting for MediaProjection permission")
            return
        }
        
        // Only request if we don't have an active MediaProjection
        if (Agent.getInstance()?.mediaProjection != null) {
            Log.d("MainActivity", "MediaProjection already exists, skipping request")
            return
        }
        
        isWaitingForMediaProjection = true
        try {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenshotLauncher.launch(projectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e("MainActivity", "Error requesting MediaProjection permission", e)
            isWaitingForMediaProjection = false
        }
    }

    // Add this method to check if we need to request permission
    private fun checkAndRequestMediaProjection() {
        if (Agent.getInstance()?.mediaProjection == null && !isWaitingForMediaProjection && !isFinishing && !isDestroyed) {
            requestMediaProjectionPermission()
        }
    }
}


fun checkAccessibilityPermission(context: Context): Boolean {
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )
    val isEnabled = enabledServices?.contains(context.packageName) == true
    Log.d("Gosling", "Accessibility check: $enabledServices, enabled: $isEnabled")
    return isEnabled
}
