# Handoff: Stage 19 (Online DDL) 완료

## 한 줄
ADD COLUMN nullable (metadata-only).

## 결정
- D-076: nullable만 허용.
- D-077: bytes 재인코딩 없음 (bitmap 자동 NULL).
- D-078: DROP/ALTER TYPE 비목표.

## 코드
- `catalog.OnlineDdl`
