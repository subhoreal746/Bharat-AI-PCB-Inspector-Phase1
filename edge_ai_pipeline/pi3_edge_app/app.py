#!/usr/bin/env python3
"""
Offline Edge Quality Control Server for Raspberry Pi 3 Model B+ & Arm Devices
Clean single-shot inspection for uploaded/dataset images + live camera loop.
"""

import csv
import glob
import io
import os
import sqlite3
import threading
import time
from collections import deque
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Dict, List, Optional

import cv2
import numpy as np
from fastapi import FastAPI, File, Form, UploadFile
from fastapi.responses import HTMLResponse, JSONResponse, StreamingResponse

from advisory_engine import DetectionEvent, ExecuTorchAdvisoryEngine
from vision_runtime import EdgeVisionEngine

BASE_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = BASE_DIR.parent
DB_PATH = str(BASE_DIR / "inspection_logs.db")

CPP_RUNNER = os.environ.get("EXECUTORCH_LLAMA_BIN", str(BASE_DIR / "bin" / "llama_main"))
LLM_BACKEND = "cpp_runner" if Path(CPP_RUNNER).exists() else "optimum"

vision = EdgeVisionEngine(
    yolo_onnx_path=str(BASE_DIR / "models" / "unified_pcb_640.onnx"),
    patchcore_onnx_path=str(BASE_DIR / "models" / "patchcore_transistor.onnx"),
    conf_thresh=0.33,
    pc_raw_thresh=39.5,
)
advisory_engine = ExecuTorchAdvisoryEngine(
    model_dir=str(BASE_DIR / "models" / "smollm2_360m"),
    backend=LLM_BACKEND,
    cpp_runner_bin=CPP_RUNNER,
    db_path=DB_PATH,
    max_seq_len=384,
)

DEMO_PCBS = sorted(glob.glob(str(PROJECT_ROOT / "demo_pack/pcb_*.*")))
DEMO_TRNS = sorted(glob.glob(str(PROJECT_ROOT / "demo_pack/trn_*.*")))

state_lock = threading.Lock()
latest_jpeg: bytes = b""
latest_telemetry: Dict = {
    "mode": "supervised",
    "source": "camera",
    "sample_name": "Live Camera",
    "fps": 0.0,
    "llm_backend": LLM_BACKEND,
    "active_detections": [],
    "latest_advisory": None,
    "component_counter": 1,
    "last_status_msg": "Ready. Upload an image or click Next Demo PCB / Transistor.",
}
pcb_idx = 0
trn_idx = 0
recent_alerts: deque = deque(maxlen=25)
persistence_counter: Dict[str, int] = {}
cooldown_tracker: Dict[str, float] = {}


def process_and_render_frame(
    frame: np.ndarray, mode: str, source_label: str, force_trigger: bool = False
) -> Dict:
    global latest_jpeg
    t0 = time.perf_counter()
    # Upscale small dataset thumbnails (< 480px) cleanly so overlay text is crisp
    h0, w0 = frame.shape[:2]
    scale_up = max(1.0, 520.0 / max(h0, w0))
    annotated = (
        cv2.resize(frame, (int(w0 * scale_up), int(h0 * scale_up)), interpolation=cv2.INTER_CUBIC)
        if scale_up > 1.0
        else frame.copy()
    )
    h_img, w_img = annotated.shape[:2]
    font_scale = max(0.5, min(0.85, w_img / 850.0))
    thickness = 2 if w_img >= 450 else 1

    active_list: List[Dict] = []
    triggered_event = None
    status_msg = ""

    with state_lock:
        comp_idx = latest_telemetry["component_counter"]

    if mode == "supervised":
        dets = vision.detect_supervised(frame)
        seen_classes = set()
        for d in dets:
            seen_classes.add(d.class_name)
            x1, y1, x2, y2 = [int(c * scale_up) for c in d.bbox]
            cv2.rectangle(annotated, (x1, y1), (x2, y2), (0, 65, 255), thickness)
            label = f"{d.class_name} {d.confidence*100:.1f}%"
            ty = y1 - 8 if y1 > 28 else min(h_img - 28, y2 + 20)
            cv2.putText(
                annotated, label, (x1, ty),
                cv2.FONT_HERSHEY_SIMPLEX, font_scale, (0, 65, 255), thickness
            )
            active_list.append({
                "class": d.class_name,
                "score": round(d.confidence, 3),
                "area_pct": round(d.area_pct, 2),
            })

        if not dets:
            status_msg = f"PASS: Zero surface defects detected on {source_label}."
            cv2.putText(
                annotated, "PASS - NO DEFECTS DETECTED", (16, 34),
                cv2.FONT_HERSHEY_SIMPLEX, font_scale * 1.1, (60, 220, 100), thickness
            )
        else:
            status_msg = f"DETECTED {len(dets)} defect(s): {', '.join(f'{d.class_name} ({d.confidence*100:.1f}%)' for d in dets)}"

        for cname in list(persistence_counter.keys()):
            if cname not in seen_classes:
                persistence_counter[cname] = 0

        for d in dets:
            persistence_counter[d.class_name] = persistence_counter.get(d.class_name, 0) + 1
            now = time.time()
            if force_trigger or (
                persistence_counter[d.class_name] >= 3
                and (now - cooldown_tracker.get(d.class_name, 0)) > 6.0
            ):
                cooldown_tracker[d.class_name] = now
                persistence_counter[d.class_name] = 0
                triggered_event = DetectionEvent(
                    component_id=f"PCB-{comp_idx:04d}",
                    pipeline="supervised_yolo",
                    defect_class=d.class_name,
                    score=d.confidence,
                    area_pct=d.area_pct,
                    bbox=d.bbox,
                )
                break
    else:
        is_anom, norm_score, raw_score, bbox, area_pct = vision.detect_unsupervised(frame)
        status_color = (0, 80, 255) if is_anom else (60, 220, 100)
        status_txt = (
            f"ANOMALY ({norm_score*100:.1f}% | score={raw_score:.1f})"
            if is_anom
            else f"PASS / CONFORMING (score={raw_score:.1f} <= 39.5)"
        )
        status_msg = status_txt
        cv2.putText(annotated, status_txt, (16, 34), cv2.FONT_HERSHEY_SIMPLEX, font_scale, status_color, thickness)

        if is_anom:
            if bbox:
                x1, y1, x2, y2 = [int(c * scale_up) for c in bbox]
                cv2.rectangle(annotated, (x1, y1), (x2, y2), (0, 140, 255), thickness)
            active_list.append({
                "class": "transistor_anomaly",
                "score": round(norm_score, 3),
                "area_pct": round(area_pct, 2),
            })
            persistence_counter["transistor_anomaly"] = persistence_counter.get("transistor_anomaly", 0) + 1
            now = time.time()
            if force_trigger or (
                persistence_counter["transistor_anomaly"] >= 3
                and (now - cooldown_tracker.get("transistor_anomaly", 0)) > 6.0
            ):
                cooldown_tracker["transistor_anomaly"] = now
                persistence_counter["transistor_anomaly"] = 0
                triggered_event = DetectionEvent(
                    component_id=f"TRN-{comp_idx:04d}",
                    pipeline="unsupervised_patchcore",
                    defect_class="transistor_anomaly",
                    score=norm_score,
                    area_pct=max(1.2, area_pct),
                    bbox=bbox,
                )
        else:
            persistence_counter["transistor_anomaly"] = 0

    advisory = None
    if triggered_event is not None:
        advisory = advisory_engine.advise(triggered_event)
        with state_lock:
            latest_telemetry["latest_advisory"] = advisory
            latest_telemetry["component_counter"] += 1
            recent_alerts.appendleft(advisory)
    elif force_trigger:
        pass_card = {
            "component_id": f"CHK-{comp_idx:04d}",
            "pipeline": "supervised_yolo" if mode == "supervised" else "unsupervised_patchcore",
            "defect_class": "none (conforming)",
            "score": 0.99,
            "area_pct": 0.0,
            "severity": "LOW",
            "disposition": "PASS",
            "standard": "IPC-A-610 / ISO-9443",
            "root_cause": "Zero surface defects or structural anomalies detected.",
            "operator_action": "PASS unit to next assembly station.",
            "llm_latency_ms": 0.0,
        }
        with state_lock:
            latest_telemetry["latest_advisory"] = pass_card
            latest_telemetry["component_counter"] += 1

    dt = max(1e-4, time.perf_counter() - t0)
    fps = round(1.0 / dt, 1)
    cv2.putText(
        annotated,
        f"MODE: {mode.upper()} | {source_label} | {fps} FPS",
        (12, h_img - 12),
        cv2.FONT_HERSHEY_SIMPLEX,
        font_scale * 0.85,
        (120, 255, 120),
        1,
    )

    _, jpg = cv2.imencode(".jpg", annotated, [int(cv2.IMWRITE_JPEG_QUALITY), 88])
    with state_lock:
        latest_jpeg = jpg.tobytes()
        latest_telemetry["fps"] = fps
        latest_telemetry["active_detections"] = active_list
        latest_telemetry["last_status_msg"] = status_msg

    return {"status_msg": status_msg, "detections": active_list, "advisory": advisory}


def camera_worker():
    cap = cv2.VideoCapture(0)
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)

    while True:
        with state_lock:
            mode = latest_telemetry["mode"]
            source = latest_telemetry["source"]

        # When inspecting an uploaded or dataset image, hold the rendered result cleanly
        if source in {"dataset", "upload"}:
            time.sleep(0.4)
            continue

        ok, frame = cap.read()
        if not ok or frame is None:
            frame = np.full((480, 640, 3), 20, dtype=np.uint8)
            cv2.putText(
                frame, "OFFLINE READY - UPLOAD IMAGE OR CLICK DEMO BUTTON",
                (45, 235), cv2.FONT_HERSHEY_SIMPLEX, 0.58, (0, 200, 255), 2
            )
            process_and_render_frame(frame, mode, "STANDBY", force_trigger=False)
            time.sleep(0.25)
        else:
            process_and_render_frame(frame, mode, "LIVE CAM", force_trigger=False)


@asynccontextmanager
async def lifespan(app: FastAPI):
    t = threading.Thread(target=camera_worker, daemon=True)
    t.start()
    yield


app = FastAPI(title="ARM x MeitY Edge Visual QC Assistant", lifespan=lifespan)


@app.get("/", response_class=HTMLResponse)
def index():
    return (BASE_DIR / "static" / "index.html").read_text(encoding="utf-8")


@app.get("/video_feed")
def video_feed():
    def gen():
        while True:
            with state_lock:
                frame_bytes = latest_jpeg
            if frame_bytes:
                yield b"--frame\r\nContent-Type: image/jpeg\r\n\r\n" + frame_bytes + b"\r\n"
            time.sleep(0.08)

    return StreamingResponse(gen(), media_type="multipart/x-mixed-replace; boundary=frame")


@app.post("/api/upload")
async def upload_image(file: UploadFile = File(...), mode: str = Form("supervised")):
    raw_bytes = await file.read()
    arr = np.frombuffer(raw_bytes, np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if img is None:
        return JSONResponse({"error": "Invalid image file"}, status_code=400)

    if mode not in {"supervised", "unsupervised"}:
        mode = "supervised"

    label = file.filename or "uploaded_image"
    with state_lock:
        latest_telemetry["mode"] = mode
        latest_telemetry["source"] = "upload"
        latest_telemetry["sample_name"] = label

    return process_and_render_frame(img, mode, label, force_trigger=True)


@app.post("/api/mode/{mode}")
def set_mode(mode: str):
    if mode not in {"supervised", "unsupervised"}:
        return JSONResponse({"error": "Invalid mode"}, status_code=400)
    with state_lock:
        latest_telemetry["mode"] = mode
        latest_telemetry["source"] = "camera"
        latest_telemetry["sample_name"] = "Live Camera"
    return {"mode": mode}


@app.post("/api/next_sample/{kind}")
def next_sample(kind: str):
    global pcb_idx, trn_idx

    if kind == "pcb":
        if not DEMO_PCBS:
            return JSONResponse({"error": "No demo PCB images found"}, status_code=404)
        img_path = Path(DEMO_PCBS[pcb_idx % len(DEMO_PCBS)])
        pcb_idx += 1
        mode = "supervised"
    elif kind == "transistor":
        if not DEMO_TRNS:
            return JSONResponse({"error": "No demo transistor images found"}, status_code=404)
        img_path = Path(DEMO_TRNS[trn_idx % len(DEMO_TRNS)])
        trn_idx += 1
        mode = "unsupervised"
    else:
        return JSONResponse({"error": "Invalid sample kind"}, status_code=400)

    img = cv2.imread(str(img_path))
    label = img_path.name
    with state_lock:
        latest_telemetry["mode"] = mode
        latest_telemetry["source"] = "dataset"
        latest_telemetry["sample_name"] = label

    return process_and_render_frame(img, mode, label, force_trigger=True)


@app.get("/api/status")
def status():
    with state_lock:
        return {**latest_telemetry, "recent_alerts": list(recent_alerts)}


@app.get("/api/export.csv")
def export_csv():
    buf = io.StringIO()
    writer = csv.writer(buf)
    with sqlite3.connect(DB_PATH) as conn:
        cur = conn.execute("SELECT * FROM inspection_logs ORDER BY id DESC")
        writer.writerow([d[0] for d in cur.description])
        writer.writerows(cur.fetchall())
    buf.seek(0)
    return StreamingResponse(
        iter([buf.getvalue()]),
        media_type="text/csv",
        headers={"Content-Disposition": "attachment; filename=qc_inspection_audit.csv"},
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000, workers=1)
