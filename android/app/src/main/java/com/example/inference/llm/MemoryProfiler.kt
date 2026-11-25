package com.example.inference.llm

import java.io.File

data class AdvisoryProfilingMetrics(
    val baselineRssMb: Double = 0.0,
    val loadedRssMb: Double = 0.0,
    val generationPeakRssMb: Double = 0.0,
    val unloadRssMb: Double = 0.0,
    val loadLatencyMs: Long = 0L,
    val prefillLatencyMs: Long = 0L,
    val generationLatencyMs: Long = 0L
)

/**
 * Lightweight process memory profiler for tracking Android RSS (Resident Set Size).
 * Reads `/proc/self/statm` on Linux/Android with JVM heap fallback.
 */
object MemoryProfiler {
    fun getProcessRssBytes(): Long {
        return runCatching {
            File("/proc/self/statm").readText().trim().split("\\s+".toRegex()).let { parts ->
                val residentPages = parts[1].toLong()
                residentPages * 4096L
            }
        }.getOrElse {
            val runtime = Runtime.getRuntime()
            (runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L)
        }
    }

    fun getProcessRssMb(): Double {
        return getProcessRssBytes() / (1024.0 * 1024.0)
    }
}
