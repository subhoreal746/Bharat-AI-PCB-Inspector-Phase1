from pathlib import Path
import shutil
import random

ROOT = Path(__file__).resolve().parents[1]

OUT = ROOT / "data" / "unified_pcb"

random.seed(42)

# ============================================================
# Unified PCB taxonomy v0.1
# ============================================================

UNIFIED = {
    "open": 0,
    "short": 1,
    "mouse_bite": 2,
    "spur": 3,
    "spurious_copper": 4,
    "missing_copper": 5,
    "copper_burr": 6,
    "pin_hole": 7,
    "hole_breakout": 8,
    "scratch": 9,
    "copper": 10,
}

# ============================================================
# Dataset mappings
# ============================================================

DATASETS = {

    "deeppcb": {
        "images": ROOT / "data/deeppcb_diff/images",
        "labels": ROOT / "data/deeppcb_diff/labels",

        "classes": {
            0: "open",
            1: "short",
            2: "mouse_bite",
            3: "spur",
            4: "copper",
            5: "pin_hole",
        },
    },

    "dspcbsd": {
        "images": ROOT / "data/dspcbsd/Data_YOLO/images",
        "labels": ROOT / "data/dspcbsd/Data_YOLO/labels",

        "classes": {
            0: "short",                 # SH
            1: "spur",                  # SP
            2: "spurious_copper",       # SC
            3: "open",                  # OP
            4: "mouse_bite",            # MB
            5: "hole_breakout",         # HB
            6: "scratch",               # CS
            # 7 CFO intentionally excluded for v0.1
            # 8 BMFO intentionally excluded for v0.1
        },
    },

    "pcb_ind": {
        "images": ROOT / "data/pcb_ind/YOLO/images",
        "labels": ROOT / "data/pcb_ind/YOLO/labels",

        "classes": {
            0: "mouse_bite",
            1: "missing_copper",
            2: "scratch",
            3: "spurious_copper",
            4: "copper_burr",
            # 5 stain intentionally excluded for v0.1
            6: "short",
            7: "open",
        },
    },
}


# ============================================================
# Helpers
# ============================================================

def find_image(image_dir, stem):
    for ext in [".jpg", ".jpeg", ".png", ".JPG", ".JPEG", ".PNG"]:
        p = image_dir / f"{stem}{ext}"
        if p.exists():
            return p
    return None


def process_dataset(name, cfg):

    print()
    print("=" * 60)
    print(f"Processing {name}")
    print("=" * 60)

    image_root = cfg["images"]
    label_root = cfg["labels"]

    # Only actual label files — ignore YOLO cache files
    label_files = sorted(
        p for p in label_root.rglob("*.txt")
        if p.is_file()
    )

    print(f"Label files found: {len(label_files)}")

    samples = []

    for label_path in label_files:

        # Preserve train/val split when it exists
        relative = label_path.relative_to(label_root)

        if relative.parts[0] in ("train", "val"):
            split = relative.parts[0]
        else:
            # For datasets without an explicit split,
            # we'll assign later.
            split = None

        stem = label_path.stem

        if split:
            image_path = find_image(
                image_root / split,
                stem
            )
        else:
            image_path = find_image(image_root, stem)

        if image_path is None:
            print(f"WARNING: image not found for {label_path}")
            continue

        samples.append(
            (label_path, image_path, split)
        )

    print(f"Valid image/label pairs: {len(samples)}")

    for label_path, image_path, split in samples:

        # Determine output split
        if split is None:
            split = "train"

        out_image_dir = OUT / "images" / split
        out_label_dir = OUT / "labels" / split

        out_image_dir.mkdir(parents=True, exist_ok=True)
        out_label_dir.mkdir(parents=True, exist_ok=True)

        # Unique filename prevents collisions between datasets
        new_stem = f"{name}_{image_path.stem}"

        out_image = out_image_dir / f"{new_stem}{image_path.suffix.lower()}"
        out_label = out_label_dir / f"{new_stem}.txt"

        # ----------------------------------------------------
        # Convert labels
        # ----------------------------------------------------

        output_lines = []

        for line in label_path.read_text().splitlines():

            parts = line.split()

            if len(parts) != 5:
                continue

            old_class = int(parts[0])

            if old_class not in cfg["classes"]:
                continue

            unified_name = cfg["classes"][old_class]
            unified_id = UNIFIED[unified_name]

            output_lines.append(
                f"{unified_id} " + " ".join(parts[1:])
            )

        # Don't include images that have no usable labels
        if not output_lines:
            continue

        # ----------------------------------------------------
        # Copy image + write labels
        # ----------------------------------------------------

        if not out_image.exists():
            shutil.copy2(image_path, out_image)

        out_label.write_text(
            "\n".join(output_lines) + "\n"
        )


# ============================================================
# Build
# ============================================================

for name, cfg in DATASETS.items():
    process_dataset(name, cfg)


# ============================================================
# Write data.yaml
# ============================================================

yaml_text = f"""path: {OUT}

train: images/train
val: images/val

names:
"""

for name, idx in sorted(UNIFIED.items(), key=lambda x: x[1]):
    yaml_text += f"  {idx}: {name}\n"

(OUT / "data.yaml").write_text(yaml_text)

print()
print("=" * 60)
print("UNIFIED DATASET CREATED")
print("=" * 60)
print(f"Location: {OUT}")
print(f"Config:   {OUT / 'data.yaml'}")
print()
print("Classes:")

for name, idx in sorted(UNIFIED.items(), key=lambda x: x[1]):
    print(f"  {idx}: {name}")
