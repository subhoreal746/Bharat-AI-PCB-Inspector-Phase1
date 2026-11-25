#!/usr/bin/env python3
"""
Converts VisA (pcb1-pcb4) ground-truth pixel masks into YOLOv8 bounding boxes,
maps defect types into the 11-class unified_pcb schema, samples clean populated
PCBs as hard negatives, and merges them into data/unified_pcb.
"""

import csv
import random
import shutil
from collections import Counter
from pathlib import Path
import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
VISA_DIR = ROOT / "datasets" / "visa_pcb"
UNIFIED_DIR = ROOT / "data" / "unified_pcb"

# Map VisA populated-PCB defect descriptions into your existing 11-class schema:
# 0: open, 1: short, 2: mouse_bite, 3: spur, 4: spurious_copper,
# 5: missing_copper, 6: copper_burr, 7: pin_hole, 8: hole_breakout, 9: scratch, 10: copper
DEFECT_MAP = {
    "bent": 1,            # bent pins / bridged leads -> short / clearance violation
    "melt": 5,            # thermal / pad damage -> missing_copper
    "scratch": 9,         # surface abrasion -> scratch
    "missing": 5,         # missing component / pad -> missing_copper
    "wrong": 1,           # misaligned / bridged placement -> short
    "crack": 0,           # broken trace / body -> open
    "cut": 0,             # severed lead / trace -> open
    "hole": 7,            # void / pin_hole
    "burr": 6,            # copper_burr
}


def map_visa_label(raw_label: str) -> int:
    s = raw_label.lower().strip()
    for key, cid in DEFECT_MAP.items():
        if key in s:
            return cid
    return 9  # default surface anomaly -> scratch


def mask_to_yolo_boxes(mask_path: Path, class_id: int, min_area_px: int = 25) -> list[str]:
    mask = cv2.imread(str(mask_path), cv2.IMREAD_GRAYSCALE)
    if mask is None:
        return []
    h, w = mask.shape[:2]
    _, binary = cv2.threshold(mask, 0, 255, cv2.THRESH_BINARY)
    num_labels, _, stats, _ = cv2.connectedComponentsWithStats(binary, connectivity=8)

    lines = []
    for i in range(1, num_labels):
        x, y, bw, bh, area = stats[i]
        if area < min_area_px:
            continue
        cx = (x + bw / 2.0) / w
        cy = (y + bh / 2.0) / h
        nw = bw / w
        nh = bh / h
        lines.append(f"{class_id} {cx:.6f} {cy:.6f} {nw:.6f} {nh:.6f}")
    return lines


def main():
    random.seed(42)
    csv_path = VISA_DIR / "split_csv" / "1cls.csv"
    if not csv_path.exists():
        raise FileNotFoundError(f"Missing {csv_path}")

    anomalous_items = []
    normal_items = []
    raw_types = Counter()

    with open(csv_path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            obj = row.get("object", "").strip()
            if obj not in {"pcb1", "pcb2", "pcb3", "pcb4"}:
                continue
            label = row.get("label", "").strip()
            img_rel = row.get("image", "").strip()
            mask_rel = row.get("mask", "").strip()
            img_path = VISA_DIR / img_rel
            if not img_path.exists():
                continue

            if label.lower() == "normal":
                normal_items.append((obj, img_path))
            else:
                raw_types[label] += 1
                mask_path = VISA_DIR / mask_rel if mask_rel else None
                anomalous_items.append((obj, label, img_path, mask_path))

    print("Found VisA PCB anomalous samples:", len(anomalous_items), dict(raw_types))
    print("Found VisA PCB normal (hard-negative) samples:", len(normal_items))

    # Sample up to 240 clean populated PCBs as hard negatives (prevents false positives on SMD pins)
    random.shuffle(normal_items)
    selected_normals = normal_items[:240]

    # Build unified dataset records: (stem, img_path, yolo_lines, is_val)
    records = []
    class_counts = Counter()

    random.shuffle(anomalous_items)
    val_anom_cutoff = max(1, int(len(anomalous_items) * 0.20))
    for idx, (obj, raw_lbl, img_p, mask_p) in enumerate(anomalous_items):
        if mask_p is None or not mask_p.exists():
            continue
        cid = map_visa_label(raw_lbl)
        boxes = mask_to_yolo_boxes(mask_p, cid)
        if not boxes:
            continue
        class_counts[cid] += len(boxes)
        stem = f"visa_{obj}_{img_p.stem}"
        records.append((stem, img_p, boxes, idx < val_anom_cutoff))

    val_norm_cutoff = max(1, int(len(selected_normals) * 0.20))
    for idx, (obj, img_p) in enumerate(selected_normals):
        stem = f"visa_{obj}_norm_{img_p.stem}"
        records.append((stem, img_p, [], idx < val_norm_cutoff))

    # Write into data/unified_pcb/{images,labels}/{train,val}
    added_train, added_val = 0, 0
    for stem, img_p, boxes, is_val in records:
        split = "val" if is_val else "train"
        dst_img = UNIFIED_DIR / "images" / split / f"{stem}{img_p.suffix.lower()}"
        dst_lbl = UNIFIED_DIR / "labels" / split / f"{stem}.txt"
        dst_img.parent.mkdir(parents=True, exist_ok=True)
        dst_lbl.parent.mkdir(parents=True, exist_ok=True)

        shutil.copy2(img_p, dst_img)
        dst_lbl.write_text("\n".join(boxes) + ("\n" if boxes else ""), encoding="utf-8")
        if is_val:
            added_val += 1
        else:
            added_train += 1

    print(f"[SUCCESS] Merged {added_train} train and {added_val} val populated-PCB images into {UNIFIED_DIR}")
    print("Added bounding boxes by class_id:", dict(class_counts))


if __name__ == "__main__":
    main()
