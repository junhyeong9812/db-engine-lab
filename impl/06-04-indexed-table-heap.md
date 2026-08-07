# impl/06-04 — IndexedTableHeap (보강 X1 — 인덱스 유지)

> **종류**: 보강형 (기존 `TableHeap`·`BTreeIndex`를 한 계약으로 묶는다 — 새 개념 도입이 아니므로 §4 생략)
> **상위 단계**: `docs/stages/06-query-api.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: heap과 index를 **하나의 insert 계약**으로 묶는다. PK 중복 검사가 풀스캔에서 인덱스 조회로 바뀐다.
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/table/IndexedTableHeap.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/table/IndexedTableHeapTest.kt`
> **검증**: `IndexedTableHeapTest` 3 PASSED
> **예상 타이핑 시간**: 30분

---

## 0. 보강 동기

codex 지적 X1: **B+tree는 B+tree대로, TableHeap은 TableHeap대로 따로 놀고 있다.** insert할 때 인덱스가 자동으로 갱신되지 않고, unique 검증도 따로이고, 실패 시 되돌리기도 없다. 실제 DB 엔진의 핵심이 통째로 비어 있는 상태다.

이 세션은 그 셋을 **한 계약**으로 묶는다.

## 1. 만족시킬 invariant

- **CI-1**: `insert(tuple)` = PK 값으로 index 중복 검사 → `heap.insert` → `index.insert`. 세 동작이 한 계약이다.
- **CI-2**: `findByKey(key)`는 index 조회로 위치를 얻어 tuple을 돌려준다.
- **CI-3**: nullable PK 컬럼은 생성 시점에 거부한다.
- **CI-4**: 이 단계에서는 BIGINT PK만 지원한다 (B+tree 키가 `Long`이므로).

## 2. 의존성

- `impl/03-03-range-scan.md` (`BTreeIndex`)
- `impl/06-01-table-seqscan.md` (`TableHeap`)
- `impl/06-03-constraint-validator.md` (`ConstraintViolation`)

## 3. 문제 정의

06-03의 검증은 정확하지만 insert마다 풀스캔이다. 그런데 우리는 단계 3에서 **정확히 이 문제를 풀라고 B+tree를 만들었다.** 쓰지 않고 있었을 뿐이다.

붙이는 순간 새 책임이 생긴다 — **heap과 index가 함께 움직여야 한다.** 행을 넣었는데 인덱스에 안 넣으면 그 행은 인덱스로 찾을 수 없고, 인덱스에만 넣으면 없는 행을 가리킨다. 둘 중 하나만 성공하는 상태를 만들면 안 된다.

그래서 순서가 중요하다: **검증 → heap → index.** 검증을 먼저 하면 실패 시 아무것도 바뀌지 않는다. 이건 07-01 `WorkUnit`의 all-or-nothing과 같은 발상의 작은 버전이다.

한계도 분명히 해두자 — heap과 index 사이에서 죽으면 여전히 어긋난다. **그건 이 계층에서 풀 수 없고 단계 8 WAL이 필요하다.**

## 4. 구현 코드

`TableHeap`을 상속하지 않고 **감싼다(composition)**. `IndexedTableHeap`은 저장 방식을 바꾸는 것이 아니라 책임을 하나 더 얹는 것이므로, 상속보다 위임이 관계를 정확히 나타낸다.

```kotlin
// src/main/kotlin/com/dbenginelab/table/IndexedTableHeap.kt @ 5505edc
package com.dbenginelab.table

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.storage.BTreeIndex
import java.io.Closeable

/**
 * Stage 6 보강 (X1): TableHeap + 자동 BTreeIndex 유지.
 *
 * insert(tuple)이 heap.insert + (PK 컬럼 기준) index.insert 를 한 계약으로.
 * findByKey(key)는 BTree로 빠른 lookup.
 *
 * Limitations:
 *  - Single PK column index만 (composite PK는 후속).
 *  - BIGINT PK only (BTreeIndex가 Long key — stage 3 BTree 한계).
 *  - DELETE/UPDATE 미지원 (TableHeap 자체가 미지원).
 */
class IndexedTableHeap(
    val heap: TableHeap,
    val index: BTreeIndex,
    private val pkColumnName: String,
) : Closeable {

    init {
        val col = heap.schema.column(pkColumnName)
        require(!col.nullable) { "PK column $pkColumnName must be NOT NULL" }
        require(col.type == com.dbenginelab.catalog.Type.BIGINT) {
            "stage 6 IndexedTableHeap only supports BIGINT PK (got ${col.type})"
        }
    }

    val schema: TableSchema get() = heap.schema

    /** Atomic-ish insert: validates uniqueness via index, then writes heap and index. */
    fun insert(tuple: Tuple) {
        require(tuple.schema == heap.schema)
        val key = tuple.get(pkColumnName) as Long
        // Q: 왜 index search를 heap insert 전에?
        if (index.search(key) != null) {
            throw ConstraintViolation("PK $pkColumnName=$key already exists in index")
        }
        heap.insert(tuple)
        // pseudo-row-id: insertion order (heap doesn't expose real row id at stage 6).
        // For learning, the value stored in the index is the row count after insert.
        index.insert(key, heap.rowCount().toLong())
    }

    fun findByKey(key: Long): Tuple? {
        val pos = index.search(key) ?: return null
        // Position is just for verification; we scan to find the matching tuple.
        var i = 0L
        for (tuple in heap.scan()) {
            i++
            if (i == pos) return tuple
        }
        return null
    }

    fun rowCount(): Int = heap.rowCount()

    override fun close() {
        heap.close()
        index.close()
    }
}
```

> **정본 특이사항**: `insert` 안의 `// Q: 왜 index search를 heap insert 전에?` 는 **정본 코드에 그대로 들어있는 주석**인데 답이 없다(이전 회차의 잔존물). 고치지 않고 둔다 — 답은 §3에 있다. **검증이 먼저여야 실패했을 때 heap이 손대지지 않은 상태로 남기 때문**이고, 그 성질을 §5의 두 번째 테스트가 검증한다.

## 5. 검증 테스트 (green)

```kotlin
// src/test/kotlin/com/dbenginelab/table/IndexedTableHeapTest.kt @ 5505edc
package com.dbenginelab.table

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.Constraint
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.storage.BTreeIndex
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IndexedTableHeapTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
        constraints = listOf(Constraint.PrimaryKey(listOf("id"))),
    )

    @Test
    fun `insert 후 findByKey로 빠르게 찾음`(@TempDir tempDir: Path) {
        val heapPath = tempDir.resolve("h.data").toString()
        val idxPath = tempDir.resolve("h.idx").toString()
        PagedFile(heapPath).use { hpf -> BufferPool(hpf, 16).use { hbp ->
            PagedFile(idxPath).use { ipf -> BufferPool(ipf, 16).use { ibp ->
                val heap = TableHeap(schema, hpf, hbp)
                val idx = BTreeIndex(ipf, ibp)
                val ith = IndexedTableHeap(heap, idx, "id")
                ith.insert(Tuple(schema, listOf(1L, "Alice")))
                ith.insert(Tuple(schema, listOf(2L, "Bob")))
                ith.insert(Tuple(schema, listOf(3L, "Charlie")))

                val found = ith.findByKey(2L)!!
                assertEquals("Bob", found.get("name"))
                assertNull(ith.findByKey(999L))
                assertEquals(3, ith.rowCount())
            }}
        }}
    }

    @Test
    fun `PK 중복 insert는 ConstraintViolation - heap 변경 없음`(@TempDir tempDir: Path) {
        val heapPath = tempDir.resolve("h.data").toString()
        val idxPath = tempDir.resolve("h.idx").toString()
        PagedFile(heapPath).use { hpf -> BufferPool(hpf, 16).use { hbp ->
            PagedFile(idxPath).use { ipf -> BufferPool(ipf, 16).use { ibp ->
                val heap = TableHeap(schema, hpf, hbp)
                val idx = BTreeIndex(ipf, ibp)
                val ith = IndexedTableHeap(heap, idx, "id")
                ith.insert(Tuple(schema, listOf(1L, "Alice")))
                assertThrows<ConstraintViolation> {
                    ith.insert(Tuple(schema, listOf(1L, "Bob")))
                }
                assertEquals(1, ith.rowCount())  // heap 변경 없음
            }}
        }}
    }

    @Test
    fun `nullable PK 거부`(@TempDir tempDir: Path) {
        val nullableSchema = TableSchema(
            name = "x",
            columns = listOf(ColumnDef("id", Type.BIGINT, nullable = true)),
        )
        val heapPath = tempDir.resolve("h.data").toString()
        val idxPath = tempDir.resolve("h.idx").toString()
        PagedFile(heapPath).use { hpf -> BufferPool(hpf, 16).use { hbp ->
            PagedFile(idxPath).use { ipf -> BufferPool(ipf, 16).use { ibp ->
                val heap = TableHeap(nullableSchema, hpf, hbp)
                val idx = BTreeIndex(ipf, ibp)
                assertThrows<IllegalArgumentException> {
                    IndexedTableHeap(heap, idx, "id")
                }
            }}
        }}
    }
}
```

```bash
./gradlew test --tests 'com.dbenginelab.table.IndexedTableHeapTest'
```

**기대 결과**: `IndexedTableHeapTest` **3 PASSED**

invariant 대응:
- **CI-1**, **CI-2** ← `insert 후 findByKey로 빠르게 찾음`
- **CI-1**(원자성) ← `PK 중복 insert는 ConstraintViolation - heap 변경 없음` — **검증을 heap보다 먼저 한다는 결정이 여기서 검증된다**
- **CI-3** ← `nullable PK 거부`

## 6. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `insert`에서 index 검사와 `heap.insert`의 순서를 바꿔라(heap 먼저). 어느 테스트가 깨지나?

<details><summary>답</summary>

**실측: `PK 중복 insert는 ConstraintViolation - heap 변경 없음`이 실패한다** (3개 중 1개).

예외는 여전히 나온다. 깨지는 건 **"heap 변경 없음"** 쪽이다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
// 바꾼 뒤
heap.insert(tuple)                    // ← 중복인데 이미 들어감
if (index.search(key) != null) {
    throw ConstraintViolation(...)    // ← 그 다음에 터짐
}
```

`assertEquals(1, heap.rowCount())`를 기대하는데 **2**가 나온다. 예외를 던지고도 데이터는 남은 것이다.

이게 원자성이 깨진다는 말의 구체적 모습이다 — **호출자는 실패했다고 들었는데 상태는 바뀌었다.** 예외를 잡아 재시도하면 중복이 또 쌓인다.

그리고 더 나쁜 건 index에는 안 들어갔다는 점이다. heap에는 있고 index에는 없는 행 → `findByKey`로는 안 보이고 `scan`에는 보인다(과제 5번의 상태를 예외 경로에서 만들어낸 셈).

**검사를 먼저 하는 것**이 이 모든 걸 막는다. 07-01의 `WorkUnit.commit`이 validation 루프와 apply 루프를 분리한 것과 같은 원리다.
</details>

**2.** `findByKey`가 index로 위치를 알아낸 뒤에도 **여전히 heap을 처음부터 훑는다.** 왜인가? 진짜 O(log N)으로 만들려면?

<details><summary>답</summary>

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
fun findByKey(key: Long): Tuple? {
    val pos = index.search(key) ?: return null   // ← O(log N)으로 위치는 알아냈다
    var i = 0L
    for (tuple in heap.scan()) {                 // ← 그런데 처음부터 훑는다 O(N)
        i++
        if (i == pos) return tuple
    }
```

근거는 `TableHeap`이 **"n번째 행 하나만 가져오기"를 제공하지 않는다**는 것이다. `scan()`뿐이다. 그러니 인덱스가 "3,000번째"라고 알려줘도 세면서 갈 수밖에 없다.

**즉 지금 인덱스는 존재 여부 판정에만 쓰이고 위치 정보는 낭비되고 있다.** 중복 검사는 O(log N)으로 빨라졌지만 조회는 여전히 O(N)이다.

진짜로 만들려면 필요한 것:

1. **row id 개념** — "몇 번째"가 아니라 `(pageNumber, slotNumber)` 같은 **물리 주소**여야 한다. 지금은 `heap.rowCount()`를 저장하는데, 이건 삽입 순서일 뿐이고 삭제가 생기면 즉시 어긋난다.
2. **`TableHeap.get(rowId)`** — 그 주소의 page만 fetch해서 tuple 하나를 꺼내는 API.

그러면 `findByKey`가 "index 조회(log N) + page 1개 읽기"가 되어 실제로 O(log N)이 된다. PostgreSQL의 `ctid`, Oracle의 `ROWID`가 정확히 이것이다.
</details>

**3.** secondary index(PK가 아닌 컬럼의 인덱스)를 추가하려면? 지금 구조가 그걸 쉽게 하나?

<details><summary>답</summary>

**어렵게 한다.** 지금 클래스는 인덱스가 **하나**이고 그것이 **PK**라는 것을 전제로 짜여 있다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
class IndexedTableHeap(
    val heap: TableHeap,
    val index: BTreeIndex,          // ← 단수
    private val pkColumnName: String,   // ← PK 하나
)
```

`insert`도 "PK 중복 검사 → heap → index" 한 갈래뿐이다.

바꾸려면:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
class IndexedTableHeap(
    val heap: TableHeap,
    private val indexes: Map<String, BTreeIndex>,   // 컬럼명 → 인덱스
    private val uniqueColumns: Set<String>,         // 그중 유일 제약이 걸린 것
)
```

그리고 `insert`가 **모든 인덱스를 갱신**해야 한다. 여기서 새 문제가 줄줄이 나온다:

- 인덱스 3개 중 2개만 갱신되고 죽으면? (과제 5번이 3배로 커진다)
- secondary index는 **중복 키를 허용**해야 한다 — 그런데 `BTreeIndex`는 duplicate key를 `require`로 막는다(단계 3의 결정). 값이 같은 행이 여럿인 인덱스를 지원하려면 B+tree 자체를 고쳐야 한다.

두 번째가 진짜 걸림돌이다. **단계 3에서 "duplicate key 거부"를 택한 결정이 여기까지 따라온다.** 초반의 단순화가 나중에 무엇을 막는지 보여주는 예다.
</details>

**4.** 복합 PK를 지원하려면? B+tree 키가 `Long`인 것이 어떤 제약을 만드나?

<details><summary>답</summary>

`BTreeIndex.insert(key: Long, value: Long)`이므로 **키가 반드시 `Long` 하나**여야 한다. 컬럼 두 개를 하나의 `Long`으로 합쳐야 한다.

가장 단순한 방법 — **비트 패킹**:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
val key = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
```

**위험 하나**: `a`나 `b`가 32비트를 넘으면 **충돌한다.** `a = 1, b = 0`과 `a = 0, b = 2³²`가 같은 키가 되어, 서로 다른 두 행이 PK 중복으로 거부되거나 서로를 덮는다. `BIGINT` 컬럼은 64비트인데 32비트만 쓰는 셈이라 현실적이지 않다.

다른 방법과 그 대가도 같이 보면:

| 방법 | 대가 |
|---|---|
| 비트 패킹 | 값 범위 제한, 충돌 |
| 문자열로 이어붙여 해시 | 해시 충돌 시 오탐, **정렬 순서가 사라져 range scan 불가** |
| `BTreeIndex`를 제네릭·복합 키로 확장 | page 레이아웃(고정 16B entry)을 다시 설계해야 함 |

세 번째가 정답이지만 단계 3의 `ENTRY_SIZE = KEY_SIZE + VALUE_SIZE = 16` 고정을 깨야 한다. **가변 길이 키를 담는 B+tree는 page 안 레이아웃이 훨씬 복잡해진다**(슬롯 디렉토리가 필요해진다) — 실제 DB가 복잡한 이유 중 하나다.
</details>

**5.** `heap.insert`는 성공했는데 `index.insert`에서 프로세스가 죽으면? 이 상태를 뭐라고 부르며 어떻게 고쳐야 하나?

<details><summary>답</summary>

**인덱스 불일치(index corruption / index-table inconsistency)** 라 부른다.

증상이 고약하다 — 같은 데이터에 대해 **접근 경로마다 다른 답**이 나온다:

```
SELECT * FROM t                 → scan을 타므로 그 행이 보인다
SELECT * FROM t WHERE pk = 42   → index를 타므로 안 보인다
COUNT(*)                        → 어느 경로를 타느냐에 따라 다르다
```

사용자 입장에서는 "있는데 없다"는 상태다. 그리고 **아무도 에러를 내지 않는다.**

고치는 방법은 두 층으로 나뉜다:

1. **예방** — heap 변경과 index 변경을 **하나의 원자적 단위**로 묶는다. 그러려면 둘 다를 로그에 먼저 적고 함께 복구해야 한다 → **단계 8 WAL**. 그래서 진짜 DB는 인덱스 갱신도 WAL에 기록한다.
2. **탐지·복구** — 이미 어긋난 것을 찾아내는 도구. PostgreSQL의 `REINDEX`(인덱스를 통째로 다시 만들기), `amcheck`(인덱스와 테이블 대조).

지금 우리 코드에는 **둘 다 없다.** 그래서 06-04 §7이 "메모리 안에서만 원자적"이라고 적어둔 것이고, 이 한계가 단계 8의 동기가 된다.
</details>

## 7. 다음 한계

인덱스와 heap이 한 계약으로 묶였지만, **그 계약은 메모리 안에서만 원자적이다.** 중간에 죽으면 어긋난 채로 남는다(과제 5번).

→ **단계 7 WorkUnit**이 여러 insert를 한 덩어리로 묶고, **단계 8 WAL**이 그 덩어리를 crash로부터 지킨다.
