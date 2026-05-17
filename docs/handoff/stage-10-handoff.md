# Handoff: Stage 10 (MVCC) 완료

## 한 줄
PostgreSQL식 tuple versioning + Snapshot Isolation.

## 결정
- D-048: tuple versioning (xmin/xmax + visibility 5조건).
- D-049: SI only (SSI 미지원). first-committer-wins 미구현.
- D-050: Vacuum 미구현 — 옛 버전 누적.

## 코드
- `mvcc.MVCCStore<K,V>`, `Version`, `Snapshot`, `SnapshotProvider`

## 다음 입력 (11)
- 모든 query 풀스캔 — Statistics + cost model로 plan 선택.
