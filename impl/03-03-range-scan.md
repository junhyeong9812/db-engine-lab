# impl/03-03 — Range Scan

> **종류**: 세션형 (신규 개념 도입 — 실패 테스트 → 구현 → 검증)
> **상위 단계**: `docs/stages/03-index.md`
> **코드 정본**: git `742c55c` — "stage 3-3: range scan (21/21 tests)"
> **이 세션의 범위**: point lookup(`search`)만 되던 B+tree에 구간 조회(`rangeScan`)를 붙인다.
> **작성 파일**:
> - 수정: `src/main/kotlin/com/dbenginelab/storage/BTreeIndex.kt` — `rangeScan` 메서드 추가
> - 수정: `src/test/kotlin/com/dbenginelab/storage/BTreeIndexTest.kt` — 테스트 2개 추가
> **검증**: `BTreeIndexTest` 10 PASSED (range scan 2 추가) · storage 전체 누적 21 PASSED
> **예상 타이핑 시간**: 25분 (실제 신규 타이핑은 §5의 `rangeScan` 한 블록 — 전문을 싣는 이유는 §5 머리말 참조)

---

## 0. 참조

### 주 참조 (SimpleDB)
- `BTreeFile.indexIterator` — leaf sibling traversal 패턴.
- 우리 코드 대응: `BTreeIndex.rangeScan` — iterator 대신 `List<Pair<Long, Long>>` 일괄 반환(단순화).

### 차이 채택 여부
- SimpleDB는 lazy iterator, 우리는 eager list. **채택 안 함** — 단계 3의 초점은 sibling pointer 순회이고, iterator의 lifecycle(순회 내내 페이지 pin 유지)은 단계 6 Operator에서 다룬다.

### 핵심 설계 결정 근거
- leaf는 `auxPage`를 sibling pointer로 재사용한다(internal 노드에서는 leftmost child를 가리킨다). 한 필드를 노드 종류에 따라 다르게 쓰는 선택 — page 헤더를 늘리지 않는 대신 "auxPage의 의미가 문맥 의존"이라는 값을 치른다.

## 1. 만족시킬 invariant

- **CI-7**: `rangeScan(from, to)` → `from <= key < to` 인 모든 key를 **ascending order**로 반환.
- **CI-8**: leaf 경계를 넘어 sibling pointer(`auxPage`)를 따라 **끊김 없이** 이어진다.

## 2. 의존성

- 이전 세션: `impl/03-02-btree-split.md` (split + root promotion까지 끝난 `BTreeIndex.kt` 245줄 상태)
- 파일/클래스: `BTreePage`(`findSlot`·`keyAt`·`valueAt`·`auxPage`·`INVALID`), `BufferPool`(`fetchPage`·`unpinPage`), `PagedFile`

## 3. 문제 정의 (TDD step 1)

지금 B+tree는 `search(key)` 하나만 안다. 키 하나를 주면 값 하나를 준다.

그런데 SQL이 실제로 던지는 질문은 이렇다 — `WHERE age >= 100 AND age < 200`. 키 하나가 아니라 **구간**이다. `search`를 100번 호출해 흉내낼 수는 있지만, 그러면 root에서 leaf까지 내려가는 navigation을 100번 반복한다. B+tree가 leaf들을 sibling pointer로 엮어둔 이유가 정확히 이걸 피하기 위해서다 — **한 번만 내려가고, 그 다음부터는 옆으로 걷는다.**

구체 시나리오: 1..400을 넣은 뒤 `rangeScan(100, 200)` → 100개가 `(100,200), (101,202), …, (199,398)` 순으로 나와야 한다. 그리고 `MAX_ENTRIES + 200`개를 넣어 split이 여러 번 일어난 뒤 전 구간을 훑어도 하나도 빠지지 않아야 한다(CI-8).

알고리즘 스케치 — §5를 치기 전에 이걸 먼저 보고 자기 손으로 재현해봐라:

```
1. findLeafPage(from) → 시작 leaf
2. while leaf != INVALID:
     각 entry where key >= from:
       if key >= to: return result
       add (key, value)
     leaf = leaf.auxPage  (sibling)
```

## 4. 실패 테스트 (TDD step 2)

아래 파일을 통째로 저장한다. 뒤쪽 두 개(`range scan …`)가 이번에 새로 추가되는 것이고 나머지 8개는 03-01·03-02에서 이미 친 것이다 — 이 파일이 곧 이번 세션의 최종 테스트 파일이다.

```kotlin
// src/test/kotlin/com/dbenginelab/storage/BTreeIndexTest.kt @ 742c55c
package com.dbenginelab.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BTreeIndexTest {

    @Test
    fun `insert 후 search로 같은 값 찾는다`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                idx.insert(42L, 1000L)
                idx.insert(100L, 2000L)
                idx.insert(7L, 3000L)

                assertEquals(1000L, idx.search(42L))
                assertEquals(2000L, idx.search(100L))
                assertEquals(3000L, idx.search(7L))
            }
        }
    }

    @Test
    fun `존재하지 않는 key는 null 반환`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                idx.insert(1L, 10L)
                assertNull(idx.search(999L))
            }
        }
    }

    @Test
    fun `정렬 안 된 순서로 insert 해도 정렬된 상태 유지`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                val keys = listOf(50L, 10L, 80L, 30L, 70L, 20L, 60L, 90L, 40L)
                keys.forEachIndexed { i, k -> idx.insert(k, (i * 1000).toLong()) }

                for ((i, k) in keys.withIndex()) {
                    assertEquals((i * 1000).toLong(), idx.search(k), "key=$k")
                }
            }
        }
    }

    @Test
    fun `duplicate key insert는 IllegalArgumentException`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                idx.insert(5L, 100L)
                assertThrows<IllegalArgumentException> { idx.insert(5L, 200L) }
            }
        }
    }

    @Test
    fun `reopen 후에도 데이터 보존 (BufferPool flush)`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                idx.insert(1L, 10L)
                idx.insert(2L, 20L)
                idx.insert(3L, 30L)
                idx.close()
            }
        }
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 16).use { bp ->
                val idx = BTreeIndex(pf, bp)
                assertEquals(10L, idx.search(1L))
                assertEquals(20L, idx.search(2L))
                assertEquals(30L, idx.search(3L))
                assertEquals(3, idx.size())
            }
        }
    }

    @Test
    fun `MAX_ENTRIES 초과 insert 시 split 발생, 모두 검색 가능`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 32).use { bp ->
                val idx = BTreeIndex(pf, bp)
                val n = BTreePage.MAX_ENTRIES + 50
                for (k in 1..n) {
                    idx.insert(k.toLong(), (k * 10).toLong())
                }
                assertEquals(n, idx.size())
                for (k in 1..n) {
                    assertEquals((k * 10).toLong(), idx.search(k.toLong()), "key=$k")
                }
            }
        }
    }

    @Test
    fun `정렬 안 된 순서로 대량 insert (split 다회 발생) 후에도 모두 검색 가능`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 64).use { bp ->
                val idx = BTreeIndex(pf, bp)
                // Mix of orderings: ascending, descending, random-ish.
                val keys = mutableListOf<Long>()
                for (k in 1..200L) keys += k
                for (k in 500L downTo 300L) keys += k
                for (k in 250L..299L) keys += k
                keys.forEachIndexed { i, k -> idx.insert(k, i.toLong()) }

                for ((i, k) in keys.withIndex()) {
                    assertEquals(i.toLong(), idx.search(k), "key=$k")
                }
                assertEquals(keys.size, idx.size())
            }
        }
    }

    @Test
    fun `range scan은 정렬 순서로 in-range 키만 반환`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 32).use { bp ->
                val idx = BTreeIndex(pf, bp)
                for (k in 1L..400L) idx.insert(k, k * 2)

                // Q: to=200 인데 왜 마지막이 199인가? 경계를 닫으면(<=) 무엇이 곤란해지나?
                val r = idx.rangeScan(100L, 200L)
                // <details><summary>A</summary>
                //
                // half-open [from, to) 이면 연속 구간을 이어붙일 때 겹치지 않는다 — scan(0,100)+scan(100,200)이 곧 scan(0,200).
                // </details>
                assertEquals(100, r.size)
                assertEquals(100L to 200L, r.first())
                assertEquals(199L to 398L, r.last())
            }
        }
    }

    @Test
    fun `range scan은 leaf 경계를 넘어 sibling pointer 따라간다`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 32).use { bp ->
                val idx = BTreeIndex(pf, bp)
                // Q: 왜 MAX_ENTRIES + 200 인가? 이 수를 MAX_ENTRIES - 1 로 낮추면 무엇을 못 잡나?
                val n = BTreePage.MAX_ENTRIES + 200  // split 다회 발생
                // <details><summary>A</summary>
                //
                // leaf가 하나뿐이면 sibling을 한 번도 안 따라가므로 CI-8이 전혀 검증되지 않는다 — split을 강제해야 경계 넘기가 일어난다.
                // </details>
                for (k in 1..n) idx.insert(k.toLong(), k.toLong())

                val r = idx.rangeScan(1L, (n + 1).toLong())
                assertEquals(n, r.size)
                for (i in 0 until n) {
                    assertEquals((i + 1).toLong() to (i + 1).toLong(), r[i])
                }
            }
        }
    }

    @Test
    fun `split 후 reopen해도 일관성 유지`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("btree.db").toString()
        val n = BTreePage.MAX_ENTRIES + 100
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 32).use { bp ->
                val idx = BTreeIndex(pf, bp)
                for (k in 1..n) idx.insert(k.toLong(), (k * 7).toLong())
                idx.close()
            }
        }
        PagedFile(path).use { pf ->
            BufferPool(pf, capacity = 32).use { bp ->
                val idx = BTreeIndex(pf, bp)
                for (k in 1..n) {
                    assertEquals((k * 7).toLong(), idx.search(k.toLong()), "key=$k after reopen")
                }
            }
        }
    }
}
```

**예상 실패**: **컴파일 실패** — `Unresolved reference: rangeScan` (BTreeIndexTest.kt 두 곳).

아직 `BTreeIndex`에 `rangeScan`이 없으므로 테스트 소스 자체가 컴파일되지 않는다. 실행이 아니라 **빌드가 빨갛게 되는 것**이 이번 세션의 출발점이다. `./gradlew test` 로 직접 확인하고 넘어갈 것 — 넘겨짚지 말고 그 메시지를 눈으로 봐라.

## 5. 구현 코드 (TDD step 3 — make it pass)

`BTreeIndex.kt` **전문**이다. 실제로 새로 타이핑할 곳은 `search` 바로 아래 `rangeScan` 한 블록뿐이다. 그럼에도 전문을 싣는 이유는 — **지금 내 파일이 정본과 같은 상태인지 대조할 수 있어야** 다음 단계에서 원인 모를 실패를 만나지 않기 때문이다. 다르면 지금 맞춰라.

```kotlin
// src/main/kotlin/com/dbenginelab/storage/BTreeIndex.kt @ 742c55c
package com.dbenginelab.storage

import java.io.Closeable

/**
 * B+tree index storing Long → Long mappings.
 *
 * Stage 3-1 limitations:
 *  - Single-leaf only. No split, no internal nodes.
 *  - When the leaf fills up (MAX_ENTRIES = 255), insert throws UnsupportedOperationException.
 *  - No duplicate keys.
 *  - No delete.
 *
 * Multi-leaf with split is added in stage 3-2; multi-level in stage 3-3.
 */
class BTreeIndex(
    private val pagedFile: PagedFile,
    private val bufferPool: BufferPool,
) : Closeable {

    init {
        // Q: 첫 페이지가 반드시 0번이어야 한다고 check로 못 박는다. 이게 없으면 언제 무엇이 깨지나?
        if (pagedFile.pageCount() == 0) {
        // <details><summary>A</summary>
        //
        // root 위치를 상수 0으로 고정했기 때문 — 어긋나면 reopen 시 엉뚱한 페이지를 root로 읽는다.
        // </details>
            val page = bufferPool.newPage()
            check(page.id.pageNumber == ROOT_PAGE_NUMBER) {
                "first allocated page must be root (page 0), got ${page.id.pageNumber}"
            }
            BTreePage(page).initAsEmpty(BTreePage.Type.LEAF)
            bufferPool.unpinPage(page.id, isDirty = true)
        }
    }

    fun insert(key: Long, value: Long) {
        val leafPageNumber = findLeafPage(key)
        insertIntoLeaf(leafPageNumber, key, value)
    }

    fun search(key: Long): Long? {
        val leafPageNumber = findLeafPage(key)
        val page = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNumber))
        try {
            val btp = BTreePage(page)
            val slot = btp.findSlot(key)
            return if (slot < btp.keyCount && btp.keyAt(slot) == key) btp.valueAt(slot) else null
        } finally {
            bufferPool.unpinPage(page.id, isDirty = false)
        }
    }

    /**
     * Range scan: returns all (key, value) pairs where [fromInclusive] <= key < [toExclusive],
     * in key ascending order. Uses leaf sibling pointers (auxPage on leaves).
     */
    fun rangeScan(fromInclusive: Long, toExclusive: Long): List<Pair<Long, Long>> {
        require(fromInclusive <= toExclusive) { "fromInclusive ($fromInclusive) > toExclusive ($toExclusive)" }
        val result = mutableListOf<Pair<Long, Long>>()
        // Q: navigation은 여기 한 번뿐이고 그 뒤로는 while로 옆으로 간다. search를 반복 호출하는 것과 비용이 어떻게 갈리나?
        var leafPageNo = findLeafPage(fromInclusive)
        // <details><summary>A</summary>
        //
        // navigation 비용이 구간 길이와 무관하게 1회 — B+tree가 leaf를 sibling으로 엮어둔 이유가 이것이다.
        // </details>
        while (leafPageNo != BTreePage.INVALID) {
            val page = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNo))
            try {
                val btp = BTreePage(page)
                val startSlot = btp.findSlot(fromInclusive)
                for (i in startSlot until btp.keyCount) {
                    val k = btp.keyAt(i)
                    // Q: 왜 early return? break로는 왜 안 되나?
                    if (k >= toExclusive) return result
                    // <details><summary>A</summary>
                    //
                    // break는 안쪽 for만 끝내고 바깥 while이 계속 돈다 — 플래그를 하나 더 두어야 한다. return이 단순하고, finally의 unpin은 return 전에 실행된다.
                    // </details>
                    result.add(k to btp.valueAt(i))
                }
                leafPageNo = btp.auxPage
            } finally {
                bufferPool.unpinPage(page.id, isDirty = false)
            }
        }
        return result
    }

    /** Returns total number of (key, value) entries across all leaves. */
    fun size(): Int {
        var total = 0
        var leafPageNo = leftmostLeafPage()
        while (leafPageNo != BTreePage.INVALID) {
            val page = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNo))
            try {
                val btp = BTreePage(page)
                total += btp.keyCount
                leafPageNo = btp.auxPage
            } finally {
                bufferPool.unpinPage(page.id, isDirty = false)
            }
        }
        return total
    }

    private fun leftmostLeafPage(): Int {
        var pageNo = ROOT_PAGE_NUMBER
        while (true) {
            val page = bufferPool.fetchPage(PageId(pagedFile.fileId, pageNo))
            val btp = BTreePage(page)
            if (btp.type == BTreePage.Type.LEAF) {
                bufferPool.unpinPage(page.id, isDirty = false)
                return pageNo
            }
            val next = btp.auxPage
            bufferPool.unpinPage(page.id, isDirty = false)
            pageNo = next
        }
    }

    private fun findLeafPage(key: Long): Int {
        var pageNo = ROOT_PAGE_NUMBER
        while (true) {
            val page = bufferPool.fetchPage(PageId(pagedFile.fileId, pageNo))
            val btp = BTreePage(page)
            if (btp.type == BTreePage.Type.LEAF) {
                bufferPool.unpinPage(page.id, isDirty = false)
                return pageNo
            }
            // INTERNAL: auxPage = leftmost child. entries = (sepKey, rightChild).
            // findSlot(key) returns first slot with sepKey >= key.
            // Child for `key`:
            //   slot == 0 → auxPage (key < sepKey[0])
            //   slot > 0 → valueAt(slot - 1) (sepKey[slot-1] <= key < sepKey[slot])
            // If slot < keyCount and sepKey[slot] == key, we still want right side
            // (B+tree convention: separator key lives in right subtree's leftmost leaf).
            val slot = btp.findSlot(key)
            // B+tree separator convention: if key == sepKey, go RIGHT (separator key lives
            // in right subtree's leftmost leaf). This check must precede the slot==0 branch,
            // otherwise findSlot returning 0 (key == sepKey[0]) would send us to the leftmost
            // child where the key does NOT exist.
            // Q: 세 갈래 중 첫 줄(키가 separator와 같으면 오른쪽)을 맨 아래로 옮기면 어떤 키가 사라지나?
            val childPageNo = when {
            // <details><summary>A</summary>
            //
            // separator와 정확히 같은 키 — slot==0 가지가 먼저 걸려 leftmost child로 내려가는데 거기엔 그 키가 없다(03-02에서 실제로 잡은 버그).
            // </details>
                slot < btp.keyCount && btp.keyAt(slot) == key -> btp.valueAt(slot).toInt()
                slot == 0 -> btp.auxPage
                else -> btp.valueAt(slot - 1).toInt()
            }
            bufferPool.unpinPage(page.id, isDirty = false)
            pageNo = childPageNo
        }
    }

    private fun insertIntoLeaf(leafPageNumber: Int, key: Long, value: Long) {
        val leafPage = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNumber))
        val btp = BTreePage(leafPage)
        val slot = btp.findSlot(key)
        require(!(slot < btp.keyCount && btp.keyAt(slot) == key)) {
            "duplicate key not supported in stage 3: $key"
        }
        if (!btp.isFull()) {
            btp.insertAt(slot, key, value)
            bufferPool.unpinPage(leafPage.id, isDirty = true)
            return
        }
        // Q: split 경로로 넘기기 직전에 왜 unpin하나? 안 하면 무엇이 쌓이나?
        bufferPool.unpinPage(leafPage.id, isDirty = false)
        // <details><summary>A</summary>
        //
        // split 쪽에서 같은 페이지를 다시 fetch한다 — 여기서 안 풀면 pin이 누적되어 BufferPool이 그 프레임을 evict할 수 없다.
        // </details>
        splitLeafAndInsert(leafPageNumber, key, value)
    }

    private fun splitLeafAndInsert(leafPageNumber: Int, key: Long, value: Long) {
        if (leafPageNumber == ROOT_PAGE_NUMBER) {
            splitRootLeaf(key, value)
            return
        }

        val leftPage = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNumber))
        val left = BTreePage(leftPage)

        val rightPageRaw = bufferPool.newPage()
        val right = BTreePage(rightPageRaw)
        right.initAsEmpty(BTreePage.Type.LEAF, parentPage = left.parentPage, auxPage = left.auxPage)
        // Q: right가 left의 옛 sibling을 물려받은 뒤에 left가 right를 가리킨다. 이 두 줄의 순서를 뒤집으면?
        left.auxPage = rightPageRaw.id.pageNumber
        // <details><summary>A</summary>
        //
        // 먼저 left.auxPage를 덮으면 옛 sibling 주소가 사라져 right가 이어받지 못한다 — 그 뒤 leaf 사슬이 통째로 끊긴다(CI-8 붕괴).
        // </details>

        moveHalfTo(left, right)
        insertIntoCorrectSide(left, right, key, value)

        val separator = right.keyAt(0)
        val parentPageNo = left.parentPage

        bufferPool.unpinPage(leftPage.id, isDirty = true)
        bufferPool.unpinPage(rightPageRaw.id, isDirty = true)

        insertIntoParent(parentPageNo, separator, rightPageRaw.id.pageNumber)
    }

    private fun splitRootLeaf(key: Long, value: Long) {
        val rootPage = bufferPool.fetchPage(PageId(pagedFile.fileId, ROOT_PAGE_NUMBER))
        val rootView = BTreePage(rootPage)

        // Allocate leftChild and copy root content into it.
        val leftChildPageRaw = bufferPool.newPage()
        val leftChild = BTreePage(leftChildPageRaw)
        copyPageContent(from = rootView, to = leftChild)
        leftChild.parentPage = ROOT_PAGE_NUMBER

        // Allocate rightChild as empty leaf.
        val rightChildPageRaw = bufferPool.newPage()
        val rightChild = BTreePage(rightChildPageRaw)
        rightChild.initAsEmpty(
            type = BTreePage.Type.LEAF,
            parentPage = ROOT_PAGE_NUMBER,
            auxPage = leftChild.auxPage,
        )
        leftChild.auxPage = rightChildPageRaw.id.pageNumber

        moveHalfTo(leftChild, rightChild)
        insertIntoCorrectSide(leftChild, rightChild, key, value)

        val separator = rightChild.keyAt(0)

        // Turn root into INTERNAL with auxPage=leftChild, single entry (separator, rightChild).
        // Q: root 페이지 번호는 그대로 두고 내용만 INTERNAL로 갈아끼운다. 새 페이지를 root로 삼으면 무엇이 곤란해지나?
        rootView.initAsEmpty(
        // <details><summary>A</summary>
        //
        // root 번호가 바뀌면 그 위치를 어딘가에 따로 저장하고 reopen 때 읽어야 한다 — 상수 0을 유지하는 쪽이 싸다.
        // </details>
            type = BTreePage.Type.INTERNAL,
            parentPage = BTreePage.INVALID,
            auxPage = leftChildPageRaw.id.pageNumber,
        )
        rootView.insertAt(0, separator, rightChildPageRaw.id.pageNumber.toLong())

        bufferPool.unpinPage(rootPage.id, isDirty = true)
        bufferPool.unpinPage(leftChildPageRaw.id, isDirty = true)
        bufferPool.unpinPage(rightChildPageRaw.id, isDirty = true)
    }

    private fun moveHalfTo(left: BTreePage, right: BTreePage) {
        val total = left.keyCount
        val splitPoint = total / 2
        for (i in splitPoint until total) {
            right.insertAt(right.keyCount, left.keyAt(i), left.valueAt(i))
        }
        // Q: 옮긴 절반을 실제로 지우지 않고 keyCount만 줄인다. 남은 바이트는 어떻게 되나?
        left.keyCount = splitPoint
        // <details><summary>A</summary>
        //
        // 그대로 남지만 keyCount 밖이라 아무도 읽지 않는다 — 다음 insertAt이 덮어쓴다. 지우는 비용을 아끼는 통상적 수법.
        // </details>
    }

    private fun insertIntoCorrectSide(left: BTreePage, right: BTreePage, key: Long, value: Long) {
        val firstRightKey = right.keyAt(0)
        if (key < firstRightKey) {
            val slot = left.findSlot(key)
            left.insertAt(slot, key, value)
        } else {
            val slot = right.findSlot(key)
            right.insertAt(slot, key, value)
        }
    }

    private fun copyPageContent(from: BTreePage, to: BTreePage) {
        to.type = from.type
        to.keyCount = 0
        to.auxPage = from.auxPage
        to.parentPage = from.parentPage
        val count = from.keyCount
        for (i in 0 until count) {
            to.insertAt(to.keyCount, from.keyAt(i), from.valueAt(i))
        }
    }

    private fun insertIntoParent(parentPageNo: Int, separator: Long, rightChildPageNo: Int) {
        val parentPage = bufferPool.fetchPage(PageId(pagedFile.fileId, parentPageNo))
        val parent = BTreePage(parentPage)
        if (!parent.isFull()) {
            val slot = parent.findSlot(separator)
            parent.insertAt(slot, separator, rightChildPageNo.toLong())
            bufferPool.unpinPage(parentPage.id, isDirty = true)
        } else {
            bufferPool.unpinPage(parentPage.id, isDirty = false)
            // Q: 여기가 이 단계의 진짜 천장이다. 어떤 입력이 이 예외를 띄우나?
            throw UnsupportedOperationException(
            // <details><summary>A</summary>
            //
            // internal 노드가 꽉 찰 만큼 leaf split이 반복될 때 — 대략 MAX_ENTRIES² 규모의 키를 넣으면 도달한다. §8 참조.
            // </details>
                "internal node split not yet supported (stage 3-3)"
            )
        }
    }

    override fun close() {
        bufferPool.flushAll()
    }

    companion object {
        const val ROOT_PAGE_NUMBER: Int = 0
    }
}
```

> **정본 특이사항 (고치지 말 것)**: 맨 위 KDoc은 "Stage 3-1 limitations … single-leaf only"라고 적혀 있지만 3-2에서 split이 들어오면서 이미 낡은 주석이 되었다. 정본 그대로 옮긴 것이니 손대지 말고, "코드는 자라는데 주석은 안 자란다"의 실물 표본으로 봐라.

## 6. 검증 테스트 (TDD step 4 — green)

이번 세션은 테스트 파일에 더 손댈 것이 없다 — **§4의 파일이 곧 최종본**이다. `rangeScan` 구현이 들어간 지금 그대로 다시 돌린다.

```bash
./gradlew test --tests 'com.dbenginelab.storage.BTreeIndexTest'
```

**기대 결과**: `BTreeIndexTest` **10 PASSED** (§4에서 컴파일조차 안 되던 range scan 2개 포함). storage 전체로는 누적 **21 PASSED**.

invariant 대응:
- **CI-7** ← `range scan은 정렬 순서로 in-range 키만 반환` — 경계값 `199 → 398` 이 half-open을 잡는다.
- **CI-8** ← `range scan은 leaf 경계를 넘어 sibling pointer 따라간다` — `MAX_ENTRIES + 200` 으로 split 다회를 강제한다.

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** `rangeScan(fromInclusive = 500, toExclusive = 100)` 을 호출하면? `require`를 지우면 대신 무엇이 반환되는가?

<details><summary>답</summary>

`require`가 있으면 `IllegalArgumentException("fromInclusive (500) > toExclusive (100)")`.

지우면 **빈 리스트가 조용히 반환된다.** 더 나쁜 건 없다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
var leafPageNo = findLeafPage(500)      // 500이 있을 leaf로 감
val startSlot = btp.findSlot(500)       // 500 이상인 첫 자리
for (i in startSlot until btp.keyCount) {
    val k = btp.keyAt(i)                // k >= 500
    if (k >= toExclusive) return result // 100 이상이니 첫 원소에서 즉시 return
```

첫 키가 이미 `100`보다 크므로 바로 빠져나온다. `result`는 비어 있다.

**"빈 리스트"가 왜 나쁜가**를 생각해봐라. 호출자 입장에서 "그 구간에 데이터가 없다"와 "인자를 거꾸로 줬다"가 **구분되지 않는다.** 조건을 뒤집어 쓴 버그가 정상 결과처럼 보이며 지나간다.

`require`는 "실패를 앞당기는" 장치다 — 02-01 과제 2번의 zero-fill과 같은 발상이다.
</details>

**2.** `leafPageNo = btp.auxPage` 줄을 지우고 테스트를 돌려라. 통과하는 테스트와 실패하는 테스트를 가르는 조건은?

<details><summary>답</summary>

**실측: 10개 중 2개 실패** — range scan 테스트 둘 다. 나머지 8개는 통과한다.

가르는 조건은 딱 하나 — **`rangeScan`을 쓰는가.** `search`·`insert`·`size`는 이 줄을 지나지 않는다(`size()`는 자기 루프에 별도의 `auxPage` 이동을 갖고 있다).

range scan 두 개가 **둘 다** 실패한다는 점이 중요하다. 이름만 보면 sibling을 다루는 건 두 번째뿐인 것 같지만:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
`range scan은 정렬 순서로 in-range 키만 반환` → n = 400
`range scan은 leaf 경계를 넘어 sibling…`     → n = MAX_ENTRIES + 200 = 455
```

**400도 이미 255(MAX_ENTRIES)를 넘는다.** leaf가 여러 개라 첫 번째 테스트도 사슬을 탄다. 만약 `n = 200`이었다면 leaf가 하나뿐이라 이 테스트는 **버그를 못 잡았을 것이다.**

교훈: 이 결함이 잡히는지는 **테스트 데이터의 크기**가 결정한다. 경계값(`MAX_ENTRIES`)을 넘기지 않는 테스트는 sibling 관련 결함에 대해 아무 말도 해주지 않는다.
</details>

**3.** `rangeScan(Long.MIN_VALUE, Long.MAX_VALUE)` 로 전체 스캔하면 `size()`와 같은 수가 나오는가? 100만 건이라면 무엇이 문제인가?

<details><summary>답</summary>

**같은 수가 나온다.** `findLeafPage(Long.MIN_VALUE)`는 항상 leftmost leaf로 내려가고, 거기서부터 사슬 끝까지 걷되 `k >= Long.MAX_VALUE`는 성립하지 않으므로 전부 담는다. `size()`가 하는 일과 사실상 같아진다.

문제는 **API 형태**다:

```kotlin
// (발췌 — 설명용. 파일이 아니므로 그대로 치지 말 것)
fun rangeScan(...): List<Pair<Long, Long>>
//                  ^^^^ 전부 메모리에 올린다
```

100만 건이면 `Pair` 객체 100만 개가 힙에 쌓인다. 객체 하나당 대략 40~48바이트(객체 헤더 + Long 박싱 2개 참조)라 **수십 MB**다. 게다가 **첫 결과를 받기까지 마지막 leaf까지 다 읽어야 한다** — `LIMIT 10`짜리 질의도 전체를 훑고 나서야 10개를 준다.

01-01의 `scanAll()`과 **똑같은 문제**다. 그때는 record 전체를 `List`에 담았고, 지금은 구간 전체를 담는다.

해법은 `Sequence`(lazy)로 바꾸는 것인데, 그러면 새 문제가 생긴다 — **순회하는 동안 page를 pin한 채로 있어야 한다.** 호출자가 순회를 중간에 멈추면 unpin은 누가 하나? 그게 단계 6 `Operator`가 다루는 lifecycle 문제이고, §0에서 "iterator는 단계 6에서"라고 미뤄둔 이유다.
</details>

**4.** 스캔 도중 다른 스레드가 insert하면? 무엇이 어떻게 어긋날 수 있는지 적어라.

<details><summary>답</summary>

지금은 **막을 수단이 없다.** 어긋날 수 있는 것들:

1. **읽는 도중 split이 일어난다.** 스캔이 leaf A를 읽고 `auxPage`를 따라 B로 가려는 순간, insert가 A를 split해서 A와 A' 사이에 새 page를 끼워 넣는다. `auxPage`를 이미 읽었다면 **새로 생긴 A'를 통째로 건너뛴다.**
2. **같은 키를 두 번 본다.** split로 절반이 오른쪽으로 옮겨간 뒤 스캔이 그 오른쪽을 다시 읽으면, 이미 담은 키를 또 담는다.
3. **BufferPool 수준의 경합.** 02-02 과제 4번과 같은 문제 — 스캔이 들고 있는 page가 evict될 수 있다.

1·2번을 묶어 부르는 이름이 **팬텀(phantom)** 이다. "같은 조건으로 두 번 읽었는데 결과 집합이 다르다."

주목할 점은 이게 **락만으로도, MVCC만으로도 각각 풀 수 있다**는 것이다 — 단계 9는 스캔 구간을 잠가서, 단계 10은 스캔이 과거 스냅샷을 보게 해서. 10-02의 `phantom 방지` 테스트가 후자를 검증한다. 두 접근의 대가가 어떻게 다른지는 그때 다시 만난다.
</details>

**5.** `insertIntoParent`의 `UnsupportedOperationException`을 실제로 띄워봐라. 키를 몇 개 넣어야 도달하는가? 먼저 예측하고 확인해라.

<details><summary>답</summary>

**대략 6만 5천 개** — 정확히는 `MAX_ENTRIES × (MAX_ENTRIES + 1) = 255 × 256 = 65,280` 근처다.

계산 근거:
- root(INTERNAL)는 entry를 **255개**까지 담는다. entry 하나가 leaf 하나를 가리키므로 자식 leaf는 최대 `255 + 1 = 256`개(auxPage로 가리키는 leftmost 하나 추가).
- leaf 하나에 255개.
- 그러니 이 트리가 담을 수 있는 최대치가 `256 × 255 = 65,280`.
- 그 다음 leaf split이 일어나면 root에 entry를 하나 더 넣어야 하는데 root가 꽉 차서 → `UnsupportedOperationException("internal node split not yet supported")`.

순차 삽입에서는 각 leaf가 절반만 채워지는 게 아니라 거의 꽉 차므로 이 수치에 가깝게 간다. 무작위 삽입이면 leaf 점유율이 낮아 더 일찍 도달한다.

**직접 확인할 때 주의**: `BufferPool` capacity를 충분히(수백 이상) 주지 않으면 thrashing으로 매우 느려진다(02-02 과제 2번). 그리고 예외가 나기 **전까지는 완벽히 정상 동작한다** — 6만 5천 건까지는 아무 문제가 없다가 갑자기 벽에 부딪히는 것이 이 한계의 성격이다.
</details>

결과는 `docs/stages/03-index.md` 또는 `docs/decision-log.md`에 기록.

## 8. 다음 한계

이 세션의 코드는 **internal 노드가 꽉 차는 순간 깨진다** — `insertIntoParent`가 `UnsupportedOperationException("internal node split not yet supported")`을 던진다. leaf split은 되지만 그 위 레벨의 split이 없어서 트리는 사실상 2레벨에 갇혀 있다.

또 `delete`가 아예 없다. 지우기가 들어오면 leaf underflow → merge·redistribution이 필요하고, 그건 방금 만든 sibling pointer 관리와 정면으로 얽힌다.

→ 단계 4는 이 한계를 남겨둔 채 진행한다. 인덱스를 schema/catalog 위에 올려 "테이블 컬럼에 붙는 인덱스"로 만드는 것이 먼저이기 때문이다.
