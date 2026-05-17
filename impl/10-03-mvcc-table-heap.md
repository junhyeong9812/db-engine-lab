# impl/10-03 — MVCCTableHeap (C4 — MVCC + TableHeap 통합)

> **검증**: MVCCTableHeapTest 2 PASSED.
> 작성 파일:
> - 신규: `src/main/kotlin/com/dbenginelab/mvcc/MVCCTableHeap.kt`

## 0. 보강 동기
codex C4: MVCCStore<K,V>는 in-memory 데모. TableHeap (disk)과 별개. 통합 필요.

## 1. invariant
- 기존 heap row들이 bootstrap 시 xid=0 version으로 등록.
- insert/delete는 in-memory version chain 갱신.
- read(key, snapshot) → snapshot에 visible한 가장 최근 버전.

## 2. 구현 코드 — 한 줄 한 줄

작성 위치: `src/main/kotlin/com/dbenginelab/mvcc/MVCCTableHeap.kt`

```kotlin
package com.dbenginelab.mvcc                                         // mvcc 패키지

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap                               // 단계 6-1 TableHeap
import java.io.Closeable

class MVCCTableHeap(
    val heap: TableHeap,                                             // 실 disk storage
    private val pkColumn: String,                                    // 어떤 컬럼이 PK인지
) : Closeable {

    private val mvcc = MVCCStore<Long, Tuple>()                      // in-memory version chain
    val schema: TableSchema get() = heap.schema

    init {
        for (tuple in heap.scan()) {                                 // Q: bootstrap에서 모든 row를 mvcc에?
            val key = tuple.get(pkColumn) as Long
            mvcc.insert(key, tuple, xid = 0L)                        // xid=0 = "creation snapshot"
        }
    }
    // <details><summary>A</summary>
    // reopen 시 disk에 있는 모든 row가 "이미 committed" 상태 — xid=0 (가장 옛 snapshot)으로 추가.
    // </details>

    fun insert(tuple: Tuple, xid: Long) {                            // tx가 자기 xid로 insert
        require(tuple.schema == schema)
        val key = tuple.get(pkColumn) as Long
        mvcc.insert(key, tuple, xid)                                 // version chain에 추가
    }

    fun delete(key: Long, xid: Long) {
        mvcc.delete(key, xid)                                        // tombstone (value=null)
    }

    fun read(key: Long, snapshot: MVCCStore.Snapshot): Tuple? =      // snapshot 기준 read
        mvcc.get(key, snapshot)

    fun versionCount(key: Long): Int = mvcc.versionCount(key)

    override fun close() {
        heap.close()
        // Q: MVCC chain은 close 시 어떻게? persist 안 함?
        // <details><summary>A</summary>
        // 학습 단순화 — MVCC chain은 in-memory only. 진짜는 WAL과 통합해 persist.
        // </details>
    }
}
```

## 3. 검증 (2 PASSED)
- insert + snapshot read
- delete 후 옛 snapshot은 보고 새 snapshot은 못 봄

## 4. 깨뜨릴 과제
- MVCC chain persist — WAL과 통합 어떻게?
- vacuum 추가 (옛 version 회수)?
- Snapshot isolation의 write skew anomaly?
