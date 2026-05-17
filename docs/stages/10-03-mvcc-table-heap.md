# Stage 10-03 — MVCCTableHeap (C4 보강)

> **Status**: implemented + verified
> 깨지는 가정: MVCCStore<K,V>는 in-memory 데모. TableHeap (disk)과 별개.

## 도입
- `mvcc.MVCCTableHeap`: TableHeap을 wrap하고 in-memory MVCC chain으로 visibility 관리.
- bootstrap: 기존 heap rows를 xid=0 version으로 등록.

## invariant
- snapshot 기준 read는 visible version 반환.
- delete는 tombstone version.
- 옛 snapshot은 옛 version 봄.

## 다음 한계
- MVCC chain persist 안 됨 (in-memory only).
- Vacuum 없음 — chain 누적.
- WAL 통합 후 진짜 persistent MVCC 가능.
