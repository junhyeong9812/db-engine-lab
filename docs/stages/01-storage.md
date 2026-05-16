# Stage 01 — Append-Only File Storage

> Phase A / 단계 1.
> 깨지는 가정: 메모리에만 두면 사라진다.
> 도입: append-only file storage, `Record` 표현.
> 산출물 기준: engine correctness.

---

## 1. 이 단계가 해결하는 문제

데이터를 **재시작 후에도** 다시 읽을 수 있게 영속적으로 저장한다.

가장 단순한 형태: **append-only 파일** — 한 번 쓴 데이터는 절대 수정하지 않고, 새 데이터는 항상 파일 끝에 덧붙인다.

---

## 2. 만족해야 할 invariant

| ID | 불변조건 | 깨지면 무엇이 일어나는가 |
|----|---------|----------------------|
| I-1 | append 후 close → 재오픈 → scanAll 결과는 append한 모든 record를 순서대로 포함 | durability 깨짐. 단계 0의 메모리만 상태와 같아짐 |
| I-2 | 한 record의 경계(어디서 시작·끝)는 파일에서 명확히 식별 가능 | record가 섞이거나 partial read 발생 |
| I-3 | flush() 호출 후 process kill해도 그 시점까지의 append는 살아남음 (단, OS crash는 fsync 정책에 따라 별도) | 사용자가 "저장됐다"고 믿은 데이터가 사라짐 |

---

## 3. 비목표 (이 단계에서 안 다룸)

- **삭제·수정**: append-only이므로 불가. 단계 7 transaction에서 다룸.
- **인덱스**: 이 단계는 풀스캔만. 단계 3에서 인덱스.
- **동시성**: 단일 writer 가정. 단계 9에서 lock.
- **페이지 단위 IO**: 한 record씩 raw write. 단계 2에서 페이지 도입.
- **fsync 보장 수준**: `flush()` API는 두되, fsync 정책은 단계 8 WAL에서 재정의.
- **타입·스키마**: 모두 raw `ByteArray`. 단계 4에서 스키마.
- **다중 파일**: 단일 파일. 단계 4에서 catalog가 다중 테이블 → 다중 파일 가능.

---

## 4. 다음 단계의 동기 (이 단계가 깨지는 지점)

| 동기 | 다음 단계 |
|------|----------|
| 풀스캔이 느림 (record 수 늘면 O(N)) | 단계 3: Index (BTree) |
| 한 record가 크면 통째로 read·write 비효율 | 단계 2: Page 단위 IO |
| partial write로 마지막 record가 잘릴 수 있음 | 단계 1 다음 세션 또는 단계 8 WAL |
| 한 process 한 번에 한 writer만 가능 | 단계 9: Lock |

---

## 5. 세션 분할 계획 (잠정)

| 세션 | 범위 |
|------|------|
| **01-01: append-only kv** | `Record` + `AppendOnlyFile` (write/flush/scanAll/close). 단일 writer. 단일 파일. **이번 세션.** |
| 01-02: partial write 처리 | crash로 잘린 record 감지 + skip. checksum 가설 (단계 8에서 강화). |
| 01-03: reopen 시 consistency | open 시 끝 truncate 정책. |

01-02·01-03은 사용자 학습 페이스 보고 결정. 01-01에서 깨뜨릴 과제로 충분히 경험하면 01-02는 자연 이어짐.

---

## 6. 참조 정책 (이 단계)

`docs/reference-policy.md` 참조.

### 주 참조 (SimpleDB)
- `simpledb/storage/HeapFile.java` (단순화 버전)
- `simpledb/storage/Tuple.java`, `TupleDesc.java` (단계 4에서 본격 등장, 이번엔 raw bytes만)

### 대조 참조 (BusTub)
- `src/storage/disk/disk_manager.cpp` — `ReadPage(page_id_t)`, `WritePage(page_id_t)`
- 차이: BusTub은 처음부터 page 단위 (단계 2 선반영). SimpleDB도 page 단위지만 본 프로젝트는 단계 1에서는 raw record 단위로 시작 → 단계 2에서 page로 전환.

### 본 프로젝트 선택
- **raw record 단위 시작** (page는 단계 2). 이유: 페이지 개념을 먼저 끌어들이면 단계 1에 비해 도입할 개념이 너무 많아짐. "실패하는 가정의 순서" 원칙에 따라 가장 작은 단위부터.

---

## 7. 패키지 구조

```
src/main/kotlin/com/dbenginelab/
└── storage/
    ├── Record.kt
    ├── AppendOnlyFile.kt
    └── StorageError.kt          # sealed error hierarchy (constraints.md Kotlin 규칙)

src/test/kotlin/com/dbenginelab/
└── storage/
    └── AppendOnlyFileTest.kt
```

---

## 8. 단계 종료 조건

- [ ] `impl/01-01-append-only-kv.md` 의 모든 검증 테스트 통과.
- [ ] I-1, I-2, I-3 모두 만족.
- [ ] 직접 깨뜨릴 과제 3개 모두 수행 (결과를 decision-log 또는 학습 메모로 기록).
- [ ] (옵션) Spot check: SimpleDB `HeapFile.java` 코드 직접 확인 (codex 보정 5 — 초반 단계는 매번).
- [ ] `docs/handoff/stage-01-handoff.md` 작성.

---

## 9. 단계 1 학습 메모 (사용자가 채움)

(사용자가 막힌 지점, 새로 배운 점, 깨뜨릴 과제 결과 등을 자유 형식으로 누적)

- (비어있음)

---

## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 초안 |
