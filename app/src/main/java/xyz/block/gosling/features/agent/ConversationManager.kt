package xyz.block.gosling.features.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.lang.ref.WeakReference

// we will not bother with really old conversations
const val OLDEST_CONVERSATION_MS = 1 * 60 * 60 * 1000

class ConversationManager(context: Context) {
    private val contextRef = WeakReference(context)
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _currentConversation = MutableStateFlow<Conversation?>(null)
    val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()


    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    init {
        loadConversations()
    }

    fun recentConversations(): List<Conversation> {
        val currentTimeMillis = System.currentTimeMillis()
        val latest = currentTimeMillis - (1000 * 60 * 60) // 1 hour

        return _conversations.value
            .filter { it.startTime >= latest }
            .sortedByDescending { it.startTime }
    }



    private fun loadConversations() {
        contextRef.get()?.getExternalFilesDir(null)?.let { filesDir ->
            val conversationsDir = File(filesDir, "session_dumps")
            if (conversationsDir.exists()) {
                val conversations = conversationsDir.listFiles()
                    ?.filter { it.extension == "json" }
                    ?.mapNotNull { file ->
                        try {
                            json.decodeFromString<Conversation>(file.readText())
                        } catch (e: Exception) {
                            null
                        }
                    }
                    ?.filter { it.startTime > System.currentTimeMillis() - OLDEST_CONVERSATION_MS }
                    ?.sortedByDescending { it.startTime } ?: emptyList()

                _conversations.value = conversations
                
                // Set the most recent conversation as the current conversation if it exists
                if (conversations.isNotEmpty()) {
                    _currentConversation.value = conversations.first()
                }
            }
        }
    }

    fun updateCurrentConversation(conversation: Conversation) {
        _currentConversation.update { conversation }

        _conversations.update { conversations ->
            val updatedList = if (conversations.any { it.id == conversation.id }) {
                conversations.map { if (it.id == conversation.id) conversation else it }
            } else {
                conversations + conversation
            }
            updatedList.sortedByDescending { it.startTime }
        }

        saveConversation(conversation)
    }
    
    fun setCurrentConversation(conversationId: String) {
        val conversation = _conversations.value.find { it.id == conversationId }
        conversation?.let { updateCurrentConversation(it) }
    }

    private fun saveConversation(conversation: Conversation) {
        contextRef.get()?.getExternalFilesDir(null)?.let { filesDir ->
            val conversationsDir = File(filesDir, "session_dumps")
            conversationsDir.mkdirs()
            val file = File(conversationsDir, conversation.fileName)
            file.writeText(json.encodeToString(conversation))
        }
    }

    fun clearConversations() {
        contextRef.get()?.getExternalFilesDir(null)?.let { filesDir ->
            val conversationsDir = File(filesDir, "session_dumps")
            if (conversationsDir.exists()) {
                conversationsDir.deleteRecursively()
                conversationsDir.mkdirs()
            }
        }
        _conversations.value = emptyList()
        _currentConversation.value = null
    }

    fun deleteConversation(conversationId: String) {
        contextRef.get()?.getExternalFilesDir(null)?.let { filesDir ->
            val conversationsDir = File(filesDir, "session_dumps")
            val conversationFile = File(conversationsDir, "$conversationId.json")
            if (conversationFile.exists()) {
                conversationFile.delete()
            }
        }

        _conversations.update { conversations ->
            conversations.filterNot { it.id == conversationId }
        }

        if (_currentConversation.value?.id == conversationId) {
            _currentConversation.value = null
        }
    }

    fun fileNameFor(userInput: String): String {
        val sanitizedCommand = userInput
            .replace(Regex("[^a-zA-Z0-9]"), "_")
            .lowercase().take(50)
        var idx = 0

        val directory = File(contextRef.get()?.getExternalFilesDir(null), "session_dumps")
        val existingFiles = directory.listFiles()?.toList() ?: emptyList()

        while (true) {
            val formattedIdx = idx.toString().padStart(4, '0')
            val fileName = "$formattedIdx-$sanitizedCommand.json"

            if (!existingFiles.any {
                    it.name.startsWith(formattedIdx)
                }) {
                return fileName
            }
            idx++
        }
    }
}
