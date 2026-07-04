"""Export Depth-Anything-V2-Metric-Indoor-Small to an ExecuTorch .pte.

Why metric: the original depth.pte is RELATIVE depth — per-frame scale, so a
featureless wall filling the view normalizes to noise and can never register
as "close". The metric-indoor variant outputs actual METERS per pixel: a wall
at 0.5m reads ~0.5 everywhere in the frame, which the app maps directly to
zone nearness. Exported at a reduced input size so it runs continuously on
the Galaxy A23's CPU (518x518 measured 3.8s/pass; 252x252 is ~5x cheaper).

Usage (from the yolo-export venv that already has torch+executorch):
    python scripts/export_depth_metric.py --size 252 --out depth_metric.pte

Output tensor: [1, H, W] float32 meters (same H,W as input).
"""

import argparse

import torch
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
from executorch.exir import to_edge_transform_and_lower
from transformers import DepthAnythingForDepthEstimation

MODEL_ID = "depth-anything/Depth-Anything-V2-Metric-Indoor-Small-hf"


class MetricDepth(torch.nn.Module):
    """Bare forward: normalized RGB in, meters out (post-processing on device)."""

    def __init__(self, size: int):
        super().__init__()
        self.model = DepthAnythingForDepthEstimation.from_pretrained(MODEL_ID)
        self.model.eval()
        self.size = size

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.model(pixel_values=x).predicted_depth  # [1, H, W] meters


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", type=int, default=252, help="input side, multiple of 14")
    ap.add_argument("--out", default="depth_metric.pte")
    args = ap.parse_args()
    assert args.size % 14 == 0, "ViT-S/14: input side must be a multiple of 14"

    model = MetricDepth(args.size)
    example = (torch.randn(1, 3, args.size, args.size),)

    with torch.no_grad():
        ref = model(*example)
    print(f"eager output shape={tuple(ref.shape)} "
          f"range=[{ref.min():.2f}, {ref.max():.2f}] m")

    exported = torch.export.export(model, example)
    lowered = to_edge_transform_and_lower(
        exported, partitioner=[XnnpackPartitioner()]
    ).to_executorch()

    with open(args.out, "wb") as f:
        f.write(lowered.buffer)
    print(f"wrote {args.out} ({len(lowered.buffer) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
