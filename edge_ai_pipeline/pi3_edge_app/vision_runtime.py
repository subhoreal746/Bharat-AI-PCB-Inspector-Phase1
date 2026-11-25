"""
Calibrated ONNX Runtime Vision Engine for Raspberry Pi 3 Model B+ & Arm Edge Devices
Runs:
  1. Unified Supervised YOLOv8n INT8 (11 PCB + Steel defect classes @ 416x416 letterboxed)
  2. Unsupervised PatchCore Transistor Anomaly Detector (@ 256x256, calibrated threshold=39.5)
"""

from dataclasses import dataclass
from typing import List, Optional, Tuple
import cv2
import numpy as np
import onnxruntime as ort

UNIFIED_CLASSES = {
    0: "open",
    1: "short",
    2: "mouse_bite",
    3: "spur",
    4: "spurious_copper",
    5: "missing_copper",
    6: "copper_burr",
    7: "pin_hole",
    8: "hole_breakout",
    9: "scratch",
    10: "copper",
}


@dataclass
class BoxDetection:
    class_id: int
    class_name: str
    confidence: float
    bbox: List[int]       # [x1, y1, x2, y2] in original frame coordinates
    area_pct: float


def letterbox(
    img: np.ndarray, new_shape: int = 416, color: Tuple[int, int, int] = (114, 114, 114)
) -> Tuple[np.ndarray, float, Tuple[int, int]]:
    """Aspect-ratio preserving resize + padding identical to Ultralytics YOLO preprocessing."""
    h, w = img.shape[:2]
    r = min(new_shape / h, new_shape / w)
    new_unpad = (int(round(w * r)), int(round(h * r)))
    dw = (new_shape - new_unpad[0]) / 2.0
    dh = (new_shape - new_unpad[1]) / 2.0

    if (w, h) != new_unpad:
        img = cv2.resize(img, new_unpad, interpolation=cv2.INTER_LINEAR)

    top, bottom = int(round(dh - 0.1)), int(round(dh + 0.1))
    left, right = int(round(dw - 0.1)), int(round(dw + 0.1))
    img = cv2.copyMakeBorder(img, top, bottom, left, right, cv2.BORDER_CONSTANT, value=color)
    return img, r, (left, top)


def refine_populated_defect_class(frame_bgr: np.ndarray, bbox: List[int], cid: int) -> int:
    """Refines generic scratch predictions on green populated PCBs using ROI HSV metallurgy/charring."""
    if cid != 9:
        return cid
    hsv = cv2.cvtColor(frame_bgr, cv2.COLOR_BGR2HSV)
    # Check if board has a green solder mask (leaves grayscale/amber bare PCBs untouched)
    green_mask = (hsv[:, :, 0] >= 35) & (hsv[:, :, 0] <= 95) & (hsv[:, :, 1] >= 40)
    if float(green_mask.mean()) < 0.15:
        return cid

    x1, y1, x2, y2 = bbox
    roi = hsv[max(0, y1):max(y1 + 1, y2), max(0, x1):max(x1 + 1, x2)]
    if roi.size == 0:
        return cid

    char_ratio = float((roi[:, :, 2] < 65).mean())
    solder_ratio = float(((roi[:, :, 2] > 165) & (roi[:, :, 1] < 75)).mean())

    if char_ratio >= 0.16:
        return 5  # missing_copper (thermal charring / damaged pad)
    if solder_ratio >= 0.25:
        return 1  # short (metallic solder bridge across conductors)
    return 9      # scratch (solder-mask surface abrasion)


class EdgeVisionEngine:
    def __init__(
        self,
        yolo_onnx_path: str = "models/unified_pcb_416_int8.onnx",
        patchcore_onnx_path: Optional[str] = "models/patchcore_transistor.onnx",
        conf_thresh: float = 0.35,
        iou_thresh: float = 0.45,
        pc_raw_thresh: float = 39.5,
    ):
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 3
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

        self.yolo_sess = ort.InferenceSession(
            yolo_onnx_path, sess_options=opts, providers=["CPUExecutionProvider"]
        )
        inp0 = self.yolo_sess.get_inputs()[0]
        self.yolo_input = inp0.name
        self.yolo_size = int(inp0.shape[2]) if isinstance(inp0.shape[2], int) else 416
        self.conf_thresh = conf_thresh
        self.iou_thresh = iou_thresh

        self.pc_sess = None
        self.pc_input = None
        self.pc_batch = 1
        self.pc_raw_thresh = pc_raw_thresh
        if patchcore_onnx_path:
            try:
                self.pc_sess = ort.InferenceSession(
                    patchcore_onnx_path, sess_options=opts, providers=["CPUExecutionProvider"]
                )
                inp_meta = self.pc_sess.get_inputs()[0]
                self.pc_input = inp_meta.name
                if isinstance(inp_meta.shape[0], int) and inp_meta.shape[0] > 1:
                    self.pc_batch = inp_meta.shape[0]
            except Exception as e:
                print(f"[WARN] PatchCore ONNX disabled: {e}")

    def detect_supervised(self, frame_bgr: np.ndarray) -> List[BoxDetection]:
        h0, w0 = frame_bgr.shape[:2]
        padded, ratio, (pad_x, pad_y) = letterbox(frame_bgr, self.yolo_size)
        img_rgb = cv2.cvtColor(padded, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        blob = np.transpose(img_rgb, (2, 0, 1))[None, ...]

        preds = self.yolo_sess.run(None, {self.yolo_input: blob})[0]
        preds = np.squeeze(preds, axis=0).T

        boxes_xywh = preds[:, :4]
        class_scores = preds[:, 4:]
        class_ids = np.argmax(class_scores, axis=1)
        confidences = class_scores[np.arange(len(class_scores)), class_ids]

        mask = confidences >= self.conf_thresh
        boxes_xywh = boxes_xywh[mask]
        confidences = confidences[mask]
        class_ids = class_ids[mask]

        if len(boxes_xywh) == 0:
            return []

        xyxy_list = []
        nms_boxes = []
        for cx, cy, bw, bh in boxes_xywh:
            x1 = int(max(0, min(w0, ((cx - bw / 2) - pad_x) / ratio)))
            y1 = int(max(0, min(h0, ((cy - bh / 2) - pad_y) / ratio)))
            x2 = int(max(0, min(w0, ((cx + bw / 2) - pad_x) / ratio)))
            y2 = int(max(0, min(h0, ((cy + bh / 2) - pad_y) / ratio)))
            xyxy_list.append([x1, y1, x2, y2])
            nms_boxes.append([x1, y1, max(1, x2 - x1), max(1, y2 - y1)])

        indices = cv2.dnn.NMSBoxes(
            nms_boxes, confidences.tolist(), self.conf_thresh, self.iou_thresh
        )
        detections: List[BoxDetection] = []
        frame_area = float(max(1, w0 * h0))

        for idx in np.array(indices).flatten():
            x1, y1, x2, y2 = xyxy_list[idx]
            conf = float(confidences[idx])
            area_pct = ((x2 - x1) * (y2 - y1) / frame_area) * 100.0
            touches_border = (x1 <= 8 or y1 <= 8 or x2 >= (w0 - 8) or y2 >= (h0 - 8))
            if touches_border and conf < 0.50 and area_pct < 2.5:
                continue
            cid = refine_populated_defect_class(frame_bgr, [x1, y1, x2, y2], int(class_ids[idx]))
            detections.append(
                BoxDetection(
                    class_id=cid,
                    class_name=UNIFIED_CLASSES.get(cid, f"class_{cid}"),
                    confidence=float(confidences[idx]),
                    bbox=[x1, y1, x2, y2],
                    area_pct=area_pct,
                )
            )
        return detections

    def detect_unsupervised(
        self, frame_bgr: np.ndarray
    ) -> Tuple[bool, float, float, Optional[List[int]], float]:
        """Returns (is_anomalous, normalized_score_0_1, raw_score, bbox_xyxy, area_pct)."""
        if self.pc_sess is None:
            return False, 0.0, 0.0, None, 0.0

        h0, w0 = frame_bgr.shape[:2]
        img = cv2.resize(frame_bgr, (256, 256))
        # Anomalib ONNX graph includes internal ImageNet normalization; input must be [0..1] RGB
        img_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        blob = np.transpose(img_rgb, (2, 0, 1))[None, ...]
        if self.pc_batch > 1:
            blob = np.repeat(blob, self.pc_batch, axis=0)

        outputs = self.pc_sess.run(None, {self.pc_input: blob})
        raw_score = float(outputs[0][0])

        # Calibrated sigmoid around pc_raw_thresh=39.5 (good ~34-38, defective ~40-55+)
        norm_score = float(1.0 / (1.0 + np.exp(-(raw_score - self.pc_raw_thresh) * 0.6)))
        is_anom = raw_score >= self.pc_raw_thresh

        bbox = None
        area_pct = 0.0
        if is_anom and len(outputs) >= 3:
            amap = outputs[2][0, 0]  # (256, 256) float anomaly map
            amap_norm = ((amap - amap.min()) / (np.ptp(amap) + 1e-6) * 255).astype(np.uint8)
            _, thresh = cv2.threshold(amap_norm, 195, 255, cv2.THRESH_BINARY)
            cnts, _ = cv2.findContours(thresh, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            if cnts:
                largest = max(cnts, key=cv2.contourArea)
                x, y, bw, bh = cv2.boundingRect(largest)
                x1, y1 = int(x * w0 / 256), int(y * h0 / 256)
                x2, y2 = int((x + bw) * w0 / 256), int((y + bh) * h0 / 256)
                bbox = [x1, y1, x2, y2]
                area_pct = max(0.5, ((x2 - x1) * (y2 - y1) / float(max(1, w0 * h0))) * 100.0)

        return is_anom, norm_score, raw_score, bbox, area_pct
