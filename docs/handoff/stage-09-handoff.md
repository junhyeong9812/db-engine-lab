# Handoff: Stage 09 (LockManager) 완료

## 한 줄
S/X LockManager. throw on conflict (wait는 9-2).

## 결정
- D-045: 단계 9-1은 단순 S/X + throw.
- D-046: Strict 2PL → releaseAll(txId).
- D-047: S→X upgrade는 다른 holder 없을 때만.

## 코드
- `lock.LockManager`, `lock.LockConflict`

## 다음 입력 (10)
- 잠금만으로 reader/writer 동시성 약함. MVCC로 read는 lock 없이.
