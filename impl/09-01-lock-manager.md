# impl/09-01 — LockManager (한 줄 한 줄)

> **검증**: LockManagerTest 7 PASSED.
> 작성 파일:
> - 신규 패키지: `src/main/kotlin/com/dbenginelab/lock/`
> - 신규: LockManager.kt
> - 신규 테스트: LockManagerTest.kt

## 0. 참조
- SimpleDB `LockManager` (lab3).
- BusTub `lock_manager` (project 3 — multi-granularity 강함).

## 1. invariant
- SHARED는 multiple tx 동시 보유.
- EXCLUSIVE는 단독.
- 같은 tx 같은 mode 재요청 idempotent.
- Strict 2PL — releaseAll at commit/abort.

## 2. LockManager.kt — 한 줄 한 줄

```kotlin
package com.dbenginelab.lock                                         // 신규 lock 패키지

class LockManager {
    enum class Mode { SHARED, EXCLUSIVE }                            // 단순 S/X (multi-granularity 생략)
    private data class Holder(val txId: Long, val mode: Mode)
    private val holders: MutableMap<String, MutableList<Holder>> = mutableMapOf()

    @Synchronized                                                    // Q: 왜 @Synchronized?
    fun acquire(txId: Long, resource: String, mode: Mode) {
        // <details><summary>A</summary>
        // 단계 13 multi-thread 가정 — concurrent acquire/release 보호. JVM intrinsic lock.
        // </details>
        val current = holders.getOrPut(resource) { mutableListOf() }
        val mine = current.firstOrNull { it.txId == txId }
        if (mine != null) {                                          // 같은 tx 재요청
            // Q: 같은 mode 재요청 그냥 return — 왜 idempotent?
            if (mine.mode == Mode.EXCLUSIVE) return                  // EXCLUSIVE는 SHARED도 cover
            if (mine.mode == Mode.SHARED && mode == Mode.SHARED) return
            // <details><summary>A</summary>
            // 같은 tx는 자기 자신과 충돌 안 함. 중복 acquire가 no-op이 정확 — application 중복 호출도 silent OK.
            // </details>

            // SHARED → EXCLUSIVE 업그레이드
            if (current.any { it.txId != txId }) {                   // 다른 holder 있으면
                throw LockConflict(txId, resource, mode, current.toList())
            }
            current.remove(mine)
            current.add(Holder(txId, Mode.EXCLUSIVE))
            return
        }
        when (mode) {
            Mode.SHARED -> {
                if (current.any { it.mode == Mode.EXCLUSIVE }) {     // X와 충돌
                    throw LockConflict(txId, resource, mode, current.toList())
                }
                current.add(Holder(txId, Mode.SHARED))
            }
            Mode.EXCLUSIVE -> {
                if (current.isNotEmpty()) {                          // 어떤 holder 있으면 충돌
                    throw LockConflict(txId, resource, mode, current.toList())
                }
                current.add(Holder(txId, Mode.EXCLUSIVE))
            }
        }
    }

    @Synchronized
    fun releaseAll(txId: Long) {                                     // Strict 2PL — 한꺼번에 release
        val empty = mutableListOf<String>()
        for ((res, list) in holders) {
            list.removeAll { it.txId == txId }
            if (list.isEmpty()) empty.add(res)
        }
        for (res in empty) holders.remove(res)                       // 빈 resource 정리
    }

    @Synchronized
    fun isHeld(txId: Long, resource: String): Boolean =
        holders[resource]?.any { it.txId == txId } ?: false

    @Synchronized
    fun holderCount(resource: String): Int = holders[resource]?.size ?: 0
}

class LockConflict(
    val requesterTxId: Long, val resource: String,
    val requestedMode: LockManager.Mode, val currentHolders: List<Any>,
) : RuntimeException("tx $requesterTxId cannot acquire $requestedMode on $resource (held by $currentHolders)")
```

## 3. 검증 (7 PASSED)
- 여러 tx SHARED 동시
- SHARED 보유 중 다른 tx EXCLUSIVE 충돌
- EXCLUSIVE 보유 중 SHARED 충돌
- S → X upgrade 단독 시 OK
- 업그레이드 시 다른 tx SHARED 있으면 충돌
- releaseAll 후 다른 tx EXCLUSIVE OK
- 같은 tx SHARED 두 번 idempotent

## 4. 깨뜨릴 과제
- deadlock — TX1 holds A wants B, TX2 holds B wants A. wait 모델로 어떻게? (wait-die, wound-wait)
- phantom 방지 — next-key lock 필요.
- S→X upgrade deadlock — TX1, TX2 모두 S 가진 채 X 요청 → 둘 다 throw.
