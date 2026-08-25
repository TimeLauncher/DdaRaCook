"""Local recipe crop dataset validator.

The photographs live under ignored ``test-images/``.  This validator keeps the
local manifests wired to the code-owned recipe definitions without committing
the photographs themselves.

    python server/validate_crop_eval.py
"""
from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from math import hypot, isfinite
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = ROOT / "test-images"
DATA_ROOT = TEST_ROOT / "crop-eval"
ANNOTATIONS_PATH = DATA_ROOT / "roi-annotations.json"
FIXTURES_PATH = ROOT / "app/src/main/java/com/example/myapplication/RecipeFixtures.kt"
PERSISTENCE_PATH = ROOT / "app/src/main/java/com/example/myapplication/AppPersistence.kt"
TARGET_CLASSES = {"CUTTING_BOARD_ROI", "PAN_COOKING_ROI"}
VERDICTS = {"DONE", "NOT_DONE"}
SERVER_TYPES = {
    "PRESENCE": "PRESENCE",
    "COUNT": "COUNT",
    "IDENTIFICATION": "IDENTIFY",
    "COLOR_CHANGE": "COLOR_CHANGE",
    "STATE_TRANSITION": "STATE_CHANGE",
    "TIMER_ONLY": "TIME_ONLY",
}


@dataclass(frozen=True)
class FixtureStep:
    instruction: str
    check_type: str
    check_condition: str | None
    needs_start_image: bool


def load_json(path: Path):
    with path.open(encoding="utf-8") as stream:
        return json.load(stream)


def recipe_blocks(source: str) -> dict[str, str]:
    marker = re.compile(r'Recipe\(\s*id\s*=\s*"([^"]+)"')
    matches = list(marker.finditer(source))
    return {
        match.group(1): source[match.start(): matches[index + 1].start() if index + 1 < len(matches) else len(source)]
        for index, match in enumerate(matches)
    }


def call_blocks(source: str, name: str) -> list[str]:
    blocks: list[str] = []
    needle = f"{name}("
    start = 0
    while True:
        found = source.find(needle, start)
        if found < 0:
            return blocks
        depth = 0
        in_string = False
        escaped = False
        for index in range(found + len(name), len(source)):
            char = source[index]
            if in_string:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == '"':
                    in_string = False
                continue
            if char == '"':
                in_string = True
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    blocks.append(source[found:index + 1])
                    start = index + 1
                    break
        else:
            raise ValueError(f"닫히지 않은 {name}( 블록")


def quoted(block: str, field: str) -> str:
    match = re.search(rf'{field}\s*=\s*"((?:\\.|[^"\\])*)"', block)
    if not match:
        raise ValueError(f"{field} 문자열을 찾을 수 없습니다")
    return bytes(match.group(1), "utf-8").decode("unicode_escape") if "\\" in match.group(1) else match.group(1)


def parse_fixtures() -> dict[tuple[str, int], FixtureStep]:
    source = FIXTURES_PATH.read_text(encoding="utf-8")
    parsed: dict[tuple[str, int], FixtureStep] = {}
    for recipe_id, recipe in recipe_blocks(source).items():
        for block in call_blocks(recipe, "RecipeStep"):
            order_match = re.search(r"order\s*=\s*(\d+)", block)
            type_match = re.search(r"checkType\s*=\s*CheckType\.(\w+)", block)
            start_match = re.search(r"needsStartImage\s*=\s*(true|false)", block)
            condition_match = re.search(r'checkCondition\s*=\s*(null|"((?:\\.|[^"\\])*)")', block)
            if not all((order_match, type_match, start_match, condition_match)):
                raise ValueError(f"{recipe_id} RecipeStep 필드 파싱 실패")
            app_type = type_match.group(1)
            condition = None if condition_match.group(1) == "null" else condition_match.group(2)
            parsed[(recipe_id, int(order_match.group(1)))] = FixtureStep(
                instruction=quoted(block, "instruction"),
                check_type=SERVER_TYPES[app_type],
                check_condition=condition,
                needs_start_image=start_match.group(1) == "true",
            )
    return parsed


def current_fixture_version() -> int:
    source = PERSISTENCE_PATH.read_text(encoding="utf-8")
    match = re.search(r"CURRENT_FIXTURE_VERSION\s*=\s*(\d+)", source)
    if not match:
        raise ValueError("CURRENT_FIXTURE_VERSION을 찾을 수 없습니다")
    return int(match.group(1))


def validate_annotations(crop: dict, images: list[dict], annotations: dict) -> list[str]:
    errors: list[str] = []
    if annotations.get("coordinateFormat") != "normalized_xywh":
        errors.append("roi annotations coordinateFormat must be normalized_xywh")
    annotation_rows = annotations.get("images")
    if not isinstance(annotation_rows, list):
        return errors + ["roi-annotations.json images must be an array"]

    image_by_id = {row["imageId"]: row for row in images if row.get("imageId")}
    annotation_ids: set[str] = set()
    gaze_x, gaze_y = crop.get("selectionPolicy", {}).get(
        "gazeAnchorNormalized", [0.5, 0.65]
    )
    for index, row in enumerate(annotation_rows):
        image_id = row.get("imageId")
        where = f"annotation[{index}] {image_id}"
        if not image_id or image_id in annotation_ids:
            errors.append(f"{where}: missing or duplicate imageId")
            continue
        annotation_ids.add(image_id)
        case = image_by_id.get(image_id)
        if case is None:
            errors.append(f"{where}: imageId is not in crop manifest")
            continue

        source_path = TEST_ROOT / case["source"]
        with Image.open(source_path) as source_image:
            actual_width, actual_height = source_image.size
        if (row.get("imageWidth"), row.get("imageHeight")) != (
            actual_width,
            actual_height,
        ):
            errors.append(
                f"{where}: dimensions {(row.get('imageWidth'), row.get('imageHeight'))} "
                f"!= source {(actual_width, actual_height)}"
            )

        candidates = row.get("candidates")
        if not isinstance(candidates, list) or not candidates:
            errors.append(f"{where}: candidates must be a non-empty array")
            continue
        minimum_count = 2 if case.get("multipleSameClassCandidates") else 1
        if len(candidates) < minimum_count:
            errors.append(f"{where}: expected at least {minimum_count} candidates")

        candidate_ids: set[str] = set()
        active_candidates: list[dict] = []
        distances: list[tuple[float, dict]] = []
        for candidate in candidates:
            candidate_id = candidate.get("candidateId")
            candidate_where = f"{where}/{candidate_id}"
            if not candidate_id or candidate_id in candidate_ids:
                errors.append(f"{where}: missing or duplicate candidateId {candidate_id}")
            candidate_ids.add(candidate_id)
            if candidate.get("class") != case.get("targetClass"):
                errors.append(
                    f"{candidate_where}: class={candidate.get('class')} "
                    f"!= targetClass={case.get('targetClass')}"
                )
            bbox = candidate.get("bbox")
            if (
                not isinstance(bbox, list)
                or len(bbox) != 4
                or any(not isinstance(value, (int, float)) for value in bbox)
                or any(not isfinite(value) for value in bbox)
            ):
                errors.append(f"{candidate_where}: invalid bbox")
                continue
            x, y, width, height = bbox
            if (
                x < 0
                or y < 0
                or width <= 0
                or height <= 0
                or x + width > 1
                or y + height > 1
            ):
                errors.append(f"{candidate_where}: bbox outside normalized image: {bbox}")
            if width * height < 0.02:
                errors.append(f"{candidate_where}: bbox is unexpectedly small: {bbox}")
            distances.append(
                (hypot(x + width / 2 - gaze_x, y + height / 2 - gaze_y), candidate)
            )
            if candidate.get("activeTarget") is True:
                active_candidates.append(candidate)
            if candidate.get("visibility") not in {"FULL", "PARTIAL"}:
                errors.append(f"{candidate_where}: invalid visibility")

        if len(active_candidates) != 1:
            errors.append(f"{where}: exactly one activeTarget is required")
        elif distances:
            nearest = min(distances, key=lambda item: item[0])[1]
            if nearest is not active_candidates[0]:
                errors.append(
                    f"{where}: activeTarget is not nearest to gaze anchor; "
                    f"nearest={nearest.get('candidateId')}"
                )

    for image_id in sorted(set(image_by_id) - annotation_ids):
        errors.append(f"roi annotations missing imageId: {image_id}")
    return errors


def validate() -> list[str]:
    errors: list[str] = []
    crop = load_json(DATA_ROOT / "crop-manifest.json")
    labels = load_json(DATA_ROOT / "judgment-labels.json")
    pairs = load_json(DATA_ROOT / "judgment-manifest.json")
    annotations = load_json(ANNOTATIONS_PATH)
    fixtures = parse_fixtures()

    fixture_version = current_fixture_version()
    if crop.get("fixtureVersion") != fixture_version:
        errors.append(
            f"crop manifest fixtureVersion={crop.get('fixtureVersion')} != 코드 {fixture_version}"
        )

    images = crop.get("images")
    if not isinstance(images, list):
        return ["crop-manifest.json images는 배열이어야 합니다"]
    image_ids: set[str] = set()
    sources: set[str] = set()
    for index, row in enumerate(images):
        where = f"crop[{index}]"
        image_id = row.get("imageId")
        source = row.get("source")
        if not image_id or image_id in image_ids:
            errors.append(f"{where}: imageId 누락 또는 중복 — {image_id}")
        image_ids.add(image_id)
        if not source or source in sources:
            errors.append(f"{where}: source 누락 또는 중복 — {source}")
        sources.add(source)
        if source and not (TEST_ROOT / source).is_file():
            errors.append(f"{where}: 원본 없음 — {source}")
        if row.get("targetClass") not in TARGET_CLASSES:
            errors.append(f"{where}: targetClass={row.get('targetClass')}")
        recipe_id = row.get("recipeId")
        orders = row.get("recipeStepOrders")
        if not isinstance(orders, list) or not orders:
            errors.append(f"{where}: recipeStepOrders가 비어 있습니다")
        else:
            for order in orders:
                if (recipe_id, order) not in fixtures:
                    errors.append(f"{where}: 코드에 없는 단계 {recipe_id}#{order}")

    expected_sources = {
        str(path.relative_to(TEST_ROOT)).replace("\\", "/")
        for folder in (TEST_ROOT / "1_sausagesoute", TEST_ROOT / "2_pasta")
        for path in folder.glob("*.jpg")
    }
    if sources != expected_sources:
        for source in sorted(expected_sources - sources):
            errors.append(f"crop manifest 누락 원본 — {source}")
        for source in sorted(sources - expected_sources):
            errors.append(f"crop manifest 불필요 원본 — {source}")

    pair_by_id: dict[str, dict] = {}
    for index, row in enumerate(pairs):
        pair_id = row.get("pairId")
        if not pair_id or pair_id in pair_by_id:
            errors.append(f"pair[{index}]: pairId 누락 또는 중복 — {pair_id}")
            continue
        pair_by_id[pair_id] = row
        for role in ("current", "start"):
            source = row.get(role)
            if source and source not in sources:
                errors.append(f"{pair_id}: {role}가 crop manifest에 없음 — {source}")

    label_ids: set[str] = set()
    for index, row in enumerate(labels):
        pair_id = row.get("pairId")
        where = f"label[{index}] {pair_id}"
        if not pair_id or pair_id in label_ids:
            errors.append(f"{where}: pairId 누락 또는 중복")
            continue
        label_ids.add(pair_id)
        pair = pair_by_id.get(pair_id)
        if pair is None:
            errors.append(f"{where}: judgment manifest 쌍 없음")
            continue
        if bool(pair.get("start")) != bool(row.get("hasStartImage")):
            errors.append(f"{where}: start 파일과 hasStartImage 불일치")
        if row.get("groundTruth") not in VERDICTS:
            errors.append(f"{where}: groundTruth={row.get('groundTruth')}")
        key = (row.get("recipeId"), row.get("stepOrder"))
        step = fixtures.get(key)
        if step is None:
            errors.append(f"{where}: 코드에 없는 레시피 단계 {key}")
            continue
        expected = {
            "instruction": step.instruction,
            "checkType": step.check_type,
            "checkCondition": step.check_condition,
            "hasStartImage": step.needs_start_image,
        }
        for field, value in expected.items():
            if row.get(field) != value:
                errors.append(f"{where}: {field}={row.get(field)!r}, 코드={value!r}")

    for pair_id in sorted(set(pair_by_id) - label_ids):
        errors.append(f"judgment manifest에만 있는 pairId — {pair_id}")

    errors.extend(validate_annotations(crop, images, annotations))
    return errors


def main() -> int:
    try:
        errors = validate()
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"❌ crop 평가셋을 읽을 수 없습니다: {error}")
        return 1
    if errors:
        print(f"❌ crop 평가셋 검증 실패 — {len(errors)}건")
        for error in errors:
            print(f"   {error}")
        return 1

    crop = load_json(DATA_ROOT / "crop-manifest.json")
    labels = load_json(DATA_ROOT / "judgment-labels.json")
    done = sum(row["groundTruth"] == "DONE" for row in labels)
    print(
        f"✅ crop 평가셋 정상 — 원본 {len(crop['images'])}장 · "
        f"판정 {len(labels)}쌍 (DONE {done} / NOT_DONE {len(labels) - done})"
    )
    return 0


if __name__ == "__main__":
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass
    raise SystemExit(main())
