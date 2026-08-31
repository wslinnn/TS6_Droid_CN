package dev.tsdroid.data

import android.content.Context
import android.util.Log
import dev.tsdroid.viewmodel.ChatMessage
import dev.tsdroid.viewmodel.FileAttachment
import java.io.File

class MessageStore(private val context: Context) {

    companion object {
        private const val TAG = "MessageStore"
        private const val MAX_MESSAGES = 500
    }

    private val messagesDir = File(context.filesDir, "messages")

    fun load(serverAddress: String): Pair<List<ChatMessage>, Map<Int, List<ChatMessage>>> {
        val file = fileFor(serverAddress)
        if (!file.exists()) return Pair(emptyList(), emptyMap())
        return try {
            val json = file.readText()
            parseServerMessages(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load messages for $serverAddress", e)
            Pair(emptyList(), emptyMap())
        }
    }

    fun save(
        serverAddress: String,
        channelMessages: List<ChatMessage>,
        privateMessages: Map<Int, List<ChatMessage>>,
    ) {
        try {
            messagesDir.mkdirs()
            val json = serializeServerMessages(channelMessages, privateMessages)
            fileFor(serverAddress).writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save messages for $serverAddress", e)
        }
    }

    private fun fileFor(serverAddress: String): File {
        return File(messagesDir, sanitizeFilename(serverAddress) + ".json")
    }

    private fun sanitizeFilename(address: String): String {
        return address.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    // --- Serialization ---

    private fun serializeServerMessages(
        channel: List<ChatMessage>,
        private_: Map<Int, List<ChatMessage>>,
    ): String {
        val root = org.json.JSONObject()
        val channelArr = org.json.JSONArray()
        channel.takeLast(MAX_MESSAGES).forEach { channelArr.put(toJson(it)) }
        root.put("channel", channelArr)
        val privateObj = org.json.JSONObject()
        for ((userId, msgs) in private_) {
            val arr = org.json.JSONArray()
            msgs.takeLast(MAX_MESSAGES).forEach { arr.put(toJson(it)) }
            privateObj.put(userId.toString(), arr)
        }
        root.put("private", privateObj)
        return root.toString()
    }

    private fun toJson(msg: ChatMessage): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("s", msg.sender)
            put("t", msg.text)
            put("ts", msg.timestamp)
            put("me", msg.isMe)
            put("sid", msg.senderId)
            msg.fileAttachment?.let { fa ->
                put(
                    "fa",
                    org.json.JSONObject().apply {
                        put("fn", fa.fileName)
                        put("fs", fa.fileSize)
                        put("fi", fa.fileId)
                        put("im", fa.isImage)
                        put("ch", fa.channelId)
                    }
                )
            }
        }
    }

    // --- Parsing ---

    private fun parseServerMessages(json: String): Pair<List<ChatMessage>, Map<Int, List<ChatMessage>>> {
        if (json.isBlank()) return Pair(emptyList(), emptyMap())
        return try {
            val root = org.json.JSONObject(json)
            val channelMessages = mutableListOf<ChatMessage>()
            root.optJSONArray("channel")?.let { arr ->
                for (i in 0 until arr.length()) {
                    fromJson(arr.getJSONObject(i), isPrivate = false)?.let { channelMessages.add(it) }
                }
            }
            val privateMessages = mutableMapOf<Int, List<ChatMessage>>()
            root.optJSONObject("private")?.let { obj ->
                for (key in obj.keys()) {
                    val userId = key.toIntOrNull() ?: continue
                    val arr = obj.optJSONArray(key) ?: continue
                    val msgs = mutableListOf<ChatMessage>()
                    for (i in 0 until arr.length()) {
                        fromJson(arr.getJSONObject(i), isPrivate = true)?.let { msgs.add(it) }
                    }
                    privateMessages[userId] = msgs
                }
            }
            Pair(channelMessages, privateMessages)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message history", e)
            Pair(emptyList(), emptyMap())
        }
    }

    private fun fromJson(o: org.json.JSONObject, isPrivate: Boolean): ChatMessage? {
        return try {
            ChatMessage(
                sender = o.getString("s"),
                text = o.getString("t"),
                timestamp = o.optLong("ts", System.currentTimeMillis()),
                isMe = o.optBoolean("me", false),
                isPrivate = isPrivate,
                senderId = o.optInt("sid", 0),
                fileAttachment = o.optJSONObject("fa")?.let { fa ->
                    FileAttachment(
                        fileName = fa.getString("fn"),
                        fileSize = fa.optLong("fs", 0),
                        fileId = fa.optString("fi"),
                        isImage = fa.optBoolean("im", false),
                        channelId = fa.optLong("ch", 0),
                    )
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse message: ${e.message}")
            null
        }
    }
}
