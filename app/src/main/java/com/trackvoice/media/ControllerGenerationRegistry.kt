package com.trackvoice.media

/**
 * Main-looper-confined identity for a registered [android.media.session.MediaController.Callback].
 *
 * A framework session token can outlive a particular MediaController instance.  Android can also
 * deliver one final callback after unregisterCallback returns.  The session key alone therefore
 * cannot establish that a callback still belongs to the controller currently tracked by the
 * monitor.
 */
internal class ControllerGenerationRegistry {
    private var nextGeneration = 0L
    private val activeGenerations = mutableMapOf<String, Long>()

    fun attach(sessionKey: String): Long {
        val generation = ++nextGeneration
        activeGenerations[sessionKey] = generation
        return generation
    }

    fun isCurrent(sessionKey: String, generation: Long): Boolean =
        activeGenerations[sessionKey] == generation

    /**
     * Invalidating an earlier generation must not detach a replacement that reused the same
     * framework session key.
     */
    fun invalidate(sessionKey: String, generation: Long): Boolean {
        if (!isCurrent(sessionKey, generation)) return false
        activeGenerations.remove(sessionKey)
        return true
    }

    fun currentGeneration(sessionKey: String): Long? = activeGenerations[sessionKey]

    fun invalidateAll() {
        activeGenerations.clear()
    }
}
