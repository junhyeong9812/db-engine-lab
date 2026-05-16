# impl/05-01 — Constraints (PK / Unique / FK)

> 상위: `docs/stages/05-constraints.md`
> 범위: PK / Unique / FK 정의 + persistence + Schema 레벨 검증.
> **CHECK는 단계 6 expression 후로 보류**. 실제 데이터 검증은 단계 6 mutation operator에서.

---

## 0. 참조 출처
- 참조 자료 부재 (SimpleDB·BusTub 둘 다 약함). PostgreSQL constraints docs 패턴 차용.

## 1. invariant
- **CI-1**: PK 컬럼은 NOT NULL 자동 강제 (schema validation).
- **CI-2**: 한 테이블에 PK 하나만.
- **CI-3**: 존재하지 않는 컬럼 참조 시 거부.
- **CI-4**: Constraint persistence 후 reopen 시 정확히 복원.

## 2. 핵심 결정
- **`sealed class Constraint`** — PK/Unique/FK는 이 단계에서 닫힌 집합 (codex 보정 2: 진짜 닫힌 영역만 sealed).
- **TableSchema.constraints: List<Constraint>** — 부가 메타데이터.
- **검증 위치 분리**:
  - Schema 레벨 (정의 정합성): TableSchema.init 에서 즉시 검증 (PK 컬럼 nullable 등).
  - Data 레벨 (실제 row 검증): 단계 6 mutation operator 진입 시.
- **Catalog persistence 확장** — tag byte로 constraint type 식별, 단순 binary.

## 3. 코드 핵심

```kotlin
sealed class Constraint {
    data class PrimaryKey(val columns: List<String>) : Constraint()
    data class Unique(val columns: List<String>) : Constraint()
    data class ForeignKey(val columns: List<String>, val refTable: String, val refColumns: List<String>) : Constraint()
}
```

TableSchema 검증:
```kotlin
private fun validateConstraints() {
    val pks = constraints.filterIsInstance<Constraint.PrimaryKey>()
    require(pks.size <= 1) { "table $name has more than one PRIMARY KEY" }
    for (constraint in constraints) {
        when (constraint) {
            is Constraint.PrimaryKey -> constraint.columns.forEach { c ->
                require(!column(c).nullable) { "PRIMARY KEY column $c must be NOT NULL" }
            }
            is Constraint.Unique -> constraint.columns.forEach { columnIndex(it) }
            is Constraint.ForeignKey -> constraint.columns.forEach { columnIndex(it) }
        }
    }
}
```

## 4. 깨뜨릴 과제
- 과제 1: PK 컬럼이 nullable일 때 정확히 어떤 시점에 throw? init? insert?
- 과제 2: FK 정의 후 refTable이 존재하지 않으면? (지금은 검증 안 함 — 단계 6에서)
- 과제 3: 같은 컬럼에 PK와 Unique 동시 적용 시 의미 중복 — 어떻게 처리?

## 5. 다음 한계
- 실제 row 단위 검증 (PK 유니크, FK 존재) 없음 → **단계 6 mutation operator 통합**.
- CHECK constraint expression 미지원 → 단계 6 expression 후 5-2 추가 가능.

---
| 2026-05-16 | 초안 |
