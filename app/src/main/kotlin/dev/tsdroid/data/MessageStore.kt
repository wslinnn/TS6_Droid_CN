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

    /** Persisted chat history plus the unread counters that go with it.
     *  Private conversations are keyed by peer uid (or synthetic "clid:<n>"). */
    data class StoredMessages(
        val channelMessages: List<ChatMessage> = emptyList(),
        val privateMessages: Map<String, List<ChatMessage>> = emptyMap(),
        val unreadChannel: Int = 0,
        val unreadPrivate: Map<String, Int> = emptyMap(),
    )

    fun load(serverAddress: String): StoredMessages {
        val file = fileFor(serverAddress)
        if (!file.exists()) return StoredMessages()
        return try {
            parseServerMessages(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load messages for $serverAddress", e)
            StoredMessages()
        }
    }

    fun save(
        serverAddress: String,
        channelMessages: List<ChatMessage>,
        privateMessages: Map<String, List<ChatMessage>>,
        unreadChannel: Int,
        unreadPrivate: Map<String, Int>,
    ) {
        try {
            messagesDir.mkdirs()
            val json = serializeServerMessages(channelMessages, privateMessages, unreadChannel, unreadPrivate)
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
        private_: Map<String, List<ChatMessage>>,
        unreadChannel: Int,
        unreadPrivate: Map<String, Int>,
    ): String {
        val root = org.json.JSONObject()
        val channelArr = org.json.JSONArray()
        channel.takeLast(MAX_MESSAGES).forEach { channelArr.put(toJson(it)) }
        root.put("channel", channelArr)
        val privateObj = org.json.JSONObject()
        for ((convKey, msgs) in private_) {
            val arr = org.json.JSONArray()
            msgs.takeLast(MAX_MESSAGES).forEach { arr.put(toJson(it)) }
            privateObj.put(convKey, arr)
        }
        root.put("private", privateObj)
        // Unread counts are persisted alongside the messages so a reconnect
        // restores the badges exactly as they were left
        root.put("unreadChannel", unreadChannel)
        val unreadObj = org.json.JSONObject()
        for ((convKey, count) in unreadPrivate) {
            unreadObj.put(convKey, count)
        }
        root.put("unreadPrivate", unreadObj)
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

    private fun parseServerMessages(json: String): StoredMessages {
        if (json.isBlank()) return StoredMessages()
        return try {
            val root = org.json.JSONObject(json)
            val channelMessages = mutableListOf<ChatMessage>()
            root.optJSONArray("channel")?.let { arr ->
                for (i in 0 until arr.length()) {
                    fromJson(arr.getJSONObject(i), isPrivate = false)?.let { channelMessages.add(it) }
                }
            }
            val privateMessages = mutableMapOf<String, List<ChatMessage>>()
            root.optJSONObject("private")?.let { obj ->
                for (key in obj.keys()) {
                    val arr = obj.optJSONArray(key) ?: continue
                    val msgs = mutableListOf<ChatMessage>()
                    for (i in 0 until arr.length()) {
                        fromJson(arr.getJSONObject(i), isPrivate = true)?.let { msgs.add(it) }
                    }
                    privateMessages[migrateConvKey(key)] = msgs
                }
            }
            val unreadPrivate = mutableMapOf<String, Int>()
            root.optJSONObject("unreadPrivate")?.let { obj ->
                for (key in obj.keys()) {
                    val count = obj.optInt(key, 0)
                    if (count > 0) unreadPrivate[migrateConvKey(key)] = count
                }
            }
            StoredMessages(
                channelMessages = channelMessages,
                privateMessages = privateMessages,
                unreadChannel = root.optInt("unreadChannel", 0),
                unreadPrivate = unreadPrivate,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message history", e)
            StoredMessages()
        }
    }

    /** Old format keyed private chats by session client id (a bare number);
     *  new format keys by peer uid or a synthetic "clid:<n>". */
    private fun migrateConvKey(key: String): String {
        return key.toIntOrNull()?.let { "clid:$it" } ?: key
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
