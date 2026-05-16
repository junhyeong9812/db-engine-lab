# Handoff: Stage 04 (Schema/Catalog) 완료

> 2026-05-16. 직전: stage-03. 다음: 5 (Constraints).

## 한 줄 요약
Type + ColumnDef + TableSchema + Tuple + Catalog. Catalog persistence 단순 binary. 누적 28 PASSED.

## 결정 (누적)
- D-027: Type은 enum + per-type handler (sealed 미사용, 확장 지점).
- D-028: ColumnDef/TableSchema는 data class. Tuple은 일반 class (values 가변성).
- D-029: NULL은 bitmap encoding.
- D-030: Catalog는 별도 metadata 파일, 매번 전체 재기록 (단일 thread).

## 코드
- `catalog.Type` (enum + encode/decode/fixedSize)
- `catalog.ColumnDef` (data class)
- `catalog.TableSchema` (data class, validates uniqueness)
- `catalog.Tuple` (List<Any?> + schema, encode/decode with NULL bitmap)
- `catalog.Catalog` (registerTable/getTable/dropTable/listTables, persistent)

## 다음 입력 (Stage 5)
- TableSchema에 `constraints: List<Constraint>` 추가 또는 별도 ConstraintSet.
- Constraint 검증은 Tuple 생성 시 + 단계 6 mutation operator에서.
- PK는 자동 UNIQUE + NOT NULL. FK는 다른 테이블 참조 (RESTRICT only).

---
| 2026-05-16 | stage 4 |
