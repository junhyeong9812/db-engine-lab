# Handoff: Stage 11 (Optimizer) 완료

## 한 줄
Statistics + SimpleOptimizer (Rule + Cost).

## 결정
- D-051: 풀스캔 statistics (incremental 미지원).
- D-052: LogicalPlan / PhysicalPlan 분리.
- D-053: Cost = IO + CPU. equality selectivity = 1/distinct.

## 코드
- `optimizer.Statistics/StatisticsCollector/LogicalPlan/SimpleOptimizer`

## 다음 입력 (12)
- SQL parser로 표면 추가. AST → LogicalPlan.
