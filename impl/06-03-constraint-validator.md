# impl/06-03 — ConstraintValidator (PK / Unique / FK at insert)

> 상위: `docs/stages/06-query-api.md`
> 범위: insert 시점에 PK/Unique/FK 검증. 풀스캔 O(N) (단계 11 index-backed 전).
> **검증**: ConstraintValidatorTest 3 PASSED.

---

## 0. 참조 출처
- 참조 부재 (SimpleDB·BusTub은 약함). PostgreSQL constraint checking 패턴.

## 1. invariant
- **CI-1**: PK 중복 insert → ConstraintViolation throw.
- **CI-2**: Unique 중복 insert → throw. NULL은 distinct로 취급 (SQL 표준).
- **CI-3**: FK 위반 insert → throw. NULL FK는 허용.

## 2. 의존성
- 단계 5의 sealed Constraint, TableSchema.constraints.
- 06-01 TableHeap.scan().

## 3. 문제 정의

단계 5에서 schema에 PK/Unique/FK 정의했지만 **실제 데이터 검증은 안 함**. insert 시점에 검증 필요.

핵심 결정: 검증 위치
- (a) TableHeap.insert 안에서 — heap이 constraint 알아야
- (b) ConstraintValidator 별도 — heap과 분리, 호출자 책임
- → **(b)** 선택. 단순성 + 단계 7 WorkUnit에서 commit 시점 일괄 검증 가능.

## 4. 실패 테스트

```kotlin
@Test
fun `중복 PK 거부`(@TempDir tempDir: Path) {
    val v = ConstraintValidator(heap)
    v.validateInsert(Tuple(schema, listOf(1L, "a@x.com")))
    heap.insert(...)
    assertThrows<ConstraintViolation> {
        v.validateInsert(Tuple(schema, listOf(1L, "b@x.com")))
    }
}
```

## 5. 구현 코드 (ConstraintValidator.kt)

```kotlin
class ConstraintValidator(
    private val heap: TableHeap,
    private val foreignKeyLookup: (String) -> TableHeap? = { null },
) {
    private val schema: TableSchema get() = heap.schema

    fun validateInsert(tuple: Tuple) {
        for (constraint in schema.constraints) {
            when (constraint) {
                is Constraint.PrimaryKey -> validateUniqueColumns(tuple, constraint.columns, "PRIMARY KEY")
                is Constraint.Unique -> validateUniqueColumns(tuple, constraint.columns, "UNIQUE")
                is Constraint.ForeignKey -> validateForeignKey(tuple, constraint)
            }
        }
    }

    private fun validateUniqueColumns(tuple: Tuple, columns: List<String>, label: String) {
        val newValues = columns.map { tuple.get(it) }
        // Q: NULL이 하나라도 있으면 검증 skip — 왜?
        if (newValues.any { it == null }) return
        // <details><summary>A</summary>
        //
        // SQL 표준: UNIQUE는 NULL을 distinct로 취급 → 여러 row가 NULL 가져도 OK. NOT NULL은 schema 레벨에서 별도 강제 (단계 5 ColumnDef.nullable).
        // </details>

        for (existing in heap.scan()) {
            val existingValues = columns.map { existing.get(it) }
            if (existingValues.any { it == null }) continue
            if (existingValues == newValues) {
                throw ConstraintViolation(
                    "$label violation on (${columns.joinToString(",")}) in table ${schema.name}: $newValues already exists"
                )
            }
        }
    }

    private fun validateForeignKey(tuple: Tuple, fk: Constraint.ForeignKey) {
        val childValues = fk.columns.map { tuple.get(it) }
        if (childValues.any { it == null }) return  // NULL FK 허용

        // Q: foreignKeyLookup이 lambda인 이유? Catalog 직접 가지면 안 되나?
        val refHeap = foreignKeyLookup(fk.refTable)
            ?: throw ConstraintViolation("FOREIGN KEY refTable ${fk.refTable} not available")
        // <details><summary>A</summary>
        //
        // ConstraintValidator를 Catalog에 의존시키면 단계 4 Catalog 인터페이스 변경 시 영향 큼. lambda는 호출자가 heap 매핑 책임 — 결합도 낮음.
        // </details>

        for (parent in refHeap.scan()) {
            if (fk.refColumns.map { parent.get(it) } == childValues) return
        }
        throw ConstraintViolation("FOREIGN KEY violation: $childValues not in ${fk.refTable}")
    }
}

class ConstraintViolation(message: String) : RuntimeException(message)
```

## 6. 검증 테스트

- 중복 PK insert → throw
- 중복 Unique insert → throw
- FK 통과 / 위반 양쪽

## 7. 직접 깨뜨릴 과제
- 과제 1: 풀스캔 O(N) per insert — 10만 row면 insert 10만 곱 = 100억. 어떻게 줄이나? (BTree index, 단계 11)
- 과제 2: FK CASCADE delete 추가하려면? (delete operator + 다른 table mutation)
- 과제 3: composite UNIQUE (다중 컬럼) 에서 한 컬럼만 NULL이면 distinct? (현 구현은 skip — SQL 표준 부합)

## 8. 다음 한계
- validate 후 insert가 atomic 아님 → **단계 7 WorkUnit (deferred apply)**.
