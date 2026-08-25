"""레시피 추출 실험 하네스.

자막 조회는 유료 크레딧을 쓰지만 모델 호출은 NVIDIA 키라 무료다. 자막을 한 번만
받아 캐시해두면 모델·프롬프트를 몇 번이든 공짜로 바꿔가며 비교할 수 있다.

    python recipe_lab.py fetch <youtube-url>              자막 1회 조회 후 캐시 (크레딧 1건)
    python recipe_lab.py list                             캐시된 자막·실행 결과 목록
    python recipe_lab.py run <videoId> --label base       캐시 자막으로 추출 (크레딧 0건)
    python recipe_lab.py run <videoId> --label 70b --model meta/llama-3.3-70b-instruct
    python recipe_lab.py score <videoId> --label base     한 실행의 점수표
    python recipe_lab.py score gold:beef-brisket-pasta    정답 레시피의 점수표
    python recipe_lab.py diff <videoId> base 70b          두 실행을 나란히 비교

`score` 의 지표는 노트 `notes/recipe-extraction-mvp.md` 의 "다음 개선 우선순위"를
그대로 숫자로 옮긴 것이다. 자동 판정 비율과 재료 정합성이 핵심이다.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from dataclasses import asdict
from pathlib import Path
from typing import Any, Optional

BASE_DIR = Path(__file__).resolve().parent
TRANSCRIPT_DIR = BASE_DIR / "testdata" / "transcripts"
RUN_DIR = BASE_DIR / "testdata" / "runs"
GOLD_DIR = BASE_DIR / "testdata" / "gold"


def _load_dotenv() -> None:
    """.env 를 읽어 환경변수로 넣는다. 이미 설정된 값은 덮지 않는다."""
    env_path = BASE_DIR / ".env"
    if not env_path.exists():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        # NVIDIA_API_KEY="nvapi-..." 처럼 따옴표로 감싼 값이 섞여 있다.
        # 벗기지 않으면 따옴표가 키에 붙어 401 이 난다.
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        os.environ.setdefault(key.strip(), value)


def _write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def _read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


# ── 자막 캐시 ────────────────────────────────────────────────────────────────

def cmd_fetch(args: argparse.Namespace) -> int:
    from recipe_extractor import RecipeExtractionError, fetch_transcript, parse_youtube_video_id

    video_id = parse_youtube_video_id(args.url)
    path = TRANSCRIPT_DIR / f"{video_id}.json"
    if path.exists() and not args.force:
        print(f"이미 캐시돼 있습니다: {path.relative_to(BASE_DIR)}")
        print("다시 받으려면 --force (크레딧 1건이 더 듭니다).")
        return 0

    print(f"자막을 받는 중… ({video_id}) — 크레딧 1건을 씁니다.")
    try:
        source = fetch_transcript(args.url)
    except RecipeExtractionError as error:
        print(f"실패 (HTTP {error.http_status}): {error}")
        return 1
    _write_json(path, asdict(source))
    print(f"저장: {path.relative_to(BASE_DIR)}")
    print(f"  제목 {source.title}")
    print(f"  언어 {source.language} · {len(source.text)}자")
    return 0


def _load_transcript(video_id: str):
    from recipe_extractor import TranscriptSource

    path = TRANSCRIPT_DIR / f"{video_id}.json"
    if not path.exists():
        raise SystemExit(
            f"캐시된 자막이 없습니다: {video_id}\n"
            f"먼저 `python recipe_lab.py fetch <url>` 을 실행하세요."
        )
    return TranscriptSource(**_read_json(path))


# ── 추출 실행 ────────────────────────────────────────────────────────────────

def cmd_run(args: argparse.Namespace) -> int:
    from recipe_extractor import RecipeExtractionError, extract_recipe

    source = _load_transcript(args.video_id)
    if args.model:
        os.environ["RECIPE_EXTRACTION_MODEL"] = args.model
    if args.fewshot:
        os.environ["RECIPE_EXTRACTION_FEWSHOT"] = "1" if args.fewshot == "on" else "0"
    model = os.getenv("RECIPE_EXTRACTION_MODEL") or "(기본값)"
    few_shot = os.getenv("RECIPE_EXTRACTION_FEWSHOT", "1") not in {"0", "false", "no"}

    print(f"모델 {model} · 예시 {'있음' if few_shot else '없음'}")
    print(f"자막 {source.title} ({len(source.text)}자)")
    started = time.monotonic()
    try:
        result = extract_recipe(source)
    except RecipeExtractionError as error:
        elapsed = time.monotonic() - started
        print(f"실패 (HTTP {error.http_status}, {elapsed:.1f}초): {error}")
        return 1
    elapsed = time.monotonic() - started

    result["_meta"] = {
        "model": model,
        "fewShot": few_shot,
        "elapsedSeconds": round(elapsed, 1),
        "label": args.label,
    }
    path = RUN_DIR / f"{args.video_id}__{args.label}.json"
    _write_json(path, result)
    print(f"성공 ({elapsed:.1f}초) → {path.relative_to(BASE_DIR)}\n")
    _print_scorecard(scorecard(result), title=f"{args.video_id} / {args.label}")
    return 0


# ── 점수표 ───────────────────────────────────────────────────────────────────

def _normalize(name: str) -> str:
    """'다진 마늘' 과 '다진마늘' 을 같은 재료로 본다."""
    return re.sub(r"\s+", "", str(name)).strip().lower()


def scorecard(result: dict) -> dict:
    recipe = result.get("recipe", {})
    steps = recipe.get("steps", []) or []
    ingredients = recipe.get("ingredients", []) or []

    auto_steps = [s for s in steps if s.get("isAutoCheck")]
    unknown_amounts = [i for i in ingredients if str(i.get("amount", "")).strip() == "분량 미상"]

    ingredient_names = {_normalize(i.get("name", "")) for i in ingredients}
    ingredient_names.discard("")
    target_names: set[str] = set()
    for step in steps:
        for target in step.get("targetIngredients", []) or []:
            normalized = _normalize(target)
            if normalized:
                target_names.add(normalized)

    # 재료 목록과 단계가 서로를 가리키는지 (노트 개선 우선순위 1번)
    missing_from_list = sorted(target_names - ingredient_names)
    never_used = sorted(ingredient_names - target_names)

    # 표기만 다른 중복 (다진 마늘 / 다진마늘)
    seen: dict[str, list[str]] = {}
    for item in ingredients:
        seen.setdefault(_normalize(item.get("name", "")), []).append(str(item.get("name", "")))
    duplicates = sorted(names[0] for names in seen.values() if len(names) > 1)

    check_types: dict[str, int] = {}
    for step in steps:
        key = str(step.get("checkType", "?"))
        check_types[key] = check_types.get(key, 0) + 1

    total_seconds = 0
    for step in steps:
        policy = step.get("inspectionPolicy") or {}
        total_seconds += int(policy.get("maxExpectedSeconds") or 0)

    warnings = result.get("warnings", []) or []
    downgraded = sum(1 for w in warnings if "수동 진행" in str(w))

    return {
        "title": recipe.get("title", ""),
        "model": (result.get("_meta") or {}).get("model", "-"),
        "fewShot": (result.get("_meta") or {}).get("fewShot"),
        "elapsed": (result.get("_meta") or {}).get("elapsedSeconds"),
        "ingredients": len(ingredients),
        "steps": len(steps),
        "autoSteps": len(auto_steps),
        "autoRatio": (len(auto_steps) / len(steps)) if steps else 0.0,
        "downgraded": downgraded,
        "unknownAmounts": len(unknown_amounts),
        "parallelTimers": sum(1 for s in steps if s.get("parallelTimer")),
        "needsStartImage": sum(1 for s in steps if s.get("needsStartImage")),
        "checkTypes": check_types,
        "totalMaxExpectedSeconds": total_seconds,
        "missingFromList": missing_from_list,
        "neverUsed": never_used,
        "duplicateNames": duplicates,
        "warnings": len(warnings),
    }


def _print_scorecard(card: dict, title: str) -> None:
    ratio = f"{card['autoSteps']}/{card['steps']}"
    percent = f"{card['autoRatio'] * 100:.0f}%" if card["steps"] else "-"
    print(f"── {title} ──")
    print(f"  제목            {card['title']}")
    shot = "" if card["fewShot"] is None else (" · 예시 있음" if card["fewShot"] else " · 예시 없음")
    print(f"  모델            {card['model']}{shot}", end="")
    print(f"  ({card['elapsed']}초)" if card["elapsed"] else "")
    print(f"  재료 / 단계     {card['ingredients']}개 / {card['steps']}단계")
    print(f"  자동 판정       {ratio} ({percent})   ← 핵심 지표")
    print(f"  수동 강등       {card['downgraded']}단계")
    print(f"  분량 미상       {card['unknownAmounts']}/{card['ingredients']}")
    print(f"  기준 사진 사용  {card['needsStartImage']}단계")
    print(f"  병렬 타이머     {card['parallelTimers']}개")
    print(f"  판정 유형       {card['checkTypes']}")
    print(f"  예상 시간 합계  {card['totalMaxExpectedSeconds']}초")
    if card["missingFromList"]:
        print(f"  ⚠ 단계에만 있음 {card['missingFromList']}")
    if card["neverUsed"]:
        print(f"  ⚠ 아무 단계도 안 씀 {card['neverUsed']}")
    if card["duplicateNames"]:
        print(f"  ⚠ 표기 중복      {card['duplicateNames']}")
    print()


def _resolve_result(target: str, label: Optional[str]) -> tuple[dict, str]:
    if target.startswith("gold:"):
        path = GOLD_DIR / f"{target[5:]}.json"
        if not path.exists():
            raise SystemExit(f"정답 파일이 없습니다: {path}")
        return _read_json(path), target
    if not label:
        raise SystemExit("--label 이 필요합니다.")
    path = RUN_DIR / f"{target}__{label}.json"
    if not path.exists():
        raise SystemExit(f"실행 결과가 없습니다: {path}")
    return _read_json(path), f"{target} / {label}"


def cmd_score(args: argparse.Namespace) -> int:
    result, title = _resolve_result(args.target, args.label)
    _print_scorecard(scorecard(result), title=title)
    if args.gold:
        gold, gold_title = _resolve_result(f"gold:{args.gold}", None)
        _print_scorecard(scorecard(gold), title=f"{gold_title} (정답)")
    return 0


def cmd_diff(args: argparse.Namespace) -> int:
    left, left_title = _resolve_result(args.video_id, args.label_a)
    right, right_title = _resolve_result(args.video_id, args.label_b)
    a, b = scorecard(left), scorecard(right)

    rows = [
        ("재료 수", a["ingredients"], b["ingredients"]),
        ("단계 수", a["steps"], b["steps"]),
        ("자동 판정", f"{a['autoSteps']}/{a['steps']}", f"{b['autoSteps']}/{b['steps']}"),
        ("수동 강등", a["downgraded"], b["downgraded"]),
        ("분량 미상", a["unknownAmounts"], b["unknownAmounts"]),
        ("기준 사진", a["needsStartImage"], b["needsStartImage"]),
        ("병렬 타이머", a["parallelTimers"], b["parallelTimers"]),
        ("예상 시간 합", a["totalMaxExpectedSeconds"], b["totalMaxExpectedSeconds"]),
        ("재료 누락", len(a["missingFromList"]), len(b["missingFromList"])),
        ("미사용 재료", len(a["neverUsed"]), len(b["neverUsed"])),
        ("소요 시간", f"{a['elapsed']}초", f"{b['elapsed']}초"),
    ]
    width = max(
        [len(args.label_a), len(args.label_b)]
        + [len(str(r[1])) for r in rows]
        + [len(str(r[2])) for r in rows]
    ) + 2
    shot = {True: "예시O", False: "예시X", None: "-"}
    print(f"{'':16}{args.label_a:>{width}}{args.label_b:>{width}}")
    print(f"{'':16}{a['model'].split('/')[-1][:width - 1]:>{width}}{b['model'].split('/')[-1][:width - 1]:>{width}}")
    print(f"{'':16}{shot[a['fewShot']]:>{width}}{shot[b['fewShot']]:>{width}}")
    print("─" * (16 + width * 2))
    for name, left_value, right_value in rows:
        mark = "" if str(left_value) == str(right_value) else "  ←"
        print(f"{name:16}{str(left_value):>{width}}{str(right_value):>{width}}{mark}")
    return 0


def cmd_list(_args: argparse.Namespace) -> int:
    print("캐시된 자막:")
    files = sorted(TRANSCRIPT_DIR.glob("*.json")) if TRANSCRIPT_DIR.exists() else []
    if not files:
        print("  (없음) — python recipe_lab.py fetch <url>")
    for path in files:
        data = _read_json(path)
        print(f"  {path.stem:14} {data.get('title', '')[:40]} ({len(data.get('text', ''))}자)")

    print("\n실행 결과:")
    runs = sorted(RUN_DIR.glob("*.json")) if RUN_DIR.exists() else []
    if not runs:
        print("  (없음) — python recipe_lab.py run <videoId> --label base")
    for path in runs:
        meta = (_read_json(path).get("_meta") or {})
        print(f"  {path.stem:28} {meta.get('model', '-')} ({meta.get('elapsedSeconds')}초)")

    print("\n정답 레시피:")
    golds = sorted(GOLD_DIR.glob("*.json")) if GOLD_DIR.exists() else []
    for path in golds:
        print(f"  gold:{path.stem}")
    return 0


def main(argv: Optional[list[str]] = None) -> int:
    _load_dotenv()
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    p_fetch = sub.add_parser("fetch", help="자막을 1회 조회해 캐시 (크레딧 1건)")
    p_fetch.add_argument("url")
    p_fetch.add_argument("--force", action="store_true", help="캐시가 있어도 다시 받는다")
    p_fetch.set_defaults(func=cmd_fetch)

    p_run = sub.add_parser("run", help="캐시된 자막으로 추출 (크레딧 0건)")
    p_run.add_argument("video_id")
    p_run.add_argument("--label", required=True, help="실행 이름 (base, 70b, fewshot ...)")
    p_run.add_argument("--model", help="RECIPE_EXTRACTION_MODEL 덮어쓰기")
    p_run.add_argument("--fewshot", choices=["on", "off"], help="기준 예시 투입 여부 (기본 on)")
    p_run.set_defaults(func=cmd_run)

    p_score = sub.add_parser("score", help="점수표 출력")
    p_score.add_argument("target", help="videoId 또는 gold:<name>")
    p_score.add_argument("--label")
    p_score.add_argument("--gold", help="나란히 볼 정답 이름 (beef-brisket-pasta)")
    p_score.set_defaults(func=cmd_score)

    p_diff = sub.add_parser("diff", help="같은 영상의 두 실행 비교")
    p_diff.add_argument("video_id")
    p_diff.add_argument("label_a")
    p_diff.add_argument("label_b")
    p_diff.set_defaults(func=cmd_diff)

    p_list = sub.add_parser("list", help="캐시·실행·정답 목록")
    p_list.set_defaults(func=cmd_list)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
