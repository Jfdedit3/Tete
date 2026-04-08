package com.jfdedit3.tete

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface

class ClipboardBridge(
    context: Context,
) {
    private val clipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @JavascriptInterface
    fun readText(): String {
        val clip = clipboardManager.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(null)?.toString().orEmpty()
    }

    @JavascriptInterface
    fun writeText(text: String?) {
        val safeText = text.orEmpty()
        clipboardManager.setPrimaryClip(
            ClipData.newPlainText("tete_clipboard", safeText),
        )
    }

    @JavascriptInterface
    fun hasText(): Boolean {
        val clip = clipboardManager.primaryClip ?: return false
        if (clip.itemCount == 0) return false
        return clip.getItemAt(0).coerceToText(null)?.isNotEmpty() == true
    }
}
