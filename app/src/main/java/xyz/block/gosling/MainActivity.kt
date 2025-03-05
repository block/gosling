package xyz.block.gosling

import GoslingUI
import android.Manifest
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import xyz.block.gosling.ui.theme.GoslingTheme
import xyz.block.gosling.ui.theme.LocalGoslingColors

class MainActivity : ComponentActivity() {
    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1234
    }

    private lateinit var settingsManager: SettingsManager
    private lateinit var accessibilitySettingsLauncher: ActivityResultLauncher<Intent>
    private var isAccessibilityEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(this)
        isAccessibilityEnabled = settingsManager.isAccessibilityEnabled

        // Start the Agent service
        startForegroundService(Intent(this, Agent::class.java))

        startService(Intent(this, OverlayService::class.java))

        // Register the launcher for accessibility settings
        accessibilitySettingsLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            // Check accessibility permission when returning from settings
            val isEnabled = checkAccessibilityPermission(this)
            settingsManager.isAccessibilityEnabled = isEnabled
            isAccessibilityEnabled = isEnabled
            Log.d("Gosling", "MainActivity: Updated accessibility state after settings: $isEnabled")
        }

        enableEdgeToEdge()
        setContent {
            GoslingTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainContent(
                        modifier = Modifier.padding(innerPadding),
                        settingsManager = settingsManager,
                        openAccessibilitySettings = { openAccessibilitySettings() },
                        isAccessibilityEnabled = isAccessibilityEnabled
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GoslingApplication.isMainActivityRunning = true

        // Check and save accessibility permission state
        val isEnabled = checkAccessibilityPermission(this)
        settingsManager.isAccessibilityEnabled = isEnabled
        isAccessibilityEnabled = isEnabled
        Log.d("Gosling", "MainActivity: Updated accessibility state on resume: $isEnabled")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val result = data?.getStringExtra("result")
            Log.d("Gosling", "mMCP result: $result")
            // TODO: Handle the result
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        GoslingApplication.isMainActivityRunning = false
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        accessibilitySettingsLauncher.launch(intent)
    }
}

@Composable
fun MainContent(
    modifier: Modifier = Modifier,
    settingsManager: SettingsManager,
    openAccessibilitySettings: () -> Unit,
    isAccessibilityEnabled: Boolean
) {
    var showSetup by remember { mutableStateOf(settingsManager.isFirstTime) }
    var showSettings by remember { mutableStateOf(false) }

    if (showSetup) {
        SetupWizard(
            onSetupComplete = { showSetup = false },
            modifier = modifier,
            settingsManager = settingsManager,
            openAccessibilitySettings = openAccessibilitySettings,
            isAccessibilityEnabled = isAccessibilityEnabled
        )
    } else if (showSettings) {
        SettingsScreen(
            settingsManager = settingsManager,
            onBack = { showSettings = false },
            openAccessibilitySettings = openAccessibilitySettings,
            isAccessibilityEnabled = isAccessibilityEnabled
        )
    } else {
        Box(
            modifier = modifier.fillMaxSize()
        ) {
            // Settings button in top-right corner with badge
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                IconButton(
                    onClick = { showSettings = true }
                ) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                if (!isAccessibilityEnabled) {
                    Badge(
                        modifier = Modifier
                            .align(Alignment.TopEnd),
                        containerColor = MaterialTheme.colorScheme.error
                    ) {
                        Text("!")
                    }
                }
            }

            // Main UI content aligned to bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                GoslingUI(context = LocalContext.current)
            }
        }
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val goslingColors = LocalGoslingColors.current
    val settingsManager = remember { SettingsManager(context) }
    val activity = context as ComponentActivity

    var isAccessibilityEnabled by remember { mutableStateOf(settingsManager.isAccessibilityEnabled) }
    var mmcpResult by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Gosling Assistant Setup",
            style = MaterialTheme.typography.headlineMedium
        )

        // Accessibility Permission Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = goslingColors.inputBackground
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Accessibility Permissions",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Allow Gosling to interact with other apps",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        openAccessibilitySettings(context)
                        isAccessibilityEnabled = settingsManager.isAccessibilityEnabled
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAccessibilityEnabled) goslingColors.secondaryButton else goslingColors.primaryBackground
                    )
                ) {
                    Text(if (isAccessibilityEnabled) "Accessibility Enabled" else "Enable Accessibility")
                }
            }
        }

        // Gosling UI
        if (isAccessibilityEnabled) {
            // Test mMCP Button
            Button(
                onClick = {
                    val intent = Intent("com.example.mMCP.ACTION_TOOL_CALL").apply {
                        setPackage(context.packageName)
                        putExtra("tool_name", "hello_world")
                        putExtra("parameters", "{}")
                    }
                    activity.startActivityForResult(intent, 1001)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Test mMCP Hello World")
            }
            
            mmcpResult?.let {
                Text("mMCP Result: $it")
            }

            GoslingUI(context = context)
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

fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    context.startActivity(intent)
}
