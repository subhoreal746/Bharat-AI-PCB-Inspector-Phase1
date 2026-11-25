package com.example.domain.usecase

import com.example.domain.model.InspectionDisposition
import com.example.domain.model.InspectionRecord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

enum class DashboardPeriod(val label: String, val daysBack: Int?) {
    ALL_TIME("All Time", null),
    LAST_30_DAYS("30 Days", 30),
    LAST_7_DAYS("7 Days", 7)
}

enum class TrendWindow(val label: String, val days: Int) {
    LAST_7_DAYS("Last 7 Days", 7),
    LAST_14_DAYS("Last 14 Days", 14)
}

enum class TrendMetric(val label: String) {
    DEFECT_COUNT("Defect Counts"),
    INSPECTION_COUNT("Inspection Counts")
}

/**
 * API-24+ compatible local calendar day value object (avoids java.time NewApi requirement).
 */
data class CalendarDay(
    val year: Int,
    val month: Int, // 1..12
    val dayOfMonth: Int
) : Comparable<CalendarDay> {
    override fun compareTo(other: CalendarDay): Int {
        if (year != other.year) return year.compareTo(other.year)
        if (month != other.month) return month.compareTo(other.month)
        return dayOfMonth.compareTo(other.dayOfMonth)
    }
}

data class DailyTrendPoint(
    val date: CalendarDay,
    val shortLabel: String,
    val fullDateLabel: String,
    val defectCount: Int,
    val inspectionCount: Int
)

data class DashboardMetrics(
    val totalInspections: Int = 0,
    val passCount: Int = 0,
    val reviewCount: Int = 0,
    val rejectCount: Int = 0,
    val incompleteCount: Int = 0,
    val hasDemonstrationRecords: Boolean = false,
    val trendPoints: List<DailyTrendPoint> = emptyList(),
    val hasAnyTrendRecords: Boolean = false,
    val recentInspections: List<InspectionRecord> = emptyList()
) {
    val passPercent: Float get() = percentageOf(passCount)
    val reviewPercent: Float get() = percentageOf(reviewCount)
    val rejectPercent: Float get() = percentageOf(rejectCount)
    val incompletePercent: Float get() = percentageOf(incompleteCount)

    private fun percentageOf(count: Int): Float {
        if (totalInspections <= 0) return 0f
        return (count.toFloat() / totalInspections.toFloat()) * 100f
    }
}

class ComputeDashboardMetricsUseCase(
    private val timeZone: TimeZone = TimeZone.getDefault()
) {

    fun compute(
        allRecords: List<InspectionRecord>,
        period: DashboardPeriod,
        trendWindow: TrendWindow,
        nowEpochMs: Long = System.currentTimeMillis()
    ): DashboardMetrics {
        val today = calendarDayFor(nowEpochMs)
        val overviewRecords = if (period.daysBack == null) {
            allRecords
        } else {
            val cutoffDate = addDays(today, -(period.daysBack - 1))
            allRecords.filter { rec ->
                val date = calendarDayFor(rec.timestampEpochMs)
                date >= cutoffDate && date <= today
            }
        }

        var passCount = 0
        var reviewCount = 0
        var rejectCount = 0
        var incompleteCount = 0
        var hasDemo = false

        for (rec in overviewRecords) {
            if (rec.isDemonstration) hasDemo = true
            when (rec.officialDisposition) {
                InspectionDisposition.PASS -> passCount++
                InspectionDisposition.REVIEW -> reviewCount++
                InspectionDisposition.REJECT -> rejectCount++
                InspectionDisposition.INCOMPLETE -> incompleteCount++
            }
        }

        val startDay = addDays(today, -(trendWindow.days - 1))
        val groupedByDate = allRecords.groupBy { rec ->
            calendarDayFor(rec.timestampEpochMs)
        }

        val shortFmt = SimpleDateFormat(
            if (trendWindow.days <= 7) "EEE" else "M/d",
            Locale.US
        ).apply { this.timeZone = this@ComputeDashboardMetricsUseCase.timeZone }
        val fullFmt = SimpleDateFormat("MMM d", Locale.US).apply {
            this.timeZone = this@ComputeDashboardMetricsUseCase.timeZone
        }

        var trendHasData = false
        val points = (0 until trendWindow.days).map { offset ->
            val day = addDays(startDay, offset)
            val dayRecords = groupedByDate[day].orEmpty()
            if (dayRecords.isNotEmpty()) trendHasData = true
            val dateObj = noonDateFor(day)
            DailyTrendPoint(
                date = day,
                shortLabel = shortFmt.format(dateObj),
                fullDateLabel = fullFmt.format(dateObj),
                defectCount = dayRecords.sumOf { it.totalDefects },
                inspectionCount = dayRecords.size
            )
        }

        return DashboardMetrics(
            totalInspections = overviewRecords.size,
            passCount = passCount,
            reviewCount = reviewCount,
            rejectCount = rejectCount,
            incompleteCount = incompleteCount,
            hasDemonstrationRecords = hasDemo,
            trendPoints = points,
            hasAnyTrendRecords = trendHasData,
            recentInspections = allRecords.sortedByDescending { it.timestampEpochMs }.take(5)
        )
    }

    private fun calendarDayFor(epochMs: Long): CalendarDay {
        val cal = Calendar.getInstance(timeZone, Locale.US)
        cal.timeInMillis = epochMs
        return CalendarDay(
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH) + 1,
            dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun addDays(day: CalendarDay, delta: Int): CalendarDay {
        val cal = Calendar.getInstance(timeZone, Locale.US).apply {
            clear()
            set(day.year, day.month - 1, day.dayOfMonth, 12, 0, 0)
            add(Calendar.DAY_OF_MONTH, delta)
        }
        return CalendarDay(
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH) + 1,
            dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun noonDateFor(day: CalendarDay) = Calendar.getInstance(timeZone, Locale.US).apply {
        clear()
        set(day.year, day.month - 1, day.dayOfMonth, 12, 0, 0)
    }.time
}
