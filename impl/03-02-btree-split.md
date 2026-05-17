# impl/03-02 — BTree Split + Root Promotion (한 줄 한 줄)

> **검증**: BTreeIndexTest 9 누적 (split 추가 3 PASSED).
> 작성 파일:
> - 수정: `src/main/kotlin/com/dbenginelab/storage/BTreeIndex.kt` — split 메서드들 추가
> - 수정: `src/test/kotlin/com/dbenginelab/storage/BTreeIndexTest.kt` — split 테스트 추가

## 0. 참조
- SimpleDB `BTreeLeafPage.split`, `BTreeInternalPage.split`.
- BusTub `b_plus_tree.cpp::SplitLeaf`.

## 1. invariant (누적 + 신규)
- CI-4: leaf full 후 insert → split. 모든 기존 + 새 entry search 가능.
- CI-5: split 후 새 leaf parent pointer가 root.
- CI-6 (B+tree 규약): separator key는 right subtree의 leftmost leaf에 존재. navigation 시 `key == sepKey` → right.

## 2. 알고리즘

### 2.1 non-root leaf split
```
1. Allocate new right page
2. Initialize as LEAF, same parent
3. Move upper half entries to right
4. Update left.auxPage = right.pageNumber (sibling 포인터)
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

### 2.3 navigation (search 경로) — ISSUE-001 버그 수정 핵심
```kotlin
val childPageNo = when {
    // ⚠ B+tree separator convention: key == sepKey → go RIGHT
    slot < keyCount && keyAt(slot) == key -> valueAt(slot).toInt()
    slot == 0 -> auxPage                                              // leftmost
    else -> valueAt(slot - 1).toInt()                                 // between separators
}
```
**버그**: 이전 코드는 `slot == 0` 분기가 먼저 → key가 첫 separator와 같을 때 leftmost로 잘못 navigation. **수정**: separator match를 첫 분기로. (ISSUE-001 참조)

## 3. 추가 코드 — BTreeIndex.kt

```kotlin
fun insert(key: Long, value: Long) {                                 // 기존 insert 교체
    val leafPageNumber = findLeafPage(key)                            // 어느 leaf?
    insertIntoLeaf(leafPageNumber, key, value)
}

fun search(key: Long): Long? {
    val leafPageNumber = findLeafPage(key)
    val page = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNumber))
    try {
        val btp = BTreePage(page)
        val slot = btp.findSlot(key)
        return if (slot < btp.keyCount && btp.keyAt(slot) == key) btp.valueAt(slot) else null
    } finally { bufferPool.unpinPage(page.id, isDirty = false) }
}

private fun findLeafPage(key: Long): Int {                           // root → leaf 따라감
    var pageNo = ROOT_PAGE_NUMBER
    while (true) {
        val page = bufferPool.fetchPage(PageId(pagedFile.fileId, pageNo))
        val btp = BTreePage(page)
        if (btp.type == BTreePage.Type.LEAF) {
            bufferPool.unpinPage(page.id, isDirty = false)
            return pageNo                                             // leaf 도달
        }
        // INTERNAL: navigation (separator convention 핵심)
        val slot = btp.findSlot(key)
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
    require(!(slot < btp.keyCount && btp.keyAt(slot) == key))         // duplicate reject
    if (!btp.isFull()) {                                              // 여유 있으면 그냥 insert
        btp.insertAt(slot, key, value)
        bufferPool.unpinPage(leafPage.id, isDirty = true)
        return
    }
    bufferPool.unpinPage(leafPage.id, isDirty = false)
    splitLeafAndInsert(leafPageNumber, key, value)                    // full → split
}

private fun splitLeafAndInsert(leafPageNumber: Int, key: Long, value: Long) {
    if (leafPageNumber == ROOT_PAGE_NUMBER) {                         // root split = 특별 처리
        splitRootLeaf(key, value); return
    }
    val leftPage = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNumber))
    val left = BTreePage(leftPage)
    val rightPageRaw = bufferPool.newPage()                           // 새 leaf 할당
    val right = BTreePage(rightPageRaw)
    right.initAsEmpty(BTreePage.Type.LEAF, parentPage = left.parentPage, auxPage = left.auxPage)
    left.auxPage = rightPageRaw.id.pageNumber                         // sibling 포인터
    moveHalfTo(left, right)                                           // 절반 이동
    insertIntoCorrectSide(left, right, key, value)                    // 새 entry insert
    val separator = right.keyAt(0)                                    // promote할 key
    val parentPageNo = left.parentPage
    bufferPool.unpinPage(leftPage.id, isDirty = true)
    bufferPool.unpinPage(rightPageRaw.id, isDirty = true)
    insertIntoParent(parentPageNo, separator, rightPageRaw.id.pageNumber)
}

private fun splitRootLeaf(key: Long, value: Long) {
    val rootPage = bufferPool.fetchPage(PageId(pagedFile.fileId, ROOT_PAGE_NUMBER))
    val rootView = BTreePage(rootPage)
    // Q: 왜 root를 직접 split 안 하고 content를 새 page로 옮김?
    val leftChildPageRaw = bufferPool.newPage()
    val leftChild = BTreePage(leftChildPageRaw)
    copyPageContent(from = rootView, to = leftChild)                  // root → leftChild 복사
    leftChild.parentPage = ROOT_PAGE_NUMBER
    // <details><summary>A</summary>
    // page 0 = root invariant 유지 — 외부 참조 없이도 reopen 시 root 위치 보장.
    // </details>

    val rightChildPageRaw = bufferPool.newPage()
    val rightChild = BTreePage(rightChildPageRaw)
    rightChild.initAsEmpty(BTreePage.Type.LEAF, parentPage = ROOT_PAGE_NUMBER, auxPage = leftChild.auxPage)
    leftChild.auxPage = rightChildPageRaw.id.pageNumber

    moveHalfTo(leftChild, rightChild)
    insertIntoCorrectSide(leftChild, rightChild, key, value)
    val separator = rightChild.keyAt(0)

    // root를 INTERNAL로 변환
    rootView.initAsEmpty(BTreePage.Type.INTERNAL, parentPage = BTreePage.INVALID, auxPage = leftChildPageRaw.id.pageNumber)
    rootView.insertAt(0, separator, rightChildPageRaw.id.pageNumber.toLong())

    bufferPool.unpinPage(rootPage.id, isDirty = true)
    bufferPool.unpinPage(leftChildPageRaw.id, isDirty = true)
    bufferPool.unpinPage(rightChildPageRaw.id, isDirty = true)
}

private fun moveHalfTo(left: BTreePage, right: BTreePage) {
    val total = left.keyCount
    val splitPoint = total / 2                                        // Q: 정확히 절반인 이유?
    for (i in splitPoint until total) {
        right.insertAt(right.keyCount, left.keyAt(i), left.valueAt(i))
    }
    left.keyCount = splitPoint
    // <details><summary>A</summary>
    // 양쪽 모두 isFull() 직전 상태 → 다음 insert에 여유 (B+tree balance).
    // </details>
}

private fun insertIntoCorrectSide(left: BTreePage, right: BTreePage, key: Long, value: Long) {
    val firstRightKey = right.keyAt(0)
    if (key < firstRightKey) { left.insertAt(left.findSlot(key), key, value) }
    else { right.insertAt(right.findSlot(key), key, value) }
}

private fun copyPageContent(from: BTreePage, to: BTreePage) {
    to.type = from.type; to.keyCount = 0
    to.auxPage = from.auxPage; to.parentPage = from.parentPage
    for (i in 0 until from.keyCount) {
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
        throw UnsupportedOperationException("internal split not yet (단계 3-3+)")
    }
}
```

## 4. 검증 (3 PASSED 추가)
- MAX_ENTRIES 초과 insert → split → 모두 search OK
- 정렬 안 된 대량 insert (n=271, split 다회) → 모두 search
- split 후 reopen → 일관

## 5. 깨뜨릴 과제
- splitPoint=0/total-1로 바꾸면 balance 깨지는 입력?
- navigation 분기 순서 다시 `slot==0` 먼저로 바꾸면? (ISSUE-001 재현)
- split 도중 process kill (writePage 후 parent insert 전) → reopen?
