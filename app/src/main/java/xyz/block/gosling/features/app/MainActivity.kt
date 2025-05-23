package xyz.block.gosling.features.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
        private const val REQUEST_CALENDAR_CONTACTS_PERMISSION = 1235
        private const val TAG = "MainActivity"
    }

    private lateinit var settingsStore: SettingsStore
    private lateinit var accessibilitySettingsLauncher: ActivityResultLauncher<Intent>
    private var isAccessibilityEnabled by mutableStateOf(false)
    lateinit var agentServiceManager: AgentServiceManager
    var currentAgent by mutableStateOf<Agent?>(null)
        private set

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
                val staleConversations = agent.conversationManager.conversations.value
                    .filter { it.endTime == null }
                
                // If there are stale conversations, mark them as completed
                // but keep the most recent one as the current conversation
                if (staleConversations.isNotEmpty()) {
                    val mostRecent = staleConversations.maxByOrNull { it.startTime }
                    
                    staleConversations.forEach { conversation ->
                        if (conversation.id != mostRecent?.id) {
                            // Mark other stale conversations as completed
                            agent.conversationManager.updateCurrentConversation(
                                conversation.copy(endTime = currentTime)
                            )
                        }
                    }
                    
                    // Set the most recent stale conversation as current
                    mostRecent?.let {
                        agent.conversationManager.setCurrentConversation(it.id)
                    }
                }
                
                Log.d(TAG, "Agent service started successfully")
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
            Log.d(TAG, "MainActivity: Updated accessibility state after settings: $isEnabled")
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
        
        // Request calendar and contacts permissions if needed
        val permissionsToRequest = mutableListOf<String>()
        
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != 
            PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CALENDAR)
        }
        
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != 
            PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }
        
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(
                permissionsToRequest.toTypedArray(),
                REQUEST_CALENDAR_CONTACTS_PERMISSION
            )
        }
    }

    override fun onResume() {
        super.onResume()
        GoslingApplication.isMainActivityRunning = true
        OverlayService.getInstance()?.updateOverlayVisibility()

        val isEnabled = checkAccessibilityPermission(this)
        settingsStore.isAccessibilityEnabled = isEnabled
        isAccessibilityEnabled = isEnabled
        Log.d(TAG, "MainActivity: Updated accessibility state on resume: $isEnabled")

        if (Settings.canDrawOverlays(this)) {
            if (OverlayService.getInstance() == null) {
                startService(Intent(this, OverlayService::class.java))
            }

            if (currentAgent == null) {
                agentServiceManager.bindAndStartAgent { agent ->
                    currentAgent = agent
                    
                    // Ensure we have a current conversation set if available
                    if (agent.conversationManager.currentConversation.value == null && 
                        agent.conversationManager.conversations.value.isNotEmpty()) {
                        val mostRecent = agent.conversationManager.conversations.value.maxByOrNull { it.startTime }
                        mostRecent?.let {
                            agent.conversationManager.setCurrentConversation(it.id)
                        }
                    }
                    
                    Log.d(TAG, "Agent service started successfully")
                }
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
        }

        currentAgent = null
        agentServiceManager.unbindAgent()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        accessibilitySettingsLauncher.launch(intent)
    }

    private fun checkAccessibilityPermission(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val isEnabled = enabledServices?.contains(context.packageName) == true
        Log.d(TAG, "Accessibility check: $enabledServices, enabled: $isEnabled")
        return isEnabled
    }
}


