import os
import shutil
import random
import xml.etree.ElementTree as ET


# --------------------------------------------------
# Paths
# --------------------------------------------------

SOURCE_DIR = "data/raw/NEU-DET"
IMAGE_DIR = os.path.join(SOURCE_DIR, "IMAGES")
ANNOTATION_DIR = os.path.join(SOURCE_DIR, "ANNOTATIONS")

OUTPUT_DIR = "data/neu_yolo"


# --------------------------------------------------
# Class mapping
# --------------------------------------------------

CLASS_NAMES = [
    "crazing",
    "inclusion",
    "patches",
    "pitted_surface",
    "rolled-in_scale",
    "scratches"
]

CLASS_MAP = {
    name: index
    for index, name in enumerate(CLASS_NAMES)
}


# --------------------------------------------------
# Train / validation split
# --------------------------------------------------

TRAIN_RATIO = 0.8
RANDOM_SEED = 42


# --------------------------------------------------
# Convert one XML annotation to YOLO format
# --------------------------------------------------

def convert_annotation(xml_path, label_path):

    tree = ET.parse(xml_path)
    root = tree.getroot()

    size = root.find("size")

    image_width = int(size.find("width").text)
    image_height = int(size.find("height").text)

    with open(label_path, "w") as label_file:

        for obj in root.findall("object"):

            class_name = obj.find("name").text.strip()

            if class_name not in CLASS_MAP:
                raise ValueError(
                    f"Unknown class: {class_name}"
                )

            class_id = CLASS_MAP[class_name]

            box = obj.find("bndbox")

            xmin = float(box.find("xmin").text)
            ymin = float(box.find("ymin").text)
            xmax = float(box.find("xmax").text)
            ymax = float(box.find("ymax").text)

            # Convert to YOLO format
            x_center = ((xmin + xmax) / 2) / image_width
            y_center = ((ymin + ymax) / 2) / image_height

            width = (xmax - xmin) / image_width
            height = (ymax - ymin) / image_height

            label_file.write(
                f"{class_id} "
                f"{x_center:.6f} "
                f"{y_center:.6f} "
                f"{width:.6f} "
                f"{height:.6f}\n"
            )


# --------------------------------------------------
# Main
# --------------------------------------------------

def main():

    random.seed(RANDOM_SEED)

    xml_files = [
        filename
        for filename in os.listdir(ANNOTATION_DIR)
        if filename.endswith(".xml")
    ]

    xml_files.sort()

    print(f"Found {len(xml_files)} annotation files.")

    random.shuffle(xml_files)

    split_index = int(len(xml_files) * TRAIN_RATIO)

    train_files = xml_files[:split_index]
    val_files = xml_files[split_index:]

    print(f"Training images:   {len(train_files)}")
    print(f"Validation images: {len(val_files)}")

    for split, files in [
        ("train", train_files),
        ("val", val_files)
    ]:

        image_output_dir = os.path.join(
            OUTPUT_DIR,
            "images",
            split
        )

        label_output_dir = os.path.join(
            OUTPUT_DIR,
            "labels",
            split
        )

        for xml_filename in files:

            base_name = os.path.splitext(xml_filename)[0]

            xml_path = os.path.join(
                ANNOTATION_DIR,
                xml_filename
            )

            image_filename = base_name + ".jpg"

            image_path = os.path.join(
                IMAGE_DIR,
                image_filename
            )

            if not os.path.exists(image_path):
                raise FileNotFoundError(
                    f"Image not found: {image_path}"
                )

            # Copy image
            shutil.copy2(
                image_path,
                os.path.join(
                    image_output_dir,
                    image_filename
                )
            )

            # Convert annotation
            label_path = os.path.join(
                label_output_dir,
                base_name + ".txt"
            )

            convert_annotation(
                xml_path,
                label_path
            )

    print()
    print("Dataset conversion completed successfully.")


if __name__ == "__main__":
    main()
