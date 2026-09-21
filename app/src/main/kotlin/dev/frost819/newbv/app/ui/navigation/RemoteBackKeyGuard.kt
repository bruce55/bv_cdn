package dev.frost819.newbv.app.ui.navigation

/** Prevents one physical Back press from popping twice when a child navigates on key-down. */
internal class RemoteBackKeyGuard {
    private var pressed = false
    private var entryAtDown: String? = null

    /** Records the destination before children handle Back; held-key repeats keep the first ID. */
    fun onDown(entryId: String?) {
        if (!pressed) {
            pressed = true
            entryAtDown = entryId
        }
    }

    /** Consumes only the matching key-up after navigation already handled this physical press. */
    fun consumeUp(entryId: String?): Boolean {
        val handled = pressed && entryAtDown != entryId
        pressed = false
        entryAtDown = null
        return handled
    }
}
