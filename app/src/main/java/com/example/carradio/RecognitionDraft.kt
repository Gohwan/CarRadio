package com.example.carradio

/** Protects confirmed or edited text from a late intermediate recognition callback. */
internal class RecognitionDraft {
    var text: String = ""
        private set
    var completed: Boolean = false
        private set
    private var edited = false

    fun accept(value: String, final: Boolean) {
        if (edited || (completed && !final) || value.isBlank()) return
        text = value.trim().take(200)
        completed = final
    }

    fun edit(value: String) {
        text = value.take(200)
        edited = true
    }

    fun forSave(): String? = text.trim().takeIf { it.isNotEmpty() }
}
