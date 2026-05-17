package com.dbenginelab.mvcc

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Stage 10 보강 (X4): SI가 막아주는 anomaly 검증 + 막지 못하는 anomaly 명시.
 *
 * Snapshot Isolation (우리 모델):
 *  - Dirty Read: 방지 ✅
 *  - Lost Update: 방지 못 함 ❌ (first-committer-wins 미구현)
 *  - Non-Repeatable Read: 방지 ✅ (snapshot 일관성)
 *  - Phantom Read: 방지 ✅ (snapshot 일관성)
 *  - Write Skew: 방지 못 함 ❌ (SSI 필요)
 */
class IsolationAnomalyTest {

    @Test
    fun `dirty read 방지 - tx2는 tx1 미커밋 변경 못 봄`() {
        val store = MVCCStore<Long, String>()
        val sp = SnapshotProvider()
        val tx1 = sp.begin()
        store.insert(1L, "tx1-dirty", tx1.xid)
        // tx1 미커밋
        val tx2 = sp.begin()
        assertNull(store.get(1L, tx2))  // ✅ dirty read 방지
    }

    @Test
    fun `repeatable read - tx2 두 번 read 같은 결과 (tx3가 commit해도)`() {
        val store = MVCCStore<Long, String>()
        val sp = SnapshotProvider()
        val tx1 = sp.begin(); store.insert(1L, "v1", tx1.xid); sp.commit(tx1)

        val tx2 = sp.begin()
        val r1 = store.get(1L, tx2)

        val tx3 = sp.begin(); store.insert(1L, "v2", tx3.xid); sp.commit(tx3)

        val r2 = store.get(1L, tx2)
        assertEquals(r1, r2)  // ✅ repeatable read
        assertEquals("v1", r2)
    }

    @Test
    fun `phantom 방지 - tx2 snapshot 시점 이후 tx3 insert는 invisible`() {
        val store = MVCCStore<Long, String>()
        val sp = SnapshotProvider()
        val tx1 = sp.begin(); store.insert(1L, "v1", tx1.xid); sp.commit(tx1)

        val tx2 = sp.begin()
        val tx3 = sp.begin(); store.insert(2L, "phantom", tx3.xid); sp.commit(tx3)

        assertNull(store.get(2L, tx2))  // ✅ phantom 방지 — tx2는 key=2 못 봄
    }

    @Test
    fun `lost update 미방지 - first-committer-wins 미구현 (학습 데모)`() {
        // SI는 "lost update"를 SSI 또는 first-committer-wins로 막아야 하는데
        // 우리 단순 모델은 막지 못함 — 두 tx가 같은 key update하면 마지막이 이김.
        val store = MVCCStore<Long, String>()
        val sp = SnapshotProvider()
        val tx0 = sp.begin(); store.insert(1L, "init", tx0.xid); sp.commit(tx0)

        val tx1 = sp.begin(); val tx2 = sp.begin()
        store.insert(1L, "tx1-update", tx1.xid)  // tx1 update
        store.insert(1L, "tx2-update", tx2.xid)  // tx2 update — 우리 모델은 silent OK
        sp.commit(tx1); sp.commit(tx2)

        val tx3 = sp.begin()
        // 마지막 update 결과 보임. SSI라면 한 tx abort.
        assertEquals("tx2-update", store.get(1L, tx3))
        // 학습 메모: 이 anomaly를 막으려면 write conflict detection 또는 SSI.
    }
}
