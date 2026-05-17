# impl/05-01 — Constraints (한 줄 한 줄)

> **검증**: ConstraintTest 5 PASSED.
> 작성 파일:
> - 신규: `src/main/kotlin/com/dbenginelab/catalog/Constraint.kt`
> - 수정: `src/main/kotlin/com/dbenginelab/catalog/TableSchema.kt` (constraints field + validateConstraints)
> - 수정: `src/main/kotlin/com/dbenginelab/catalog/Catalog.kt` (constraint persistence)

## 0. 참조
참조 부재 (SimpleDB·BusTub 약함). PostgreSQL constraint patterns.

## 1. invariant
- PK 컬럼은 자동 NOT NULL 강제 (schema validation).
- 한 테이블에 PK 하나만.
- 존재하지 않는 컬럼 참조 거부.
- Constraint persistence + reopen 정확.

## 2. Constraint.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.catalog

// Q: 왜 sealed? 단계 5에서 진짜 닫힌 집합인가?
sealed class Constraint {
    // <details><summary>A</summary>
    // PK/Unique/FK는 단계 5에서 진짜 닫힌 집합. CHECK는 단계 6 expression 의존이라 별도. sealed가 적합.
    // </details>

    data class PrimaryKey(val columns: List<String>) : Constraint() {
        init { require(columns.isNotEmpty()) { "PrimaryKey must have at least one column" } }
    }

    data class Unique(val columns: List<String>) : Constraint() {
        init { require(columns.isNotEmpty()) { "Unique must have at least one column" } }
    }

    data class ForeignKey(
        val columns: List<String>,                                    // 이 테이블의 컬럼들
        val refTable: String,                                         // 참조 테이블 이름
        val refColumns: List<String>,                                 // 참조 컬럼들
    ) : Constraint() {
        init {
            require(columns.size == refColumns.size && columns.isNotEmpty()) {
                "ForeignKey columns and refColumns must match in count"
            }
        }
    }
}
```

## 3. TableSchema.validateConstraints — 한 줄 한 줄

```kotlin
private fun validateConstraints() {
    val pks = constraints.filterIsInstance<Constraint.PrimaryKey>()
    require(pks.size <= 1) { "table $name has more than one PRIMARY KEY" }    // PK 단일
    for (constraint in constraints) {
        when (constraint) {
            is Constraint.PrimaryKey -> {
                constraint.columns.forEach { c ->
                    val col = column(c)                              // 존재하지 않으면 throw
                    // Q: PK column이 nullable이면 왜 거부?
                    require(!col.nullable) {
                        "PRIMARY KEY column $c must be NOT NULL in table $name"
                    }
                    // <details><summary>A</summary>
                    // PK는 unique + identifier — NULL이면 식별 불가. SQL 표준 강제.
                    // </details>
                }
            }
            is Constraint.Unique -> {
                constraint.columns.forEach { c -> columnIndex(c) }   // 존재만 검증
            }
            is Constraint.ForeignKey -> {
                constraint.columns.forEach { c -> columnIndex(c) }
                // Q: refTable, refColumns 존재 검증은 왜 안 함?
                // <details><summary>A</summary>
                // schema validation은 다른 테이블 알 수 없음 (TableSchema 단일). 단계 6 mutation 시 validator가 검증.
                // </details>
            }
        }
    }
}
```

## 4. Catalog persistence — tag byte 기반 binary

```kotlin
private fun writeConstraint(dos: DataOutputStream, c: Constraint) {
    when (c) {
        is Constraint.PrimaryKey -> {                                // tag 0
            dos.writeByte(0)
            writeStringList(dos, c.columns)
        }
        is Constraint.Unique -> {                                    // tag 1
            dos.writeByte(1)
            writeStringList(dos, c.columns)
        }
        is Constraint.ForeignKey -> {                                // tag 2
            dos.writeByte(2)
            writeStringList(dos, c.columns)
            writeString(dos, c.refTable)
            writeStringList(dos, c.refColumns)
        }
    }
}

private fun readConstraint(dis: DataInputStream): Constraint =
    when (dis.readByte().toInt()) {
        0 -> Constraint.PrimaryKey(readStringList(dis))
        1 -> Constraint.Unique(readStringList(dis))
        2 -> Constraint.ForeignKey(
            columns = readStringList(dis),
            refTable = readString(dis),
            refColumns = readStringList(dis),
        )
        else -> error("unknown constraint tag")
    }
```

## 5. 검증 (5 PASSED)
- PK가 nullable column만 허용 → 거부
- PK는 한 테이블에 하나만
- 존재하지 않는 PK column → 거부
- Constraint persist + reopen 복원
- 복합 PK 정상

## 6. 다음 한계
- 실제 row 단위 검증 (PK 유니크, FK 존재) 없음 → **단계 6-03 ConstraintValidator**.
- CHECK constraint 미지원 → 단계 6 expression 후 5-2 가능.
