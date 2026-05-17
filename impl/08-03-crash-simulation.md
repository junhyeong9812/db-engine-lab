# impl/08-03 — Crash Simulation Tests (X6)

> **검증**: CrashSimulationTest 3 PASSED.
> 작성 파일:
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/wal/CrashSimulationTest.kt`

## 0. 보강 동기
codex X6: 89/89 PASSED이지만 단위 기능 통과 중심. 실패 주입 (crash mid-commit, partial write, randomized op) 부족.

## 1. invariant 검증
- COMMIT 안 적힌 tx → recovery에서 미반영
- WAL partial trailing bytes → replay 시 EOF로 안전 처리
- randomized commit/abort 시퀀스 → recovery 결과가 commit set과 정확히 일치

## 2. 코드 — 한 줄 한 줄

작성 위치: `src/test/kotlin/com/dbenginelab/wal/CrashSimulationTest.kt`

```kotlin
package com.dbenginelab.wal

import com.dbenginelab.catalog.*
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import com.dbenginelab.table.TableHeap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class CrashSimulationTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test
    fun `crash 직전 commit 미실행 - tx 데이터 미반영`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        val data = tempDir.resolve("u.data").toString()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tm = TransactionManager(lm)
                val tx1 = tm.begin()
                tx1.insert("users", heap, Tuple(schema, listOf(1L, "committed")))
                tx1.commit()                                          // tx1 commit
                val tx2 = tm.begin()
                tx2.insert("users", heap, Tuple(schema, listOf(2L, "lost-on-crash")))
                // Q: 의도적 commit/abort 누락 = crash 시뮬레이션
            }}
        }
        // <details><summary>A</summary>
        // tx2의 BEGIN + INSERT는 WAL에 적힘. 그러나 COMMIT 없으므로 recovery에서 무시.
        // </details>
        java.io.File(data).delete()                                   // fresh heap
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val stats = Recovery(lm) { name -> if (name == "users") heap else null }.recover()
                assertEquals(1, stats.txCommitted)                    // tx1만 commit
                assertEquals(0, stats.txAborted)                      // tx2는 ABORT도 안 적힘
                assertEquals(1, stats.rowsReapplied)
                assertEquals(1, heap.rowCount())                      // tx1 데이터만
            }}
        }
    }

    @Test
    fun `WAL 파일 끝 partial bytes - replay 시 무시`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        LogManager(log).use { lm ->
            lm.append(LogRecord.BeginTx(1L))
            lm.append(LogRecord.CommitTx(1L))
            lm.sync()
        }
        // 손상된 trailing bytes 주입 (partial write 시뮬)
        java.io.RandomAccessFile(log, "rw").use { raf ->
            raf.seek(raf.length())
            raf.writeInt(5000); raf.write(byteArrayOf(1, 2, 3))      // 5000 bytes 약속, 3만 씀
        }
        LogManager(log).use { lm ->
            val records = mutableListOf<LogRecord>()
            lm.replay { records.add(it) }
            assertEquals(2, records.size)                             // partial 무시, valid 2개만
        }
    }

    @Test
    fun `randomized tx 시퀀스 - commit abort 섞어도 일관`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        val data = tempDir.resolve("u.data").toString()
        val rnd = kotlin.random.Random(42)                            // seed 42 — 재현 가능
        val commits = mutableListOf<Long>()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tm = TransactionManager(lm)
                for (i in 1..30) {                                    // 30 tx 랜덤
                    val tx = tm.begin()
                    tx.insert("users", heap, Tuple(schema, listOf(i.toLong(), "r$i")))
                    if (rnd.nextBoolean()) {
                        tx.commit(); commits.add(i.toLong())
                    } else {
                        tx.abort()
                    }
                }
            }}
        }
        java.io.File(data).delete()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                Recovery(lm) { if (it == "users") heap else null }.recover()
                assertEquals(commits.size, heap.rowCount())           // commit한 만큼 정확
                val recovered = heap.scan().map { it.get("id") as Long }.toSet()
                assertEquals(commits.toSet(), recovered)              // 정확히 같은 id 집합
            }}
        }
    }
}
```

## 3. 깨뜨릴 과제
- crash 중 부분 page write — Page checksum 추가하면?
- 같은 row를 두 tx가 update 시 race — 단계 9 lock 통합 후 재테스트?
- LogManager.append 도중 disk full — 어떻게 감지·복구?
