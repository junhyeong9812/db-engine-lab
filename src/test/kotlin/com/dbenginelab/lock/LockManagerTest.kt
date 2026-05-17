package com.dbenginelab.lock

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockManagerTest {

    @Test fun `여러 tx SHARED 동시 보유 가능`() {
        val lm = LockManager()
        lm.acquire(1, "t", LockManager.Mode.SHARED)
        lm.acquire(2, "t", LockManager.Mode.SHARED)
        lm.acquire(3, "t", LockManager.Mode.SHARED)
        assertEquals(3, lm.holderCount("t"))
    }

    @Test fun `EXCLUSIVE는 다른 tx SHARED와 충돌`() {
        val lm = LockManager()
        lm.acquire(1, "t", LockManager.Mode.SHARED)
        assertThrows<LockConflict> { lm.acquire(2, "t", LockManager.Mode.EXCLUSIVE) }
    }

    @Test fun `EXCLUSIVE 보유 중 다른 tx SHARED 충돌`() {
        val lm = LockManager()
        lm.acquire(1, "t", LockManager.Mode.EXCLUSIVE)
        assertThrows<LockConflict> { lm.acquire(2, "t", LockManager.Mode.SHARED) }
    }

    @Test fun `같은 tx S to X 업그레이드 단독시 OK`() {
        val lm = LockManager()
        lm.acquire(1, "t", LockManager.Mode.SHARED)
        lm.acquire(1, "t", LockManager.Mode.EXCLUSIVE)
        assertEquals(1, lm.holderCount("t"))
        assertTrue(lm.isHeld(1, "t"))
    }

    @Test fun `업그레이드시 다른 tx SHARED 보유면 충돌`() {
        val lm = LockManager()
        lm.acquire(1, "t", LockManager.Mode.SHARED)
        lm.acquire(2, "t", LockManager.Mode.SHARED)
        assertThrows<LockConflict> { lm.acquire(1, "t", LockManager.Mode.EXCLUSIVE) }
    }

    @Test fun `releaseAll 후 다른 tx EXCLUSIVE 가능`() {
        val lm = LockManager()
        lm.acquire(1, "t", LockManager.Mode.SHARED)
        lm.acquire(2, "t", LockManager.Mode.SHARED)
        lm.releaseAll(1); lm.releaseAll(2)
        lm.acquire(3, "t", LockManager.Mode.EXCLUSIVE)
        assertEquals(1, lm.holderCount("t"))
        assertFalse(lm.isHeld(1, "t"))
        assertTrue(lm.isHeld(3, "t"))
    }

    @Test fun `같은 tx 같은 SHARED 두 번 idempotent`() {
        val lm = LockManager()
        lm.acquire(1, "t", LockManager.Mode.SHARED)
        lm.acquire(1, "t", LockManager.Mode.SHARED)
        assertEquals(1, lm.holderCount("t"))
    }
}
