package com.dbenginelab.admin

import com.dbenginelab.auth.AuthManager
import com.dbenginelab.auth.Privilege
import com.dbenginelab.catalog.Catalog
import com.dbenginelab.metrics.MetricsRegistry

class AdminCli(
    private val catalog: Catalog,
    private val auth: AuthManager,
    private val metrics: MetricsRegistry,
) {
    fun execute(command: String): String {
        val parts = command.trim().split(Regex("\\s+"))
        return when (parts.firstOrNull()?.lowercase()) {
            "list-tables" -> catalog.listTables().joinToString("\n")
            "show-metrics" -> metrics.snapshot().toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
            "create-user" -> {
                require(parts.size == 4) { "usage: create-user <name> <password> <role>" }
                auth.addUser(parts[1], parts[2], setOf(parts[3]))
                "user ${parts[1]} created"
            }
            "grant-check" -> {
                require(parts.size == 3) { "usage: grant-check <user> <privilege>" }
                val p = Privilege.valueOf(parts[2].uppercase())
                if (auth.hasPrivilege(parts[1], p)) "yes" else "no"
            }
            "help" -> "commands: list-tables, show-metrics, create-user, grant-check, help"
            else -> "unknown command. try 'help'"
        }
    }
}
