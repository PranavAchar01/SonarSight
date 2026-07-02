"""Export YOLO11n as an int8-quantized ExecuTorch XNNPACK .pte.

Same architecture, same 640x640 input, same [1, 84, 8400] output the app's
YoloDecoder already parses — only the kernels change (fp32 -> int8), which is
what buys the CPU speedup on the Galaxy A23's SD695.

Usage:
    uv run python scripts/export_yolo_int8.py [--out yolo_int8.pte] [--size 640]

Calibration uses the Ultralytics sample images plus random tensors; for a
production model, calibrate with frames from the actual glasses camera.
"""

import argparse
import sys
from pathlib import Path

import numpy as np
import torch


class Backbone(torch.nn.Module):
    """Every layer except Detect, replicating DetectionModel._predict_once wiring.

    Returns the multi-scale feature maps the Detect head consumes. This is the
    part that gets int8-quantized (the overwhelming majority of the FLOPs).
    """

    def __init__(self, det: torch.nn.Module):
        super().__init__()
        self.layers = det.model[:-1]
        self.save = set(det.save)
        self.detect_f = list(det.model[-1].f)

    def forward(self, x: torch.Tensor):
        y: list = []
        for m in self.layers:
            if m.f != -1:
                x = y[m.f] if isinstance(m.f, int) else [
                    x if j == -1 else y[j] for j in m.f
                ]
            x = m(x)
            y.append(x if m.i in self.save else None)
        return tuple(x if j == -1 else y[j] for j in self.detect_f)


class Head(torch.nn.Module):
    """The Detect head, kept fp32: its output mixes box coords (0..640) and
    class probs (0..1), which a shared int8 scale would crush to zero."""

    def __init__(self, det: torch.nn.Module):
        super().__init__()
        self.detect = det.model[-1]

    def forward(self, feats):
        out = self.detect(list(feats))
        return out[0] if isinstance(out, (tuple, list)) else out


class Composed(torch.nn.Module):
    def __init__(self, backbone: torch.nn.Module, head: torch.nn.Module):
        super().__init__()
        self.backbone = backbone
        self.head = head

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.head(self.backbone(x))


def load_model(size: int):
    from ultralytics import YOLO

    det = YOLO("yolo11n.pt").model.eval()  # auto-downloads
    backbone = Backbone(det).eval()
    head = Head(det).eval()
    with torch.no_grad():
        shape = Composed(backbone, head)(torch.rand(1, 3, size, size)).shape
    print(f"raw output shape: {tuple(shape)} (app expects (1, 84, {int((size / 8) ** 2 + (size / 16) ** 2 + (size / 32) ** 2)}))")
    return backbone, head


def calibration_batches(size: int):
    """Ultralytics sample images (if available) + random tensors."""
    batches = []
    try:
        import cv2
        from ultralytics.utils import ASSETS

        for img_path in sorted(Path(ASSETS).glob("*.jpg")):
            img = cv2.imread(str(img_path))
            img = cv2.cvtColor(cv2.resize(img, (size, size)), cv2.COLOR_BGR2RGB)
            t = torch.from_numpy(img).permute(2, 0, 1).float().unsqueeze(0) / 255.0
            batches.append(t)
            print(f"calibration image: {img_path.name}")
    except Exception as e:  # noqa: BLE001 - calibration extras are best-effort
        print(f"sample-image calibration unavailable ({e}); using random only")
    batches += [torch.rand(1, 3, size, size) for _ in range(8)]
    return batches


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="yolo_int8.pte")
    ap.add_argument("--size", type=int, default=640)
    args = ap.parse_args()

    torch.manual_seed(0)
    np.random.seed(0)

    backbone, head = load_model(args.size)
    example = (torch.rand(1, 3, args.size, args.size),)

    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
    from executorch.backends.xnnpack.quantizer.xnnpack_quantizer import (
        XNNPACKQuantizer,
        get_symmetric_quantization_config,
    )
    from executorch.exir import to_edge_transform_and_lower
    from torchao.quantization.pt2e.quantize_pt2e import convert_pt2e, prepare_pt2e

    print("quantizing backbone (int8, per-channel)…")
    backbone_gm = torch.export.export(backbone, example).module()
    quantizer = XNNPACKQuantizer()
    quantizer.set_global(get_symmetric_quantization_config(is_per_channel=True))
    prepared = prepare_pt2e(backbone_gm, quantizer)

    print("calibrating…")
    with torch.no_grad():
        for batch in calibration_batches(args.size):
            prepared(batch)

    quantized_backbone = convert_pt2e(prepared)

    print("composing int8 backbone + fp32 head, lowering to XNNPACK…")
    composed = Composed(quantized_backbone, head)
    exported = torch.export.export(composed, example)
    prog = to_edge_transform_and_lower(
        exported, partitioner=[XnnpackPartitioner()]
    ).to_executorch()

    out = Path(args.out)
    out.write_bytes(prog.buffer)
    print(f"wrote {out} ({out.stat().st_size / 1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
