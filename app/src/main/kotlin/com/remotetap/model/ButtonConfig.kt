package com.remotetap.model

import android.graphics.Rect

data class ButtonConfig(
    val packageName: String = "",
    val viewId: String = "",           // e.g. "com.example.app:id/submit_btn"
    val text: String = "",             // visible text label, if any
    val contentDescription: String = "", // accessibility label, if any
    val boundsInScreen: SerializableRect = SerializableRect(),
    val className: String = ""         // e.g. "android.widget.Button"
) {
    fun isEmpty() = packageName.isEmpty()

    fun hasUniqueIdentifier() = viewId.isNotEmpty() || text.isNotEmpty() || contentDescription.isNotEmpty()
}

data class SerializableRect(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
) {
    fun centerX() = (left + right) / 2
    fun centerY() = (top + bottom) / 2

    fun toRect() = Rect(left, top, right, bottom)
}
