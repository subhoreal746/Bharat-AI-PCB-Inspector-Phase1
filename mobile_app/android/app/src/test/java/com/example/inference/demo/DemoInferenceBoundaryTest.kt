package com.example.inference.demo

import com.example.domain.model.AdvisoryAvailability
import com.example.domain.model.DetectionErrorType
import com.example.domain.model.DetectionOutcome
import com.example.domain.model.ImageValidationResult
import com.example.domain.model.InspectionDisposition
import com.example.domain.model.PcbImageInput
import com.example.domain.model.PcbImageSource
import com.example.inference.llm.AdvisoryRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoInferenceBoundaryTest {

    private val demoDetector = DemoPcbDetectionEngine()
    private val demoAdvisory = DemoAdvisoryEngine()

    private fun validValidation() = ImageValidationResult(
        isValid = true,
        isProcessable = true,
        width = 1200,
        height = 900,
        clarityScore = 150.0,
        lowClarityRisk = false
    )

    @Test
    fun `demo detector refuses CAMERA and GALLERY inputs to prevent silent fallback`() = runTest {
        val cameraInput = PcbImageInput(
            sourceUri = "file:///tmp/cam.jpg",
            sourceType = PcbImageSource.CAMERA,
            boardName = "Camera PCB",
            validation = validValidation()
        )
        val galleryInput = cameraInput.copy(sourceType = PcbImageSource.GALLERY)

        val camOutcome = demoDetector.detect(cameraInput)
        val galOutcome = demoDetector.detect(galleryInput)

        assertTrue(camOutcome is DetectionOutcome.Failure)
        assertEquals(
            DetectionErrorType.UNSUPPORTED_REAL_IMAGE_FALLBACK,
            (camOutcome as DetectionOutcome.Failure).errorType
        )
        assertTrue(galOutcome is DetectionOutcome.Failure)
        assertEquals(
            DetectionErrorType.UNSUPPORTED_REAL_IMAGE_FALLBACK,
            (galOutcome as DetectionOutcome.Failure).errorType
        )
    }

    @Test
    fun `demo detector returns expected sample detections with isDemonstration true`() = runTest {
        val qfpInput = PcbImageInput(
            sourceUri = "file:///tmp/qfp.png",
            sourceType = PcbImageSource.SAMPLE,
            boardName = "QFP-100 Main Logic PCB",
            validation = validValidation(),
            sampleScenarioHint = SamplePcbCatalog.SCENARIO_MULTI_DEFECT
        )
        val outcome = demoDetector.detect(qfpInput) as DetectionOutcome.Success
        assertTrue(outcome.isDemonstration)
        assertEquals(3, outcome.detections.size)
        assertEquals("DEMO-SAMPLE-ONLY", outcome.provenance.modelSha256)
    }

    @Test
    fun `demo advisory refuses non demonstration requests`() = runTest {
        val realRequest = AdvisoryRequest(
            inspectionId = "PCB-1",
            boardName = "Live Board",
            officialDisposition = InspectionDisposition.REVIEW,
            policyRationale = "Test",
            defects = emptyList(),
            imageWidth = 1200,
            imageHeight = 900,
            lowClarityRisk = false,
            isDemonstration = false
        )
        val result = demoAdvisory.generateAdvisory(realRequest)
        assertEquals(AdvisoryAvailability.UNAVAILABLE, result.availability)
    }
}
