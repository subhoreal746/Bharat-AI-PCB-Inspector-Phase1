package com.example.inference.demo

import com.example.R
import com.example.domain.model.SamplePcbPreset

object SamplePcbCatalog {
    const val SCENARIO_MULTI_DEFECT = "multi_defect"
    const val SCENARIO_CLEAN_BOARD = "clean_board"
    const val SCENARIO_MOUSE_BITE = "mouse_bite"

    val presets: List<SamplePcbPreset> = listOf(
        SamplePcbPreset(
            id = "sample_qfp100",
            boardName = "QFP-100 Main Logic PCB",
            subtitle = "Landscape • Simulated multi-defect inspection",
            width = 1200,
            height = 900,
            orientationLabel = "1200 × 900 • Landscape",
            scenarioHint = SCENARIO_MULTI_DEFECT,
            drawableName = "sample_pcb_qfp100"
        ),
        SamplePcbPreset(
            id = "sample_i2c_gold",
            boardName = "I2C Interface Gold-Pad PCB",
            subtitle = "Landscape • Clean solder-joint reference",
            width = 1200,
            height = 900,
            orientationLabel = "1200 × 900 • Landscape",
            scenarioHint = SCENARIO_CLEAN_BOARD,
            drawableName = "sample_pcb_i2c"
        ),
        SamplePcbPreset(
            id = "sample_industrial_ctrl",
            boardName = "Industrial Controller Module",
            subtitle = "Portrait • Edge mouse-bite and copper burr check",
            width = 900,
            height = 1200,
            orientationLabel = "900 × 1200 • Portrait",
            scenarioHint = SCENARIO_MOUSE_BITE,
            drawableName = "sample_pcb_controller"
        )
    )

    fun drawableResId(drawableName: String): Int = when (drawableName) {
        "sample_pcb_qfp100" -> R.drawable.sample_pcb_qfp100
        "sample_pcb_i2c" -> R.drawable.sample_pcb_i2c
        "sample_pcb_controller" -> R.drawable.sample_pcb_controller
        else -> 0
    }
}
