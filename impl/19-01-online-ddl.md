# impl/19-01 — Online DDL (ADD COLUMN)

> **종류**: 세션형
> **상위 단계**: `docs/stages/19-online-ddl.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 기존 데이터를 다시 쓰지 않고 컬럼을 추가한다 — **메타데이터만 바꾸는 DDL.**
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/catalog/OnlineDdl.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/catalog/OnlineDdlTest.kt`
> **검증**: `OnlineDdlTest` 2 PASSED
> **예상 타이핑 시간**: 25분

---

## 0. 참조

- PostgreSQL 11+ 의 "fast default" ADD COLUMN, MySQL의 online DDL.

## 1. 만족시킬 invariant

- **CI-1**: `ADD COLUMN`은 스키마만 바꾸고 기존 행을 다시 쓰지 않는다.
- **CI-2**: NOT NULL 컬럼 추가는 **거부한다** (기존 행에 넣을 값이 없으므로).

## 2. 의존성

- `impl/04-01-catalog.md`, `impl/05-01-constraints.md` (`TableSchema`, `Catalog`)

## 3. 문제 정의 (TDD step 1)

`ALTER TABLE users ADD COLUMN nickname STRING`을 순진하게 구현하면 이렇게 된다: 테이블을 잠그고 → 모든 행을 읽어 → 컬럼을 붙여 다시 쓰고 → 잠금을 푼다. 1억 행이면 **몇 시간 동안 서비스가 멈춘다.**

핵심 통찰은 이것이다 — **기존 행을 안 고쳐도 된다.** 컬럼 개수가 늘었다는 사실만 스키마에 적어두고, 옛 행을 읽을 때 "이 행은 컬럼이 3개뿐이니 4번째는 NULL"로 해석하면 된다. **데이터가 아니라 해석 규칙을 바꾸는 것이다.**

그러면 CI-2가 왜 필요한지 바로 보인다. 새 컬럼이 NOT NULL이면 옛 행들의 그 자리에 무엇을 넣을 것인가? NULL로 해석할 수 없으니 **결국 전부 다시 써야 한다.** 그래서 거부한다. (실제 DB는 여기서 DEFAULT 값을 요구하고, 그 값을 메타데이터에 적어 같은 트릭을 이어간다.)

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/catalog/OnlineDdlTest.kt @ 5505edc
package com.dbenginelab.catalog

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class OnlineDdlTest {
    @Test fun `ADD COLUMN`(@TempDir tempDir: Path) {
        val catalog = Catalog(tempDir.resolve("c.meta").toString())
        catalog.registerTable(TableSchema("t", listOf(ColumnDef("id", Type.BIGINT, nullable = false))))
        val newSchema = OnlineDdl.addColumn(catalog, "t", ColumnDef("note", Type.STRING, nullable = true))
        assertEquals(2, newSchema.columnCount)
    }
    @Test fun `NOT NULL 추가 거부`(@TempDir tempDir: Path) {
        val catalog = Catalog(tempDir.resolve("c.meta").toString())
        catalog.registerTable(TableSchema("t", listOf(ColumnDef("id", Type.BIGINT, nullable = false))))
        assertThrows<IllegalArgumentException> {
            OnlineDdl.addColumn(catalog, "t", ColumnDef("x", Type.INT, nullable = false))
        }
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: OnlineDdl`.

## 5. 구현 코드 (TDD step 3 — make it pass)

```kotlin
// src/main/kotlin/com/dbenginelab/catalog/OnlineDdl.kt @ 5505edc
package com.dbenginelab.catalog

object OnlineDdl {
    fun addColumn(catalog: Catalog, tableName: String, newColumn: ColumnDef): TableSchema {
        // Q: 왜 nullable만 허용?
        require(newColumn.nullable) { "ADD COLUMN online supports nullable columns only" }
        // <details><summary>A</summary>
        // NOT NULL 추가는 기존 row를 채울 DEFAULT 값 필요. backfill 단계 별도. nullable이면 옛 row 자동 null로 읽힘 (Tuple.decode가 bitmap 처리).
        // </details>
        val current = catalog.getTable(tableName)
        val newSchema = current.copy(columns = current.columns + newColumn)
        // Q: dropTable + registerTable — 왜 두 단계?
        catalog.dropTable(tableName)
        // <details><summary>A</summary>
        // 단순화 — Catalog API가 update 없음. drop + register가 같은 효과. multi-thread 시 race (학습용 OK).
        // </details>
        catalog.registerTable(newSchema)
        return newSchema
    }
}
```

## 6. 검증 테스트 (TDD step 4 — green)

```bash
./gradlew test --tests 'com.dbenginelab.catalog.OnlineDdlTest'
```

**기대 결과**: `OnlineDdlTest` **2 PASSED**

invariant 대응:
- **CI-1** ← `ADD COLUMN`
- **CI-2** ← `NOT NULL 추가 거부`

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** 컬럼을 추가한 뒤 **옛 행을 읽어봐라.** 새 컬럼 자리에 무엇이 나오나? 그 값은 어디서 나온 것인가?

<details><summary>답</summary>

**`null`이 나온다. 그리고 그 값은 디스크에 없다 — 코드가 만들어낸 것이다.**

옛 행의 바이트에는 컬럼 3개분만 들어 있다. NULL bitmap도 3비트분이다. 그 바이트를 새 스키마(컬럼 4개)로 해석할 때 4번째 값을 읽을 자리가 없으니 **"없으면 null"** 로 채운다.

즉 `ADD COLUMN`이 하는 일은 **데이터를 바꾸는 것이 아니라 해석 규칙을 바꾸는 것**이다. 디스크는 1바이트도 안 움직인다. 이것이 이 세션의 전부다.

**확인 방법**: 컬럼 추가 전후로 데이터 파일의 크기와 수정 시각을 비교해봐라. 안 바뀐다.

실제 DB도 같은 원리다. PostgreSQL 11부터 `ADD COLUMN ... DEFAULT x`가 순식간에 끝나는 이유가 이것이다 — **기본값을 메타데이터에 적어두고**, 옛 행을 읽을 때 그 자리를 만나면 저장된 기본값을 돌려준다. 10 이전에는 전체 테이블을 다시 썼고, 큰 테이블에서 몇 시간씩 걸렸다.
</details>

**2.** NOT NULL 거부를 지우고 NOT NULL 컬럼을 추가해봐라. 옛 행을 읽으면 무슨 일이 일어나나?

<details><summary>답</summary>

`NOT NULL 추가 거부` 테스트가 먼저 실패한다(`assertThrows`가 안 터지므로).

그 뒤 옛 행을 읽으면 **`Tuple`의 `init` 검사**에서 터진다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
init {
    require(values.size == schema.columnCount)
    for ((i, col) in schema.columns.withIndex()) {
        val v = values[i]
        if (v == null) require(col.nullable) { "column ${col.name} NOT NULL but null" }   // ← 여기
```

디코딩은 4번째 값을 `null`로 채웠는데 스키마는 "이 컬럼은 NOT NULL"이라고 한다. **저장된 데이터가 자기 스키마를 위반하는 상태**다.

무서운 점은 **DDL 시점에는 아무 일도 안 일어난다는 것**이다. 스키마만 바꿨으니 성공한다. 터지는 것은 **나중에 그 테이블을 읽을 때**이고, 그때는 원인(며칠 전의 ALTER)과 증상(SELECT 실패)이 멀리 떨어져 있다.

그래서 이 검사는 **DDL 시점에 있어야 한다** — 스키마를 바꾸기 전에 "이 변경이 기존 데이터와 모순되는가"를 확인하는 것이다. 05-01의 "PK 컬럼은 NOT NULL을 스키마 생성 시점에 막는다"와 같은 발상이고, **모순을 만들 수 없게 하는 것**이 나중에 발견하는 것보다 항상 싸다.

(실제 DB는 `DEFAULT`를 함께 요구해서 이 문제를 푼다. 기본값이 있으면 옛 행의 빈자리를 그 값으로 채워 읽을 수 있으므로 NOT NULL이 성립한다.)
</details>

**3.** `DROP COLUMN`을 같은 방식(메타데이터만)으로 할 수 있나? 디스크의 그 바이트들은 언제 정리되나?

<details><summary>답</summary>

**할 수 있다.** 스키마에서 컬럼을 빼면 읽을 때 그 자리를 건너뛰면 된다.

다만 **가운데 컬럼을 빼면 곤란해진다.** 우리 인코딩은 컬럼 순서대로 값을 나열하므로, 2번 컬럼을 빼면 옛 행의 3·4번 값이 새 스키마의 2·3번으로 밀린다 — **자리가 어긋난다.**

실제 DB의 해법은 **컬럼을 진짜로 빼지 않는 것**이다. PostgreSQL은 `pg_attribute`에 `attisdropped = true`만 표시하고 컬럼 자리는 그대로 둔다. 그래서:

- 조회 결과에는 안 나온다
- 디스크의 바이트는 **그대로 남는다**
- 그 자리는 재사용되지 않는다(`........pg.dropped.2........` 같은 유령 이름이 남는다)

**언제 정리되나 — 그 행이 다시 쓰일 때다.** MVCC DB에서 `UPDATE`는 새 버전을 만들고, 그 새 버전은 새 스키마로 인코딩된다. 옛 버전이 VACUUM으로 회수되면 그때 바이트가 사라진다. 즉 **행이 갱신되기 전까지는 영원히 남는다.**

한 번에 정리하려면 `VACUUM FULL`이나 테이블 재작성(`ALTER TABLE ... REWRITE`)이 필요하고, 그건 곧 이 세션이 피하려던 **전체 테이블 다시 쓰기**다. **공짜로 지운 것처럼 보이지만 비용을 미룬 것**이다.
</details>

**4.** 컬럼 **타입 변경**(INT → BIGINT)은 메타데이터만으로 되나?

<details><summary>답</summary>

**안 된다.** 인코딩 크기가 다르기 때문이다.

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
INT    -> buffer.putInt(value as Int)      // 4바이트
BIGINT -> buffer.putLong(value as Long)    // 8바이트
```

옛 행에는 그 컬럼이 4바이트로 들어 있는데 새 스키마는 8바이트를 읽으려 한다. **4바이트를 더 읽어버리므로 그 뒤 모든 컬럼의 위치가 밀린다.** 04-01 과제 1번(NULL bitmap 제거)과 같은 종류의 붕괴다.

그래서 타입 변경은 대개 **전체 테이블 재작성**이 필요하다. 모든 행을 읽어 새 형식으로 다시 쓴다.

**예외가 있다** — 표현이 호환되는 경우다. PostgreSQL에서 `VARCHAR(50) → VARCHAR(100)`은 즉시 끝난다. 길이 제한은 검사 규칙일 뿐 저장 형식이 같기 때문이다. 반대로 `VARCHAR(100) → VARCHAR(50)`은 **기존 데이터 검사**가 필요하다(50자를 넘는 행이 있는지).

정리하면 세 등급이다:

| 변경 | 비용 |
|---|---|
| 저장 형식 동일, 제약만 완화 | 메타데이터만 — 즉시 |
| 저장 형식 동일, 제약 강화 | 전수 검사 필요 — 읽기만 |
| 저장 형식 변경 | 전체 재작성 — 가장 비쌈 |

DDL의 소요 시간을 예측하려면 **"저장 형식이 바뀌는가"** 를 먼저 물어야 한다.
</details>

**5.** DDL 도중 프로세스가 죽으면? catalog는 새 스키마, 데이터는 옛 형식 — 이 상태가 안전한가?

<details><summary>답</summary>

**안전하다.** 그리고 그 이유가 이 설계의 핵심이다.

`ADD COLUMN`이 바꾸는 것은 **catalog 파일 하나뿐**이다. 데이터 파일은 건드리지 않는다. 그러니 "중간 상태"라는 것이 존재하지 않는다:

```
catalog 저장 전에 죽음 → 옛 스키마. 아무 일도 없었던 것과 같다
catalog 저장 후에 죽음 → 새 스키마. 옛 행은 새 컬럼이 null로 읽힌다 — 정상 동작
```

**두 결과 모두 일관된 상태**다. 원자성이 필요한 이유는 "여러 곳을 바꿔야 하는데 일부만 바뀌는 것"인데, 바꿀 곳이 **한 곳뿐**이면 그 문제 자체가 없다.

정확히 말하면 `Catalog.save()`가 원자적이어야 한다는 조건이 남는다 — 파일을 덮어쓰는 도중에 죽으면 catalog가 깨진다. 지금 구현은 전체를 다시 쓰므로 **그 창이 열려 있다.** 제대로 하려면 임시 파일에 쓰고 `rename`하는 원자적 교체가 필요하다(POSIX에서 `rename`은 원자적이다).

**대비**: 만약 이 DDL이 데이터까지 다시 썼다면 03-02 과제 4번(split 도중 crash)과 같은 문제가 생긴다 — 절반은 새 형식, 절반은 옛 형식인 테이블. 그걸 복구하려면 WAL이 필요하다.

**"바꿀 곳을 하나로 줄이면 원자성 문제가 사라진다"** — 분산 트랜잭션을 피하는 설계 원칙과 같은 발상이다.
</details>

## 8. 다음 한계

컬럼을 추가할 수 있게 됐지만 **언제 무엇을 바꿨는지 기록이 없다.** 스키마가 지금 몇 번째 버전인지, 어제와 무엇이 다른지 알 수 없다.

→ **19-02 SchemaVersionLog**.
