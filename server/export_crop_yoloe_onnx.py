"""Bake the chosen ROI prompts into YOLOE-26n and export server ONNX.

This development-only command needs Ultralytics/PyTorch. Production inference
loads only the generated ONNX with ONNX Runtime.
"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "server" / "models" / "yoloe-26n-cook-roi.onnx"
PROMPTS = [
    "cutting board",
    "chopping board",
    "frying pan",
    "skillet",
    "cooking pan",
    "wok",
]


def main() -> int:
    parser = argparse.ArgumentParser(description="Export fixed-prompt crop YOLOE ONNX")
    parser.add_argument("--model", default="yoloe-26n-seg.pt")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    try:
        from ultralytics import YOLOE
    except ImportError as error:
        raise SystemExit("ultralytics is required for this development command") from error

    model = YOLOE(args.model)
    model.set_classes(PROMPTS)
    exported = Path(
        model.export(
            format="onnx",
            imgsz=640,
            batch=1,
            device="cpu",
            simplify=True,
            nms=True,
        )
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(exported, args.output)
    print(f"✅ fixed-prompt ONNX {args.output.stat().st_size / 1024 / 1024:.1f} MiB → {args.output}")
    return 0


if __name__ == "__main__":
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass
    raise SystemExit(main())
