# impl/03-03 — Range Scan (한 줄 한 줄)

> **검증**: 누적 11 PASSED (range scan 2 추가).
> 작성 파일:
> - 수정: `src/main/kotlin/com/dbenginelab/storage/BTreeIndex.kt` — `rangeScan` 메서드 추가

## 0. 참조
- SimpleDB `BTreeFile.indexIterator` — sibling traversal 패턴.

## 1. invariant
- CI-7: rangeScan(from, to) → `from <= key < to` 인 모든 key, ascending order.
- CI-8: leaf 경계 넘어 sibling pointer (auxPage) 따라 끊김 없이.

## 2. 알고리즘
```
1. findLeafPage(from) → 시작 leaf
2. while leaf != INVALID:
     각 entry where key >= from:
       if key >= to: return result
       add (key, value)
     leaf = leaf.auxPage  (sibling)
```

## 3. 코드 — 한 줄 한 줄

```kotlin
fun rangeScan(fromInclusive: Long, toExclusive: Long): List<Pair<Long, Long>> {
    require(fromInclusive <= toExclusive) { "fromInclusive ($fromInclusive) > toExclusive ($toExclusive)" }
    val result = mutableListOf<Pair<Long, Long>>()
    var leafPageNo = findLeafPage(fromInclusive)                      // 시작 leaf — BTree navigation
    while (leafPageNo != BTreePage.INVALID) {                         // sibling 따라가며 반복
        val page = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNo))
        try {
            val btp = BTreePage(page)
            val startSlot = btp.findSlot(fromInclusive)               // 첫 슬롯 (>= from)
            for (i in startSlot until btp.keyCount) {
                val k = btp.keyAt(i)
                // Q: 왜 early return? break 안 됨?
                if (k >= toExclusive) return result
                // <details><summary>A</summary>
                // break는 inner loop만 끝남, outer while 계속됨. flag 추가 필요. early return이 단순하고 try-finally의 unpin도 Kotlin이 return 전 실행.
                // </details>
                result.add(k to btp.valueAt(i))
            }
            leafPageNo = btp.auxPage                                  // 다음 leaf로 (sibling pointer)
        } finally {
            bufferPool.unpinPage(page.id, isDirty = false)
        }
    }
    return result
}
```

## 4. 검증 (2 PASSED)
- 정렬 순서로 in-range 키만 반환 (n=400, range=[100,200))
- leaf 경계 넘어 sibling pointer 따라 (n=455, split 다회)

## 5. 깨뜨릴 과제
- fromInclusive > toExclusive → require 효과?
- 도중 다른 thread가 insert → 결과? (단계 9 lock 전 race)
- `from=Long.MIN_VALUE, to=Long.MAX_VALUE` → 전체 scan 성능?
