package com.remotetap.model

data class Command(
    val id: String = "",
    val type: CommandType = CommandType.PRESS,
    val timestampMs: Long = 0L,
    val senderId: String = ""
)

enum class CommandType {
    PRESS
}

data class CommandResult(
    val commandId: String = "",
    val success: Boolean = false,
    val errorMessage: String = "",
    val timestampMs: Long = 0L
)
