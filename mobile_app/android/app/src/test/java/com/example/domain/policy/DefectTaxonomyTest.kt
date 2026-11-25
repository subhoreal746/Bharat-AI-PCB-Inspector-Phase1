package com.example.domain.policy

import com.example.domain.model.DefectSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefectTaxonomyTest {

    @Test
    fun `all 11 expected dataset labels are present in exact order`() {
        val expected = listOf(
            "open",
            "short",
            "mouse_bite",
            "spur",
            "spurious_copper",
            "missing_copper",
            "copper_burr",
            "pin_hole",
            "hole_breakout",
            "scratch",
            "copper"
        )
        assertEquals(expected, DefectTaxonomy.expectedDatasetLabelsByIndex)
        assertEquals(11, DefectTaxonomy.canonicalEntries.size)
    }

    @Test
    fun `normalization handles case spacing and hyphens cleanly`() {
        assertEquals("Open Circuit", DefectTaxonomy.resolve("  OPEN-Circuit ").canonicalLabel)
        assertEquals(DefectSeverity.CRITICAL, DefectTaxonomy.resolve("open").defaultSeverity)
        assertEquals(DefectSeverity.CRITICAL, DefectTaxonomy.resolve("SHORT").defaultSeverity)
        assertEquals(DefectSeverity.CRITICAL, DefectTaxonomy.resolve("missing copper").defaultSeverity)
        assertEquals(DefectSeverity.MEDIUM, DefectTaxonomy.resolve("Mouse-Bite").defaultSeverity)
        assertEquals(DefectSeverity.MEDIUM, DefectTaxonomy.resolve("spurious_copper").defaultSeverity)
        assertEquals(DefectSeverity.MEDIUM, DefectTaxonomy.resolve("pin hole").defaultSeverity)
    }

    @Test
    fun `undocumented classes and copper require manual review`() {
        val copperBurr = DefectTaxonomy.resolve("copper_burr")
        val holeBreakout = DefectTaxonomy.resolve("hole_breakout")
        val scratch = DefectTaxonomy.resolve("scratch")
        val copper = DefectTaxonomy.resolve("copper")

        assertEquals(DefectSeverity.MANUAL_REVIEW, copperBurr.defaultSeverity)
        assertTrue(copperBurr.requiresManualReview)

        assertEquals(DefectSeverity.MANUAL_REVIEW, holeBreakout.defaultSeverity)
        assertTrue(holeBreakout.requiresManualReview)

        assertEquals(DefectSeverity.MANUAL_REVIEW, scratch.defaultSeverity)
        assertTrue(scratch.requiresManualReview)

        assertEquals(DefectSeverity.REQUIRES_DATASET_VERIFICATION, copper.defaultSeverity)
        assertTrue(copper.requiresManualReview)
    }

    @Test
    fun `unknown classes are never discarded or remapped to known classes`() {
        val unknown = DefectTaxonomy.resolve("delamination_crack")
        assertFalse(unknown.isKnownClass)
        assertEquals("delamination_crack", unknown.canonicalLabel)
        assertEquals(DefectSeverity.MANUAL_REVIEW, unknown.defaultSeverity)
        assertTrue(unknown.requiresManualReview)
    }

    @Test
    fun `transistor_anomaly resolves to dedicated MANUAL_REVIEW entry`() {
        val entry = DefectTaxonomy.resolve("transistor_anomaly")
        assertTrue("Must be known class", entry.isKnownClass)
        assertEquals("transistor_anomaly", entry.normalizedKey)
        assertEquals("Transistor Anomaly", entry.canonicalLabel)
        assertEquals(DefectSeverity.MANUAL_REVIEW, entry.defaultSeverity)
        assertTrue(entry.requiresManualReview)
        assertEquals("TR-ANOMALY-REVIEW", entry.code)

        val byLabel = DefectTaxonomy.resolve("Transistor Anomaly")
        assertEquals(entry, byLabel)
    }
}
