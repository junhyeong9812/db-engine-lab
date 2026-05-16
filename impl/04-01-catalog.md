# impl/04-01 — Schema, Type, Catalog, Tuple

> 상위: `docs/stages/04-schema-catalog.md`
> 범위: Type enum, ColumnDef, TableSchema, Tuple (직렬화), Catalog (persistence).
> **✅ 검증 완료. 누적 28 tests PASSED.**

---

## 0. 참조 출처
- SimpleDB `Catalog`, `TupleDesc`, `Tuple`, `Type`.
- BusTub `catalog`, `column`, `schema`.

## 1. invariant
- **CI-1**: Tuple encode → decode 라운드트립 동일.
- **CI-2**: NOT NULL 컬럼에 null 거부.
- **CI-3**: 타입 불일치 거부.
- **CI-4**: Catalog persist 후 reopen 시 schema 복원.
- **CI-5**: 중복 컬럼명, 중복 테이블명 거부.

## 2. 핵심 결정
- **Type은 enum + per-type handler** — sealed로 닫지 않음 (codex 보정 2: storage/type 확장 지점). 단계 5에서 DECIMAL/DATE 추가 시 enum + when.
- **ColumnDef/TableSchema는 data class** — 도메인 메타데이터, value equality OK.
- **Tuple은 일반 class** — values는 List<Any?>, equals/hashCode 명시.
- **NULL bitmap** — `(N+7)/8 bytes`, 비트 단위 표시.
- **Catalog persistence** — 단순 binary format. 매번 전체 재기록 (단일 thread 가정, 단계 13 이전).

## 3. Tuple encoding 형식
```
[null bitmap (ceil(N/8) bytes)]
[encoded non-null values in column order]
```
- INT: 4 bytes
- BIGINT: 8 bytes
- STRING: 4 bytes length-prefix + UTF-8 bytes

## 4. Catalog encoding 형식
table 별로:
```
[4: name length][name bytes]
[4: column count]
repeat column count:
  [4: col name length][col name bytes]
  [1: type ordinal]
  [1: nullable flag]
```

## 5. Q/A 핵심 줄

```kotlin
// Q: 왜 data class가 ColumnDef에는 OK인데 Page에는 금지였나?
data class ColumnDef(val name: String, val type: Type, val nullable: Boolean = true)
```
<details><summary>A</summary>

ColumnDef는 immutable 값 객체 (메타데이터). Page는 mutable byte 컨테이너 — 자동 equals/hashCode가 의미 없거나 거짓 안전감.
</details>

```kotlin
// Q: requireTypeMatches에서 String, Int, Long을 직접 체크 — 더 우아한 방법?
when (col.type) {
    Type.INT -> v is Int
    Type.BIGINT -> v is Long
    Type.STRING -> v is String
}
```
<details><summary>A</summary>

Kotlin reflection은 더 우아하지만 runtime cost. enum + when이 명시적 + 빠름 + Type 추가 시 컴파일러가 missing branch 경고.
</details>

## 6. 직접 깨뜨릴 과제
- 과제 1: STRING 컬럼에 1MB string 넣으면 어떤 일? Tuple.encode의 buffer estimation 부정확하면?
- 과제 2: Catalog 저장 도중 process kill → reopen 시 partial 파일 어떻게 처리?
- 과제 3: Type에 DECIMAL 추가하려면 어디까지 바꿔야 하나? (encode, decode, requireTypeMatches, fixedSize)

## 7. 다음 한계
- Schema만으론 PK/FK/UNIQUE/CHECK 검증 불가 → **단계 5 Constraints**.

---
| 2026-05-16 | 초안 |
