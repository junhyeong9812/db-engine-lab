# impl/08-03 — Crash Simulation Tests (보강 X6)

> **종류**: 보강형 (테스트 전용 — 신규 main 코드 없음)
> **상위 단계**: `docs/stages/08-wal-recovery.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 지금까지의 테스트는 전부 "정상 종료 후 다시 열기"였다. 여기서 **실패를 주입한다.**
> **작성 파일**:
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/wal/CrashSimulationTest.kt`
> **검증**: `CrashSimulationTest` 3 PASSED
> **예상 타이핑 시간**: 30분

---

## 0. 보강 동기

codex 지적 X6: 89/89 PASSED였지만 **전부 단위 기능 통과 중심**이었다. crash mid-commit, partial write, 무작위 연산 시퀀스 같은 **실패 주입이 없다.** 통과한 테스트 수가 많다는 것과 시스템이 crash에 견딘다는 것은 다른 이야기다.

이 세션은 새 기능을 만들지 않는다. **08-01·08-02가 주장한 대로 정말 동작하는지 공격한다.**

## 1. 검증할 invariant

- **CI-1**: COMMIT 레코드가 없는 트랜잭션은 recovery에서 반영되지 않는다.
- **CI-2**: WAL 끝에 잘린 바이트가 남아도 replay가 EOF로 안전하게 멈춘다.
- **CI-3**: 무작위 commit/abort 시퀀스에서도 recovery 결과가 **커밋 집합과 정확히 일치**한다.

CI-3이 이번 세션의 핵심이다. 손으로 고른 시나리오 몇 개가 아니라 **생성된 시퀀스**로 검사한다 — 사람이 떠올리지 못한 순서를 기계가 만들어내게 하는 것이다.

## 2. 문제 정의

crash를 테스트로 재현하려면 "죽는 것"을 흉내내야 한다. 프로세스를 진짜로 죽일 수는 없으니 **죽었을 때 남는 상태**를 직접 만든다:

- **commit 직전 crash** = COMMIT 레코드를 적지 않고 그냥 두기.
- **partial write** = 로그 파일 끝에 불완전한 바이트를 손으로 덧붙이기.
- **임의 시점 crash** = 무작위로 commit/abort를 섞고, 결과를 예측값과 대조하기.

세 번째가 왜 강력한지 생각해봐라. 앞의 둘은 "내가 아는 실패"를 검증한다. 세 번째는 **내가 모르는 조합**을 만들어낸다.

## 3. 검증 테스트

```kotlin
// src/test/kotlin/com/dbenginelab/wal/CrashSimulationTest.kt @ 5505edc
package com.dbenginelab.wal

import com.dbenginelab.catalog.ColumnDef
import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.catalog.Type
import com.dbenginelab.storage.BufferPool
import com.dbenginelab.storage.PagedFile
import com.dbenginelab.table.TableHeap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Stage 8 보강 (X6): 실패 주입 — crash mid-commit, partial write, randomized.
 */
class CrashSimulationTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test
    fun `crash 직전 commit 미실행 — tx 데이터 미반영`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        val data = tempDir.resolve("u.data").toString()
        // tx1 insert + commit, tx2 insert (commit 안 함 — crash 시뮬레이션)
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tm = TransactionManager(lm)
                val tx1 = tm.begin()
                tx1.insert("users", heap, Tuple(schema, listOf(1L, "committed")))
                tx1.commit()
                val tx2 = tm.begin()
                tx2.insert("users", heap, Tuple(schema, listOf(2L, "lost-on-crash")))
                // no commit, no abort — "crash"
            }}
        }
        // recovery — fresh heap
        java.io.File(data).delete()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val stats = Recovery(lm) { name -> if (name == "users") heap else null }.recover()
                assertEquals(1, stats.txCommitted)  // tx1만 commit
                assertEquals(0, stats.txAborted)    // tx2는 ABORT 안 적힘 (crash)
                assertEquals(1, stats.rowsReapplied)
                assertEquals(1, heap.rowCount())
            }}
        }
    }

    @Test
    fun `WAL 파일 끝 partial bytes — replay 시 무시`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        LogManager(log).use { lm ->
            lm.append(LogRecord.BeginTx(1L))
            lm.append(LogRecord.CommitTx(1L))
            lm.sync()
        }
        // 손상된 trailing bytes 주입
        // 손상된 trailing bytes 주입 (partial write 시뮬)
        java.io.RandomAccessFile(log, "rw").use { raf ->
            raf.seek(raf.length())
            raf.writeInt(5000); raf.write(byteArrayOf(1, 2, 3))  // 5000 bytes claimed, only 3 written
        }
        LogManager(log).use { lm ->
            val records = mutableListOf<LogRecord>()
            lm.replay { records.add(it) }
            assertEquals(2, records.size)  // BeginTx + CommitTx만, partial 무시
        }
    }

    @Test
    fun `randomized tx 시퀀스 - commit abort 섞어도 일관`(@TempDir tempDir: Path) {
        val log = tempDir.resolve("w.log").toString()
        val data = tempDir.resolve("u.data").toString()
        val rnd = kotlin.random.Random(42)
        val commits = mutableListOf<Long>()
        LogManager(log).use { lm ->
            PagedFile(data).use { pf -> BufferPool(pf, 16).use { bp ->
                val heap = TableHeap(schema, pf, bp)
                val tm = TransactionManager(lm)
                for (i in 1..30) {
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
                assertEquals(commits.size, heap.rowCount())
                val recovered = heap.scan().map { it.get("id") as Long }.toSet()
                assertEquals(commits.toSet(), recovered)
            }}
        }
    }
}
```

```bash
./gradlew test --tests 'com.dbenginelab.wal.CrashSimulationTest'
```

**기대 결과**: `CrashSimulationTest` **3 PASSED**

invariant 대응: CI-1·CI-2·CI-3이 각각 테스트 하나씩에 정확히 대응한다 — 이번 세션은 테스트가 곧 invariant다.

## 4. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** 무작위 시퀀스의 반복 횟수를 10배(30 → 300)로 늘려라. 여전히 통과하는가?

<details><summary>답</summary>

**통과한다.** 정본 코드에 그 경로의 버그가 없기 때문이다. 다만 이 과제의 목적은 버그를 찾는 게 아니라 **무작위 테스트의 성질을 체감하는 것**이다.

주목할 것 두 가지:

**(1) 시드가 고정되어 있다.** `kotlin.random.Random(42)` — 그래서 매번 **같은** 시퀀스가 나온다. 이건 의도적이다. 테스트가 어제는 통과하고 오늘은 실패하면(flaky) 아무도 믿지 않기 때문이다. 대신 **한 가지 시퀀스만 검사한다**는 대가가 있다.

**(2) 시드를 바꾸면 다른 시퀀스가 나온다.** `Random(1)`, `Random(2)`… 로 여러 번 돌려보면 커버리지가 넓어진다. 실무에서는 **CI에서는 고정 시드, 별도 장시간 작업에서는 무작위 시드**로 나눠 돌리고, 무작위 쪽에서 실패하면 **그 시드를 기록해 고정 테스트로 승격**시킨다.

실패했을 때 최소화하는 방법도 알아둬라 — 실패한 시퀀스를 절반씩 잘라가며 여전히 실패하는 최소 부분을 찾는다. 이걸 자동화한 것이 **shrinking**이고, property-based testing 라이브러리(Kotest, jqwik)의 핵심 기능이다.
</details>

**2.** `LogManager.replay`의 EOF 처리를 지우고 예외가 그대로 나가게 해라. 어느 테스트가 잡나? 실제 운영에서 이 예외가 나면 DB는 어떻게 해야 옳은가?

<details><summary>답</summary>

`WAL 파일 끝 partial bytes — replay 시 무시`(08-03)와 `partial trailing record는 EOF로 안전 처리`(08-01) **둘 다** 실패한다.

운영에서의 선택은 둘이고, **둘 다 위험하다**:

| 선택 | 위험 |
|---|---|
| **기동 거부** | 잘린 꼬리 하나 때문에 DB가 안 뜬다. 그 트랜잭션은 어차피 커밋 안 됐는데도. 새벽 3시에 서비스가 멈춘다 |
| **잘린 부분만 버리고 기동** | 손상이 "잘림"이 아니라 "중간 훼손"이면 **뒤의 멀쩡한 커밋을 조용히 버린다.** 아무도 모른 채 데이터가 사라진다 |

현실의 DB는 **둘을 구분해서** 처리한다:

- **파일 끝의 불완전한 레코드** → 정상적인 crash 흔적으로 보고 버린다. 조용히.
- **체크섬 불일치** → 손상으로 보고 **기동을 거부하거나 크게 경고**한다.

구분의 근거가 **체크섬**이다. 우리 코드에는 없어서 둘을 구분할 수 없고, 그래서 "조용히 버린다" 하나만 택할 수 있다. 01-01 과제 4번에서 나온 결론이 여기서 다시 청구된다.
</details>

**3.** 로그 파일 **중간**을 손상시켜라(끝이 아니라). replay는 어떻게 되나? "잘림"과 "손상"을 구분할 수 있나?

<details><summary>답</summary>

**구분하지 못한다.** 그리고 결과가 손상 값에 따라 갈린다:

| 손상된 길이 필드 | replay 결과 |
|---|---|
| 남은 파일보다 큼 | `EOFException` → **거기서 조용히 멈춘다.** 뒤의 멀쩡한 레코드 전부 유실 |
| 여전히 파일 안 | 쓰레기를 레코드로 해석 → 알 수 없는 tag면 예외, 운 나쁘면 **유효한 레코드로 읽힌다** |

두 번째가 최악이다. 손상된 바이트가 우연히 `CommitTx(txId=…)`로 읽히면 **커밋한 적 없는 트랜잭션이 커밋된 것으로 복구된다.**

첫 번째도 심각하다 — 파일 중간이 깨졌을 뿐인데 그 뒤 **몇 시간치 커밋을 조용히 버린다.** 그리고 `replay`는 정상 종료한 것처럼 보인다.

필요한 것은 두 가지다:
1. **레코드별 체크섬** — 이 레코드가 온전한지 판정
2. **손상 시의 정책** — 멈출 것인가, 건너뛸 것인가, 기동을 거부할 것인가

지금 코드에는 1번이 없어서 2번을 정할 수조차 없다. **"무엇이 잘못됐는지 모르면 무엇을 할지도 정할 수 없다"** — 관측 가능성이 정책의 전제라는 이야기다.
</details>

**4.** 이 세 테스트가 못 잡는 crash 시나리오를 하나 더 생각해내서 **직접 테스트로 써라.**

<details><summary>답</summary>

가장 큰 사각은 **"복구 도중의 crash"** 다. 지금 세 테스트는 전부 **정상 실행 중 crash → 복구는 끝까지 성공**을 가정한다.

써볼 만한 시나리오:

**(a) apply 루프 중간에 죽는 경우**
```
Recovery가 3건 중 2건을 heap에 넣고 죽는다
→ 재시작해서 다시 복구하면 그 2건이 또 들어가나?
→ 08-02의 IdempotentRecovery라면 recovery.meta 덕에 막힌다
→ 08-01의 Recovery라면? 막지 못한다
```
`Recovery`(08-01)와 `IdempotentRecovery`(08-02)에 **같은 시나리오를 각각 돌려 결과가 다른 것**을 보이면 08-02가 무엇을 해결했는지 테스트로 증명된다.

**(b) `recovery.meta`만 갱신되고 죽는 경우** — 08-02 과제 2번이 다루는 유실 시나리오를 테스트로 고정.

**(c) 로그가 비어 있는 상태에서 복구** — 신규 DB의 첫 기동. 예외 없이 0건으로 끝나야 한다. **경계값 테스트가 하나도 없다.**

(c)가 가장 쉽고, 의외로 실제 버그가 자주 숨는 곳이다. 거기부터 써봐라.
</details>

## 5. 다음 한계

crash에는 버텼지만 **동시성은 여전히 없다.** 두 트랜잭션이 같은 데이터를 동시에 건드리면 무슨 일이 일어나는지 아무도 정의하지 않았다.

→ **단계 9 LockManager**.
