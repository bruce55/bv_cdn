package dev.frost819.newbv.app.ui.navigation

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteBackKeyGuardTest {
    @Test
    fun `player key-down navigation consumes the same press key-up`() {
        val guard = RemoteBackKeyGuard()
        guard.onDown("player")
        assertTrue(guard.consumeUp("detail"))
        assertFalse(guard.consumeUp("detail"))
    }

    @Test
    fun `closing a menu and normal system back still receive key-up`() {
        val guard = RemoteBackKeyGuard()
        guard.onDown("player")
        assertFalse(guard.consumeUp("player"))
        guard.onDown("detail")
        assertFalse(guard.consumeUp("detail"))
    }

    @Test
    fun `held-key repeats do not erase the original destination`() {
        val guard = RemoteBackKeyGuard()
        guard.onDown("player")
        guard.onDown("detail")
        assertTrue(guard.consumeUp("detail"))
        guard.onDown("detail")
        assertFalse(guard.consumeUp("detail"))
    }
}
