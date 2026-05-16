# impl/03-03 — Range scan via leaf sibling pointer

> 상위 단계: `docs/stages/03-index.md`
> 범위: `rangeScan(from, to)` — leaf sibling pointer 따라.
> **✅ 21/21 PASSED. internal split은 보류 (3-4 후보, 65k entries까지 충분).**

---

## 0. 참조 출처
- SimpleDB `BTreeFile.indexIterator` — sibling traversal 패턴.

## 1. invariant
- **CI-7**: rangeScan(from, to) → 모든 key with `from <= key < to`, key ascending order.
- **CI-8**: leaf 경계 넘어가도 sibling pointer (auxPage) 따라 끊김 없이.

## 2. 알고리즘
```
1. findLeafPage(from) → starting leaf
2. while leaf != INVALID:
     for each entry in leaf where key >= from:
         if key >= to: return result
         add (key, value) to result
     leaf = leaf.auxPage
```

## 3. 코드 (BTreeIndex.kt 추가)

```kotlin
fun rangeScan(fromInclusive: Long, toExclusive: Long): List<Pair<Long, Long>> {
    require(fromInclusive <= toExclusive)
    val result = mutableListOf<Pair<Long, Long>>()
    var leafPageNo = findLeafPage(fromInclusive)
    while (leafPageNo != BTreePage.INVALID) {
        val page = bufferPool.fetchPage(PageId(pagedFile.fileId, leafPageNo))
        try {
            val btp = BTreePage(page)
            val startSlot = btp.findSlot(fromInclusive)
            for (i in startSlot until btp.keyCount) {
                val k = btp.keyAt(i)
                // Q: why early return instead of break?
                if (k >= toExclusive) return result
                result.add(k to btp.valueAt(i))
            }
            leafPageNo = btp.auxPage
        } finally {
            bufferPool.unpinPage(page.id, isDirty = false)
        }
    }
    return result
}
```
<details><summary>A (why early return)</summary>

`break`로 inner loop 종료 후 outer while도 끝내려면 flag가 필요. early return이 단순 + try-finally의 unpin도 정확히 수행 (Kotlin은 return 전 finally 실행).
</details>

## 4. 검증 테스트
- 기본 range (from=100, to=200) → 100 entries
- 대량 (n=455, split 다회) → leaf 경계 넘어 전체 반환

## 5. 직접 깨뜨릴 과제
- 과제 1: `fromInclusive > toExclusive` 일 때 어떤 일? require로 잡는 게 맞는가?
- 과제 2: rangeScan 도중 다른 thread가 insert → 결과는? (단계 9 lock 전 race)
- 과제 3: `from=Long.MIN_VALUE, to=Long.MAX_VALUE` → 전체 scan. 성능은?

## 6. 다음 한계
- internal node도 full 되면 throw → 03-04 internal split (보류, 65k까지 충분).
- 단일 thread 가정. → 단계 9.

---
| 2026-05-16 | 초안 — 검증 완료 |
