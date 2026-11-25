import cv2
import os


IMAGE_DIR = "data/neu_yolo/images/train"
LABEL_DIR = "data/neu_yolo/labels/train"
OUTPUT_DIR = "data/test/yolo_visualization"

CLASS_NAMES = [
    "crazing",
    "inclusion",
    "patches",
    "pitted_surface",
    "rolled-in_scale",
    "scratches"
]


os.makedirs(OUTPUT_DIR, exist_ok=True)


# Take the first 10 images
image_files = sorted(
    [
        f for f in os.listdir(IMAGE_DIR)
        if f.endswith(".jpg")
    ]
)[:10]


for image_file in image_files:

    image_path = os.path.join(
        IMAGE_DIR,
        image_file
    )

    label_file = os.path.splitext(image_file)[0] + ".txt"

    label_path = os.path.join(
        LABEL_DIR,
        label_file
    )

    image = cv2.imread(image_path)

    if image is None:
        print("Could not load:", image_path)
        continue

    height, width = image.shape[:2]

    if os.path.exists(label_path):

        with open(label_path, "r") as f:

            for line in f:

                values = line.strip().split()

                if len(values) != 5:
                    continue

                class_id = int(values[0])

                x_center = float(values[1])
                y_center = float(values[2])
                box_width = float(values[3])
                box_height = float(values[4])

                # YOLO normalized coordinates
                # → pixel coordinates

                x_center *= width
                y_center *= height
                box_width *= width
                box_height *= height

                x1 = int(x_center - box_width / 2)
                y1 = int(y_center - box_height / 2)

                x2 = int(x_center + box_width / 2)
                y2 = int(y_center + box_height / 2)

                # Keep coordinates inside image
                x1 = max(0, x1)
                y1 = max(0, y1)
                x2 = min(width - 1, x2)
                y2 = min(height - 1, y2)

                cv2.rectangle(
                    image,
                    (x1, y1),
                    (x2, y2),
                    (0, 255, 0),
                    2
                )

                label = CLASS_NAMES[class_id]

                cv2.putText(
                    image,
                    label,
                    (x1, max(15, y1 - 5)),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.5,
                    (0, 255, 0),
                    1,
                    cv2.LINE_AA
                )

    output_path = os.path.join(
        OUTPUT_DIR,
        image_file
    )

    cv2.imwrite(
        output_path,
        image
    )

    print("Saved:", output_path)


print()
print("Visualization complete.")
