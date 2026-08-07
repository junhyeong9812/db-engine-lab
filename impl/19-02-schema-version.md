# impl/19-02 — SchemaVersionLog (보강 X3)

> **종류**: 보강형
> **상위 단계**: `docs/stages/19-online-ddl.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 스키마 변경 이력을 남긴다 — 무엇을 언제 바꿨는가.
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/catalog/SchemaVersion.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/catalog/SchemaVersionTest.kt`
> **검증**: `SchemaVersionTest` 2 PASSED
> **예상 타이핑 시간**: 25분

---

## 0. 보강 동기

codex 지적 X3: 19-01로 스키마를 바꿀 수 있게 됐는데 **이력이 없다.** 지금 스키마가 몇 번째 버전인지, 어떤 변경이 언제 적용됐는지 알 방법이 없다. 실제 운영에서 이건 사고 조사 자체를 불가능하게 만든다 — "어제까지 잘 되던 게 왜 안 되지"에 답하려면 무엇이 바뀌었는지 알아야 한다.

## 1. 만족시킬 invariant

- **CI-1**: 스키마 변경이 순번과 함께 기록되고, reopen 후에도 남는다.
- **CI-2**: 변경이 하나도 없으면 버전은 0이다.

## 2. 의존성

- `impl/19-01-online-ddl.md` (`OnlineDdl` — 기록할 사건을 만드는 쪽)

## 3. 문제 정의

스키마 변경 이력은 **append-only 로그**다. 단계 8의 WAL과 같은 모양이고, 실은 같은 이유로 그렇게 만든다 — 순서가 의미를 갖고, 과거를 고칠 일이 없다.

버전 번호는 그 로그의 길이다. 레코드가 N개면 버전 N. 그래서 CI-2(비어 있으면 0)가 자연스럽게 따라온다.

이 로그가 나중에 무엇을 가능하게 하는지 생각해봐라 — 복제에서 replica가 "나는 스키마 버전 5까지 반영했다"고 말할 수 있고, 백업에서 "이 덤프는 버전 7 시점"이라고 적을 수 있다. **버전 번호 하나가 여러 계층의 대화를 가능하게 한다.**

## 4. 구현 코드

```kotlin
// src/main/kotlin/com/dbenginelab/catalog/SchemaVersion.kt @ 5505edc
package com.dbenginelab.catalog

/**
 * Stage 19 보강 (X3): Schema version tracking.
 *
 * 각 TableSchema의 변경 이력을 별도 파일에 누적. OnlineDdl이 schema 변경 시
 * append. Recovery / migration 시 version 비교로 호환성 판단.
 *
 * Format (텍스트):
 *   v1 2026-05-16 12:00:00 CREATE TABLE users (...)
 *   v2 2026-05-16 13:00:00 ADD COLUMN age INT
 */
data class SchemaChange(
    val version: Int,
    val timestamp: Long,
    val description: String,
)

class SchemaVersionLog(private val path: String) {
    private val changes: MutableList<SchemaChange> = mutableListOf()

    init {
        load()
    }

    fun currentVersion(): Int = changes.lastOrNull()?.version ?: 0

    fun record(description: String): SchemaChange {
        val change = SchemaChange(
            version = currentVersion() + 1,
            timestamp = System.currentTimeMillis(),
            description = description,
        )
        changes.add(change)
        appendLine(change)
        return change
    }

    fun history(): List<SchemaChange> = changes.toList()

    private fun load() {
        val file = java.io.File(path)
        if (!file.exists()) return
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val parts = line.split('\t', limit = 3)
            require(parts.size == 3) { "malformed schema version log line: $line" }
            changes.add(SchemaChange(
                version = parts[0].toInt(),
                timestamp = parts[1].toLong(),
                description = parts[2],
            ))
        }
    }

    private fun appendLine(change: SchemaChange) {
        val file = java.io.File(path)
        file.parentFile?.mkdirs()
        file.appendText("${change.version}\t${change.timestamp}\t${change.description}\n")
    }
}
```

## 5. 검증 테스트 (green)

```kotlin
// src/test/kotlin/com/dbenginelab/catalog/SchemaVersionTest.kt @ 5505edc
package com.dbenginelab.catalog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class SchemaVersionTest {

    @Test
    fun `record schema changes and reopen`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("schema.log").toString()
        SchemaVersionLog(path).apply {
            record("CREATE TABLE users (id BIGINT NOT NULL)")
            record("ADD COLUMN name STRING")
        }
        val reopened = SchemaVersionLog(path)
        val history = reopened.history()
        assertEquals(2, history.size)
        assertEquals(1, history[0].version)
        assertEquals(2, history[1].version)
        assertEquals(2, reopened.currentVersion())
    }

    @Test
    fun `empty log version 0`(@TempDir tempDir: Path) {
        val log = SchemaVersionLog(tempDir.resolve("s.log").toString())
        assertEquals(0, log.currentVersion())
    }
}
```

```bash
./gradlew test --tests 'com.dbenginelab.catalog.SchemaVersionTest'
```

**기대 결과**: `SchemaVersionTest` **2 PASSED**

invariant 대응:
- **CI-1** ← `record schema changes and reopen`
- **CI-2** ← `empty log version 0`

## 6. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** 로그를 지우고 catalog만 남겨봐라. 스키마는 최신인데 버전은 0이다 — 이 불일치가 어떤 오판을 만드나?

<details><summary>답</summary>

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
fun currentVersion(): Int = changes.lastOrNull()?.version ?: 0
private fun load() {
    if (!file.exists()) return       // 파일 없으면 조용히 빈 목록
}
```

**에러 없이 "버전 0"이라고 답한다.** 스키마에는 컬럼이 열 개 추가되어 있는데도.

이 값을 믿는 쪽에서 오판이 생긴다:

- **복제** — replica가 "나는 버전 5"라고 하고 primary가 "나는 버전 0"이라고 하면, 감독 로직은 **replica가 앞서 있다**고 판단한다. 실제로는 primary가 최신인데. 잘못된 방향으로 동기화를 시도하거나 승격을 막는다.
- **백업/마이그레이션** — "이 덤프는 버전 7 시점"이라고 적힌 백업을 버전 0인 DB에 복원하려 하면, 도구는 **7단계의 마이그레이션을 적용해야 한다**고 판단한다. 이미 적용된 변경을 다시 적용하면 `ADD COLUMN`이 중복 컬럼 에러를 내거나, 더 나쁘게는 데이터를 변형한다.

**"모른다"와 "0이다"를 구분하지 못하는 것**이 근본 문제다. 파일이 없을 때 `0`을 돌려주는 대신 예외를 던지거나 `null`을 돌려주면 호출자가 판단할 여지가 생긴다. 04-01의 NULL bitmap, 14-01의 NULL 표현과 같은 주제가 또 나온다 — **"없음"과 "0"은 다른 값이다.**
</details>

**2.** 반대로 로그만 남기고 catalog를 지우면? 둘 중 어느 쪽이 **단일 출처**여야 하나?

<details><summary>답</summary>

catalog를 지우면 **테이블이 통째로 사라진다.** 로그에는 "ADD COLUMN age INT"라는 **설명 문자열**만 있고 실제 스키마 구조가 없기 때문이다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
data class SchemaChange(val version: Int, val timestamp: Long, val description: String)
//                                                              ↑ 사람이 읽는 문자열일 뿐
```

이 로그로는 스키마를 재구성할 수 없다.

**단일 출처는 catalog여야 한다.** 이유:

1. **읽기 경로가 catalog에 의존한다.** 매 질의가 스키마를 필요로 하는데, 로그를 재생해서 스키마를 만들려면 매번 전체 이력을 훑어야 한다.
2. **로그는 감사·진단용이다.** "언제 무엇을 바꿨나"에 답하는 것이 목적이지 현재 상태를 담는 것이 아니다.

다만 **로그가 재구성 가능하게 설계된 시스템도 있다** — 이벤트 소싱(event sourcing)이 그것이고, 그때는 로그가 단일 출처가 되고 현재 상태는 파생물(projection)이 된다. 08-01 WAL이 그 방향에 가깝다 — 데이터 파일을 잃어도 로그만 있으면 재구성된다.

**차이는 "로그가 상태를 완전히 담는가"** 다. WAL은 담고(`InsertRow`가 tuple 바이트를 통째로 갖는다), 이 스키마 로그는 안 담는다(설명 문자열뿐). 그래서 역할이 갈린다.
</details>

**3.** 변경을 되돌리는(rollback) 기능을 넣으려면 로그에 무엇이 더 필요한가?

<details><summary>답</summary>

**"무엇이었는지"(before image)** 가 필요하다. 지금은 "무엇을 했다"만 있다.

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
// 지금
SchemaChange(version = 2, description = "ADD COLUMN age INT")

// 되돌리려면 최소한
SchemaChange(
    version = 2,
    forward = "ADD COLUMN age INT",
    backward = "DROP COLUMN age",          // 역연산
)
// 또는 아예 전후 스키마 스냅샷
```

역연산을 적는 방식이 흔하고(Rails·Flyway의 `up`/`down` 마이그레이션), 그 한계도 분명하다 — **모든 연산에 역연산이 있는 것은 아니다.**

```
ADD COLUMN     → DROP COLUMN        ✓ 되돌릴 수 있다
DROP COLUMN    → ADD COLUMN?        ✗ 데이터가 이미 사라졌다
INT → VARCHAR  → VARCHAR → INT?     ✗ 변환 중 잃은 정보를 복구 못 한다
```

**정보를 버리는 연산은 되돌릴 수 없다.** 그래서 실무의 마이그레이션 규칙이 "파괴적 변경은 두 단계로 나눈다"가 된다 — 먼저 컬럼을 안 쓰게 배포하고, 충분히 지난 뒤 별도 배포로 지운다. 그 사이에는 언제든 되돌릴 수 있다.

08-01의 redo-only 설계(§2)와 같은 문제다 — **undo를 하려면 옛 값을 갖고 있어야 한다.** 계층이 달라도 제약은 같다.
</details>

**4.** `OnlineDdl`이 스키마를 바꾸는데 이 로그에 기록하는 것을 빼먹으면? 불가능하게 만들려면?

<details><summary>답</summary>

**빼먹는 것이 가능하다.** 두 클래스가 서로를 모른다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
OnlineDdl.addColumn(...)      // catalog를 바꾼다
SchemaVersionLog.record(...)  // 별도로 호출해야 한다
```

호출자가 둘을 순서대로 부르는 규약인데, **규약은 코드로 강제되지 않는다.** 새 DDL 연산(`DROP COLUMN` 등)을 추가하는 사람이 두 번째 호출을 잊으면 이력에 구멍이 뚫린다. 그리고 **아무도 눈치채지 못한다** — 로그가 없다는 것은 조용한 상태다.

불가능하게 만드는 방법:

1. **`OnlineDdl`이 `SchemaVersionLog`를 필수 생성자 인자로 받는다.**
   ```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
   class OnlineDdl(private val catalog: Catalog, private val versionLog: SchemaVersionLog)
   ```
   그리고 `addColumn` 안에서 catalog 갱신과 기록을 **함께** 한다. 밖에서 따로 부를 일이 없어진다.
2. **더 강하게 — `Catalog`가 스키마 변경을 받을 때 기록한다.** 그러면 어떤 경로로 스키마가 바뀌든 이력이 남는다.

2번이 더 안전하지만 `Catalog`의 책임이 늘어난다. 1번은 "DDL은 반드시 `OnlineDdl`을 거친다"는 전제가 필요하다.

**규약을 문서에 적는 대신 타입으로 강제하라** — 이 프로젝트에서 반복되는 주제다. 06-03의 `ConstraintValidator` 분리, 09-02의 `TransactionWithLock`이 자동으로 락을 잡게 한 것이 전부 같은 발상이다. **사람이 기억해야 하는 것은 언젠가 잊힌다.**
</details>

## 7. 다음 한계

DB의 기능은 거의 다 있는데 **운영자가 쓸 도구가 없다.** 테이블 목록을 보고, 통계를 갱신하고, 백업을 뜨는 일을 전부 코드로 해야 한다.

→ **단계 20 Admin CLI**.
