package com.dbenginelab.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MetricsRegistry {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val gauges = ConcurrentHashMap<String, AtomicLong>()

    fun incCounter(name: String, delta: Long = 1) {
        counters.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(delta)
    }
    fun setGauge(name: String, value: Long) {
        gauges.computeIfAbsent(name) { AtomicLong(0) }.set(value)
    }
    fun counter(name: String): Long = counters[name]?.get() ?: 0
    fun gauge(name: String): Long = gauges[name]?.get() ?: 0
    fun snapshot(): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        counters.forEach { (k, v) -> out["counter.$k"] = v.get() }
        gauges.forEach { (k, v) -> out["gauge.$k"] = v.get() }
        return out
    }
}

class SlowQueryLog(private val thresholdMillis: Long = 1000) {
    private val entries = mutableListOf<Entry>()
    data class Entry(val timestamp: Long, val sql: String, val durationMillis: Long)

    @Synchronized
    fun record(sql: String, durationMillis: Long) {
        if (durationMillis >= thresholdMillis) {
            entries.add(Entry(System.currentTimeMillis(), sql, durationMillis))
        }
    }
    @Synchronized
    fun entries(): List<Entry> = entries.toList()
}
