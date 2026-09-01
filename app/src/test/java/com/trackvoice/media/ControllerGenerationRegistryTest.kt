package com.trackvoice.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerGenerationRegistryTest {
    @Test
    fun staleControllerCallbackCannotInvalidateReplacementWithSameSessionKey() {
        val registry = ControllerGenerationRegistry()
        val sessionKey = "com.example.player:42"

        val controllerA = registry.attach(sessionKey)
        val controllerB = registry.attach(sessionKey)

        assertFalse(registry.isCurrent(sessionKey, controllerA))
        assertFalse(registry.invalidate(sessionKey, controllerA))
        assertTrue(registry.isCurrent(sessionKey, controllerB))
    }

    @Test
    fun currentControllerInvalidationMakesSubsequentCallbackStale() {
        val registry = ControllerGenerationRegistry()
        val sessionKey = "com.example.player:42"
        val generation = registry.attach(sessionKey)

        assertTrue(registry.invalidate(sessionKey, generation))
        assertFalse(registry.isCurrent(sessionKey, generation))
    }

    @Test
    fun callbackFromBeforeMonitorRestartCannotBecomeCurrentAgain() {
        val registry = ControllerGenerationRegistry()
        val sessionKey = "com.example.player:42"
        val beforeRestart = registry.attach(sessionKey)

        registry.invalidateAll()
        val afterRestart = registry.attach(sessionKey)

        assertFalse(registry.isCurrent(sessionKey, beforeRestart))
        assertTrue(registry.isCurrent(sessionKey, afterRestart))
    }
}
