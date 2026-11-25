# Deterministic-First Edge Visual Quality Control and On-Device Advisory Reasoning

**ARM x MeitY Bharat AI-SoC Student Challenge — Session 2, Problem Statement 5**
**Team:** Subhojit Baidya & Samridh Sarkar (North-Eastern Hill University, Shillong)

## Repository Layout
* **[`edge_ai_pipeline/`](./edge_ai_pipeline/)**: Trained ONNX vision models (`YOLOv8n` & `PatchCore`), quantized `SmolLM2-360M` ExecuTorch artifact, deterministic policy engine, FastAPI runtime, SHA-256 provenance records, environment lockfiles, and the full technical evaluation report (`Deterministic-First_Edge_Visual_Quality_Control_and_On-Device_Advisory_Reasoning.pdf`).
* **[`mobile_app/`](./mobile_app/)**: Offline-first Android application (*Bharat AI PCB Inspector*) featuring ONNX Runtime Android 1.22.0, ExecuTorch Android 1.6.0, SHA-256 model provenance, Room SQLite audit ledger, and CSV export.
