# impl/06-04 — IndexedTableHeap (X1 — Index maintenance)

> **검증**: IndexedTableHeapTest 3 PASSED.
> 작성 파일:
> - 신규: `src/main/kotlin/com/dbenginelab/table/IndexedTableHeap.kt`

## 0. 보강 동기
codex X1: BTree 따로 있고 TableHeap 따로 있음. INSERT 시 index 자동 갱신 + unique 검증 + rollback 시 undo 등이 한 계약으로 안 묶임. 실 DB 엔진 핵심 누락.

## 1. invariant
- insert(tuple) → PK column 값으로 index에 중복 검사 → heap.insert + index.insert (한 계약).
- findByKey(key) → index search로 row pointer → heap에서 가져옴.
- nullable PK 거부 (init에서).
- BIGINT PK 만 (BTree key가 Long).

## 2. 구현 코드 — 한 줄 한 줄

작성 위치: `src/main/kotlin/com/dbenginelab/table/IndexedTableHeap.kt`

```kotlin
package com.dbenginelab.table                                        // table 패키지 — TableHeap 옆

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.storage.BTreeIndex                            // 단계 3 BTree
import java.io.Closeable

class IndexedTableHeap(                                              // Q: TableHeap 상속 안 하고 wrap?
    val heap: TableHeap,
    val index: BTreeIndex,
    private val pkColumnName: String,
) : Closeable {
    // <details><summary>A</summary>
    // 상속 대신 composition — TableHeap이 final 클래스, IndexedTableHeap이 추가 책임만.
    // </details>

    init {
        val col = heap.schema.column(pkColumnName)                   // schema에서 PK column 찾음
        require(!col.nullable) { "PK column $pkColumnName must be NOT NULL" }
        require(col.type == com.dbenginelab.catalog.Type.BIGINT) {
            "stage 6 IndexedTableHeap only supports BIGINT PK (got ${col.type})"
        }
    }

    val schema: TableSchema get() = heap.schema                      // delegate

    fun insert(tuple: Tuple) {                                       // 한 계약 — heap + index
        require(tuple.schema == heap.schema)
        val key = tuple.get(pkColumnName) as Long                    // PK 값 추출
        if (index.search(key) != null) {                             // Q: index search를 heap insert 전에?
            throw ConstraintViolation("PK $pkColumnName=$key already exists in index")
        }
        // <details><summary>A</summary>
        // unique 검증을 heap insert 전에 — 검증 실패 시 heap 변경 없음 (atomic). 검증 후 둘 다 변경.
        // </details>
        heap.insert(tuple)                                           // heap에 추가
        index.insert(key, heap.rowCount().toLong())                  // index에 (key, row#) 추가
    }

    fun findByKey(key: Long): Tuple? {                               // index 활용한 빠른 lookup
        val pos = index.search(key) ?: return null                    // index에서 row 위치
        var i = 0L
        for (tuple in heap.scan()) {                                 // Q: pos만 알아도 풀스캔?
            i++
            if (i == pos) return tuple
        }
        return null
        // <details><summary>A</summary>
        // 단계 6 학습 단순화 — TableHeap이 random access 안 제공. 단계 11+ row id로 random access 가능.
        // </details>
    }

    fun rowCount(): Int = heap.rowCount()                            // delegate

    override fun close() {
        heap.close()
        index.close()
    }
}
```

## 3. 검증 (3 PASSED)
- insert 후 findByKey 빠르게 찾음
- PK 중복 insert → ConstraintViolation, heap 변경 없음
- nullable PK 거부

## 4. 깨뜨릴 과제
- Secondary index 추가하려면? (multiple BTreeIndex per heap)
- Composite PK?
- INSERT 도중 crash 시 — heap apply 되고 index 안 됐다면? (transaction과 통합 필요)
