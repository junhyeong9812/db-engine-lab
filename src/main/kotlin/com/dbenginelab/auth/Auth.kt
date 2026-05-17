package com.dbenginelab.auth

import java.security.MessageDigest

enum class Privilege { SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, GRANT }

data class Role(val name: String, val privileges: Set<Privilege>)

data class User(val name: String, val passwordHash: String, val roles: Set<String>)

object PasswordHasher {
    fun hash(plain: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(plain.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

class AuthManager {
    private val users: MutableMap<String, User> = mutableMapOf()
    private val roles: MutableMap<String, Role> = mutableMapOf()

    fun addRole(role: Role) { roles[role.name] = role }
    fun addUser(name: String, plainPassword: String, roleNames: Set<String>) {
        users[name] = User(name, PasswordHasher.hash(plainPassword), roleNames)
    }
    fun authenticate(name: String, plainPassword: String): Boolean {
        val u = users[name] ?: return false
        return u.passwordHash == PasswordHasher.hash(plainPassword)
    }
    fun hasPrivilege(userName: String, p: Privilege): Boolean {
        val u = users[userName] ?: return false
        return u.roles.any { roles[it]?.privileges?.contains(p) ?: false }
    }
}
