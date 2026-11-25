#!/usr/bin/env python3
"""Export unified_pcb YOLO and PatchCore transistor models to lightweight ONNX/INT8 for edge deployment."""

import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "models" / "edge_vision"
OUT_DIR.mkdir(parents=True, exist_ok=True)


def export_yolo():
    yolo_py = ROOT / ".venv" / "bin" / "python"
    code = """
import shutil
from pathlib import Path
from ultralytics import YOLO
from onnxruntime.quantization import quantize_dynamic, QuantType

best_pt = Path("runs/detect/runs/detect/unified_pcb/v1_baseline/weights/best.pt")
if not best_pt.exists():
    best_pt = Path("backups/unified_pcb_v1/best.pt")

print(f"[YOLO] Loading {best_pt}...")
model = YOLO(str(best_pt))

# 1. 640x640 ONNX (Nothing Phone 2a / high-res inspection)
onnx_640 = model.export(format="onnx", imgsz=640, simplify=True, opset=13)
shutil.copy(onnx_640, "models/edge_vision/unified_pcb_640.onnx")

# 2. 416x416 ONNX + INT8 Quantized ONNX (Raspberry Pi 3 Model B+ 1GB RAM)
onnx_416 = model.export(format="onnx", imgsz=416, simplify=True, opset=13)
shutil.copy(onnx_416, "models/edge_vision/unified_pcb_416.onnx")

quantize_dynamic(
    model_input="models/edge_vision/unified_pcb_416.onnx",
    model_output="models/edge_vision/unified_pcb_416_int8.onnx",
    weight_type=QuantType.QUInt8,
)
print("[YOLO] Exported 640 ONNX, 416 ONNX, and 416 INT8 ONNX cleanly!")
"""
    subprocess.run([str(yolo_py), "-c", code], cwd=str(ROOT), check=True)


def export_patchcore():
    from anomalib.models import Patchcore

    ckpt_path = (
        ROOT
        / "results"
        / "patchcore_transistor_baseline"
        / "Patchcore"
        / "MVTecAD"
        / "transistor"
        / "v1"
        / "weights"
        / "lightning"
        / "model.ckpt"
    )
    print(f"[PatchCore] Loading {ckpt_path}...")
    model = Patchcore.load_from_checkpoint(str(ckpt_path), map_location="cpu", weights_only=False)
    model.eval()

    pc_out = OUT_DIR / "patchcore_transistor"
    pc_out.mkdir(parents=True, exist_ok=True)
    exported = model.to_onnx(export_root=str(pc_out), input_size=(256, 256))
    print(f"[PatchCore] Exported ONNX to: {exported}")


if __name__ == "__main__":
    export_yolo()
    export_patchcore()
