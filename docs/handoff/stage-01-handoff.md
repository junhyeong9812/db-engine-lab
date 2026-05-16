# Handoff: Stage 01 (Append-Only File Storage) 완료 시점

> 작성일: 2026-05-16
> 직전 handoff: stage-00-initial.md
> 다음 단계: 2 (page-based IO + thin buffer pool)

## 0. 한 줄 요약

Append-only key-value 파일 저장소 완성. `./gradlew test` 3개 테스트 모두 통과. I-1, I-2, I-3 invariant 검증.

## 1. 결정된 사항 (누적)
- D-001 ~ D-016 (이전 결정).
- **D-017 (이번)**: 단계 1 src/ 구조 — `com.dbenginelab.storage` 패키지에 Record, StorageError, AppendOnlyFile.

## 2. 만족하는 invariant (누적)
- **I-1**: append 후 close → 재오픈 → scanAll 결과 동일 ✅ (test: `I-1 append 후 reopen하면 모든 record를 순서대로 다시 읽는다`)
- **I-2**: record 경계 식별 가능 ✅ (test: `I-2 빈 record(zero-length key·value)도 정상 처리`)
- **I-3**: flush 후 process kill해도 데이터 보존 — 코드상 fsync(`file.fd.sync()`) 호출, OS crash는 별도 (단계 8 WAL 정책 의존)

## 3. 사용한 참조 출처
- SimpleDB `HeapFile.java` (단순화 — page 단위 아닌 raw record 단위로 시작).
- BusTub `disk_manager.cpp` (대조 — page 단위 처음부터 vs 우리 raw record).
- 본 프로젝트 선택: raw record → 단계 2에서 page로 전환 (실패 가정 순서 원칙).

## 4. 핵심 코드 위치
- `src/main/kotlin/com/dbenginelab/storage/Record.kt` — key/value byte 컨테이너
- `src/main/kotlin/com/dbenginelab/storage/StorageError.kt` — sealed error hierarchy (CorruptRecord, UnexpectedEof)
- `src/main/kotlin/com/dbenginelab/storage/AppendOnlyFile.kt` — RandomAccessFile 기반, length-prefix frame
- `src/test/kotlin/com/dbenginelab/storage/AppendOnlyFileTest.kt` — 3 테스트

공개 API: `Record(key, value)`, `AppendOnlyFile(path)` + `append(record)`, `flush()`, `scanAll(): List<Record>`, `close()` (`Closeable`).

## 5. 깨진 가설 / 갱신된 결정
- impl/01-01의 가설 5.4 "scan 후 append 시 위치 명시 seek 불요"는 **갱신** — `append()` 안에 `file.seek(file.length())` 명시 추가. scan/append 혼용 시 안전.

## 6. 사용자가 막혔던 지점 (학습 메모)
- (사용자 따라치기 전, Claude 검증 시점이라 비어있음)

## 7. Spot check 결과
- 본 단계는 Claude 사전 구현·검증 단계 — 사용자 따라치기 시점에 SimpleDB `HeapFile.java`와 비교 권장.

## 8. 다음 단계가 받아야 할 입력
- Record, AppendOnlyFile API는 단계 2에서 그대로 유지 (또는 page-wrap).
- 단계 2 PagedFile은 별도 추상화 — AppendOnlyFile과 공존.
- StorageError sealed hierarchy 확장 (단계 2에서 PageNotFound 등 추가).
- 단계 1 학습 메모: 단계 2 page IO 도입 후 raw record IO와의 관계 명확히 (`/docs/stages/02-page-buffer.md` 5절 후보 확인 질문 참조).

## 9. 새 세션을 위한 권고
- 이 문서 + `docs/stages/02-page-buffer.md` Invalidation triggers 확인 → 갱신 → 단계 2 진입.
- codex 호출 시 본 문서 + reference-policy.md 첨부.

---

| 날짜 | 변경 |
|------|------|
| 2026-05-16 | 단계 1 완료 시점 snapshot (검증 통과) |
