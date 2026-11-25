# Edge Visual Quality Control & On-Device Advisory Reasoning Pipeline

**ARM x MeitY Bharat AI-SoC Student Challenge — Session 2, Problem Statement 5**
**Authors:** Subhojit Baidya, Samridh Sarkar (Dept. of ECE, North-Eastern Hill University, Shillong)

## Overview
This directory contains the offline edge inspection models, training/export scripts, deterministic SOP screening engine (`Policy v1.1.0-screening`), quantized ExecuTorch small language model, FastAPI operator runtime, cryptographic provenance records (`SHA256_AND_ENV_REPORT.txt`, `GOLDEN_VALIDATION.json`), reproducible environment lockfiles, and the technical evaluation report (`Deterministic-First_Edge_Visual_Quality_Control_and_On-Device_Advisory_Reasoning.pdf`).

## Architecture & Validated Benchmarks
* **Supervised 11-Class PCB Defect Detector (`YOLOv8n`):** Trained across `open`, `short`, `mouse_bite`, `spur`, `spurious_copper`, `missing_copper`, `copper_burr`, `pin_hole`, `hole_breakout`, `scratch`, and baseline `copper` (`pcb-11-v1`), achieving **0.773 mAP@0.5** and **0.74 peak macro F1** at confidence threshold **0.327**, followed by geometric and HSV color-space refinement.
* **Unsupervised Component Anomaly Localization (`PatchCore`):** Memory-bank anomaly detector (`patchcore_transistor_single.onnx`) benchmarked on the MVTec AD transistor subset (`bent_lead`, `cut_lead`, `damaged_case`, `misplaced`).
* **Deterministic Screening Policy Engine (`Policy v1.1.0-screening`):** Modeled on an IPC-A-610 Class 2 baseline with an active optical clarity guard (`minDims` `224x224` to `512x512`). Computes authoritative `PASS`, `REVIEW`, or `REJECT` dispositions in **2 ms** (completing the synchronous perception-and-policy core in **321 ms** on MediaTek Dimensity 7200 Pro edge hardware).
* **Isolated On-Device Advisory SLM (`SmolLM2-360M-Instruct`):** Executed asynchronously via ExecuTorch (`smollm2-360m-pcb-json-v1`) to generate schema-validated JSON operator guidance without altering bounding boxes, defect counts, or official dispositions.

## Pipeline Latency & Model Contracts
| Stage / Artifact | Contract / Runtime | Measured Latency |
| :--- | :--- | :--- |
| Image Preprocessing | `yolo-letterbox-rgb-114-v2` (`minDims 224x224` clarity gate) | 78 ms |
| YOLOv8n PCB Detector | `active_pcb_detector.onnx` (`[1,3,640,640]` -> `[1,15,8400]`) | 199 ms |
| Decode & NMS | `yolo-v8-640-fp32-v3` | 42 ms |
| SOP Policy Evaluation | `Policy v1.1.0-screening` (IPC-A-610 Class 2 baseline) | 2 ms |
| **Deterministic Core Total** | **Synchronous Authoritative Gating** | **321 ms** |
| SmolLM2-360M Advisory | `active_smollm2_advisory.pte` (ExecuTorch, asynchronous) | 32,351 ms |

## Environment Setup
Three isolated Python environments are locked in this directory:
* `requirements_vision.txt` — YOLOv8n training, ONNX Runtime & FastAPI operator HMI
* `requirements_anomaly.txt` — PatchCore feature extraction & ONNX export
* `requirements_llm.txt` — SmolLM2-360M ExecuTorch / XNNPACK export & quantization

    python3 -m venv venv
    source venv/bin/activate
    pip install -r requirements_vision.txt
    uvicorn pi3_edge_app.app:app --host 0.0.0.0 --port 8000
