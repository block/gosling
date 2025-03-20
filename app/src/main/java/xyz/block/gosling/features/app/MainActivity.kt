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
        private const val PREFS_NAME = "GoslingPrefs"
        private const val MESSAGES_KEY = "chat_messages"
    }

    private lateinit var settingsStore: SettingsStore
    private lateinit var accessibilitySettingsLauncher: ActivityResultLauncher<Intent>
    private var isAccessibilityEnabled by mutableStateOf(false)
    private val sharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
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
                Log.d("MainActivity", "Agent service started successfully")
            }

            startService(Intent(this, OverlayService::class.java))
        }

        // Register the launcher for accessibility settings
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

        // Check and save accessibility permission state
        val isEnabled = checkAccessibilityPermission(this)
        settingsStore.isAccessibilityEnabled = isEnabled
        isAccessibilityEnabled = isEnabled
        Log.d("Gosling", "MainActivity: Updated accessibility state on resume: $isEnabled")

        // Start services if overlay permission is granted
        if (Settings.canDrawOverlays(this)) {
            // Start the overlay service if it's not running
            if (OverlayService.getInstance() == null) {
                startService(Intent(this, OverlayService::class.java))
            }

            // Bind to the agent service if we don't have an agent
            if (currentAgent == null) {
                agentServiceManager.bindAndStartAgent { agent ->
                    currentAgent = agent
                    Log.d("MainActivity", "Agent service started successfully")
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

        // Only unbind when the activity is being destroyed
        currentAgent = null
        agentServiceManager.unbindAgent()
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        accessibilitySettingsLauncher.launch(intent)
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
