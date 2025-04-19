package xyz.block.gosling.features.app

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.block.gosling.features.agent.Conversation
import xyz.block.gosling.features.agent.firstImage
import xyz.block.gosling.features.agent.firstText
import xyz.block.gosling.features.agent.getConversationTitle
import xyz.block.gosling.features.agent.getCurrentAssistantMessage

@Composable
private fun FullscreenImageDialog(
    imageBitmap: ImageBitmap,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onDismiss() }
                    )
                }
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = "Fullscreen image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close fullscreen image",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val seconds = durationMs / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}

private fun decodeBase64Image(base64Url: String): ImageBitmap? {
    return try {
        val base64Data = base64Url.substringAfter("base64,")
        val imageBytes = Base64.decode(base64Data, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        bitmap?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

@Composable
fun ConversationCard(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isCurrentConversation: Boolean = false
) {
    // Get the first user message to check for image
    val userMessage = conversation.messages.firstOrNull { it.role == "user" }
    val imageContent = userMessage?.let { firstImage(it) }
    var showFullscreenImage by remember { mutableStateOf(false) }

    // Decode the image if present
    val imageBitmap = remember(imageContent?.imageUrl?.url) {
        imageContent?.imageUrl?.url?.let { decodeBase64Image(it) }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentConversation) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .height(350.dp),  // Make the card taller
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),  // Fill the available height
                verticalArrangement = Arrangement.spacedBy(8.dp)  // Increased spacing
            ) {
                Text(
                    text = getConversationTitle(conversation),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isCurrentConversation)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                getCurrentAssistantMessage(conversation)?.let { message ->
                    val messageText = firstText(message)
                    if (messageText.isNotBlank() && messageText != "<empty>") {
                        Text(
                            text = messageText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrentConversation)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 5,  // Increased from 3 to 5 lines
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = DateUtils.getRelativeTimeSpanString(
                            conversation.startTime,
                            System.currentTimeMillis(),
                            DateUtils.SECOND_IN_MILLIS,
                            DateUtils.FORMAT_ABBREV_RELATIVE
                        ).toString() + (conversation.endTime?.let { endTime ->
                            val duration = endTime - conversation.startTime
                            " (${formatDuration(duration)})"
                        } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isCurrentConversation)
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                    if (conversation.endTime == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = if (isCurrentConversation)
                                MaterialTheme.colorScheme.onPrimaryContainer
                            else
                                MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            imageBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "Conversation image",
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { showFullscreenImage = true }
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "View conversation history",
                tint = if (isCurrentConversation)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            )
        }
    }

    if (showFullscreenImage && imageBitmap != null) {
        FullscreenImageDialog(
            imageBitmap = imageBitmap,
            onDismiss = { showFullscreenImage = false }
        )
    }
}
