package com.dbenginelab.mvcc

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MVCCStoreTest {

    @Test fun `tx1 insert commit 후 tx2 snapshot이 봄`() {
        val store = MVCCStore<Long, String>()
        val sp = SnapshotProvider()
        val s1 = sp.begin(); store.insert(1L, "v1", s1.xid); sp.commit(s1)
        val s2 = sp.begin()
        assertEquals("v1", store.get(1L, s2))
    }

    @Test fun `미커밋 tx 변경은 다른 snapshot에 안 보임`() {
        val store = MVCCStore<Long, String>()
        val sp = SnapshotProvider()
        val s1 = sp.begin(); store.insert(1L, "v1", s1.xid)
        val s2 = sp.begin()
        assertNull(store.get(1L, s2))
    }

    @Test fun `delete 후 새 snapshot 안 보고 옛 snapshot 봄`() {
        val store = MVCCStore<Long, String>()
        val sp = SnapshotProvider()
        val s0 = sp.begin(); store.insert(1L, "v1", s0.xid); sp.commit(s0)
        val s1 = sp.begin()
        val s2 = sp.begin(); store.delete(1L, s2.xid); sp.commit(s2)
        assertEquals("v1", store.get(1L, s1))
        val s3 = sp.begin()
        assertNull(store.get(1L, s3))
    }

    @Test fun `같은 key 여러 버전 — snapshot에 맞는 버전`() {
        val store = MVCCStore<Long, String>()
        val sp = SnapshotProvider()
        val s1 = sp.begin(); store.insert(1L, "v1", s1.xid); sp.commit(s1)
        val s2 = sp.begin(); store.insert(1L, "v2", s2.xid); sp.commit(s2)
        val s3 = sp.begin()
        assertEquals("v2", store.get(1L, s3))
        assertEquals(2, store.versionCount(1L))
    }
}
