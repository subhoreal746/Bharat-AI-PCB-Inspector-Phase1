# Bharat AI PCB Inspector — On-Device Benchmark Report

**Benchmark date:** 22 November 2026  
**Device:** Motorola Moto G54 5G  
**Application package:** `in.bharatai.pcbinspector`  
**Scope:** On-device model initialization estimates, inspection latency, LLM generation, memory behavior, and multi-model coexistence.

> **Benchmark status:** Exploratory on-device characterization. Results are measurements from a small number of repeated tests and snapshots, not a formal accuracy benchmark or controlled laboratory study. Model-loading times for YOLO and PatchCore are approximate estimates; they were not instrumented with explicit start/end timing.

---

## 1. Executive Summary

Bharat AI PCB Inspector was evaluated on a Motorola Moto G54 5G to characterize its two vision inspection engines—YOLOv8n and PatchCore—and its advisory language model, Llama 3.2 3B Instruct. Tests included repeated inspection of the same PCB image, LLM generation with different vision-model residency configurations, application memory snapshots, and full-stack coexistence with all three models loaded.

### Key observations

- **YOLOv8n:** Mean total inspection pipeline latency was **324.8 ms** across five runs. The measured YOLO inference component averaged **281 ms**.
- **PatchCore:** Mean total inspection pipeline latency was **3,664.2 ms (3.664 s)** across five runs. The UI does not expose a distinct PatchCore inference timing, so the total cannot be cleanly split into model execution and other pipeline work.
- **Relative pipeline duration:** PatchCore's mean total pipeline was approximately **11.3×** the YOLO pipeline in these tests. This is an end-to-end comparison, not a comparison of isolated neural-network kernels.
- **LLM generation:** In dual-loaded YOLO + Llama tests, median decode throughput was **2.57 tokens/s** across the five runs, with one substantial outlier. PatchCore + Llama produced a median decode throughput of **2.88 tokens/s** in its five-run series.
- **Full-stack residency:** YOLO, PatchCore, and Llama were loaded together. Generation was completed for two runs per inspection type. Memory snapshots showed substantial memory pressure and swap use.
- **Memory pressure:** The phone's available RAM became very low in multi-model tests. Large changes in RSS and swap accounting were observed after inference and during idle; these measurements do not prove a memory leak or that model allocations were freed.
- **Repeatability:** Each inspection engine returned the same visible result for the same test image in five repeated runs. This demonstrates same-input repeatability only—not accuracy or generalization.

## 2. Device and Runtime Environment

| Item | Configuration |
|---|---|
| Device | Motorola Moto G54 5G |
| SoC | MediaTek MT6855 / Dimensity 7020 |
| CPU | 8 cores: 6 Cortex-A55 + 2 Cortex-A78 |
| Android | Android 15 (SDK 35) |
| RAM | 8 GB (approximately 7.79 GiB reported) |
| ABI | `arm64-v8a` |
| Vision runtime | ONNX Runtime Android 1.22.0 |
| LLM runtime | ExecuTorch Android 1.6.0 |
| App package | `in.bharatai.pcbinspector` |

The tests were performed on the physical device through the Android app UI and ADB. Repeated inference tests reused the same PCB image for each model's test series.

## 3. Model Overview and Initialization Estimates

| Model | Task | Runtime / model identity | Loading time |
|---|---|---:|
| YOLOv8n | PCB defect detection | ONNX Runtime; `active_pcb_detector.onnx` | **~1–2 s (estimate)** |
| PatchCore | Component anomaly detection | ONNX Runtime; `active_patchcore.onnx` | **~3–5 s (estimate)** |
| Llama 3.2 3B Instruct | Advisory generation | ExecuTorch; `active_advisory.pte` | **17,382 ms (recorded timing)** |

YOLO and PatchCore loading durations are approximate estimates rather than timing-instrumented measurements. The Llama load duration has a recorded value of 17,382 ms; a separate log timestamp span was approximately 8.906 seconds, but the two timing boundaries were different. They should not be treated as equivalent measurements.

### Llama model artifacts and runtime metadata

- `active_advisory.pte`: 2,379,319,808 bytes (approximately 2.38 GB)
- `active_tokenizer.json`: 9,906,781 bytes (approximately 9.91 MB)
- SDPA / KV cache enabled
- Maximum context: 4096
- Maximum sequence: 2048
- Dynamic shape
- Method: `forward`
- EOS IDs: `128009` and `128001`

## 4. YOLOv8n — PCB Defect Detection

### 4.1 Model/runtime provenance

| Field | Value |
|---|---|
| Inference engine | `OnnxPcbDetectionEngine` |
| Model identity | `active_pcb_detector.onnx` |
| Model contract | `yolo640-fp32-c11` |
| Preprocessing | `yolo-letterbox-rgb-114-v2` |
| Decoder | `yolo-v8-640-fp32-v3` |
| Class mapping | `pcb-11-v1` |
| ONNX Runtime | 1.22.0 |
| Model SHA-256 | UI value was truncated; full hash not captured |

### 4.2 Repeated inspection latency

Five inspections were performed on the same PCB image.

| Run | Preprocessing (ms) | YOLO inference (ms) | Decode + NMS (ms) | Policy (ms) | Total pipeline (ms) |
|---:|---:|---:|---:|---:|---:|
| 1 | 28 | 263 | 8 | 1 | 308 |
| 2 | 34 | 270 | 5 | 1 | 316 |
| 3 | 29 | 286 | 5 | 1 | 327 |
| 4 | 28 | 289 | 6 | 2 | 333 |
| 5 | 29 | 297 | 6 | 1 | 340 |

### 4.3 YOLO latency statistics

| Metric | Mean | Median | Minimum | Maximum |
|---|---:|---:|---:|---:|
| Preprocessing | 29.6 ms | 29 ms | 28 ms | 34 ms |
| YOLO inference | 281.0 ms | 286 ms | 263 ms | 297 ms |
| Decode + NMS | 6.0 ms | 6 ms | 5 ms | 8 ms |
| Policy evaluation | 1.2 ms | 1 ms | 1 ms | 2 ms |
| **Total pipeline** | **324.8 ms** | **327 ms** | **308 ms** | **340 ms** |

The average pipeline time corresponds to a latency-derived theoretical rate of approximately **3.08 inspections/s**. This is not a sustained throughput measurement.

An earlier separate run recorded 129 ms preprocessing, 269 ms inference, 45 ms decode/NMS, 1 ms policy, and 456 ms total. It is excluded from the five-run statistics.

### 4.4 Detection output repeatability

All five repeated runs showed the same detection:

- Defect: **Copper Burr**
- Raw class: `copper_burr`
- Displayed confidence: **89.9%**
- Defect code: `PCB-CUBURR`
- Disposition: Review / manual verification
- Bounding box: `[0.45, 0.43, 0.15, 0.12]`

The output was repeatable for this image, but no ground-truth validation was performed.

### 4.5 YOLO memory observations

Two different memory snapshot series were captured at different points in the testing process. They are preserved separately because their baselines and post-load values differ.

**Series A — isolated YOLO load attempt**

| State | App PSS | App RSS (`dumpsys`) | Process VmRSS (`/proc`) | Native Heap PSS |
|---|---:|---:|---:|---:|
| Before YOLO | 205,882 KB | 302,765 KB | 226,044 KB | 11,856 KB |
| After YOLO | 279,956 KB | 378,638 KB | 280,012 KB | 65,160 KB |
| Change | +74,074 KB | +75,873 KB | +53,968 KB | +53,264 KB |

Other baseline details: Dalvik Heap PSS 7,088 KB, graphics PSS 80,657 KB, heap size 50,008 KB, allocated 22,920 KB, free 27,087 KB. After load: Dalvik PSS 16,927 KB, graphics PSS 80,766 KB, heap size 148,670 KB, allocated 113,025 KB, free 35,644 KB. Process VmSwap changed by −12 KB.

**Series B — earlier baseline/load snapshots from the consolidated model report**

| State | Total PSS | Total RSS | Swap PSS |
|---|---:|---:|---:|
| Baseline before model loading | 172,265 KB | 274,473 KB | 1,829 KB |
| YOLO loaded | 231,959 KB | 340,776 KB | 94 KB |

Loaded snapshot also reported Native Heap PSS 54,133 KB, Dalvik PSS 14,436 KB, and graphics PSS approximately 60,800 KB.

These are app-process snapshots, not exact YOLO model-weight footprints. Runtime initialization, buffers, allocator behavior, graphics, and other app allocations can contribute to the change. The two snapshot series are not directly interchangeable or necessarily comparable due to different test states.

### 4.6 YOLO memory sampling during repeated inference

| Time | Total PSS (KB) | Total RSS (KB) | Swap PSS (KB) |
|---|---:|---:|---:|
| 14:29:21 | 335,402 | 281,178 | 169,233 |
| 14:29:26 | 376,731 | 382,202 | 109,667 |
| 14:29:31 | 382,274 | 400,926 | 96,559 |
| 14:29:36 | 398,452 | 417,313 | 96,387 |
| 14:29:41 | 397,117 | 416,202 | 96,271 |
| 14:29:47 | 409,121 | 428,686 | 95,915 |

PSS rose during this short sample window while Swap PSS fell. Possible contributors include model/runtime initialization, memory allocation, caching, image buffers, and garbage collection. The samples are insufficient to diagnose a leak.

## 5. PatchCore — Component Anomaly Detection

### 5.1 Model/runtime provenance

| Field | Value |
|---|---|
| Inference engine | `PatchCoreTransistorEngine` |
| Model identity | `active_patchcore.onnx` |
| Model contract | `patchcore-256-fp32-v1` |
| Preprocessing | `patchcore-warp-rgb256-b2-v1` |
| Decoder | `patchcore-anomaly-v1` |
| Class mapping | `pcb-anomaly-v1` |
| ONNX Runtime | 1.22.0 |
| Model SHA-256 | UI value truncated; full hash not captured |

### 5.2 Repeated inspection latency

The same PCB image was inspected five times. In the consolidated model report, YOLO was unloaded before this PatchCore-only inspection phase. The UI does not expose a separate PatchCore inference-only timing field.

| Run | Preprocessing (ms) | YOLO inference | Decode + NMS (ms) | Policy (ms) | Total (ms) |
|---:|---:|---:|---:|---:|---:|
| 1 | 85 | — | 122 | 3 | 3,905 |
| 2 | 9 | — | 8 | 1 | 3,661 |
| 3 | 10 | — | 7 | 1 | 3,590 |
| 4 | 9 | — | 8 | 1 | 3,563 |
| 5 | 9 | — | 10 | 1 | 3,602 |

### 5.3 PatchCore latency statistics

| Metric | Mean | Median | Minimum | Maximum |
|---|---:|---:|---:|---:|
| Preprocessing | 24.4 ms | 9 ms | 9 ms | 85 ms |
| Decode + NMS | 31.0 ms | 8 ms | 7 ms | 122 ms |
| Policy evaluation | 1.4 ms | 1 ms | 1 ms | 3 ms |
| **Total pipeline** | **3,664.2 ms** | **3,602 ms** | **3,563 ms** | **3,905 ms** |

Run 1 is an outlier in visible preprocessing and decode/NMS fields. Runs 2–5 cluster more tightly around approximately 3.6 seconds. The remaining pipeline time must **not** be labelled PatchCore inference time because the UI does not expose that timing boundary.

A separate PatchCore inspection series was also captured while YOLO and PatchCore were both resident. Its total pipeline times were 3,663, 3,837, 3,550, 3,522, and 3,588 ms (mean 3,632 ms, median 3,588 ms). This dual-residency series is reported separately and is not mixed with the five-run series above.

### 5.4 PatchCore output repeatability

The five-run series produced the same visible result:

- Anomalies detected: **1**
- Raw class: `transistor_anomaly`
- Displayed confidence: **100.0%**
- Disposition: Manual review
- Result code: `TR-ANOMALY-REVIEW`
- Bounding box: `[0.30, 0.33, 0.63, 0.47]`
- Explanation: Requires manual verification

A displayed confidence of 100.0% is not proof of a calibrated 100% probability. The same result on one image is repeatability, not accuracy.

### 5.5 PatchCore loading time

PatchCore model loading time was not captured with explicit start/end instrumentation. The current estimate is **approximately 3–5 seconds**, based on the observed loading process, and should be treated as an estimate—not a measured benchmark value. Logcat did not provide a reliable timing boundary from which the exact duration could be recovered.

### 5.6 PatchCore memory observations

PatchCore memory data comes from several phases with different test histories. These snapshots are not a single controlled experiment and should not be combined into one definitive footprint.

**A. Dual-model residency — YOLO + PatchCore loaded**

| State | Total PSS | Total RSS | Swap PSS |
|---|---:|---:|---:|
| Before PatchCore load (YOLO already loaded) | 339,428 KB | 302,320 KB | 152,158 KB |
| Both models loaded | 1,026,313 KB | 1,018,363 KB | 120,158 KB |
| Pre-inference snapshot | 1,026,141 KB | 1,018,315 KB | 120,156 KB |

Loaded-state breakdown: Native Heap PSS 770,942 KB; Dalvik PSS 11,025 KB; graphics (EGL + GL) 68,923 KB; code PSS 24,244 KB; system PSS 138,084 KB; total PSS approximately 1,026,267 KB.

**B. During dual-loaded PatchCore inference**

| Time | Total PSS (KB) | Total RSS (KB) | Swap PSS (KB) |
|---|---:|---:|---:|
| 14:38:46 | 1,374,345 | 1,367,399 | 119,168 |
| 14:38:51 | 1,357,522 | 1,350,652 | 119,100 |
| 14:38:56 | 1,394,874 | 1,388,132 | 118,996 |
| 14:39:01 | 1,394,731 | 1,388,193 | 118,824 |
| 14:39:06 | 1,395,739 | 1,389,289 | 118,764 |
| 14:39:12 | 1,395,159 | 1,388,817 | 118,668 |
| 14:39:17 | 1,396,222 | 1,389,986 | 118,608 |
| 14:39:22 | 1,395,473 | 1,389,290 | 118,568 |
| 14:39:27 | 1,396,228 | 1,390,337 | 118,448 |

Observed ranges: PSS 1,357,522–1,396,228 KB; RSS 1,350,652–1,390,337 KB; Native Heap PSS approximately 1,078,424–1,079,924 KB; Swap PSS 118,448–119,168 KB.

**C. Immediately after inference and after idle**

| Metric | Immediately after inference | After ~60 s idle |
|---|---:|---:|
| Total PSS | 1,262,858 KB | 1,255,091 KB |
| Total RSS | 610,604 KB | 137,932 KB |
| Total Swap PSS | 764,541 KB | 1,229,052 KB |
| Native Heap PSS | 451,548 KB | 216 KB |
| Native Heap RSS | 456,060 KB | 2,260 KB |
| Native Heap Swap PSS | 689,860 KB | 1,139,293 KB |
| Dalvik Heap PSS | 0 KB | 0 KB |
| Dalvik Heap RSS | 1,816 KB | 1,816 KB |
| Dalvik Heap Swap PSS | 8,702 KB | 6,958 KB |

These samples show a large shift in resident versus swapped accounting. They do not prove that model memory was freed or that a leak exists.

**D. PatchCore active after YOLO unload (not clean cold isolation)**

| Snapshot | Total PSS | Total RSS | Swap PSS |
|---:|---:|---:|---:|
| 1 | 972,509 KB | 1,079,207 KB | 91 KB |
| 2 | 972,266 KB | 1,078,967 KB | 91 KB |
| 3 | 972,254 KB | 1,078,955 KB | 89 KB |

During later inference monitoring, memory rose from approximately 972 MB PSS / 1,078 MB RSS to approximately 1,334 MB PSS / 1,444 MB RSS. Native Heap PSS in later samples was approximately 1.086 GB. The app had previously loaded both models and experienced memory pressure; unloading YOLO does not guarantee allocator-held memory or previous memory state is reset. These are process observations, not a definitive standalone PatchCore memory requirement.

## 6. Llama 3.2 3B Instruct — Generation Benchmark

The LLM benchmark measured generation while the vision model(s) were loaded in the app. These are **co-resident-model tests**, not standalone Llama benchmarks. Prompt tokens were counted through the tokenizer when available; generation token counts were counted from callback steps. If tokenizer counts were unavailable, no estimated count was substituted.

Metric definitions used in the app:
- **Generation duration:** elapsed time for the `generate()` call.
- **Overall TPS:** generated tokens divided by generation duration.
- **TTFT:** time from generation start to first callback; this includes time until first callback and is not pure prefill compute time.
- **Decode TPS:** generated tokens divided by generation duration minus TTFT; approximate and includes callback/JNI/string overhead.

### 6.1 YOLO + Llama — five runs

All five runs used an 892-token prompt; YOLO and Llama were loaded simultaneously.

| Run | Prompt tokens | Generated tokens | Generation (ms) | Generation (s) | Overall TPS | TTFT (ms) | Decode TPS |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 892 | 194 | 158,026 | 158.026 | 1.23 | 81,974 | 2.57 |
| 2 | 892 | 156 | 141,923 | 141.923 | 1.10 | 82,110 | 2.60 |
| 3 | 892 | 140 | 271,460 | 271.460 | 0.52 | 82,459 | 0.74 |
| 4 | 892 | 186 | 154,000 | 154.000 | 1.21 | — | 2.57 |
| 5 | 892 | 208 | 162,672 | 162.672 | 1.28 | — | 2.57 |

*Individual TTFT values for runs 4 and 5 were not preserved in the consolidated source data; the aggregate statistics below are retained.*

Aggregate statistics (as recorded):

| Metric | Mean | Median | Min | Max |
|---|---:|---:|---:|---:|
| Generated tokens | 176.8 | 186 | 140 | 208 |
| Generation duration | 177.616 s | 158.026 s | 141.923 s | 271.460 s |
| Overall throughput | 1.068 tokens/s | 1.21 | 0.52 | 1.28 |
| TTFT | 81.8306 s | 81.765 s | 81.409 s | 82.459 s |
| Decode throughput | 2.210 tokens/s | 2.57 | 0.74 | 2.60 |

Run 3 is retained in the primary results. It generated 140 tokens in 271.460 seconds and reported 0.74 decode TPS, while the other four runs were 2.57–2.60 decode TPS. TTFT stayed in a narrow range, so the observed slowdown was predominantly after first-token time; its cause was not isolated.

Secondary descriptive averages excluding Run 3 (not replacing the full dataset): mean generation 154.155 s, mean overall throughput 1.205 tokens/s, mean decode throughput 2.5775 tokens/s, and mean TTFT 81.936 s.

### 6.2 PatchCore + Llama — five runs

All five runs used a 745-token prompt; PatchCore and Llama were loaded simultaneously.

| Run | Prompt tokens | Generated tokens | Generation (ms) | Generation (s) | Overall TPS | TTFT (ms) | Decode TPS |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 745 | 126 | 112,314 | 112.314 | 1.12 | 68,383 | 2.87 |
| 2 | 745 | 138 | 114,378 | 114.378 | 1.21 | 66,517 | 2.88 |
| 3 | 745 | 123 | 108,962 | 108.962 | 1.13 | 66,539 | 2.90 |
| 4 | 745 | 124 | 109,180 | 109.180 | 1.14 | 66,500 | 2.91 |
| 5 | 745 | 136 | 113,610 | 113.610 | 1.20 | 66,407 | 2.88 |

| Metric | Mean | Median | Minimum | Maximum |
|---|---:|---:|---:|---:|
| Generated tokens | 129.4 | 126 | 123 | 138 |
| Generation duration | 111.689 s | 112.314 s | 108.962 s | 114.378 s |
| Overall throughput | 1.160 tokens/s | 1.14 | 1.12 | 1.21 |
| TTFT | 66.869 s | 66.517 s | 66.407 s | 68.383 s |
| Decode throughput | 2.888 tokens/s | 2.88 | 2.87 | 2.91 |

This series was more consistent than the YOLO + Llama series, with decode throughput between 2.87 and 2.91 tokens/s. The prompt was shorter than the YOLO + Llama prompt, so TTFT and generation duration are not directly controlled comparisons.

### 6.3 Full stack — YOLO + PatchCore + Llama

All three models were loaded together. Two generation runs were captured for each inspection type; sample size is small.

| Workload | Run | Prompt tokens | Generated | Generation (ms) | Duration (s) | Overall TPS | TTFT (ms) | Decode TPS |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| YOLO | 1 | 892 | 161 | 143,096 | 143.096 | 1.13 | 81,974 | 2.63 |
| YOLO | 2 | 892 | 172 | 145,521 | 145.521 | 1.18 | 81,329 | 2.68 |
| PatchCore | 1 | 745 | 122 | 107,660 | 107.660 | 1.13 | 67,052 | 3.00 |
| PatchCore | 2 | 745 | 123 | 107,629 | 107.629 | 1.14 | 66,278 | 2.97 |

| Metric | YOLO workload | PatchCore workload |
|---|---:|---:|
| Prompt tokens | 892 | 745 |
| Generated tokens (mean) | 166.5 | 122.5 |
| Generation duration (mean) | 144.309 s | 107.645 s |
| Overall TPS (mean) | 1.155 | 1.139 |
| TTFT (mean) | 81.652 s | 66.665 s |
| Decode TPS (mean) | 2.655 | 2.985 |

The PatchCore-workload decode throughput was about 12.4% higher in this two-run sample, but prompt length differed and the sample count is too small to infer that PatchCore residency improves LLM performance. No generation quality metric was evaluated.

## 7. Memory Behavior — LLM and Full-Stack Tests

Memory values below are app/process/system snapshots and can vary with runtime allocation, allocator behavior, graphics, Android reclaim, and system activity. PSS, RSS, Native Heap, `VmSwap`, and system swap are different measurements; they must not be treated as interchangeable.

### 7.1 YOLO + Llama, after generation

**`dumpsys meminfo`**

| Metric | Value |
|---|---:|
| Total PSS | 3,662,378 KB (~3.49 GiB) |
| Total RSS | 2,744,163 KB (~2.62 GiB) |
| SwapPss | 1,005,614 KB (~0.96 GiB) |
| Native Heap PSS | 2,412,465 KB |
| Native Heap RSS | 2,414,228 KB |
| Native Heap SwapPss | 913,544 KB |
| Dalvik Heap PSS | 100,673 KB |
| Dalvik Heap RSS | 101,632 KB |
| Graphics PSS | 99,491 KB |
| Heap Size | 3,783,462 KB |
| Heap Allocated | 3,683,330 KB |
| Heap Free | 100,131 KB |

**`/proc/<pid>/status`**

| Metric | Value |
|---|---:|
| VmPeak | 11,013,760 KB |
| VmSize | 10,688,300 KB |
| VmRSS | 2,589,792 KB |
| VmSwap | 976,124 KB |

**System snapshot:** RAM total 7.4 GiB, used 7.0 GiB, free 388 MiB; swap total 5.5 GiB, used 2.7 GiB, free 2.8 GiB.

### 7.2 PatchCore + Llama, after generation

**`dumpsys meminfo`**

| Metric | Value |
|---|---:|
| Total PSS | 4,600,834 KB (~4.39 GiB) |
| Total RSS | 3,160,748 KB (~3.01 GiB) |
| SwapPss | 1,523,228 KB (~1.45 GiB) |
| Native Heap PSS | 2,857,096 KB (~2.72 GiB) |
| Native Heap RSS | 2,858,772 KB (~2.72 GiB) |
| Native Heap SwapPss | 1,465,891 KB (~1.40 GiB) |
| Dalvik Heap PSS | 76,421 KB |
| Graphics PSS | 84,524 KB |
| Heap Size | 5,205,986 KB |
| Heap Allocated | 5,104,943 KB |
| Heap Free | 101,042 KB |

**`/proc/<pid>/status`**

| Metric | Value |
|---|---:|
| VmPeak | 12,751,792 KB |
| VmSize | 12,717,836 KB |
| VmRSS | 3,047,428 KB (~2.91 GiB) |
| VmSwap | 1,535,352 KB (~1.46 GiB) |

**System snapshot:** RAM total 7.4 GiB, used 7.2 GiB, free 163 MiB; swap total 5.5 GiB, used 2.5 GiB, free 3.0 GiB.

Compared with the YOLO + Llama snapshot, PatchCore + Llama showed higher PSS (+25.6%), RSS (+15.2%), SwapPss (+51.5%), and Native Heap PSS (+18.4%). System free RAM was 388 MiB versus 163 MiB. These snapshots were not captured under fully controlled identical conditions; the differences are observations and cannot be attributed entirely to PatchCore.

### 7.3 Full stack before generation

**Baseline before loading models**

| Metric | Value |
|---|---:|
| PSS | 180,708 KB (~176.5 MiB) |
| RSS | 277,702 KB (~271.2 MiB) |
| SwapPss | 1,211 KB (~1.18 MiB) |
| Native Heap PSS / RSS | 8,552 / 10,344 KB |
| Dalvik PSS / RSS | 7,664 / 8,684 KB |
| VmPeak | 6,878,056 KB |
| VmSize | 6,409,608 KB |
| VmRSS | 220,456 KB |
| VmSwap | 12,884 KB |
| System RAM used/free | 4.4 GiB / 2.9 GiB |
| System swap used/free | 1.2 GiB / 4.3 GiB |

**After all three models loaded, before generation**

| Metric | Value |
|---|---:|
| PSS | 4,140,544 KB (~3.95 GiB) |
| RSS | 3,149,007 KB (~3.00 GiB) |
| SwapPss | 1,069,293 KB (~1.02 GiB) |
| Native Heap PSS | 2,904,972 KB |
| Native Heap RSS | 2,907,164 KB |
| Native Heap SwapPss | 1,009,114 KB |
| Dalvik Heap PSS | 54,293 KB |
| Graphics PSS | 61,563 KB |
| Heap Size | 5,145,070 KB |
| Heap Allocated | 5,044,695 KB |
| Heap Free | 100,374 KB |
| VmPeak | 12,693,616 KB (~12.11 GiB) |
| VmSize | 12,676,080 KB (~12.09 GiB) |
| VmRSS | 3,020,368 KB (~2.88 GiB) |
| VmSwap | 1,140,968 KB (~1.09 GiB) |
| System RAM used/free | 7.2 GiB / 145 MiB |
| System swap used/free | 1.7 GiB / 3.8 GiB |

### 7.4 Full stack after generation

| Metric | Value |
|---|---:|
| PSS | 4,577,384 KB (~4.36 GiB) |
| RSS | 3,267,047 KB (~3.11 GiB) |
| SwapPss | 1,393,121 KB (~1.33 GiB) |
| Native Heap PSS | 2,592,524 KB (~2.47 GiB) |
| Native Heap RSS | 2,914,092 KB (~2.78 GiB) |
| Native Heap SwapPss | 1,337,141 KB (~1.27 GiB) |
| Dalvik Heap PSS | 106,965 KB |
| Graphics PSS | 102,491 KB |
| Heap Size | 5,264,078 KB |
| Heap Allocated | 5,163,789 KB |
| Heap Free | 100,288 KB |
| VmPeak | 12,742,280 KB |
| VmSize | 12,683,028 KB |
| VmRSS | 3,084,580 KB (~2.94 GiB) |
| VmSwap | 1,412,000 KB |
| System RAM used/free | 7.2 GiB / 159 MiB |
| System swap used/free | 2.6 GiB / 2.9 GiB |

### 7.5 Full-stack memory change during generation

| Metric | Before generation | After generation | Change |
|---|---:|---:|
| `dumpsys` PSS | 4,140,544 KB | 4,577,384 KB | +436,840 KB (~426.6 MiB) |
| `dumpsys` RSS | 3,149,007 KB | 3,267,047 KB | +118,040 KB (~115.3 MiB) |
| `/proc` VmRSS | 3,020,368 KB | 3,084,580 KB | +64,212 KB (~62.7 MiB) |
| SwapPss | 1,069,293 KB | 1,393,121 KB | +323,828 KB (~316.2 MiB) |
| VmSwap | 1,140,968 KB | 1,412,000 KB | +271,032 KB (~264.7 MiB) |
| Native Heap PSS | 2,904,972 KB | 2,592,524 KB | −312,448 KB (~−305.1 MiB) |
| Native Heap RSS | 2,907,164 KB | 2,914,092 KB | +6,928 KB (~6.77 MiB) |

The native heap PSS decrease alongside increased native RSS and swap is an accounting/residency change; it is not evidence by itself that native memory was freed or newly allocated. System free RAM changed from 145 MiB to 159 MiB while system swap use rose from approximately 1.7 GiB to 2.6 GiB.

## 8. Comparative Performance Summary

### 8.1 Vision inspection latency

| Engine | Mean total pipeline | Median | Min–max | Notes |
|---|---:|---:|---:|---|
| YOLOv8n | 324.8 ms | 327 ms | 308–340 ms | Inference field separately exposed; mean 281 ms |
| PatchCore | 3,664.2 ms | 3,602 ms | 3,563–3,905 ms | Total pipeline only; no PatchCore-only inference field |

PatchCore's mean pipeline duration was approximately 11.3× YOLO's in this dataset.

### 8.2 LLM generation comparison

| Configuration | Prompt tokens | Mean generation duration | Mean overall TPS | Mean TTFT | Mean decode TPS | Runs |
|---|---:|---:|---:|---:|---:|---:|
| YOLO + Llama | 892 | 177.616 s | 1.068 | 81.831 s | 2.210 | 5 |
| PatchCore + Llama | 745 | 111.689 s | 1.160 | 66.869 s | 2.888 | 5 |
| Full stack, YOLO prompt | 892 | 144.309 s | 1.155 | 81.652 s | 2.655 | 2 |
| Full stack, PatchCore prompt | 745 | 107.645 s | 1.139 | 66.665 s | 2.985 | 2 |

These configurations are not prompt-matched, have different co-resident models, and have different sample counts. The values are descriptive; they do not isolate a causal effect of model residency on LLM performance.

## 9. Observed Bottlenecks and Engineering Implications

### 9.1 Inspection latency

YOLO is the faster inspection path in the current app measurements, averaging approximately 325 ms end-to-end. PatchCore averages approximately 3.66 seconds. PatchCore's latency breakdown needs additional instrumentation before the slow portion can be attributed precisely to a specific model operation.

### 9.2 Memory residency

The LLM and vision runtimes occupy substantial memory. Full-stack snapshots show very low free system RAM and significant swap use. Keeping every model resident simultaneously may be expensive on an 8 GB mobile device.

Possible engineering directions to investigate (not optimizations proven by these tests):

- Lazy-load models only when the selected inspection mode needs them.
- Unload or release an engine when switching modes, while verifying the runtime actually releases memory.
- Avoid loading the advisory LLM until a user requests an explanation.
- Profile model initialization, tensor allocation, preprocessing buffers, and peak inference memory separately.
- Capture thermal state, CPU frequency, and memory pressure alongside latency.

### 9.3 LLM responsiveness

Llama generation is slow in the tested mobile runtime. TTFT is long—approximately 67 seconds for the PatchCore prompt series and approximately 82 seconds for the YOLO prompt series. Decode throughput is around 2.9 tokens/s in the stable PatchCore series and around 2.6 tokens/s in the stable YOLO series. One YOLO + Llama run was a significant outlier.

## 10. Limitations and Validity

1. **Small sample sizes:** Five repeats for the vision and dual-loaded LLM series; only two full-stack generation runs per workload.
2. **Single repeated image per inspection series:** The benchmark does not test variability across different PCB images.
3. **No ground truth:** No precision, recall, F1, false-positive/false-negative rates, or localization accuracy were measured.
4. **Repeatability is not accuracy:** Consistent output on the same image does not prove the result is correct.
5. **PatchCore timing breakdown:** UI timing does not expose a dedicated PatchCore inference duration.
6. **Estimated loading times:** YOLO (~1–2 s) and PatchCore (~3–5 s) are estimates, not instrumented timing results.
7. **Llama prompt lengths differ:** 892 tokens for YOLO workload and 745 tokens for PatchCore workload, so TTFT/generation comparisons are not controlled.
8. **Memory snapshots:** Measurements were captured at different points and under varying device conditions. PSS, RSS, native heap, virtual memory, and swap are not interchangeable.
9. **PatchCore-only memory isolation:** The PatchCore active-after-YOLO-unload test followed prior dual-model loading and memory pressure; it is not a clean cold-start isolation test.
10. **No thermal or sustained-load study:** Thermal throttling, battery drain, charging state, long-duration performance, and sustained throughput were not systematically measured.
11. **No memory-leak diagnosis:** Memory changes alone do not establish a leak or prove model allocations were released.
12. **Model provenance incomplete:** The full SHA-256 values for the vision model artifacts were not captured.
13. **LLM quality not evaluated:** The generation benchmark measures performance, not advisory correctness, factuality, or usefulness.

## 11. Conclusions

The current on-device tests demonstrate that both inspection engines and the advisory LLM can operate on the Moto G54 5G within the Android application. YOLOv8n offers the faster inspection path, with a mean end-to-end pipeline of 324.8 ms. PatchCore's mean pipeline is approximately 3.66 seconds. Both engines returned consistent visible outputs across five repeated inspections of their respective test images, but no accuracy conclusions can be drawn from this limited repeatability test.

The Llama 3.2 3B Instruct model is operational in the app, but generation latency is high. In the co-resident tests, stable decode rates were approximately 2.6–2.9 tokens/s, with long TTFT values and one notable YOLO + Llama outlier. Full-stack operation was demonstrated in two runs per workload, but memory snapshots indicate significant pressure, including very low available RAM and considerable swap use.

The primary engineering concern exposed by these tests is **memory residency and responsiveness when multiple large models are loaded concurrently**. Lazy loading, lifecycle management, model unloading, and more precise instrumentation should be explored before claiming that the current multi-model setup is optimized for mobile deployment.

These results are a preliminary performance characterization—not a model accuracy evaluation, a sustained-throughput benchmark, or a controlled memory-footprint study.

## 12. Suggested Next Steps (Future Work)

- [ ] Instrument exact model-load durations for YOLO and PatchCore with monotonic timestamps.
- [ ] Separate PatchCore inference timing from preprocessing, postprocessing, and policy evaluation.
- [ ] Run clean cold-start, single-model memory tests for each vision engine.
- [ ] Repeat latency measurements with more runs and multiple PCB images.
- [ ] Build a labeled evaluation dataset and measure accuracy, precision, recall, false positives/negatives, and localization metrics.
- [ ] Benchmark LLM prompts with identical token counts and controlled model-residency conditions.
- [ ] Repeat full-stack testing with larger sample sizes and controlled thermal/device state.
- [ ] Measure sustained operation, thermal behavior, battery drain, and memory stability.
- [ ] Capture complete model hashes and record exact model/runtime build versions.
- [ ] Evaluate lazy loading, model unload/reload behavior, and memory lifecycle strategies.

---

*End of benchmark report.*
