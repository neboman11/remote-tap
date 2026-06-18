package com.remotetap.repository

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.remotetap.model.Command
import com.remotetap.model.CommandResult
import com.remotetap.model.CommandType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

// ntfy topic naming:
//   remotetap-{pairingCode}-cmd    — client publishes, server subscribes
//   remotetap-{pairingCode}-result — server publishes, client subscribes

private val JSON = "application/json".toMediaType()

class CommandRepository(
    private val serverUrl: String,
    private val accessToken: String,
    private val pairingCode: String
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout on streaming connections
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val cmdTopic = "remotetap-$pairingCode-cmd"
    private val resultTopic = "remotetap-$pairingCode-result"

    suspend fun sendCommand(): String = withContext(Dispatchers.IO) {
        val commandId = UUID.randomUUID().toString()
        val command = Command(
            id = commandId,
            type = CommandType.PRESS,
            timestampMs = System.currentTimeMillis()
        )
        val request = Request.Builder()
            .url("$serverUrl/$cmdTopic")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(gson.toJson(command).toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("ntfy publish failed: ${response.code}")
        }
        commandId
    }

    /**
     * Streams incoming commands from the ntfy cmd topic.
     * Runs indefinitely — caller is responsible for cancellation via coroutine scope.
     * On connection drop, the flow ends; caller should restart with retry logic.
     */
    fun observeIncomingCommands(): Flow<Command> = flow {
        val request = Request.Builder()
            .url("$serverUrl/$cmdTopic/json")
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            val source = response.body?.source() ?: return@use
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val event = line.parseNtfyEvent() ?: continue
                if (event.event != "message") continue
                val command = runCatching { gson.fromJson(event.message, Command::class.java) }.getOrNull()
                if (command != null) emit(command)
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun acknowledgeCommand(commandId: String, success: Boolean, errorMessage: String = "") {
        withContext(Dispatchers.IO) {
            val result = CommandResult(
                commandId = commandId,
                success = success,
                errorMessage = errorMessage,
                timestampMs = System.currentTimeMillis()
            )
            val request = Request.Builder()
                .url("$serverUrl/$resultTopic")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(gson.toJson(result).toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("ntfy ack failed: ${response.code}")
            }
        }
    }

    /**
     * Subscribes to the result topic and returns the first result matching [commandId].
     * Pass [sinceMs] as the timestamp just before sending the command to avoid missing
     * results that arrive before the subscription is established (ntfy caches messages).
     */
    fun observeResult(commandId: String, sinceMs: Long): Flow<CommandResult> = flow {
        // since= is in Unix seconds; subtract a small buffer for clock skew
        val sinceSeconds = (sinceMs / 1000 - 5).coerceAtLeast(0)
        val request = Request.Builder()
            .url("$serverUrl/$resultTopic/json?since=$sinceSeconds")
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            val source = response.body?.source() ?: return@use
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val event = line.parseNtfyEvent() ?: continue
                if (event.event != "message") continue
                val result = runCatching { gson.fromJson(event.message, CommandResult::class.java) }.getOrNull()
                if (result?.commandId == commandId) {
                    emit(result)
                    return@flow
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Verifies credentials by hitting the ntfy health endpoint.
     * Returns null on success, or an error message string.
     */
    suspend fun testConnection(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$serverUrl/v1/health")
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) "Server returned ${response.code}" else null
            }
        }.getOrElse { it.message ?: "Connection failed" }
    }

    private fun String.parseNtfyEvent(): NtfyEvent? = try {
        gson.fromJson(this, NtfyEvent::class.java)
    } catch (e: JsonSyntaxException) {
        null
    }
}

private data class NtfyEvent(
    val id: String = "",
    val time: Long = 0,
    val event: String = "",
    val topic: String = "",
    val message: String = ""
)
