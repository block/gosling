package xyz.block.gosling.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import xyz.block.gosling.features.agent.AiModel
import xyz.block.gosling.features.settings.SettingsStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LLMConfigStep(
    settingsStore: SettingsStore,
    onComplete: () -> Unit
) {
    var llmModel by remember { mutableStateOf(settingsStore.llmModel) }
    var currentModel by remember { mutableStateOf(AiModel.fromIdentifier(llmModel)) }
    var apiKey by remember { mutableStateOf(settingsStore.getApiKey(currentModel.provider)) }
    var expanded by remember { mutableStateOf(false) }

    val models = AiModel.AVAILABLE_MODELS.map {
        it.identifier to it.displayName
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "Model")
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
                                        currentModel = AiModel.fromIdentifier(modelId)
                                        apiKey = settingsStore.getApiKey(currentModel.provider)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "API Key")
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
            }
        }

        Button(
            onClick = {
                settingsStore.llmModel = llmModel
                settingsStore.setApiKey(currentModel.provider, apiKey)
                onComplete()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .navigationBarsPadding()
                .imePadding(),
            enabled = llmModel.isNotEmpty() && apiKey.isNotEmpty(),
        ) {
            Text("Complete Setup")
        }
    }
}
