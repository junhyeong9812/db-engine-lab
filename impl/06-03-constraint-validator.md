# impl/06-03 — ConstraintValidator (PK / Unique / FK at insert)

> **종류**: 세션형
> **상위 단계**: `docs/stages/06-query-api.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 단계 5에서 **선언만** 해둔 제약을 insert 시점에 실제로 강제한다. 방식은 풀스캔 O(N) — 인덱스 활용은 06-04·단계 11.
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/table/ConstraintValidator.kt` (`ConstraintViolation` 예외 포함)
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/table/ConstraintValidatorTest.kt`
> **검증**: `ConstraintValidatorTest` 3 PASSED
> **예상 타이핑 시간**: 40분

---

## 0. 참조

- **참조 부재**: SimpleDB·BusTub 모두 constraint 검사가 약하다. PostgreSQL의 검사 패턴을 개념 참조로 삼되 코드는 자체 설계.
- **핵심 설계 결정 근거 — 검증을 어디에 둘 것인가**:
  - (a) `TableHeap.insert` 안에서 → heap이 제약을 알아야 하고, 다른 테이블(FK)까지 알아야 한다.
  - (b) **`ConstraintValidator`를 분리** → heap은 저장만, 검증은 호출자 책임.
  - **(b) 채택.** 이유는 단순성만이 아니다. 단계 7의 `WorkUnit`이 **commit 시점에 일괄 검증**하려면 검증이 insert와 분리되어 있어야 한다. 07-01의 `commit(validators)` 시그니처가 이 결정의 결과다.

## 1. 만족시킬 invariant

- **CI-1**: PK 중복 insert는 `ConstraintViolation`을 던진다.
- **CI-2**: Unique 중복 insert는 던진다. 단 **NULL은 서로 distinct로 취급한다** (SQL 표준).
- **CI-3**: FK 위반 insert는 던진다. 단 **NULL FK는 허용한다**.

## 2. 의존성

- 이전 세션: `impl/05-01-constraints.md` (sealed `Constraint`, `TableSchema.constraints`)
- `impl/06-01-table-seqscan.md` (`TableHeap.scan()`)

## 3. 문제 정의 (TDD step 1)

단계 5에서 `Constraint.PrimaryKey(listOf("id"))`를 스키마에 붙였다. 지금 같은 `id`로 두 번 insert하면? **그냥 들어간다.** 선언은 주석과 다를 바 없는 상태다.

검증은 개념적으로 단순하다 — 넣기 전에 이미 있는지 본다. 문제는 **NULL의 취급**이 제약 종류마다 다르다는 것이다:

- **PK**: NULL이 애초에 불가능하다(단계 5에서 스키마 검사로 막았다). 값이 같으면 위반.
- **UNIQUE**: NULL은 **서로 다른 것으로 본다.** `email`이 null인 행 두 개는 UNIQUE 위반이 아니다. "값을 모른다"는 것끼리 같다고 할 근거가 없기 때문이다.
- **FK**: NULL은 **참조하지 않음**을 뜻하므로 허용된다. 값이 있으면 참조 대상에 존재해야 한다.

이 세 갈래를 헷갈리면 조용히 틀린다 — 테스트를 통과하면서 데이터만 망가진다.

방식은 풀스캔이다. insert 한 번에 테이블 전체를 훑는다. 명백히 느리고, **그 느림 자체가 06-04의 동기**다.

## 4. 실패 테스트 (TDD step 2)

```kotlin
// src/test/kotlin/com/dbenginelab/table/ConstraintValidatorTest.kt @ 5505edc
package com.dbenginelab.table

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.Constraint
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ConstraintValidatorTest {

    private fun userSchema() = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("email", Type.STRING, nullable = false),
        ),
        constraints = listOf(
            Constraint.PrimaryKey(listOf("id")),
            Constraint.Unique(listOf("email")),
        ),
    )

    @Test
    fun `중복 PK 거부`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("u.data").toString()
        val schema = userSchema()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val v = ConstraintValidator(heap)
            val t1 = Tuple(schema, listOf(1L, "a@x.com"))
            v.validateInsert(t1); heap.insert(t1)
            assertThrows<ConstraintViolation> {
                v.validateInsert(Tuple(schema, listOf(1L, "b@x.com")))
            }
        }}
    }

    @Test
    fun `중복 Unique 거부`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("u.data").toString()
        val schema = userSchema()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val v = ConstraintValidator(heap)
            val t1 = Tuple(schema, listOf(1L, "a@x.com"))
            v.validateInsert(t1); heap.insert(t1)
            assertThrows<ConstraintViolation> {
                v.validateInsert(Tuple(schema, listOf(2L, "a@x.com")))
            }
        }}
    }

    @Test
    fun `FK 통과·위반`(@TempDir tempDir: Path) {
        val uPath = tempDir.resolve("u.data").toString()
        val oPath = tempDir.resolve("o.data").toString()
        val users = userSchema()
        val orders = TableSchema(
            name = "orders",
            columns = listOf(
                ColumnDef("oid", Type.BIGINT, nullable = false),
                ColumnDef("user_id", Type.BIGINT, nullable = false),
            ),
            constraints = listOf(Constraint.ForeignKey(listOf("user_id"), "users", listOf("id"))),
        )
        PagedFile(uPath).use { upf -> BufferPool(upf, 16).use { ubp ->
            PagedFile(oPath).use { opf -> BufferPool(opf, 16).use { obp ->
                val userHeap = TableHeap(users, upf, ubp)
                val orderHeap = TableHeap(orders, opf, obp)
                userHeap.insert(Tuple(users, listOf(10L, "u@x.com")))
                val v = ConstraintValidator(orderHeap) { name -> if (name == "users") userHeap else null }
                v.validateInsert(Tuple(orders, listOf(1L, 10L)))
                assertThrows<ConstraintViolation> { v.validateInsert(Tuple(orders, listOf(2L, 99L))) }
            }}
        }}
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: ConstraintValidator`, `ConstraintViolation`.

## 5. 구현 코드 (TDD step 3 — make it pass)

```kotlin
// src/main/kotlin/com/dbenginelab/table/ConstraintValidator.kt @ 5505edc
package com.dbenginelab.table

import com.dbenginelab.catalog.Constraint
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple

class ConstraintValidator(
    private val heap: TableHeap,
    private val foreignKeyLookup: (String) -> TableHeap? = { null },
) {
    private val schema: TableSchema get() = heap.schema

    fun validateInsert(tuple: Tuple) {
        for (constraint in schema.constraints) {
            when (constraint) {
                is Constraint.PrimaryKey -> validateUniqueColumns(tuple, constraint.columns, "PRIMARY KEY")
                is Constraint.Unique -> validateUniqueColumns(tuple, constraint.columns, "UNIQUE")
                is Constraint.ForeignKey -> validateForeignKey(tuple, constraint)
            }
        }
    }

    private fun validateUniqueColumns(tuple: Tuple, columns: List<String>, label: String) {
        val newValues = columns.map { tuple.get(it) }
        // Q: NULL이 하나라도 있으면 검증 skip — 왜?
        if (newValues.any { it == null }) return
        // <details><summary>A</summary>
        //
        // SQL 표준: UNIQUE는 NULL을 distinct로 취급 → 여러 row가 NULL 가져도 OK. NOT NULL은 schema 레벨에서 별도 강제 (단계 5 ColumnDef.nullable).
        // </details>
        for (existing in heap.scan()) {
            val existingValues = columns.map { existing.get(it) }
            if (existingValues.any { it == null }) continue
            if (existingValues == newValues) {
                throw ConstraintViolation(
                    "$label violation on (${columns.joinToString(",")}) in table ${schema.name}: $newValues already exists"
                )
            }
        }
    }

    private fun validateForeignKey(tuple: Tuple, fk: Constraint.ForeignKey) {
        val childValues = fk.columns.map { tuple.get(it) }
        if (childValues.any { it == null }) return
        // Q: foreignKeyLookup이 lambda인 이유? Catalog 직접 가지면 안 되나?
        val refHeap = foreignKeyLookup(fk.refTable)
        // <details><summary>A</summary>
        //
        // ConstraintValidator를 Catalog에 의존시키면 단계 4 Catalog 인터페이스 변경 시 영향 큼. lambda는 호출자가 heap 매핑 책임 — 결합도 낮음.
        // </details>
            ?: throw ConstraintViolation("FOREIGN KEY refTable ${fk.refTable} not available")
        for (parent in refHeap.scan()) {
            if (fk.refColumns.map { parent.get(it) } == childValues) return
        }
        throw ConstraintViolation("FOREIGN KEY violation: $childValues not in ${fk.refTable}")
    }
}

class ConstraintViolation(message: String) : RuntimeException(message)
```

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.table.ConstraintValidatorTest'
```

**기대 결과**: `ConstraintValidatorTest` **3 PASSED**

invariant 대응:
- **CI-1** ← `중복 PK 거부`
- **CI-2** ← `중복 Unique 거부`
- **CI-3** ← `FK 통과·위반`

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** UNIQUE 검사에서 NULL을 "같은 값"으로 취급하도록 바꿔라(`if (newValues.any { it == null }) return` 제거). 어느 테스트가 잡는가?

<details><summary>답</summary>

**실측: 하나도 안 잡는다. 3개 전부 통과한다.**

이유는 테스트 스키마에 있다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
private fun userSchema() = TableSchema(
    columns = listOf(
        ColumnDef("id",    Type.BIGINT, nullable = false),
        ColumnDef("email", Type.STRING, nullable = false),   // ← NOT NULL
    ),
    constraints = listOf(PrimaryKey(listOf("id")), Unique(listOf("email"))),
)
```

UNIQUE가 걸린 `email`이 **NOT NULL**이라 애초에 null이 들어갈 수 없다. 그 분기를 아예 타지 않으니 지워도 티가 안 난다.

**잡는 테스트를 직접 써라** — 이번 세션의 핵심 과제다. 필요한 것:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
// nullable UNIQUE 컬럼을 가진 스키마를 새로 만들고
ColumnDef("nickname", Type.STRING, nullable = true)
constraints = listOf(Constraint.Unique(listOf("nickname")))

// nickname이 null인 행을 두 번 넣어 둘 다 통과하는지 확인
v.validateInsert(Tuple(schema, listOf(1L, null)))   // heap.insert
v.validateInsert(Tuple(schema, listOf(2L, null)))   // ← 여기서 안 터져야 정상
```

여기서 배울 것 — **테스트 스키마가 곧 테스트 범위다.** 모든 컬럼을 NOT NULL로 만들어두면 NULL 관련 로직 전체가 검증되지 않은 채 남는다.
</details>

**2.** FK 검사에서 NULL을 거부하도록 바꿔라. 현실의 어떤 스키마가 깨지나?

<details><summary>답</summary>

**실측: 이것도 3개 전부 통과한다.** FK 테스트의 `orders.user_id`가 `nullable = false`라 같은 이유로 안 잡힌다.

현실에서 깨지는 예 — **"아직 정해지지 않음"을 표현해야 하는 모든 관계**다:

```sql
tickets(id, title, assignee_id → users.id)     -- 미배정 티켓: assignee_id = NULL
orders(id, coupon_id → coupons.id)             -- 쿠폰 미사용 주문: coupon_id = NULL
employees(id, manager_id → employees.id)       -- 최상위 임원: manager_id = NULL
```

세 번째가 특히 중요하다 — **자기 참조 계층에서 루트를 표현할 방법이 NULL뿐**이다. NULL FK를 금지하면 조직도의 최상단을 넣을 수 없다.

FK에서 NULL의 의미는 "잘못된 참조"가 아니라 **"참조하지 않음"**이다. 그래서 검사 대상이 아니다. 값이 있을 때만 "그 값이 존재하는가"를 묻는다.

(참고: 복합 FK에서 일부만 NULL인 경우는 SQL 표준에 `MATCH FULL` / `MATCH SIMPLE` 두 규칙이 있고 동작이 다르다. 우리 코드는 `any { it == null }`이므로 **하나라도 null이면 통과** — `MATCH SIMPLE` 쪽이다.)
</details>

**3.** 행 10만 개짜리 테이블에 1건 insert할 때 읽는 page 수는? 10만 건을 순차 insert하면 총 몇 번 읽나?

<details><summary>답</summary>

`(id BIGINT 8B, email STRING ~14B)` + NULL bitmap 1B + 길이 프리픽스 4B ≈ **27B/행**
page당 `(4096 - 8) / 27 ≈ 151행` → 10만 행이면 약 **662 page**.

`validateInsert`는 제약마다 `heap.scan()`을 **따로** 부른다. PK와 UNIQUE 둘이면 **1건 insert에 662 × 2 = 1,324 page 읽기.**

10만 건을 순차 insert하면 i번째 insert가 i개 행을 훑으므로:

```
총 비교 횟수 = 1 + 2 + … + 100,000 = 약 50억 회
총 page 읽기 = 약 3,300만 회 (제약 2개 기준)
```

**O(N²)** 다. 행이 10배가 되면 시간은 100배가 된다. 10만 건 적재가 몇 시간 걸린다는 뜻이고, 실용성이 없다.

그리고 눈여겨볼 것 — **`heap.scan()`을 제약마다 반복한다.** 한 번 훑으면서 PK와 UNIQUE를 동시에 검사하면 절반으로 줄일 수 있다. 그래도 O(N²)인 건 변하지 않는다. **상수를 줄이는 것과 차수를 줄이는 것은 다른 일**이고, 차수를 줄이려면 인덱스가 필요하다 → 06-04.
</details>

**4.** 검증과 insert 사이에 다른 스레드가 같은 키를 넣으면? 지금 코드로 막을 수 있나?

<details><summary>답</summary>

**막을 수 없다.** 검증과 삽입이 **분리된 두 호출**이기 때문이다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
v.validateInsert(tuple)   // ← 통과
                          // ← 이 틈에 다른 스레드가 같은 PK를 넣는다
heap.insert(tuple)        // ← 중복이 들어간다
```

이걸 **TOCTOU**(Time Of Check To Time Of Use)라 부른다. "확인한 시점"과 "사용하는 시점" 사이에 세상이 바뀌는 문제로, 파일 권한 검사·재고 확인 등 어디에나 나타난다.

필요한 것은 **검사부터 삽입까지를 하나의 원자적 구간으로 묶는 것**이다. 방법이 여러 가지다:

| 방법 | 어디서 |
|---|---|
| 테이블에 배타 락 | 단계 9 `LockManager` |
| 인덱스에 유일 제약을 걸고 삽입 자체가 실패하게 | 06-04 `IndexedTableHeap`이 그 방향 |
| 낙관적 검사 + 커밋 시점 충돌 감지 | 단계 10 MVCC (다만 우리 구현은 이걸 안 함 → 10-02) |

06-03의 설계 결정(검증을 heap과 분리)이 07-01의 일괄 커밋을 가능하게 했지만, **동시에 이 틈을 만들었다.** 하나의 결정이 한쪽에서는 이득, 다른 쪽에서는 비용이 되는 전형적인 예다.
</details>

## 8. 다음 한계

정확하지만 **느리다.** insert마다 풀스캔이므로 N건 삽입에 O(N²)이다.

→ **06-04 IndexedTableHeap**. 단계 3에서 만든 B+tree를 PK 검사에 붙인다. 풀스캔 O(N)이 인덱스 조회 O(log N)이 된다. 그리고 그 순간 새 문제가 생긴다 — **heap과 index를 둘 다 갱신해야 하는데, 하나만 되고 죽으면?**
