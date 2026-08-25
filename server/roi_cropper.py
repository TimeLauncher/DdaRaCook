"""Server-side ROI crop with a fail-safe legacy fallback.

The Android app sends an orientation-baked JPEG and an explicit crop target.
Old clients omit ``cropTarget`` and remain byte-for-byte passthrough. New
automatic-camera clients request either a fixed ROI class or legacy bottom-60.
Any model/load/inference/selection failure falls back to bottom-60.
"""
from __future__ import annotations

import base64
import io
import os
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Literal, Protocol

import numpy as np
from PIL import Image

from roi_crop import (
    CUTTING_BOARD_ROI,
    PAN_COOKING_ROI,
    Detection,
    bottom_60_fallback,
    crop_window_for_bbox,
    intersection_over_union,
    scaled_dimensions,
    select_active_detection,
)


CropTarget = Literal[
    "CUTTING_BOARD_ROI",
    "PAN_COOKING_ROI",
    "LEGACY_BOTTOM_60",
    "NO_CROP",
]
MODEL_PATH = Path(
    os.getenv(
        "ROI_CROP_MODEL_PATH",
        str(Path(__file__).resolve().parent / "models" / "yoloe-26n-cook-roi.onnx"),
    )
)
ROI_CROP_ENABLED = os.getenv("ROI_CROP_ENABLED", "true").lower() == "true"
MODEL_INPUT_SIZE = 640
MINIMUM_CONFIDENCE = 0.05
MAXIMUM_GAZE_DISTANCE = 0.30
CONTEXT_PADDING = 0.22
GAZE_ANCHOR = (0.5, 0.65)
PROMPT_CLASS_TO_TARGET = {
    0: CUTTING_BOARD_ROI,
    1: CUTTING_BOARD_ROI,
    2: PAN_COOKING_ROI,
    3: PAN_COOKING_ROI,
    4: PAN_COOKING_ROI,
    5: PAN_COOKING_ROI,
}


class RoiDetector(Protocol):
    def detect(self, image: Image.Image) -> list[Detection]: ...


@dataclass(frozen=True)
class CropDecision:
    mode: str
    target: str | None
    detection_count: int = 0


class OnnxRoiDetector:
    def __init__(self, model_path: Path = MODEL_PATH):
        import onnxruntime as ort

        if not model_path.is_file():
            raise FileNotFoundError(f"ROI crop model not found: {model_path}")
        self.session = ort.InferenceSession(
            str(model_path), providers=["CPUExecutionProvider"]
        )
        self.input_name = self.session.get_inputs()[0].name

    def detect(self, image: Image.Image) -> list[Detection]:
        source = image.convert("RGB")
        scale = min(MODEL_INPUT_SIZE / source.width, MODEL_INPUT_SIZE / source.height)
        resized_width = max(1, round(source.width * scale))
        resized_height = max(1, round(source.height * scale))
        resized = source.resize((resized_width, resized_height), Image.Resampling.BILINEAR)
        pad_x = (MODEL_INPUT_SIZE - resized_width) // 2
        pad_y = (MODEL_INPUT_SIZE - resized_height) // 2
        canvas = np.full((MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, 3), 114, dtype=np.uint8)
        canvas[pad_y:pad_y + resized_height, pad_x:pad_x + resized_width] = np.asarray(resized)
        tensor = np.transpose(canvas.astype(np.float32) / 255.0, (2, 0, 1))[None]
        prediction = self.session.run(None, {self.input_name: tensor})[0][0]

        detections: list[Detection] = []
        for row in prediction:
            confidence = float(row[4])
            if confidence < MINIMUM_CONFIDENCE:
                continue
            class_name = PROMPT_CLASS_TO_TARGET.get(int(round(float(row[5]))))
            if class_name is None:
                continue
            left = max(0.0, min(source.width, (float(row[0]) - pad_x) / scale))
            top = max(0.0, min(source.height, (float(row[1]) - pad_y) / scale))
            right = max(0.0, min(source.width, (float(row[2]) - pad_x) / scale))
            bottom = max(0.0, min(source.height, (float(row[3]) - pad_y) / scale))
            if right <= left or bottom <= top:
                continue
            detections.append(
                Detection(
                    class_name=class_name,
                    confidence=confidence,
                    bbox=(
                        left / source.width,
                        top / source.height,
                        (right - left) / source.width,
                        (bottom - top) / source.height,
                    ),
                )
            )
        return _agnostic_nms(detections, iou_threshold=0.70)


def _agnostic_nms(
    detections: list[Detection],
    iou_threshold: float,
) -> list[Detection]:
    """Suppress duplicate synonym boxes regardless of their prompt class."""
    kept: list[Detection] = []
    for detection in sorted(
        detections, key=lambda candidate: candidate.confidence, reverse=True
    ):
        if all(
            intersection_over_union(detection.bbox, existing.bbox) <= iou_threshold
            for existing in kept
        ):
            kept.append(detection)
    return kept


_detector: RoiDetector | None = None
_detector_error: str | None = None
_detector_lock = threading.Lock()


def get_detector() -> RoiDetector:
    global _detector, _detector_error
    if _detector is not None:
        return _detector
    with _detector_lock:
        if _detector is None:
            try:
                _detector = OnnxRoiDetector()
            except Exception as error:
                _detector_error = f"{type(error).__name__}: {error}"
                raise
    return _detector


def cropper_status() -> dict:
    return {
        "enabled": ROI_CROP_ENABLED,
        "modelPresent": MODEL_PATH.is_file(),
        "modelPath": str(MODEL_PATH),
        "loaded": _detector is not None,
        "loadError": _detector_error,
    }


def _encode_jpeg(image: Image.Image) -> str:
    output = io.BytesIO()
    image.convert("RGB").save(output, format="JPEG", quality=80, optimize=False)
    return base64.b64encode(output.getvalue()).decode("ascii")


def _legacy_crop(image: Image.Image) -> Image.Image:
    bounds = bottom_60_fallback(image.width, image.height)
    cropped = image.crop((bounds.left, bounds.top, bounds.right, bounds.bottom))
    output_size = scaled_dimensions(cropped.width, cropped.height, 1024)
    return (
        cropped.resize(output_size, Image.Resampling.LANCZOS)
        if output_size != cropped.size
        else cropped
    )


def prepare_judge_image(
    image_b64: str,
    target: CropTarget | None,
    detector: RoiDetector | None = None,
    roi_enabled: bool = ROI_CROP_ENABLED,
) -> tuple[str, CropDecision]:
    """Crop one validated JPEG; never let ROI failure fail the judgment."""
    if target is None:
        return image_b64, CropDecision("CLIENT_PREPARED", None)
    if target == "NO_CROP":
        return image_b64, CropDecision("NO_CROP", target)

    image = Image.open(io.BytesIO(base64.b64decode(image_b64, validate=True))).convert("RGB")
    if target == "LEGACY_BOTTOM_60":
        return _encode_jpeg(_legacy_crop(image)), CropDecision("LEGACY_BOTTOM_60", target)

    detections: list[Detection] = []
    if roi_enabled:
        try:
            detections = (detector or get_detector()).detect(image)
            selected = select_active_detection(
                detections,
                target,
                gaze_anchor=GAZE_ANCHOR,
                minimum_confidence=MINIMUM_CONFIDENCE,
                maximum_gaze_distance=MAXIMUM_GAZE_DISTANCE,
            )
            if selected is not None:
                ratio = 4 / 3 if target == CUTTING_BOARD_ROI else 1.0
                bounds = crop_window_for_bbox(
                    image.width,
                    image.height,
                    selected.bbox,
                    ratio,
                    context_padding=CONTEXT_PADDING,
                )
                cropped = image.crop(
                    (bounds.left, bounds.top, bounds.right, bounds.bottom)
                )
                output_size = (1024, 768) if target == CUTTING_BOARD_ROI else (1024, 1024)
                normalized = cropped.resize(output_size, Image.Resampling.LANCZOS)
                return _encode_jpeg(normalized), CropDecision(
                    "YOLO_ROI", target, len(detections)
                )
        except Exception as error:
            print(
                f"[roi-crop] fallback target={target} "
                f"error={type(error).__name__}: {error}",
                flush=True,
            )

    return _encode_jpeg(_legacy_crop(image)), CropDecision(
        "FALLBACK_BOTTOM_60", target, len(detections)
    )
