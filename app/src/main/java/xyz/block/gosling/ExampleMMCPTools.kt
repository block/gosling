package xyz.block.gosling

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import org.json.JSONObject

class ExampleMMCPTools : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        when (intent?.action) {
            "com.example.mMCP.ACTION_TOOL_CALL" -> handleToolCall(intent)
        }
    }
    
    private fun handleToolCall(intent: Intent) {
        val toolName = intent.getStringExtra("tool_name")
        if (toolName == "hello_world") {
            val resultJson = JSONObject().apply {
                put("message", "hello mmcp example")
            }.toString()
            
            val resultIntent = Intent().apply {
                putExtra("result", resultJson)
            }
            setResult(Activity.RESULT_OK, resultIntent)
        } else {
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }
}