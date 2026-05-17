# impl/19-01 — Online DDL (한 줄 한 줄)

> **검증**: OnlineDdlTest 2 PASSED.
> 작성 파일:
> - 신규: `src/main/kotlin/com/dbenginelab/catalog/OnlineDdl.kt`
> - 신규 테스트: `src/test/kotlin/com/dbenginelab/catalog/OnlineDdlTest.kt`

## 0. 참조
MySQL online DDL + pt-osc 패턴.

## 1. invariant
- ADD COLUMN nullable 성공.
- NOT NULL 추가 거부 (DEFAULT 없이 불가).
- metadata-only — 기존 row bytes 재인코딩 없음.

## 2. OnlineDdl.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.catalog                                      // catalog 패키지 안 (단계 4와 같이)

object OnlineDdl {                                                   // singleton — 상태 없음
    fun addColumn(catalog: Catalog, tableName: String, newColumn: ColumnDef): TableSchema {
        // Q: 왜 nullable만 허용?
        require(newColumn.nullable) { "ADD COLUMN online supports nullable columns only" }
        // <details><summary>A</summary>
        // NOT NULL 추가는 기존 row를 채울 DEFAULT 값 필요. backfill 단계 별도. nullable이면 옛 row 자동 null로 읽힘 (Tuple.decode가 bitmap 처리).
        // </details>
        val current = catalog.getTable(tableName)
        val newSchema = current.copy(columns = current.columns + newColumn)  // immutable update
        // Q: dropTable + registerTable — 왜 두 단계?
        catalog.dropTable(tableName)
        catalog.registerTable(newSchema)
        // <details><summary>A</summary>
        // 단순화 — Catalog API가 update 없음. drop + register가 같은 효과. multi-thread 시 race (학습용 OK).
        // </details>
        return newSchema
    }
}
```

## 3. 검증 (2 PASSED)
- ADD COLUMN nullable 성공
- NOT NULL 추가 거부

## 4. 깨뜨릴 과제
- 동시 SELECT 진행 중 ADD COLUMN — race?
- ADD COLUMN DEFAULT 42 — backfill 어떻게? (lazy vs eager)
- DROP COLUMN — 즉시 vs lazy reclaim?

## 5. 다음 한계
- 단순 ADD COLUMN만. DROP/ALTER TYPE은 shadow column 필요 (별도 단계).
