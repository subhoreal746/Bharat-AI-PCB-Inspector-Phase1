from pathlib import Path

import torch

from anomalib.data import MVTecAD
from anomalib.data.utils.split import ValSplitMode, TestSplitMode
from anomalib.engine import Engine
from anomalib.models import Patchcore

ROOT = Path.cwd()

print("=" * 60)
print("PATCHCORE - MVTec AD TRANSISTOR BASELINE")
print("=" * 60)

print("\nPyTorch:", torch.__version__)
print("MPS available:", torch.backends.mps.is_available())

if not torch.backends.mps.is_available():
    raise RuntimeError("Apple MPS is not available.")

# 1. Dataset
datamodule = MVTecAD(
    root=ROOT / "data/mvtec_ad",
    category="transistor",
    train_batch_size=4,
    eval_batch_size=4,
    num_workers=0,
    test_split_mode=TestSplitMode.FROM_DIR,
    val_split_mode=ValSplitMode.NONE,
    seed=42,
)

# 2. PatchCore model
model = Patchcore(
    backbone="wide_resnet50_2",
    layers=["layer2", "layer3"],
    pre_trained=True,
    coreset_sampling_ratio=0.1,
    num_neighbors=9,
)

# 3. Anomalib engine
engine = Engine(
    max_epochs=1,
    accelerator="mps",
    devices=1,
    limit_val_batches=0,
    default_root_dir=ROOT / "results/patchcore_transistor_baseline",
)
print("\nStarting PatchCore training on Apple MPS...")
engine.fit(
    model=model,
    datamodule=datamodule,
)

print("\nTraining completed.")

# 4. Evaluate on the untouched test set
print("\nStarting test evaluation...")
results = engine.test(
    model=model,
    datamodule=datamodule,
)

print("\n=== PATCHCORE TEST RESULTS ===")
print(results)

print("\nBaseline experiment finished.")
