package com.dbenginelab.metrics

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MetricsTest {
    @Test fun `counter 누적`() {
        val r = MetricsRegistry()
        r.incCounter("q"); r.incCounter("q", 5)
        assertEquals(6, r.counter("q"))
    }
    @Test fun `gauge set`() {
        val r = MetricsRegistry()
        r.setGauge("b", 42); r.setGauge("b", 100)
        assertEquals(100, r.gauge("b"))
    }
    @Test fun `SlowQueryLog threshold`() {
        val log = SlowQueryLog(500)
        log.record("fast", 100); log.record("slow", 1500); log.record("th", 500)
        assertEquals(2, log.entries().size)
    }
    @Test fun `snapshot 모든 metric`() {
        val r = MetricsRegistry()
        r.incCounter("a", 3); r.setGauge("b", 7)
        val s = r.snapshot()
        assertEquals(3, s["counter.a"]); assertEquals(7, s["gauge.b"])
    }
}
