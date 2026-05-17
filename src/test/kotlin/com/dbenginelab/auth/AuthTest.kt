package com.dbenginelab.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthTest {
    @Test fun `authenticate 성공·실패`() {
        val am = AuthManager()
        am.addUser("alice", "secret", setOf("reader"))
        assertTrue(am.authenticate("alice", "secret"))
        assertFalse(am.authenticate("alice", "wrong"))
        assertFalse(am.authenticate("bob", "secret"))
    }

    @Test fun `Role privilege`() {
        val am = AuthManager()
        am.addRole(Role("reader", setOf(Privilege.SELECT)))
        am.addRole(Role("writer", setOf(Privilege.SELECT, Privilege.INSERT)))
        am.addUser("a", "p", setOf("reader"))
        am.addUser("b", "p", setOf("writer"))
        assertTrue(am.hasPrivilege("a", Privilege.SELECT))
        assertFalse(am.hasPrivilege("a", Privilege.INSERT))
        assertTrue(am.hasPrivilege("b", Privilege.INSERT))
    }
}
