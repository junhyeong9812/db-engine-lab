# Handoff: Stage 03 (BTreeIndex) 완료 시점

> 작성일: 2026-05-16
> 직전: stage-02-handoff.md
> 다음: 4 (Schema/Catalog)

## 0. 한 줄 요약
B+tree (leaf-only → split → range scan). 21/21 tests. internal split은 보류 (65k entries 한계).

## 1. 결정 (누적)
- D-023: Page 0 = root 영구 invariant. root split 시 content를 새 page로 옮기고 root를 INTERNAL로 변환.
- D-024: B+tree separator convention — sepKey는 right subtree에 존재. navigation 시 `key == sepKey` → right.
- D-025: Long key/value 단순화 (단계 4 type 도입 전). 단계 4에서 typed key로 확장 검토.
- D-026: internal split 보류 — 단계 3-4로 미룸 (현재 fanout 255 × 255 = 65k entries 한계). 학습 단계에서 충분.

## 2. invariant (누적)
- I-1~I-3, CI-1~CI-3 (storage, page/buffer).
- CI-4~CI-8 (BTree: split correctness, sibling pointer, separator convention, range scan).

## 3. 참조
- SimpleDB BTreeFile/BTreeLeafPage — 구조 차용.
- BusTub b_plus_tree — separator convention 확인.

## 4. 코드
- `storage.BTreePage` (view, header + entries).
- `storage.BTreeIndex` (multi-leaf B+tree, single-level internal).
- 공개 API: `insert(key, value)`, `search(key): Long?`, `rangeScan(from, to)`, `size()`, `close()`.

## 5. 깨진 가설 / 해결 이력
- separator navigation 분기 순서 버그 → `docs/issues-log.md` ISSUE-001 참조.

## 6. 막힌 지점
- (사용자 따라치기 전, Claude 검증 시점)

## 7. Spot check
- (사용자 따라치기 시 SimpleDB BTreeFile.split 비교 권장 — separator 처리 부분)

## 8. 다음 단계 입력
- Catalog가 인덱스 metadata 표현해야 (어떤 컬럼이 PK인지, BTree 인덱스가 어디 있는지).
- 단계 4 Tuple 직렬화는 Record (단계 1) 형식을 확장.

## 9. 새 세션 권고
- `docs/stages/04-schema-catalog.md` Invalidation triggers 확인 후 진입.

---
| 2026-05-16 | stage 3 완료 (21/21) |
