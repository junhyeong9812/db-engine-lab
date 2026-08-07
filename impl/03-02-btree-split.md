# impl/03-02 — BTree Split + Root Promotion

> **종류**: 세션형
> **상위 단계**: `docs/stages/03-index.md`
> **코드 정본**: git `0d56dee` — "stage 3-2: BTree leaf split + root promotion (19/19 tests, separator navigation bug fixed)"
> **이 세션의 범위**: leaf가 꽉 차면 둘로 쪼개고 그 위에 부모를 만든다. 여기서 B+tree가 처음으로 트리가 된다.
> **작성 파일**:
> - 수정: `src/main/kotlin/com/dbenginelab/storage/BTreeIndex.kt` — split 계열 메서드 추가, `insert`/`search` 교체
> - 수정: `src/test/kotlin/com/dbenginelab/storage/BTreeIndexTest.kt` — "leaf full 예외" 테스트를 split 테스트 3개로 교체
> **검증**: `BTreeIndexTest` 8 PASSED · storage 누적 19 PASSED
> **예상 타이핑 시간**: 60분 (이 단계에서 가장 어려운 세션)

---

## 0. 참조

- 주 참조: SimpleDB `BTreeLeafPage.split`, `BTreeInternalPage.split`.
- 대조 참조: BusTub `b_plus_tree.cpp::SplitLeaf`.

## 1. 만족시킬 invariant (누적 + 신규)

- **CI-4**: leaf가 full인 상태에서 insert하면 split이 일어나고, 기존 entry와 새 entry 모두 검색된다.
- **CI-5**: split로 생긴 새 leaf의 parent pointer가 root를 가리킨다.
- **CI-6 (B+tree 규약)**: separator key는 **right subtree의 leftmost leaf에 존재한다.** 따라서 navigation에서 `key == sepKey`면 **오른쪽**으로 간다.

## 2. 알고리즘

### 2.1 non-root leaf split

```
1. Allocate new right page
2. Initialize as LEAF, same parent, auxPage = left.auxPage (기존 다음 형제를 right가 인계)
3. Move upper half entries to right
4. left.auxPage = right.pageNumber (sibling 포인터 — 2 이후여야 기존 체인이 안 끊김)
5. 새 (key, value) insert into left/right based on key < firstRightKey
6. separator = right.keyAt(0)
7. insertIntoParent(parent, separator, right.pageNumber)
```

### 2.2 root leaf split (page 0 = root invariant 유지)

```
1. Copy root content to new leftChild page
2. Allocate rightChild as empty LEAF (parent = root)
3. Move half from leftChild to rightChild
4. Insert new (key, value) into correct side
5. separator = rightChild.keyAt(0)
6. Reinitialize root as INTERNAL with auxPage=leftChild, entry=(separator, rightChild)
```

### 2.3 navigation — ISSUE-001 버그 수정 핵심

```kotlin
// (발췌 — §5 findLeafPage 안의 분기. 이것만 따로 치지 말 것)
val childPageNo = when {
    // ⚠ B+tree separator convention: key == sepKey → go RIGHT
    slot < keyCount && keyAt(slot) == key -> valueAt(slot).toInt()
    slot == 0 -> auxPage                                              // leftmost
    else -> valueAt(slot - 1).toInt()                                 // between separators
}
```

**버그**: 이전 코드는 `slot == 0` 분기가 먼저였다 → key가 첫 separator와 정확히 같을 때 leftmost child로 잘못 내려가고, **그 키는 거기 없다.** **수정**: separator match를 첫 분기로 올린다. (ISSUE-001)

이 버그가 이 세션의 진짜 교훈이다. split 코드는 처음부터 맞았는데도 검색이 실패했다 — 원인은 쓰기가 아니라 **읽는 경로**에 있었다.

## 3. 문제 정의 (TDD step 1)

03-01은 255개에서 `UnsupportedOperationException`을 던진다. 이제 그 벽을 없앤다.

leaf가 꽉 찼을 때 할 일은 셋이다:
- **쪼갠다** — 절반을 새 page로 옮긴다. 정확히 절반인 이유는 양쪽 모두 다음 insert에 여유를 두기 위해서다.
- **잇는다** — 새 page가 옛 sibling을 물려받고, 그 다음에 왼쪽이 새 page를 가리킨다. **순서가 뒤집히면 leaf 사슬이 끊긴다.**
- **올린다** — 어느 키부터 오른쪽인지(separator)를 부모에게 알린다. 부모가 없으면(= root였으면) 부모를 새로 만든다.

세 번째가 까다롭다. root는 page 0이라는 규약이 있어서 **root를 다른 page로 옮길 수 없다.** 그래서 root의 내용을 새 자식 page로 복사하고, root 자리는 INTERNAL 노드로 갈아끼운다.

## 4. 실패 테스트 (TDD step 2)

03-01의 마지막 테스트(`leaf full 후 insert는 UnsupportedOperationException`)를 **지우고** split 테스트 3개를 넣는다. 아래가 교체 후 파일 전문이다.

```kotlin
// src/test/kotlin/com/dbenginelab/storage/BTreeIndexTest.kt @ 0d56dee
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

**예상 실패**: **assertion 실패** — split이 아직 없으므로 `MAX_ENTRIES 초과 insert 시 split 발생`에서 `UnsupportedOperationException("BTree leaf full …")`이 그대로 튀어나온다. 컴파일은 통과한다(새 API를 부르지 않으므로). 03-01과 빨간 줄의 **형태가 다르다**는 점을 확인하고 넘어가라.

**커버리지 주의**: 세 테스트 중 **비-root leaf split(§2.1)을 실제로 타는 건 두 번째(`정렬 안 된 순서로 대량 insert`) 하나뿐**이다. 첫·세 번째는 순차 insert라 root split 후 오른쪽 leaf가 255에 못 미쳐 §2.2까지만 실행된다. 테스트 3개가 통과했다고 경로 3개가 검증된 것이 아니다.

## 5. 구현 코드 (TDD step 3 — make it pass)

`BTreeIndex.kt` 전문이다. 03-01에서 친 `insert`·`search`가 **교체**되고(이제 root가 아니라 `findLeafPage`로 찾은 leaf를 대상으로 한다), split 계열 private 메서드가 통째로 추가된다.

```kotlin
// src/main/kotlin/com/dbenginelab/storage/BTreeIndex.kt @ 0d56dee
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
        if (pagedFile.pageCount() == 0) {
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
            val childPageNo = when {
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
        bufferPool.unpinPage(leafPage.id, isDirty = false)
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
        left.auxPage = rightPageRaw.id.pageNumber

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
        // Q: 왜 root를 직접 split 안 하고 content를 새 page로 옮김?
        val leftChildPageRaw = bufferPool.newPage()
        // <details><summary>A</summary>
        // page 0 = root invariant 유지 — 외부 참조 없이도 reopen 시 root 위치 보장.
        // </details>
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
        rootView.initAsEmpty(
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
        left.keyCount = splitPoint
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
            throw UnsupportedOperationException(
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

## 6. 검증 테스트 (TDD step 4 — green)

테스트 파일은 §4에서 저장한 것이 그대로 최종본이다.

```bash
./gradlew test --tests 'com.dbenginelab.storage.BTreeIndexTest'
```

**기대 결과**: `BTreeIndexTest` **8 PASSED** · storage 누적 **19 PASSED**
(6개였다가 "leaf full 예외" 1개가 빠지고 split 3개가 들어와 8개다.)

invariant 대응:
- **CI-4** ← `MAX_ENTRIES 초과 insert 시 split 발생, 모두 검색 가능`
- **CI-6** ← `정렬 안 된 순서로 대량 insert (split 다회 발생) 후에도 모두 검색 가능` — separator navigation 버그를 잡은 테스트가 이것이다
- **CI-5** ← `split 후 reopen해도 일관성 유지` — parent pointer가 틀리면 reopen 후 탐색이 깨진다

`key=128`처럼 split point 임계값 근처에서만 실패한다면 §2.3의 분기 순서를 의심할 것.

## 7. 직접 깨뜨릴 과제 (먼저 해보고 답 펼치기)

**1.** §2.3의 분기 순서를 원래대로(`slot == 0` 먼저) 되돌려라. ISSUE-001이 재현되는가? **어떤 키**가 실패하는지 특정해봐라.

<details><summary>답</summary>

**실측: 재현된다. 10개 중 3개 실패** — `MAX_ENTRIES 초과 insert 시 split 발생`, `정렬 안 된 순서로 대량 insert`, `split 후 reopen해도 일관성 유지`. (split이 일어나지 않는 앞쪽 테스트 5개는 멀쩡히 통과한다 — internal 노드가 없으면 이 분기 자체를 타지 않기 때문이다.)

실패하는 키는 **separator key 자신**이다. 1..305를 순차 insert하면 root split이 일어나면서 오른쪽 leaf의 첫 키가 separator가 되는데(대략 128 근처), 바로 그 키를 찾을 때만 깨진다.

```
root(INTERNAL): auxPage=leftChild, entries=[(128, rightChild)]

search(128):
  findSlot(128) → 0     (첫 separator가 128이므로 "128 이상인 첫 자리" = 0)
  slot == 0 분기가 먼저 걸림 → auxPage = leftChild 로 내려감
  leftChild에는 1..127만 있다 → null 반환 ✗
```

`128`은 B+tree 규약상 **오른쪽 subtree의 leftmost leaf에 있다.** `findSlot`이 0을 돌려준 것은 "128보다 작은 separator가 없다"는 뜻이지 "왼쪽으로 가라"는 뜻이 아닌데, 분기 순서가 그 둘을 뭉갠 것이다.

이 버그의 성질을 눈여겨봐라 — **305개 중 딱 1개가 틀린다.** 대충 훑어보면 "잘 되는데?" 싶고, 전수 검사를 하는 테스트만이 잡는다. 첫 테스트가 `for (k in 1..n) assertEquals(..., idx.search(k), "key=$k")`처럼 **전부** 확인하는 이유다.
</details>

**2.** `moveHalfTo`의 `splitPoint`를 `0` 또는 `total - 1`로 바꿔라. 테스트는 통과하는가?

<details><summary>답</summary>

**실측: `total - 1`로 바꿔도 10개 전부 통과한다.** 정확성에는 영향이 없다 — 어느 지점에서 자르든 정렬 순서와 separator 규약은 유지되기 때문이다.

무너지는 건 **공간 효율과 트리 모양**이다. `total - 1`이면 왼쪽에 254개, 오른쪽에 1개가 남는다:

```
순차 insert (1, 2, 3, … ) 일 때:
  splitPoint = total/2  → 왼쪽 127, 오른쪽 128    다음 split까지 127번 여유
  splitPoint = total-1  → 왼쪽 254, 오른쪽 1      오른쪽이 곧 다시 꽉 참
```

순차 삽입에서는 새 키가 항상 오른쪽 끝에 붙으므로, 오른쪽 leaf가 **1개 → 255개 → 또 split**을 반복한다. split 횟수가 늘고 leaf 개수도 늘어 트리가 옆으로 길어진다. 결국 **page 하나당 평균 점유율이 떨어져** 같은 데이터에 더 많은 page를 쓴다.

정확성 테스트로는 절대 안 잡히는 종류의 퇴화다. 확인하려면 `idx.size()`가 아니라 **`pagedFile.pageCount()`를 재봐야** 한다 — 직접 해보면 차이가 눈에 보인다.

(참고: 실제 DB는 순차 삽입을 감지해 `total - 1` 쪽에 가깝게 자르기도 한다. 순차 패턴에서는 왼쪽이 다시 채워질 일이 없어 절반씩 비워두는 게 오히려 낭비이기 때문이다. **어느 쪽이 옳은지는 워크로드가 정한다.**)
</details>

**3.** `splitLeafAndInsert`에서 `right.initAsEmpty(...)`와 `left.auxPage = ...` 두 줄의 순서를 뒤집어라. 어떤 테스트가 깨지나?

<details><summary>답</summary>

**단계 3-2의 테스트로는 안 깨진다.** 이 시점엔 sibling 사슬을 따라 걷는 코드가 아직 없기 때문이다 — `size()`와 `search()`는 `auxPage`를 다르게 쓰거나 안 쓴다.

**03-03의 range scan 테스트에서 터진다.** 그래서 이 과제는 "지금 깨보고, 03-03까지 간 뒤 다시 돌아와서 확인"이 정답이다.

사슬이 어떻게 끊기는지:

```
정상 순서:
  right.auxPage = left.auxPage   // right가 옛 다음 형제(C)를 인계
  left.auxPage  = right          // 그 다음 left가 right를 가리킴
  결과: left → right → C          ✓

뒤집으면:
  left.auxPage  = right          // 여기서 C의 주소가 사라짐
  right.auxPage = left.auxPage   // = right 자신!
  결과: left → right → right → …  ✗ 무한 루프
```

`right.auxPage`가 자기 자신을 가리키게 되므로 `rangeScan`의 `while (leafPageNo != INVALID)`가 **끝나지 않는다.** 데이터 유실보다 나쁜 무한 루프다.

교훈은 이것이다 — **덮어쓰기 전에 읽어야 하는 값이 있으면 순서가 정답의 일부**다. 03-01 과제 1번(밀어내기 방향)과 같은 종류의 함정이고, 이번엔 배열이 아니라 포인터에서 나타났다.
</details>

**4.** split 도중 프로세스를 죽여라 — `writePage`는 됐고 `insertIntoParent`는 안 된 시점. reopen하면 그 키들은 어디로 갔는가?

<details><summary>답</summary>

**찾을 수 없는 상태로 디스크에 남는다.** 지워지지도 않고 찾아지지도 않는, 최악의 조합이다.

```
split 직후 상태:
  leftChild:  1..127   (디스크에 쓰임)
  rightChild: 128..255 (디스크에 쓰임)  ← 데이터는 여기 있다
  root:       아직 rightChild를 모른다   ← insertIntoParent 전에 죽음

reopen 후 search(200):
  findLeafPage(200) → root에 separator가 없으니 auxPage(leftChild)로만 내려감
  leftChild에는 200이 없다 → null
```

데이터는 **멀쩡히 디스크에 있는데** 도달할 경로가 없다. 그리고 `size()`도 leftChild만 세므로 "128건이 사라졌다"고 보고한다. 파일 크기는 그대로인데 말이다.

이게 단계 8 WAL이 푸는 문제의 정확한 모습이다. split은 **여러 page를 원자적으로 바꿔야 하는 연산**인데(left 수정 + right 생성 + parent 수정), 지금 코드에는 그 셋을 묶는 장치가 없다. WAL은 "이 세 변경은 한 덩어리"라고 로그에 먼저 적고, 복구 때 **전부 적용하거나 전부 버린다.**

한 번 더 생각해볼 것 — 이 상황은 `fsync`를 아무리 열심히 해도 안 풀린다. 각 page를 아무리 확실히 디스크에 박아도 **"세 개가 함께"가 보장되지 않기 때문**이다. 내구성(durability)과 원자성(atomicity)이 다른 문제라는 것이 여기서 드러난다.
</details>

## 8. 다음 한계

- `insertIntoParent`가 부모 full일 때 `UnsupportedOperationException`을 던진다. internal split이 없어서 **트리 높이가 사실상 2로 상한**이다.
- 구간 조회가 없다. leaf들은 `auxPage`로 이어져 있는데 아무도 그 사슬을 따라 걷지 않는다.

→ **03-03 range scan**이 그 사슬을 처음으로 쓴다.
