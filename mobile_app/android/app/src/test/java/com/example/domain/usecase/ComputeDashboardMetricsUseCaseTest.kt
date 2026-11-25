package com.example.domain.usecase

import com.example.domain.model.AdvisoryAvailability
import com.example.domain.model.AiAdvisory
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.InspectionLatencyMetrics
import com.example.domain.model.InspectionPolicyConfig
import com.example.domain.model.InspectionRecord
import com.example.domain.model.ModelExecutionProvenance
import com.example.domain.model.PcbImageSource
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeDashboardMetricsUseCaseTest {

    private val timeZone: TimeZone = TimeZone.getTimeZone("UTC")
    private val useCase = ComputeDashboardMetricsUseCase(timeZone = timeZone)

    @Test
    fun emptyRoomRecordsProduceZeroMetricsAndNoTrendData() {
        val metrics = useCase.compute(
            allRecords = emptyList(),
            period = DashboardPeriod.ALL_TIME,
            trendWindow = TrendWindow.LAST_7_DAYS,
            nowEpochMs = epochFor(2026, 5, 10)
        )

        assertEquals(0, metrics.totalInspections)
        assertEquals(0, metrics.passCount)
        assertEquals(0, metrics.reviewCount)
        assertEquals(0, metrics.rejectCount)
        assertEquals(0, metrics.incompleteCount)
        assertFalse(metrics.hasDemonstrationRecords)
        assertFalse(metrics.hasAnyTrendRecords)
        assertEquals(7, metrics.trendPoints.size)
        assertTrue(metrics.recentInspections.isEmpty())
    }

    @Test
    fun aggregatesRealDispositionsDailyDefectsAndPeriodFilters() {
        val nowMs = epochFor(2026, 5, 10)
        val records = listOf(
            sampleRecord("PCB-1", epochFor(2026, 5, 10), InspectionDisposition.REJECT, defects = 3, isDemo = true),
            sampleRecord("PCB-2", epochFor(2026, 5, 10), InspectionDisposition.PASS, defects = 0, isDemo = false),
            sampleRecord("PCB-3", epochFor(2026, 5, 8), InspectionDisposition.REVIEW, defects = 2, isDemo = false),
            sampleRecord("PCB-4", epochFor(2026, 5, 7), InspectionDisposition.INCOMPLETE, defects = 0, isDemo = false),
            // 15 days ago: excluded from LAST_7_DAYS overview, included in ALL_TIME
            sampleRecord("PCB-5", epochFor(2026, 4, 25), InspectionDisposition.PASS, defects = 0, isDemo = false)
        )

        val allTime = useCase.compute(
            allRecords = records,
            period = DashboardPeriod.ALL_TIME,
            trendWindow = TrendWindow.LAST_7_DAYS,
            nowEpochMs = nowMs
        )
        assertEquals(5, allTime.totalInspections)
        assertEquals(2, allTime.passCount)
        assertEquals(1, allTime.reviewCount)
        assertEquals(1, allTime.rejectCount)
        assertEquals(1, allTime.incompleteCount)
        assertTrue(allTime.hasDemonstrationRecords)
        assertTrue(allTime.hasAnyTrendRecords)
        assertEquals(5, allTime.recentInspections.size)
        assertEquals("PCB-1", allTime.recentInspections.first().inspectionId)

        // Check daily buckets in LAST_7_DAYS (May 4..May 10)
        val may10 = allTime.trendPoints.last()
        assertEquals(CalendarDay(2026, 5, 10), may10.date)
        assertEquals(3, may10.defectCount)
        assertEquals(2, may10.inspectionCount)

        val may8 = allTime.trendPoints.first { it.date == CalendarDay(2026, 5, 8) }
        assertEquals(2, may8.defectCount)
        assertEquals(1, may8.inspectionCount)

        val last7Days = useCase.compute(
            allRecords = records,
            period = DashboardPeriod.LAST_7_DAYS,
            trendWindow = TrendWindow.LAST_7_DAYS,
            nowEpochMs = nowMs
        )
        assertEquals(4, last7Days.totalInspections)
        assertEquals(1, last7Days.passCount)
    }

    private fun epochFor(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance(timeZone, Locale.US).apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis
    }

    private fun sampleRecord(
        id: String,
        ts: Long,
        disposition: InspectionDisposition,
        defects: Int,
        isDemo: Boolean
    ): InspectionRecord {
        return InspectionRecord(
            inspectionId = id,
            timestampEpochMs = ts,
            boardName = "Board $id",
            sourceType = if (isDemo) PcbImageSource.SAMPLE else PcbImageSource.GALLERY,
            imageUri = "file:///tmp/$id.jpg",
            imageWidth = 1200,
            imageHeight = 900,
            lowClarityRisk = false,
            clarityScore = 100.0,
            officialDisposition = disposition,
            policyProfile = InspectionPolicyConfig.DEFAULT_PROFILE_NAME,
            policyVersion = InspectionPolicyConfig.DEFAULT_POLICY_VERSION,
            policyConfigSnapshot = InspectionPolicyConfig(),
            effectiveThresholdsSummary = "defaults",
            policyRationale = "test",
            defects = emptyList(),
            totalDefects = defects,
            isDemonstration = isDemo,
            provenance = ModelExecutionProvenance.unavailableProduction("1.22.0"),
            advisory = AiAdvisory(
                availability = AdvisoryAvailability.UNAVAILABLE,
                isDemonstration = isDemo
            ),
            latencyMetrics = InspectionLatencyMetrics()
        )
    }
}
