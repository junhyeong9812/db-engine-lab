# Handoff: Stage 06 (Query API) 완료

## 한 줄
Operator interface + SeqScan + Filter + Project + Expression + ConstraintValidator.

## 결정
- D-035: Volcano model (pull Sequence).
- D-036: TableHeap length-prefix tuple, slot directory 없음.
- D-037: ConstraintValidator는 풀스캔 O(N). index-backed는 단계 11+.

## 코드
- `executor.Operator/SeqScan/Filter/Project/InsertOp/Expression`
- `table.TableHeap`, `table.ConstraintValidator`

## 다음 입력 (7)
- WorkUnit이 InsertOp + ConstraintValidator 통합. atomic at commit.
