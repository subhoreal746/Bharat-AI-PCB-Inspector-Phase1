from pathlib import Path
import cv2

ROOT = Path("data/dspcbsd/Data_YOLO")
IMAGE_DIR = ROOT / "images/train"
LABEL_DIR = ROOT / "labels/train"
OUT_DIR = Path("data/test/dspcbsd_visualization")

OUT_DIR.mkdir(parents=True, exist_ok=True)

CLASS_NAMES = [
    "SH",
    "SP",
    "SC",
    "OP",
    "MB",
    "HB",
    "CS",
    "CFO",
    "BMFO",
]

images = sorted(IMAGE_DIR.glob("*"))[:10]

for image_path in images:
    label_path = LABEL_DIR / f"{image_path.stem}.txt"

    image = cv2.imread(str(image_path))

    if image is None:
        print(f"Could not read: {image_path}")
        continue

    h, w = image.shape[:2]

    if label_path.exists():
        for line in label_path.read_text().splitlines():
            parts = line.split()

            if len(parts) != 5:
                continue

            cls, xc, yc, bw, bh = map(float, parts)

            cls = int(cls)

            x1 = int((xc - bw / 2) * w)
            y1 = int((yc - bh / 2) * h)
            x2 = int((xc + bw / 2) * w)
            y2 = int((yc + bh / 2) * h)

            x1 = max(0, x1)
            y1 = max(0, y1)
            x2 = min(w - 1, x2)
            y2 = min(h - 1, y2)

            cv2.rectangle(image, (x1, y1), (x2, y2), (0, 255, 0), 2)

            name = CLASS_NAMES[cls] if 0 <= cls < len(CLASS_NAMES) else f"class_{cls}"

            cv2.putText(
                image,
                name,
                (x1, max(20, y1 - 5)),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.6,
                (0, 255, 0),
                2,
            )

    output_path = OUT_DIR / image_path.name
    cv2.imwrite(str(output_path), image)

    print(f"Saved: {output_path}")

print(f"\nCreated visualizations in: {OUT_DIR}")
