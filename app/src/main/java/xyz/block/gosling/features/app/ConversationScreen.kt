package xyz.block.gosling.features.app

import android.text.format.DateUtils
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import xyz.block.gosling.features.agent.AgentServiceManager
import xyz.block.gosling.features.agent.Content
import xyz.block.gosling.features.agent.Conversation
import xyz.block.gosling.features.agent.Message
import xyz.block.gosling.features.agent.firstText
import xyz.block.gosling.features.agent.getConversationTitle

@Composable
private fun MessageCard(message: Message, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (message.role == "user") 32.dp else 0.dp,
                end = if (message.role != "user") 32.dp else 0.dp
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (message.role == "user")
                MaterialTheme.colorScheme.surfaceContainer
            else
                MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            SelectionContainer {
                Text(
                    text = firstText(message),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (message.role == "user")
                        MaterialTheme.colorScheme.onSecondaryContainer
                    else
                        MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            //Text(text = message.role) Douwe: Do we need this?
            Text(
                text = DateUtils.getRelativeTimeSpanString(
                    message.time,
                    System.currentTimeMillis(),
                    DateUtils.SECOND_IN_MILLIS,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_RELATIVE
                ).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (message.role == "user")
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    conversationId: String,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    var conversation by remember { mutableStateOf<Conversation?>(null) }
    val scope = rememberCoroutineScope()
    val agentServiceManager = remember { AgentServiceManager(context) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var agent by remember { mutableStateOf<xyz.block.gosling.features.agent.Agent?>(null) }

    LaunchedEffect(conversationId) {
        agentServiceManager.bindAndStartAgent { boundAgent ->
            agent = boundAgent
            scope.launch(Dispatchers.Main) {
                boundAgent.conversationManager.conversations.collect { conversations ->
                    conversation = conversations.find { it.id == conversationId }
                    // We don't set this as the current conversation anymore
                    // Just display the conversation details
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            agentServiceManager.unbindAgent()
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete Conversation") },
            text = { Text("Are you sure you want to delete this conversation? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        agent?.let { boundAgent ->
                            scope.launch {
                                boundAgent.conversationManager.deleteConversation(conversationId)
                                showDeleteConfirmation = false
                                onNavigateBack()
                            }
                        } ?: run {
                            Log.e("ConversationScreen", "Agent is null, cannot delete conversation")
                        }
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                Button(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = conversation?.let { conv ->
                            "History: ${getConversationTitle(conv)}"
                        } ?: "Loading history...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showDeleteConfirmation = true }
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete conversation"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (conversation == null) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "Loading conversation...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                val filteredMessages = remember(conversation?.messages) {
                    conversation?.messages?.filter { message ->
                        (message.role == "user" || message.role == "assistant") &&
                                message.content != null &&
                                message.content.any { it is Content.Text && it.text != "null" }
                    } ?: emptyList()
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    reverseLayout = true
                ) {
                    items(filteredMessages.asReversed()) { message ->
                        MessageCard(message = message)
                    }
                }
            }
        }
    }
} 
