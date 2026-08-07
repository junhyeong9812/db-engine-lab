# impl/10-03 — MVCCTableHeap (보강 C4 — MVCC + TableHeap 통합)

> **종류**: 보강형
> **상위 단계**: `docs/stages/10-mvcc.md`
> **코드 정본**: git `5505edc` — "complete: 21 stages + 12 보강 (120/120 tests)"
> **이 세션의 범위**: 메모리 데모였던 MVCC를 디스크의 `TableHeap` 위에 얹는다.
> **작성 파일**:
> - 신규: `src/main/kotlin/com/dbenginelab/mvcc/MVCCTableHeap.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/mvcc/MVCCTableHeapTest.kt`
> **검증**: `MVCCTableHeapTest` 2 PASSED
> **예상 타이핑 시간**: 30분

---

## 0. 보강 동기

codex 지적 C4: `MVCCStore<K, V>`는 **in-memory 데모**다. 실제 데이터가 있는 `TableHeap`(디스크)과 아무 관계가 없다. 이대로면 MVCC를 "이해했다"고 말할 수는 있어도 "쓴다"고 말할 수 없다.

## 1. 만족시킬 invariant

- **CI-1**: 기존 heap의 행들이 bootstrap 시 `xid = 0` 버전으로 등록된다 (= 태초부터 커밋된 것으로 취급).
- **CI-2**: insert/delete는 in-memory 버전 체인을 갱신한다.
- **CI-3**: `read(key, snapshot)`은 그 snapshot에 보이는 **가장 최근** 버전을 준다.

## 2. 의존성

- `impl/10-01-mvcc.md` (`MVCCStore`의 가시성 규칙)
- `impl/06-01-table-seqscan.md` (`TableHeap`)

## 3. 문제 정의

디스크의 행에 버전을 붙이려면 원래는 tuple마다 `xmin`/`xmax`를 저장해야 한다(PostgreSQL이 그렇게 한다). 그러면 `Tuple` 인코딩과 page 레이아웃을 전부 바꿔야 하고, 그건 단계 4·6을 되돌리는 일이다.

이 세션은 **더 싼 길**을 택한다 — 버전 체인은 메모리에 두고, 디스크 heap은 그대로 둔다. bootstrap 때 기존 행을 전부 읽어 "xid=0에 만들어진 버전"으로 등록한다.

이 선택의 대가를 분명히 하자: **버전 정보가 재시작에서 살아남지 못한다.** 그래서 이건 통합의 **첫 걸음**이지 완성이 아니다. 그 한계가 §6 과제와 §7에 그대로 남는다.

## 4. 구현 코드

```kotlin
// src/main/kotlin/com/dbenginelab/mvcc/MVCCTableHeap.kt @ 5505edc
package com.dbenginelab.mvcc

import com.dbenginelab.catalog.TableSchema
import com.dbenginelab.catalog.Tuple
import com.dbenginelab.table.TableHeap
import java.io.Closeable

/**
 * Stage 10 보강 (C4): MVCC + TableHeap 통합.
 *
 * TableHeap에 disk-persistent tuples를 두고, in-memory MVCC version chain
 * 으로 visibility 관리. 각 row는 PK로 식별 (PK 컬럼 이름 caller가 지정).
 *
 * Insert/Update/Delete는 in-memory chain 갱신 + commit 시 heap.insert로 flush.
 *
 * Limitations:
 *  - 학습 데모 — vacuum 없음, version chain 누적.
 *  - Persistence: 마지막 visible version만 heap에 (간소화).
 *  - PK column = BIGINT 만.
 */
class MVCCTableHeap(
    val heap: TableHeap,
    private val pkColumn: String,
) : Closeable {

    private val mvcc = MVCCStore<Long, Tuple>()
    val schema: TableSchema get() = heap.schema

    init {
        // Bootstrap: existing heap rows become committed version with xid=0.
        for (tuple in heap.scan()) {
            val key = tuple.get(pkColumn) as Long
            mvcc.insert(key, tuple, xid = 0L)
        }
    }

    fun insert(tuple: Tuple, xid: Long) {
        require(tuple.schema == schema)
        val key = tuple.get(pkColumn) as Long
        mvcc.insert(key, tuple, xid)
    }

    fun delete(key: Long, xid: Long) {
        mvcc.delete(key, xid)
    }

    fun read(key: Long, snapshot: MVCCStore.Snapshot): Tuple? =
        mvcc.get(key, snapshot)

    fun versionCount(key: Long): Int = mvcc.versionCount(key)

    override fun close() {
        heap.close()
    }
}
```

## 5. 검증 테스트 (green)

```kotlin
// src/test/kotlin/com/dbenginelab/mvcc/MVCCTableHeapTest.kt @ 5505edc
package com.dbenginelab.mvcc

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
import kotlin.test.assertNull

class MVCCTableHeapTest {
    private val schema = TableSchema(
        name = "users",
        columns = listOf(
            ColumnDef("id", Type.BIGINT, nullable = false),
            ColumnDef("name", Type.STRING, nullable = false),
        ),
    )

    @Test
    fun `MVCC TableHeap insert + snapshot read`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("u.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val mvccHeap = MVCCTableHeap(heap, "id")
            val sp = SnapshotProvider()

            val tx1 = sp.begin()
            mvccHeap.insert(Tuple(schema, listOf(1L, "Alice")), tx1.xid)
            sp.commit(tx1)

            val tx2 = sp.begin()
            val found = mvccHeap.read(1L, tx2)!!
            assertEquals("Alice", found.get("name"))
        }}
    }

    @Test
    fun `delete 후 옛 snapshot은 보고 새 snapshot은 못 봄`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("u.data").toString()
        PagedFile(path).use { pf -> BufferPool(pf, 16).use { bp ->
            val heap = TableHeap(schema, pf, bp)
            val mvccHeap = MVCCTableHeap(heap, "id")
            val sp = SnapshotProvider()

            val tx1 = sp.begin()
            mvccHeap.insert(Tuple(schema, listOf(1L, "A")), tx1.xid); sp.commit(tx1)
            val older = sp.begin()  // delete 전 snapshot
            val tx2 = sp.begin()
            mvccHeap.delete(1L, tx2.xid); sp.commit(tx2)

            assertEquals("A", mvccHeap.read(1L, older)?.get("name"))
            val newer = sp.begin()
            assertNull(mvccHeap.read(1L, newer))
        }}
    }
}
```

```bash
./gradlew test --tests 'com.dbenginelab.mvcc.MVCCTableHeapTest'
```

**기대 결과**: `MVCCTableHeapTest` **2 PASSED**

invariant 대응:
- **CI-1**, **CI-3** ← `MVCC TableHeap insert + snapshot read`
- **CI-2**, **CI-3** ← `delete 후 옛 snapshot은 보고 새 snapshot은 못 봄`

## 6. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** insert 후 **프로세스를 재시작**하고 옛 snapshot으로 읽어봐라. 무엇이 사라졌나?

<details><summary>답</summary>

**버전 이력 전체가 사라진다.** 남는 것은 heap에 flush된 tuple들뿐이다.

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
private val mvcc = MVCCStore<Long, Tuple>()   // ← 메모리에만 있다
init {
    for (tuple in heap.scan()) {
        mvcc.insert(key, tuple, xid = 0L)      // ← 재시작하면 전부 xid=0으로 다시 만들어진다
    }
}
```

재시작 후에는 모든 행이 "태초부터 있었던 것"이 되고, **삭제 표시(tombstone)도 사라진다.** 즉 tombstone만 있고 heap에서 안 지워진 행은 **되살아난다.**

한 문장으로: **버전 체인을 메모리에만 두기로 한 §3의 결정이, 재시작 시 MVCC가 통째로 초기화된다는 대가로 돌아온다.**

그래서 이 클래스는 "MVCC를 디스크에 붙였다"가 아니라 **"디스크 데이터를 MVCC로 감쌌다"**가 정확한 표현이다. 지속성은 heap이 갖고, 버전은 프로세스 수명만큼만 산다.
</details>

**2.** bootstrap에서 기존 행에 `xid = 0` 대신 현재 xid를 주면 무엇이 깨지나?

<details><summary>답</summary>

**그 시점에 이미 열려 있던 snapshot에게 기존 데이터가 안 보이게 된다.**

`isVisible`의 첫 줄을 보면 이유가 명확하다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
if (v.xidStart > xid) return false   // 내 snapshot보다 나중에 생긴 버전은 안 보인다
```

bootstrap이 `xid = 100`을 줬는데 어떤 트랜잭션의 snapshot이 `xid = 50`이면, **원래 있던 데이터 전부가 그 트랜잭션에게 사라진다.** 디스크에는 멀쩡히 있는데.

`xid = 0`은 "**어떤 트랜잭션보다도 먼저 존재했다**"는 선언이다. 모든 snapshot의 `xid`가 1 이상이므로 `0 > xid`가 결코 성립하지 않아 **누구에게나 보인다.**

이건 MVCC 시스템의 관용적 장치다 — PostgreSQL도 `FrozenTransactionId`(xid 2)를 두고, 아주 오래된 tuple을 "동결"시켜 xid 순환(wraparound) 문제를 피한다. **"태초"를 나타내는 특별한 값**이 필요하다는 발상이 같다.
</details>

**3.** delete를 tombstone 대신 heap에서 실제로 지우도록 바꾸면? `TableHeap`이 삭제를 지원하지 않는다는 사실이 오히려 도움이 되는 지점은?

<details><summary>답</summary>

**바꿀 수가 없다.** `TableHeap`에 삭제 API가 아예 없기 때문이다 — 06-01에서 append-only + slot directory 미사용으로 단순화한 결과다.

그래서 **강제로 tombstone 방식이 된다.** 이게 도움이 되는 지점이다:

- 10-01 과제 2번에서 본 문제(실제 제거하면 옛 snapshot이 과거를 못 봄)를 **애초에 저지를 수 없다.**
- MVCC와 저장 계층의 요구가 우연히 일치한다 — MVCC는 "지우지 말고 표시만 하라"고 요구하는데, heap이 애초에 지울 줄 모른다.

다만 대가도 분명하다. **heap은 계속 자라기만 한다.** 삭제된 행이 디스크에서 회수되지 않고, 회수하려면 결국 06-01로 돌아가 slot directory와 free space 관리를 만들어야 한다.

여기서 볼 것 — **초반의 단순화가 나중에 걸림돌이 되기도 하고(06-04 과제 3번의 duplicate key), 우연히 방어선이 되기도 한다.** 어느 쪽인지는 나중 요구를 봐야 알 수 있고, 그래서 "지금 필요한 만큼만 만든다"가 합리적인 전략이 된다.
</details>

**4.** 버전을 디스크에 저장하려면 `Tuple` 인코딩을 어떻게 바꿔야 하나? 한 행당 몇 바이트가 늘어나나?

<details><summary>답</summary>

필요한 필드는 최소 두 개다:

| 필드 | 크기 | 의미 |
|---|---|---|
| `xmin` (= `xidStart`) | 8B (`Long`) | 이 버전을 만든 트랜잭션 |
| `xmax` (= `xidEnd`) | 8B (`Long`) | 이 버전을 끝낸 트랜잭션 |

**한 행당 16바이트 증가.** 04-01에서 계산한 행 크기가 대략 24바이트였으니 **약 67% 증가**다. (PostgreSQL의 실제 tuple header는 23바이트로 더 크다 — ctid·infomask 등이 더 붙는다.)

그런데 진짜 비용은 크기가 아니다:

1. **행 하나가 여러 버전으로 늘어난다.** 100번 갱신한 행은 물리적으로 101개 행이 된다. 저장 공간이 갱신 횟수에 비례한다.
2. **인덱스가 모든 버전을 가리켜야 한다.** 그래서 인덱스도 부풀고, 조회 시 "이 버전이 나에게 보이나"를 매번 확인해야 한다.
3. **회수하는 주체가 필요하다.** 아무도 안 보는 버전을 찾아 지우는 일 — 그게 **VACUUM**이다.

그래서 "MVCC를 디스크에 제대로 얹는다"는 것은 인코딩 몇 바이트 추가가 아니라 **저장·인덱스·정리 세 계층을 함께 바꾸는 일**이다. 10-03이 메모리 체인이라는 지름길을 택한 이유가 여기 있다.
</details>

## 7. 다음 한계

MVCC가 디스크 데이터를 보긴 하지만 **버전 자체는 여전히 메모리에만 있다.** 재시작하면 모든 버전 이력이 사라지고 현재 상태만 남는다.

그리고 지금까지 만든 것은 전부 **저장과 동시성**이다. 질의를 어떻게 **효율적으로** 실행할지는 아무도 결정하지 않았다 — 인덱스가 있어도 쓸지 말지 판단하는 주체가 없다.

→ **단계 11 Optimizer**.
