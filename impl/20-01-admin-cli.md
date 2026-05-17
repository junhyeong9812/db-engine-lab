# impl/20-01 — Admin CLI (한 줄 한 줄)

> **검증**: AdminCliTest 1 PASSED.
> 작성 파일:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/admin/`
> - 신규: AdminCli.kt
> - 신규 테스트: AdminCliTest.kt

## 0. 참조
PostgreSQL psql / MySQL CLI.

## 1. invariant
- 정확 명령 → 정확 동작.
- 인자 부족 → 명확 에러.
- 알 수 없는 명령 → 'unknown command'.

## 2. AdminCli.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.admin                                        // 신규 admin 패키지

import com.dbenginelab.auth.AuthManager
import com.dbenginelab.auth.Privilege
import com.dbenginelab.catalog.Catalog
import com.dbenginelab.metrics.MetricsRegistry

class AdminCli(
    private val catalog: Catalog,                                    // 단계 4
    private val auth: AuthManager,                                   // 단계 15
    private val metrics: MetricsRegistry,                            // 단계 17
) {
    fun execute(command: String): String {
        // Q: 왜 단순 space split? proper shell quoting 안 함?
        val parts = command.trim().split(Regex("\\s+"))
        return when (parts.firstOrNull()?.lowercase()) {
            "list-tables" -> catalog.listTables().joinToString("\n")
            "show-metrics" -> metrics.snapshot().toSortedMap().entries
                .joinToString("\n") { "${it.key}=${it.value}" }
            "create-user" -> {
                require(parts.size == 4) { "usage: create-user <name> <password> <role>" }
                auth.addUser(parts[1], parts[2], setOf(parts[3]))
                "user ${parts[1]} created"
            }
            "grant-check" -> {
                require(parts.size == 3) { "usage: grant-check <user> <privilege>" }
                val p = Privilege.valueOf(parts[2].uppercase())     // enum 이름 변환
                if (auth.hasPrivilege(parts[1], p)) "yes" else "no"
            }
            "help" -> "commands: list-tables, show-metrics, create-user, grant-check, help"
            else -> "unknown command. try 'help'"
        }
        // <details><summary>A</summary>
        // 학습 단순화 — 따옴표 포함 인자 ('Hello World') 처리 안 함. 진짜 CLI는 shell-quote 라이브러리.
        // </details>
    }
}
```

## 3. 검증 (1 PASSED)
- 모든 명령 검증

## 4. 깨뜨릴 과제
- password 평문 노출 — TTY hidden input?
- BACKUP 명령 추가 (LogicalBackup 통합)?
- 권한 없는 user의 admin 명령 호출 — auth 통합?
