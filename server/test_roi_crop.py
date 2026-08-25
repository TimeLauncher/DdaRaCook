import unittest

from roi_crop import (
    Detection,
    bottom_60_fallback,
    crop_window_for_bbox,
    intersection_over_union,
    scaled_dimensions,
    select_active_detection,
)


class RoiCropTest(unittest.TestCase):
    def test_active_selection_uses_gaze_not_area_or_confidence(self):
        detections = [
            Detection("PAN_COOKING_ROI", 0.99, (0.00, 0.52, 0.48, 0.38)),
            Detection("PAN_COOKING_ROI", 0.70, (0.38, 0.55, 0.40, 0.31)),
        ]
        self.assertEqual(
            select_active_detection(detections, "PAN_COOKING_ROI"),
            detections[1],
        )

    def test_active_selection_filters_class_and_confidence(self):
        detections = [
            Detection("CUTTING_BOARD_ROI", 0.90, (0.3, 0.6, 0.4, 0.3)),
            Detection("PAN_COOKING_ROI", 0.10, (0.4, 0.6, 0.3, 0.3)),
        ]
        self.assertIsNone(select_active_detection(detections, "PAN_COOKING_ROI"))

    def test_active_selection_rejects_detection_far_from_gaze(self):
        detections = [
            Detection("PAN_COOKING_ROI", 0.90, (0.75, 0.55, 0.24, 0.25)),
        ]
        self.assertIsNone(
            select_active_detection(
                detections,
                "PAN_COOKING_ROI",
                minimum_confidence=0.05,
                maximum_gaze_distance=0.30,
            )
        )

    def test_square_crop_preserves_ratio_and_contains_box_near_edge(self):
        window = crop_window_for_bbox(
            3024, 4032, (0.00, 0.55, 0.40, 0.34), 1.0, context_padding=0.18
        )
        self.assertEqual(window.width, window.height)
        self.assertEqual(window.left, 0)
        self.assertLessEqual(window.right, 3024)
        self.assertLessEqual(window.bottom, 4032)

    def test_landscape_crop_preserves_ratio_with_rounding(self):
        window = crop_window_for_bbox(
            3024, 4032, (0.3, 0.66, 0.5, 0.27), 4 / 3
        )
        self.assertAlmostEqual(window.width / window.height, 4 / 3, places=3)
        self.assertEqual(window.width, 1512)
        self.assertEqual(window.height, 1134)

    def test_iou_and_fallback_and_scaling(self):
        self.assertEqual(intersection_over_union((0, 0, 1, 1), (0, 0, 1, 1)), 1)
        self.assertEqual(intersection_over_union((0, 0, 0.2, 0.2), (0.8, 0.8, 0.2, 0.2)), 0)
        self.assertEqual(bottom_60_fallback(300, 1000).top, 400)
        self.assertEqual(scaled_dimensions(1600, 1200), (1024, 768))


if __name__ == "__main__":
    unittest.main()
