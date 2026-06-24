package com.remotetap.model

data class NotificationEvent(
    val packageName: String = "",
    val appName: String = "",
    val title: String = "",
    val text: String = "",
    val timestampMs: Long = 0L
)
