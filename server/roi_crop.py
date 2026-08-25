"""Geometry shared by YOLO crop evaluation and image preparation.

Bounding boxes are normalized top-left ``x, y, width, height``. Crop windows
are pixel ``left, top, right, bottom`` bounds suitable for Pillow and Android.
"""
from __future__ import annotations

from dataclasses import dataclass
from math import hypot
from typing import Iterable


CUTTING_BOARD_ROI = "CUTTING_BOARD_ROI"
PAN_COOKING_ROI = "PAN_COOKING_ROI"
DEFAULT_ASPECT_RATIOS = {
    CUTTING_BOARD_ROI: 4 / 3,
    PAN_COOKING_ROI: 1.0,
}


@dataclass(frozen=True)
class Detection:
    class_name: str
    confidence: float
    bbox: tuple[float, float, float, float]

    @property
    def center(self) -> tuple[float, float]:
        x, y, width, height = self.bbox
        return x + width / 2, y + height / 2


@dataclass(frozen=True)
class CropWindow:
    left: int
    top: int
    right: int
    bottom: int

    @property
    def width(self) -> int:
        return self.right - self.left

    @property
    def height(self) -> int:
        return self.bottom - self.top


def intersection_over_union(
    first: tuple[float, float, float, float],
    second: tuple[float, float, float, float],
) -> float:
    ax, ay, aw, ah = first
    bx, by, bw, bh = second
    left = max(ax, bx)
    top = max(ay, by)
    right = min(ax + aw, bx + bw)
    bottom = min(ay + ah, by + bh)
    intersection = max(0.0, right - left) * max(0.0, bottom - top)
    union = aw * ah + bw * bh - intersection
    return intersection / union if union > 0 else 0.0


def select_active_detection(
    detections: Iterable[Detection],
    expected_class: str,
    gaze_anchor: tuple[float, float] = (0.5, 0.65),
    minimum_confidence: float = 0.25,
    maximum_gaze_distance: float | None = None,
) -> Detection | None:
    """Select the expected-class object nearest the wearer gaze anchor.

    Confidence filters weak candidates but deliberately does not become the
    primary tie-break: the largest or highest-confidence pan is often the wrong
    pan in the supplied multi-pan scenes.
    """
    anchor_x, anchor_y = gaze_anchor
    candidates = []
    for detection in detections:
        distance = hypot(
            detection.center[0] - anchor_x, detection.center[1] - anchor_y
        )
        if (
            detection.class_name == expected_class
            and detection.confidence >= minimum_confidence
            and (maximum_gaze_distance is None or distance <= maximum_gaze_distance)
        ):
            candidates.append(detection)
    if not candidates:
        return None
    return min(
        candidates,
        key=lambda detection: (
            hypot(detection.center[0] - anchor_x, detection.center[1] - anchor_y),
            -detection.confidence,
        ),
    )


def crop_window_for_bbox(
    image_width: int,
    image_height: int,
    bbox: tuple[float, float, float, float],
    output_aspect_ratio: float,
    context_padding: float = 0.18,
) -> CropWindow:
    """Expand a detection around its center and preserve an output ratio."""
    if image_width <= 0 or image_height <= 0:
        raise ValueError("image dimensions must be positive")
    if output_aspect_ratio <= 0:
        raise ValueError("output aspect ratio must be positive")
    if context_padding < 0:
        raise ValueError("context padding must be non-negative")

    x, y, normalized_width, normalized_height = bbox
    if (
        x < 0
        or y < 0
        or normalized_width <= 0
        or normalized_height <= 0
        or x + normalized_width > 1
        or y + normalized_height > 1
    ):
        raise ValueError(f"bbox must be inside the image: {bbox}")

    box_width = normalized_width * image_width
    box_height = normalized_height * image_height
    center_x = (x + normalized_width / 2) * image_width
    center_y = (y + normalized_height / 2) * image_height
    crop_width = box_width * (1 + 2 * context_padding)
    crop_height = box_height * (1 + 2 * context_padding)
    if crop_width / crop_height < output_aspect_ratio:
        crop_width = crop_height * output_aspect_ratio
    else:
        crop_height = crop_width / output_aspect_ratio

    fit_scale = min(1.0, image_width / crop_width, image_height / crop_height)
    crop_width *= fit_scale
    crop_height *= fit_scale
    left = min(max(center_x - crop_width / 2, 0.0), image_width - crop_width)
    top = min(max(center_y - crop_height / 2, 0.0), image_height - crop_height)
    right = left + crop_width
    bottom = top + crop_height

    integer_width = max(1, round(crop_width))
    integer_height = max(1, round(crop_height))
    integer_left = min(max(0, round(left)), image_width - integer_width)
    integer_top = min(max(0, round(top)), image_height - integer_height)
    return CropWindow(
        left=integer_left,
        top=integer_top,
        right=integer_left + integer_width,
        bottom=integer_top + integer_height,
    )


def bottom_60_fallback(image_width: int, image_height: int) -> CropWindow:
    retained_height = max(1, image_height * 60 // 100)
    return CropWindow(0, image_height - retained_height, image_width, image_height)


def scaled_dimensions(
    width: int,
    height: int,
    max_long_edge: int = 1024,
) -> tuple[int, int]:
    if width <= 0 or height <= 0 or max_long_edge <= 0:
        raise ValueError("dimensions and max_long_edge must be positive")
    long_edge = max(width, height)
    if long_edge <= max_long_edge:
        return width, height
    scale = max_long_edge / long_edge
    return max(1, round(width * scale)), max(1, round(height * scale))
