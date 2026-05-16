# impl/03-02 — BTree leaf split + root promotion (1 internal level)

> 상위 단계: `docs/stages/03-index.md`
> 이 세션 범위: leaf split + root가 leaf일 때 INTERNAL로 승격. internal split은 03-03.
> **✅ 검증 완료: 19/19 PASSED.**
> 디버깅 이력: separator navigation 분기 순서 버그 (slot==0이 separator match보다 먼저 체크돼 잘못된 child로 감) — 수정.

---

## 0. 참조 출처
- SimpleDB `BTreeLeafPage.split`, `BTreeInternalPage.split` — 구조 차용.
- BusTub `b_plus_tree.cpp::SplitLeaf` — internal split 패턴 (03-03에서 차용).

## 1. invariant (누적 + 신규)
- CI-1~3 (03-01) + 신규:
- **CI-4**: leaf full 후 insert → split 발생. 모든 기존 entry + 새 entry 모두 search 가능.
- **CI-5**: split 후 새 leaf의 parent pointer가 root를 가리킴 (single-level).
- **CI-6 (B+tree 규약)**: separator key는 right subtree leftmost leaf에 존재. navigation 시 `key == sepKey` 이면 right로.

## 2. 핵심 알고리즘

### 2.1 leaf split (non-root)
```
1. Allocate new right page
2. Initialize as LEAF with same parent
3. Move upper half entries to right
4. Update left.auxPage = right.pageNumber (sibling pointer)
5. Insert new (key, value) into left or right based on key < firstRightKey
6. separator = right.keyAt(0)
7. insertIntoParent(parent, separator, right.pageNumber)
```

### 2.2 root split (leaf == root)
**까다로움** — root는 page 0이라는 invariant. 그러면:
```
1. Copy root content to new leftChild page
2. Allocate rightChild as empty LEAF (parent = root)
3. Move half from leftChild to rightChild
4. Insert new (key, value) into correct side
5. separator = rightChild.keyAt(0)
6. Reinitialize root as INTERNAL with auxPage=leftChild, entry=(separator, rightChild)
```

### 2.3 navigation (search 경로) — 버그 수정 핵심
```kotlin
val childPageNo = when {
    // ⚠ B+tree separator convention: key == sepKey → go RIGHT
    slot < keyCount && keyAt(slot) == key -> valueAt(slot).toInt()
    slot == 0 -> auxPage         // leftmost
    else -> valueAt(slot - 1).toInt()
}
```
**버그**: 이전 코드에서 `slot == 0` 분기가 먼저 → key가 첫 separator와 같을 때 leftmost로 잘못 navigation → search 실패.
**수정**: separator match를 첫 분기로.

## 3. 추가된 코드 (Q/A)

전체 코드는 `src/main/kotlin/com/dbenginelab/storage/BTreeIndex.kt` 참조. 핵심 메서드:

```kotlin
private fun splitLeafAndInsert(leafPageNumber: Int, key: Long, value: Long) {
    if (leafPageNumber == ROOT_PAGE_NUMBER) {
        splitRootLeaf(key, value)
        return
    }
    // ... non-root leaf split
}

private fun splitRootLeaf(key: Long, value: Long) {
    // Q: 왜 root를 직접 split하지 않고 content를 새 page로 옮기는가?
    // A: page 0이 root invariant 유지 — 외부 참조 없이도 reopen 시 root 위치 보장.
    val leftChildPageRaw = bufferPool.newPage()
    val leftChild = BTreePage(leftChildPageRaw)
    copyPageContent(from = rootView, to = leftChild)
    // ...
}

private fun moveHalfTo(left: BTreePage, right: BTreePage) {
    // Q: splitPoint = total / 2 — 왜 정확히 절반?
    // A: 양쪽 모두 isFull() 직전 상태 보장 → 다음 insert에 여유 (B+tree balance).
    val splitPoint = left.keyCount / 2
    for (i in splitPoint until left.keyCount) {
        right.insertAt(right.keyCount, left.keyAt(i), left.valueAt(i))
    }
    left.keyCount = splitPoint
}
```

## 4. 검증 테스트 (신규)

- `MAX_ENTRIES 초과 insert 시 split 발생, 모두 검색 가능` (n=305)
- `정렬 안 된 순서로 대량 insert (split 다회 발생) 후에도 모두 검색 가능` (mix order, n=271)
- `split 후 reopen해도 일관성 유지`

## 5. 직접 깨뜨릴 과제

- 과제 1: `splitPoint = 0`으로 바꾸면? `total - 1`로 바꾸면? balance가 깨지는 입력은?
- 과제 2: navigation의 `slot == 0` 분기를 다시 첫 자리로 옮기면 어떤 테스트가 깨지는가? 왜 그런지 직접 trace.
- 과제 3: split 도중 process kill (writePage 후 parent insert 전) → reopen 시 어떻게 깨지나? 단계 8 WAL의 동기.

## 6. 다음 한계
- internal node도 full 되면 split 필요 (fanout 255이면 255×255 = 65k까지 OK, 그 이상이면 throw). → **03-03 internal split + range scan**.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 + 버그 수정 (separator navigation) |
