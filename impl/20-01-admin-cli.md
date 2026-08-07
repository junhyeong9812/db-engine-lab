# impl/20-01 — Admin CLI

> **종류**: 세션형
> **상위 단계**: `docs/stages/20-admin-cli.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 운영자가 손으로 치는 명령 — 테이블 목록, 통계 갱신, 백업 등.
> **작성 파일**:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/admin/`
> - 신규: `admin/AdminCli.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/admin/AdminCliTest.kt`
> **검증**: `AdminCliTest` 1 PASSED
> **예상 타이핑 시간**: 25분

---

## 0. 참조

- `psql`의 백슬래시 명령(`\dt`, `\d`) 개념.
- **핵심 설계 결정 근거**: 관리 명령을 `sealed class`로 둘 만한 자리다 — 명령 집합은 닫혀 있고, 새 명령이 생기면 디스패처의 `when`이 컴파일 에러로 알려주기 때문이다(12-01의 AST, 14-01의 `Message`와 같은 이유). **다만 정본 코드는 그렇게 하지 않았다** — 문자열 `when`으로 디스패치한다. 그 선택의 대가는 §7 과제 3번에서 다룬다.

## 1. 만족시킬 invariant

- **CI-1**: 각 관리 명령이 대응하는 엔진 동작을 정확히 호출한다.

## 2. 의존성

- `impl/14-00-db-engine.md` (`DbEngine`), 단계 16·17의 백업·메트릭

## 3. 문제 정의 (TDD step 1)

DB의 기능은 다 있는데 **운영자가 그것을 부를 방법이 코드뿐이다.** 새벽에 장애가 나서 "지금 테이블이 몇 개고 통계가 언제 갱신됐는지" 보려면 프로그램을 짜야 한다면, 그 DB는 운영할 수 없다.

CLI가 하는 일은 새로운 기능을 만드는 것이 아니라 **이미 있는 기능에 손잡이를 다는 것**이다. 그래서 이 세션의 코드는 대부분 디스패치다 — 문자열을 명령으로 파싱하고, 명령에 맞는 메서드를 부른다.

그럼에도 결정할 것이 있다: **어디까지 CLI로 노출할 것인가.** 위험한 명령(DROP, restore)을 노출하면 편하지만 사고도 쉬워진다. 확인 절차 없이 파괴적 명령을 실행하는 CLI가 실제 사고의 흔한 원인이다.

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/admin/AdminCliTest.kt @ 5505edc
package com.dbenginelab.admin

import com.dbenginelab.auth.AuthManager
import com.dbenginelab.auth.Privilege
import com.dbenginelab.auth.Role
import com.dbenginelab.catalog.Catalog
import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Type
import com.dbenginelab.metrics.MetricsRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminCliTest {
    @Test fun `admin commands`(@TempDir tempDir: Path) {
        val catalog = Catalog(tempDir.resolve("c.meta").toString())
        catalog.registerTable(TableSchema("users", listOf(ColumnDef("id", Type.BIGINT, nullable = false))))
        val auth = AuthManager().apply { addRole(Role("admin", setOf(Privilege.SELECT, Privilege.INSERT))) }
        val metrics = MetricsRegistry().apply { incCounter("q", 5) }
        val cli = AdminCli(catalog, auth, metrics)
        assertEquals("users", cli.execute("list-tables"))
        assertTrue(cli.execute("show-metrics").contains("counter.q=5"))
        assertTrue(cli.execute("create-user alice pw admin").contains("created"))
        assertEquals("yes", cli.execute("grant-check alice SELECT"))
        assertEquals("no", cli.execute("grant-check alice DROP"))
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: AdminCli`.

## 5. 구현 코드 (TDD step 3 — make it pass)

```kotlin
// src/main/kotlin/com/dbenginelab/admin/AdminCli.kt @ 5505edc
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
        // Q: 왜 단순 space split? proper shell quoting 안 함?
        val parts = command.trim().split(Regex("\\s+"))
        // <details><summary>A</summary>
        // 학습 단순화 — 따옴표 포함 인자 ('Hello World') 처리 안 함. 진짜 CLI는 shell-quote 라이브러리.
        // </details>
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
```

## 6. 검증 테스트 (TDD step 4 — green)

```bash
./gradlew test --tests 'com.dbenginelab.admin.AdminCliTest'
```

**기대 결과**: `AdminCliTest` **1 PASSED** (`admin commands`)

**테스트 하나가 여러 명령을 한꺼번에 검사한다.** 명령이 늘어나면 이 테스트 하나가 계속 커질 텐데, 그게 좋은 구조인지 §7 과제 4번에서 판단해봐라.

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** 알 수 없는 명령을 넣어봐라. 도움말인가, 예외인가, 침묵인가? CLI에서는 어느 쪽이 옳은가?

<details><summary>답</summary>

코드를 직접 확인해봐라 — 대개 `error(...)`나 알 수 없는 명령 메시지가 나온다. **침묵이 나온다면 그게 최악이다.**

CLI에서의 원칙:

| 동작 | 평가 |
|---|---|
| **침묵** | 최악. 사용자는 명령이 실행됐다고 믿는다. 스크립트에서는 **실패를 성공으로 착각**한다 |
| **예외 스택트레이스** | 나쁨. 사용자 실수인데 프로그램 버그처럼 보인다 |
| **에러 메시지 + 사용 가능한 명령 목록 + 0이 아닌 종료 코드** | 옳음 |

**종료 코드가 특히 중요하다.** CLI는 스크립트에서 호출되므로 `$?`로 성공/실패를 판단할 수 있어야 한다. 메시지만 예쁘게 찍고 0으로 종료하면 `set -e`가 걸린 스크립트도 그냥 진행한다.

한 걸음 더 — **오타 제안**(`did you mean 'analyze'?`)까지 있으면 좋다. 편집 거리(Levenshtein) 계산 몇 줄이면 되고, 사용성 차이가 크다. `git`이 이걸 잘한다.
</details>

**2.** 파괴적 명령을 추가한다면 확인 절차를 어디에 둘 것인가? 스크립트로 자동화할 때 어느 쪽이 문제가 되나?

<details><summary>답</summary>

**확인은 CLI(사람과 맞닿는 층)에 두어야 한다.** 엔진에 두면 안 된다.

이유 — 엔진은 **비대화형 호출자**도 갖는다. `ProtocolHandler`를 통해 네트워크로 온 질의, 다른 서비스의 API 호출. 엔진이 "정말 실행할까요?"를 물으면 그쪽에서는 **응답할 사람이 없어 멈춰버린다.**

**스크립트 자동화에서 생기는 문제**가 이 결정의 핵심이다:

```bash
admin-cli drop-table old_logs        # 확인을 기다리며 영원히 멈춤
```

cron이나 CI에서 돌면 입력이 없으므로 **무한 대기하거나 EOF로 이상하게 종료**한다. 그래서 표준 해법이 정해져 있다:

1. **대화형인지 감지** — 표준 입력이 터미널이면 묻고, 파이프면 안 묻는다.
2. **`--yes` / `--force` 플래그** — 명시적으로 확인을 건너뛰게 한다. **자동화하는 사람이 의도를 밝히는 것**이 요점이다.
3. **비대화형인데 플래그가 없으면 실행하지 않고 실패** — 조용히 실행해버리는 것보다 안전하다.

`rm -i`, `kubectl delete --force`, `terraform apply -auto-approve`가 전부 이 패턴이다.

**"위험한 일은 의도를 한 번 더 표현하게 한다"** 가 원칙이고, 그 표현이 사람에게는 프롬프트, 스크립트에는 플래그다.
</details>

**3.** 지금 코드는 문자열 `when`으로 디스패치한다. 이것을 `sealed class Command`로 바꿔보고, 새 명령을 추가할 때 무엇이 달라지는지 비교해봐라.

<details><summary>답</summary>

**현재 코드(문자열 `when`)** 는 `else` 가지가 반드시 있어야 하고, 그 순간 컴파일러의 도움이 끝난다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
when (cmd) {
    "list" -> …
    "analyze" -> …
    else -> error("unknown command")     // ← 새 명령이 여기로 조용히 흘러간다
}
```

실제로 정본에는 `else -> "unknown command. try 'help'"` 가 있다. 새 명령 `backup`을 도움말 문자열에는 추가하고 **`when` 가지에 넣는 것을 잊으면**, 컴파일은 통과하고 실행 시 "unknown command"가 난다. 사용자는 "help에는 있는데 안 되네?"를 겪는다.

`sealed class`로 바꾸면 다르다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
when (cmd) {
    is Command.List -> …
    is Command.Analyze -> …
    // 새 Command.Backup을 추가하는 순간 → 컴파일 에러
}
```

**빠뜨릴 수가 없다.** 13-02 과제 4번의 `SortNode`, 12-01 과제 5번의 `ORDER BY`에서 본 것과 정확히 같은 성질이다.

이 프로젝트에서 sealed가 **여섯 번** 나왔다 — `StorageError`(01-01), `Constraint`(05-01), `Expression`(06-02), `LogRecord`(08-01), `Message`(14-01), `Command`(20-01). 기준은 매번 같았다: **집합이 닫혀 있고, 새 항목이 생기면 그것을 다루는 모든 곳이 알아야 하는가.**

반대로 `Type`(04-01)은 enum이었다 — 타입이 늘어도 `when` 하나만 고치면 되고, 오히려 확장을 열어두고 싶었기 때문이다.
</details>

**4.** 명령이 20개가 되면 지금의 테스트 1개는 어떻게 되나? 나누는 것과 하나로 두는 것 중 어느 쪽이 나은가?

<details><summary>답</summary>

지금은 `admin commands` 하나가 여러 명령을 순차로 확인한다. 20개가 되면 그 테스트 하나가 **20개의 단언 묶음**이 된다.

**실패했을 때 원인을 얼마나 빨리 아는가**로 판단하면 답이 나온다:

| | 실패 시 정보 |
|---|---|
| 테스트 1개 | "admin commands 실패" — 20개 중 무엇인지 모른다. 게다가 **첫 실패에서 멈추므로** 뒤의 19개는 아예 실행조차 안 된다 |
| 명령마다 1개 | "analyze 명령 실패" — 즉시 특정. 나머지 19개는 정상임을 동시에 확인 |

**첫 실패에서 멈춘다**는 점이 결정적이다. 명령 3개가 동시에 깨졌는데 하나만 보고되면, 고치고 다시 돌리고를 세 번 반복해야 한다.

다만 무조건 쪼개는 것도 아니다. **"한 테스트 = 한 가지 이유로만 실패한다"** 가 기준이다. 여러 단언이 **같은 이유로 함께 실패할 성질**이면 묶어도 된다(예: 하나의 명령에 대한 여러 확인).

이 기준으로 보면 이 프로젝트의 다른 테스트들도 다시 볼 만하다 — 예를 들어 `DbEngineTest`의 `end-to-end CREATE INSERT SELECT WHERE PROJECT` 하나가 다섯 가지를 검사한다. 통합 테스트라 의도적이지만, **실패하면 원인 특정에 시간이 든다**는 대가를 치른다. 13-02 §4에서 짚은 것과 같은 문제다.
</details>

## 8. 다음 한계

단일 노드 DB로서는 여기서 기능이 거의 다 찼다. 남은 것은 **한 대로 감당할 수 없을 때** — 데이터를 여러 노드로 나누는 문제다.

→ **단계 21 Sharding** (capstone).
