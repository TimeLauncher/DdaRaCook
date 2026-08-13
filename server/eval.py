"""
T2-2 · 평가 스크립트

    python eval.py --selftest                  # 지표 계산 검산 (사진·API 불필요)
    python eval.py --backend mock              # 하네스 관통 (API 호출 없음)
    python eval.py                             # 실제 평가
    python eval.py --mode both                 # 🔬 T2-4 · 1장 vs 2장 비교
    python eval.py --srgb                      # 🎨 P3→sRGB 변환 후 평가
    python eval.py --crop-top 40 --long-edge 1024   # 제품과 동일한 전처리

명세서 12절의 목표는 **"정확도 80%" 하나가 아니라 둘**입니다.

    ① A등급 정확도 80% 이상
    ② 확신 있는 오답(오탐) 0회

그래서 이 스크립트는 전체 정확도 하나를 뽑고 끝내지 않고, 등급별로 나누고
오탐을 따로 셉니다. 둘을 합치면 "정확도 85%"가 나오면서 오탐 4건이 숨습니다.

──────────────────────────────────────────────────────────
왜 실제 요리 사진 없이 먼저 만드는가
──────────────────────────────────────────────────────────
지표 계산 코드는 실제 사진으로는 **검증할 수 없습니다.** 30쌍을 돌려 "정확도
61%"가 나와도 그 61%가 맞게 계산된 값인지 확인할 방법이 없습니다.
정답과 응답을 둘 다 아는 상태로 돌려야 검산이 됩니다 → `--selftest`.

그리고 이 파일을 먼저 쓰는 진짜 이유는 **labels.json 스키마를 확정시키는 것**
입니다. 계획서 기준 촬영 3h / 라벨링 4h 로 라벨링이 더 비싼데, 30쌍을 라벨링한
뒤에 필드가 빠진 걸 발견하면 그 4시간이 날아갑니다.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import statistics
import sys
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

from dotenv import load_dotenv

load_dotenv()

import imageprep
import prompts
from judge import VERDICTS, JudgeError, Verdict, get_judge

# ══════════════════════════════════════════════════════════
# 1. 등급 (명세서 5.1)
# ══════════════════════════════════════════════════════════
# DoD 가 "A등급 80%" 이므로 등급 매핑이 없으면 DoD 를 계산할 수 없습니다.
# C등급은 MVP 제외라 checkType 자체가 없습니다 — 라벨에 나타나면 안 됩니다.
GRADE = {
    "PRESENCE": "A",       # 존재 여부
    "IDENTIFY": "A",       # 재료 식별
    "COUNT": "B",          # 개수
    "COLOR_CHANGE": "B",   # 큰 색상 변화
    "STATE_CHANGE": "B",   # 상태 전환
    "TIME_ONLY": "-",      # 사진으로 판정하지 않음 → 등급 밖
}

DOD_A_ACCURACY = 0.80      # 명세서 12절 #3
DOD_FALSE_ACCEPT = 0       # 확신 있는 오답 0회


# ══════════════════════════════════════════════════════════
# 2. 라벨 스키마
# ══════════════════════════════════════════════════════════
@dataclass
class Label:
    """`labels.json` 한 줄.

    ⚠️ 이전 초안의 `hasStart` 를 **`hasStartImage`** 로 바꿨습니다.

        hasStartImage : 시작 이미지 파일이 존재하는가 — **사실**
        --mode        : 그걸 실제로 모델에 보낼 것인가 — **실행 옵션**

    둘을 한 필드에 섞으면 T2-4(1장 vs 2장 비교)가 불가능해집니다. 같은 쌍을
    양쪽으로 돌려서 비교하는 게 그 실험인데, 라벨에 박혀 있으면 못 바꿉니다.

    그리고 `ambiguous` 를 추가했습니다. 계획서는 "애매한 쌍은 groundTruth 를
    억지로 정하지 말고 제외하거나 별도 표기"하라고 했는데 정작 표기할 필드가
    없었습니다. 라벨링 중에 반드시 마주치는 상황입니다.
    """
    pairId: str
    checkType: str
    checkCondition: str
    instruction: str
    groundTruth: Optional[str] = None
    recipeId: str = ""
    stepOrder: Optional[int] = None
    elapsedSeconds: int = 0
    hasStartImage: bool = False
    ambiguous: bool = False
    note: str = ""                 # 촬영 순간의 실제 상태 메모 (오답 분석의 근거)

    start_path: str = field(default="", repr=False)
    current_path: str = field(default="", repr=False)

    @property
    def grade(self) -> str:
        return GRADE.get(self.checkType, "?")


REQUIRED = ("pairId", "checkType", "checkCondition", "instruction")


def load_labels(labels_path: str, images_dir: str) -> list[Label]:
    """labels.json 을 읽고 **엄격하게** 검증한다.

    관대하게 넘어가면 20쌍을 다 돌린 뒤에야 오타를 발견합니다.
    여기서 걸리는 게 훨씬 쌉니다.
    """
    with open(labels_path, encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, list):
        raise SystemExit(f"❌ {labels_path} 최상위는 배열이어야 합니다")

    labels, errors, seen = [], [], set()
    for i, row in enumerate(data):
        where = f"[{i}] {row.get('pairId', '(pairId 없음)')}"

        missing = [k for k in REQUIRED if not row.get(k)]
        if missing:
            errors.append(f"{where}: 필수 필드 누락 {missing}")
            continue

        pid = row["pairId"]
        if pid in seen:
            errors.append(f"{where}: pairId 중복")
            continue
        seen.add(pid)

        if row["checkType"] not in GRADE:
            errors.append(f"{where}: 알 수 없는 checkType={row['checkType']} "
                          f"(가능: {', '.join(GRADE)})")

        gt = row.get("groundTruth")
        ambiguous = bool(row.get("ambiguous", False))
        if gt is None and not ambiguous:
            errors.append(f"{where}: groundTruth 가 없습니다. "
                          f"판단이 어려우면 ambiguous=true 로 표시하세요")
        elif gt is not None and gt not in VERDICTS:
            errors.append(f"{where}: groundTruth={gt} (가능: {', '.join(sorted(VERDICTS))})")

        known = {f.name for f in Label.__dataclass_fields__.values()}
        for k in row:
            if k not in known:
                errors.append(f"{where}: 모르는 필드 '{k}' — 오타인지 확인하세요")

        lb = Label(**{k: v for k, v in row.items() if k in known})
        lb.current_path = os.path.join(images_dir, f"{pid}_current.jpg")
        lb.start_path = os.path.join(images_dir, f"{pid}_start.jpg")

        if not imageprep.exists(lb.current_path):
            errors.append(f"{where}: 현재 이미지 없음 → {lb.current_path}")
        if lb.hasStartImage and not imageprep.exists(lb.start_path):
            errors.append(f"{where}: hasStartImage=true 인데 파일 없음 → {lb.start_path}")
        if not lb.hasStartImage and imageprep.exists(lb.start_path):
            errors.append(f"{where}: 파일은 있는데 hasStartImage=false 입니다")

        labels.append(lb)

    if errors:
        print(f"❌ labels.json 검증 실패 — {len(errors)}건\n")
        for e in errors:
            print("   " + e)
        raise SystemExit(1)

    if not labels:
        raise SystemExit("❌ 라벨이 비어 있습니다")
    return labels


# ══════════════════════════════════════════════════════════
# 3. 실행
# ══════════════════════════════════════════════════════════
@dataclass
class Outcome:
    """판정 1건의 결과 (라벨 + 응답)."""
    label: Label
    verdict: str
    reasonCode: str
    latencyMs: int
    parsed: bool
    raw: str
    sent_start: bool          # 이번 실행에서 실제로 2장을 보냈는가
    error: str = ""

    @property
    def correct(self) -> bool:
        return self.verdict == self.label.groundTruth

    @property
    def false_accept(self) -> bool:
        """🚨 오탐 — 완료가 아닌데 DONE. 명세서 12절 목표는 0회."""
        return self.label.groundTruth != "DONE" and self.verdict == "DONE"

    @property
    def false_reject(self) -> bool:
        """미탐 — 완료인데 NOT_DONE. (CANNOT_TELL 은 여기 넣지 않는다)"""
        return self.label.groundTruth == "DONE" and self.verdict == "NOT_DONE"


def run(labels: list[Label], judge, mode: str, srgb: bool,
        long_edge: Optional[int], verbose: bool,
        crop_top: Optional[int] = None) -> list[Outcome]:
    """라벨 전체를 한 번 돌린다. mode 는 '1' 또는 '2'."""
    getattr(judge, "reset", lambda: None)()   # mock 의 순환 위치를 매 실행 초기화

    prep = dict(srgb=srgb, long_edge=long_edge, crop_top=crop_top)
    out: list[Outcome] = []

    for i, lb in enumerate(labels, 1):
        send_start = (mode == "2") and lb.hasStartImage
        try:
            current_b64 = imageprep.prepare_b64(lb.current_path, **prep)
            start_b64 = (imageprep.prepare_b64(lb.start_path, **prep)
                         if send_start else None)
        except OSError as e:
            out.append(Outcome(lb, "", "", 0, False, "", send_start,
                               error=f"이미지 읽기 실패: {e}"))
            continue

        user_text = prompts.build_user_text(
            instruction=lb.instruction,
            check_type=lb.checkType,
            check_condition=lb.checkCondition,
            elapsed_seconds=lb.elapsedSeconds,
            has_start=send_start,
            step_order=lb.stepOrder,
        )

        try:
            v: Verdict = judge.judge(prompts.SYSTEM_PROMPT, user_text,
                                     start_b64, current_b64)
        except JudgeError as e:
            # 🚨 호출 실패는 CANNOT_TELL 이 아닙니다 (CONTRACT.md §5).
            #    지표에 섞으면 와이파이 문제가 정확도 저하로 둔갑합니다.
            out.append(Outcome(lb, "", "", 0, False, "", send_start,
                               error=f"{type(e).__name__}: {e}"))
            print(f"  [{i}/{len(labels)}] {lb.pairId:<24} ❌ {type(e).__name__}")
            continue

        o = Outcome(lb, v.verdict, v.reasonCode, v.latencyMs,
                    v.parsed, v.raw, send_start)
        out.append(o)

        if verbose:
            mark = "✅" if o.correct else ("🚨" if o.false_accept else "❌")
            print(f"  [{i}/{len(labels)}] {lb.pairId:<24} {mark} "
                  f"정답 {lb.groundTruth:<12} 판정 {o.verdict:<12} {o.latencyMs:>6}ms")

    return out


# ══════════════════════════════════════════════════════════
# 4. 지표
# ══════════════════════════════════════════════════════════
@dataclass
class Metrics:
    n: int = 0
    correct: int = 0
    false_accept: int = 0
    false_reject: int = 0
    cannot_tell: int = 0
    unparsed: int = 0
    errors: int = 0
    lat_median: float = 0.0
    lat_max: int = 0

    @property
    def accuracy(self) -> float:
        return self.correct / self.n if self.n else 0.0

    @property
    def cannot_tell_rate(self) -> float:
        return self.cannot_tell / self.n if self.n else 0.0


def compute(outcomes: list[Outcome]) -> Metrics:
    """지표 계산. **여기가 --selftest 가 검산하는 대상입니다.**

    호출 실패(error)는 분모에서 뺍니다. 모델이 틀린 게 아니라 못 물어본 것이라,
    정확도에 섞으면 네트워크 사고가 성능 저하로 보입니다.
    """
    m = Metrics()
    graded = [o for o in outcomes if not o.error]
    m.errors = len(outcomes) - len(graded)
    m.n = len(graded)
    if not m.n:
        return m

    for o in graded:
        m.correct += o.correct
        m.false_accept += o.false_accept
        m.false_reject += o.false_reject
        m.cannot_tell += (o.verdict == "CANNOT_TELL")
        m.unparsed += (not o.parsed)

    lat = [o.latencyMs for o in graded]
    m.lat_median = statistics.median(lat)
    m.lat_max = max(lat)
    return m


# ══════════════════════════════════════════════════════════
# 5. 리포트
# ══════════════════════════════════════════════════════════
def _pct(x: float) -> str:
    return f"{100 * x:5.1f}%"


def report(outcomes: list[Outcome], title: str) -> Metrics:
    m = compute(outcomes)
    graded = [o for o in outcomes if not o.error]

    print(f"\n{'═' * 66}")
    print(f"  {title}")
    print("═" * 66)
    print(f"  n={m.n}  정확도 {_pct(m.accuracy)}  "
          f"CANNOT_TELL {_pct(m.cannot_tell_rate)}  파싱실패 {m.unparsed}"
          + (f"  호출실패 {m.errors}" if m.errors else ""))
    print(f"  지연  중앙 {m.lat_median:.0f}ms / 최대 {m.lat_max}ms")

    # ── DoD (명세서 12절 #3) ──────────────────────────────
    a = compute([o for o in graded if o.label.grade == "A"])
    ok_a = a.n and a.accuracy >= DOD_A_ACCURACY
    ok_fa = m.false_accept <= DOD_FALSE_ACCEPT
    print("\n  ── 명세서 12절 #3 (DoD) ──")
    print(f"  {'✅' if ok_a else '❌'} A등급 정확도 {_pct(a.accuracy)} "
          f"({a.correct}/{a.n})   목표 {_pct(DOD_A_ACCURACY)}")
    print(f"  {'✅' if ok_fa else '🚨'} 확신 있는 오답 {m.false_accept}건"
          f"{'':<10}목표 {DOD_FALSE_ACCEPT}건")
    print(f"     (참고) 미탐 {m.false_reject}건 — 안전한 실패라 목표에는 없음")

    # ── 등급별 / 유형별 ───────────────────────────────────
    print("\n  ── 등급별 ──")
    for g in ("A", "B", "-"):
        rows = [o for o in graded if o.label.grade == g]
        if not rows:
            continue
        gm = compute(rows)
        print(f"  {g}등급  n={gm.n:<3} 정확도 {_pct(gm.accuracy)}  "
              f"오탐 {gm.false_accept}  미탐 {gm.false_reject}")

    print("\n  ── checkType별 ──")
    for ct in sorted({o.label.checkType for o in graded}):
        rows = [o for o in graded if o.label.checkType == ct]
        cm = compute(rows)
        flag = "  🚨" if cm.false_accept else ""
        print(f"  {ct:<14}({GRADE.get(ct, '?')}) n={cm.n:<3} "
              f"정확도 {_pct(cm.accuracy)}  오탐 {cm.false_accept}{flag}")

    # ── 혼동 행렬 ─────────────────────────────────────────
    print("\n  ── 혼동 행렬 (행=정답, 열=판정) ──")
    order = ["DONE", "NOT_DONE", "CANNOT_TELL"]
    cnt = Counter((o.label.groundTruth, o.verdict) for o in graded)
    print("               " + "".join(f"{c:>13}" for c in order))
    for gt in order:
        cells = []
        for pv in order:
            n = cnt[(gt, pv)]
            mark = "🚨" if (gt != "DONE" and pv == "DONE" and n) else "  "
            cells.append(f"{mark}{n:>11}")
        print(f"  {gt:<12}" + "".join(cells))

    # ── 오답 목록 (T2-3 프롬프트 개선의 출발점) ────────────
    wrong = [o for o in graded if not o.correct]
    if wrong:
        print(f"\n  ── 오답 {len(wrong)}건 ──")
        wrong.sort(key=lambda o: (not o.false_accept, o.label.pairId))
        for o in wrong:
            tag = "🚨오탐" if o.false_accept else ("미탐" if o.false_reject else "  ")
            print(f"  {tag} {o.label.pairId:<24} {o.label.checkType:<14}"
                  f" 정답 {o.label.groundTruth:<12} → {o.verdict}")
            if o.label.note:
                print(f"        메모: {o.label.note}")
            if not o.parsed:
                print(f"        ⚠️ 파싱 실패 raw: {o.raw[:120]}")

    skipped = [o for o in outcomes if o.error]
    if skipped:
        print(f"\n  ── 호출 실패 {len(skipped)}건 (분모에서 제외) ──")
        for o in skipped:
            print(f"  {o.label.pairId:<24} {o.error[:80]}")

    return m


def compare(a: list[Outcome], b: list[Outcome]) -> None:
    """🔬 T2-4 · 2장 모드와 1장 모드를 비교한다.

    ⚠️ 전체로 비교하면 안 됩니다. start 이미지가 없는 쌍(PRESENCE/IDENTIFY)은
    두 모드에서 **완전히 동일한 입력**이라 차이를 희석시킬 뿐입니다.
    실제로 달라진 부분집합만 따로 봐야 F4-7 가설의 답이 나옵니다.
    """
    ma, mb = compute(a), compute(b)
    print(f"\n{'═' * 66}")
    print("  🔬 T2-4 · 1장 vs 2장 (명세서 F4-7 가설 검증)")
    print("═" * 66)
    print(f"  {'':<10}{'n':>5}{'정확도':>10}{'오탐':>7}{'미탐':>7}{'지연중앙':>10}")
    for name, m in (("2장", ma), ("1장", mb)):
        print(f"  {name:<10}{m.n:>5}{_pct(m.accuracy):>10}"
              f"{m.false_accept:>7}{m.false_reject:>7}{m.lat_median:>9.0f}ms")

    sub_a = [o for o in a if o.label.hasStartImage and not o.error]
    sub_b = [o for o in b if o.label.hasStartImage and not o.error]
    if not sub_a:
        print("\n  ⚠️ hasStartImage=true 인 쌍이 없어 비교가 성립하지 않습니다.")
        return

    sa, sb = compute(sub_a), compute(sub_b)
    print(f"\n  ── 실제로 입력이 달라진 부분집합만 (n={sa.n}) ──")
    print(f"  2장  정확도 {_pct(sa.accuracy)}  오탐 {sa.false_accept}  "
          f"지연중앙 {sa.lat_median:.0f}ms")
    print(f"  1장  정확도 {_pct(sb.accuracy)}  오탐 {sb.false_accept}  "
          f"지연중앙 {sb.lat_median:.0f}ms")

    d = sa.accuracy - sb.accuracy
    saved = sb.lat_median - sa.lat_median
    print(f"\n  정확도 차이 {d * 100:+.1f}%p / 2장이 {-saved:+.0f}ms 더 느림")
    if d <= 0:
        print("  → F4-7 가설(2장이 낫다)이 지지되지 않습니다. "
              "startImage 생략을 2번과 협의하세요 (계획서 610줄).")
    else:
        print("  → 2장이 유리합니다. 지연 비용을 감수할 값어치가 있는지 확인하세요.")

    flips = [(x, y) for x, y in zip(sub_a, sub_b) if x.verdict != y.verdict]
    if flips:
        print(f"\n  ── 판정이 뒤집힌 {len(flips)}건 ──")
        for x, y in flips:
            print(f"  {x.label.pairId:<24} 정답 {x.label.groundTruth:<12}"
                  f" 2장 {x.verdict:<12} 1장 {y.verdict}")


# ══════════════════════════════════════════════════════════
# 6. CSV — 버전별 추이 비교용 (계획서 379줄)
# ══════════════════════════════════════════════════════════
CSV_COLUMNS = ["ts", "backend", "model", "promptVersion", "mode", "srgb",
               "cropTop", "longEdge", "n", "accuracy", "aGradeN", "aGradeAccuracy",
               "falseAccept", "falseReject", "cannotTellRate", "unparsed",
               "errors", "latMedian", "latMax", "note"]


def append_csv(path: str, m: Metrics, a: Metrics, args, judge, mode: str) -> None:
    new = not os.path.exists(path)
    with open(path, "a", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=CSV_COLUMNS)
        if new:
            w.writeheader()
        w.writerow({
            "ts": datetime.now().isoformat(timespec="seconds"),
            "backend": judge.name, "model": judge.model,
            "promptVersion": prompts.PROMPT_VERSION,
            "mode": mode, "srgb": int(args.srgb),
            "cropTop": args.crop_top or "", "longEdge": args.long_edge or "",
            "n": m.n, "accuracy": round(m.accuracy, 4),
            "aGradeN": a.n, "aGradeAccuracy": round(a.accuracy, 4),
            "falseAccept": m.false_accept, "falseReject": m.false_reject,
            "cannotTellRate": round(m.cannot_tell_rate, 4),
            "unparsed": m.unparsed, "errors": m.errors,
            "latMedian": round(m.lat_median), "latMax": m.lat_max,
            "note": args.note,
        })
    print(f"\n  📄 CSV 누적: {path}")


def dump_rows(path: str, outcomes: list[Outcome], mode: str) -> None:
    """쌍별 상세. T2-3 에서 오답을 분류할 때 씁니다."""
    cols = ["pairId", "checkType", "grade", "mode", "sentStart", "groundTruth",
            "verdict", "reasonCode", "correct", "falseAccept", "latencyMs",
            "parsed", "note", "raw"]
    with open(path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        for o in outcomes:
            w.writerow({
                "pairId": o.label.pairId, "checkType": o.label.checkType,
                "grade": o.label.grade, "mode": mode,
                "sentStart": int(o.sent_start),
                "groundTruth": o.label.groundTruth, "verdict": o.verdict,
                "reasonCode": o.reasonCode, "correct": int(o.correct),
                "falseAccept": int(o.false_accept), "latencyMs": o.latencyMs,
                "parsed": int(o.parsed), "note": o.label.note,
                "raw": o.raw[:500],
            })
    print(f"  📄 쌍별 상세: {path}")


# ══════════════════════════════════════════════════════════
# 7. 자기검증 — 사진도 API 도 필요 없다
# ══════════════════════════════════════════════════════════
def _fake(check_type: str, gt: str, verdict: str, parsed: bool = True) -> Outcome:
    lb = Label(pairId=f"t_{gt}_{verdict}", checkType=check_type,
               checkCondition="c", instruction="i", groundTruth=gt)
    return Outcome(lb, verdict, "OTHER", 100, parsed, "", False)


def selftest() -> int:
    """손으로 계산한 값과 compute() 를 대조한다.

    실제 사진으로는 절대 할 수 없는 검증입니다. 정답과 응답을 둘 다 지정해야
    "오탐이 정확히 3건이어야 한다"를 말할 수 있습니다.
    """
    cases = []

    # ── ① 전부 정답 ───────────────────────────────────────
    rows = [_fake("PRESENCE", "DONE", "DONE"),
            _fake("PRESENCE", "NOT_DONE", "NOT_DONE"),
            _fake("COLOR_CHANGE", "CANNOT_TELL", "CANNOT_TELL")]
    cases.append(("전부 정답", rows, dict(n=3, correct=3, false_accept=0,
                                       false_reject=0, cannot_tell=1)))

    # ── ② 오탐 — 완료가 아닌데 DONE ────────────────────────
    #    NOT_DONE→DONE 과 CANNOT_TELL→DONE 둘 다 오탐이다.
    rows = [_fake("PRESENCE", "NOT_DONE", "DONE"),
            _fake("PRESENCE", "CANNOT_TELL", "DONE"),
            _fake("PRESENCE", "DONE", "DONE")]
    cases.append(("오탐 2건", rows, dict(n=3, correct=1, false_accept=2,
                                      false_reject=0, cannot_tell=0)))

    # ── ③ 미탐과 CANNOT_TELL 은 다르다 ─────────────────────
    #    DONE→NOT_DONE 만 미탐. DONE→CANNOT_TELL 은 오답이지만 안전한 실패다.
    rows = [_fake("COLOR_CHANGE", "DONE", "NOT_DONE"),
            _fake("COLOR_CHANGE", "DONE", "CANNOT_TELL")]
    cases.append(("미탐 1 · CANNOT_TELL 1", rows,
                  dict(n=2, correct=0, false_accept=0, false_reject=1,
                       cannot_tell=1)))

    # ── ④ 호출 실패는 분모에서 빠진다 ──────────────────────
    bad = _fake("PRESENCE", "DONE", "")
    bad.error = "JudgeTimeout"
    rows = [_fake("PRESENCE", "DONE", "DONE"), bad]
    cases.append(("호출 실패 제외", rows, dict(n=1, correct=1, errors=1)))

    # ── ⑤ 파싱 실패 집계 ──────────────────────────────────
    rows = [_fake("PRESENCE", "DONE", "CANNOT_TELL", parsed=False),
            _fake("PRESENCE", "DONE", "DONE")]
    cases.append(("파싱 실패 1건", rows, dict(n=2, correct=1, unparsed=1)))

    failed = 0
    print("=" * 66)
    print("  eval.py 자기검증 — 지표 계산이 맞는가 (사진·API 불필요)")
    print("=" * 66)
    for name, rows, expect in cases:
        m = compute(rows)
        bad_keys = {k: (getattr(m, k), v) for k, v in expect.items()
                    if getattr(m, k) != v}
        if bad_keys:
            failed += 1
            print(f"  ❌ {name}")
            for k, (got, want) in bad_keys.items():
                print(f"       {k}: 계산 {got} ≠ 기대 {want}")
        else:
            print(f"  ✅ {name:<24} {expect}")

    # ── ⑥ 등급 매핑이 명세서 5.1 과 맞는가 ─────────────────
    expect_grade = {"PRESENCE": "A", "IDENTIFY": "A", "COUNT": "B",
                    "COLOR_CHANGE": "B", "STATE_CHANGE": "B"}
    wrong = {k: GRADE.get(k) for k, v in expect_grade.items() if GRADE.get(k) != v}
    if wrong:
        failed += 1
        print(f"  ❌ 등급 매핑이 명세서 5.1 과 다릅니다: {wrong}")
    else:
        print(f"  ✅ {'등급 매핑 (명세서 5.1)':<24} A=존재·식별 / B=개수·색·상태")

    # ── ⑦ DoD 판정선 ─────────────────────────────────────
    rows = ([_fake("PRESENCE", "DONE", "DONE")] * 4
            + [_fake("PRESENCE", "DONE", "NOT_DONE")])
    a = compute(rows)
    if abs(a.accuracy - 0.8) > 1e-9:
        failed += 1
        print(f"  ❌ DoD 경계: 4/5 가 {a.accuracy} 로 계산됨 (0.8 이어야 함)")
    else:
        print(f"  ✅ {'DoD 경계 4/5 = 80.0%':<24} 목표선에 정확히 걸린다")

    print("=" * 66)
    print("  " + (f"❌ {failed}건 실패" if failed else "✅ 전부 통과"))
    return 1 if failed else 0


# ══════════════════════════════════════════════════════════
# 8. main
# ══════════════════════════════════════════════════════════
def main() -> int:
    p = argparse.ArgumentParser(description="T2-2 평가 스크립트")
    p.add_argument("--labels", default="testdata/labels.json")
    p.add_argument("--images", default=None,
                   help="기본값: labels.json 과 같은 폴더의 images/")
    p.add_argument("--backend", default=None,
                   help="nemotron | openai | mock")
    p.add_argument("--mode", default="2", choices=("1", "2", "both"),
                   help="모델에 보낼 이미지 수. both = T2-4 비교 실험")
    p.add_argument("--srgb", action="store_true",
                   help="🎨 Display P3 → sRGB 변환 후 평가 (T2-3 A/B)")
    p.add_argument("--crop-top", type=int, default=None, metavar="PCT",
                   help="위쪽 PCT%% 를 버린 뒤 평가 (제품 규격: --crop-top 40). "
                        "--long-edge 보다 먼저 적용됩니다")
    p.add_argument("--long-edge", type=int, default=None,
                   help="긴 변 축소 후 평가. 생략하면 파일을 그대로 보냅니다")
    p.add_argument("--include-ambiguous", action="store_true",
                   help="사람도 판단 어려운 쌍까지 집계 (기본은 제외)")
    p.add_argument("--csv", default="testdata/eval_history.csv")
    p.add_argument("--dump", default=None, help="쌍별 상세 CSV 경로")
    p.add_argument("--note", default="", help="CSV 에 남길 메모")
    p.add_argument("--quiet", action="store_true")
    p.add_argument("--selftest", action="store_true",
                   help="지표 계산 검산만 하고 끝낸다 (사진·API 불필요)")
    args = p.parse_args()

    if args.selftest:
        return selftest()

    images_dir = args.images or os.path.join(
        os.path.dirname(os.path.abspath(args.labels)), "images")
    if not os.path.exists(args.labels):
        print(f"❌ 라벨 파일이 없습니다: {args.labels}\n"
              f"   스키마는 testdata/labels.example.json 을 보세요.\n"
              f"   지표 계산만 확인하려면: python eval.py --selftest")
        return 1

    labels = load_labels(args.labels, images_dir)
    usable = [lb for lb in labels
              if args.include_ambiguous or not lb.ambiguous]
    dropped = len(labels) - len(usable)

    try:
        judge = get_judge(args.backend)
    except JudgeError as e:
        print(f"❌ 어댑터 생성 실패: {e}")
        return 1

    print("=" * 66)
    print(f"  labels  : {args.labels}  ({len(usable)}쌍"
          + (f", 애매 {dropped}쌍 제외" if dropped else "") + ")")
    print(f"  backend : {judge.name} · {judge.model}")
    print(f"  prompt  : {prompts.PROMPT_VERSION}")
    print(f"  이미지   : mode={args.mode}"
          + (f" · 위 {args.crop_top}% 제거" if args.crop_top else "")
          + (f" · 긴변 {args.long_edge}" if args.long_edge
             else ("" if args.crop_top else " · 원본 그대로"))
          + (" · sRGB 변환" if args.srgb else " · 색공간 변환 없음(P3)"))
    print("=" * 66)

    if not usable:
        print("❌ 집계할 쌍이 없습니다 (전부 ambiguous). --include-ambiguous 를 쓰세요")
        return 1

    prep = dict(srgb=args.srgb, long_edge=args.long_edge,
                crop_top=args.crop_top, verbose=not args.quiet)

    if args.mode == "both":
        two = run(usable, judge, "2", **prep)
        one = run(usable, judge, "1", **prep)
        m = report(two, "2장 모드")
        report(one, "1장 모드")
        compare(two, one)
        outcomes, mode = two, "both"
    else:
        outcomes = run(usable, judge, args.mode, **prep)
        m = report(outcomes, f"{args.mode}장 모드")
        mode = args.mode

    a = compute([o for o in outcomes
                 if not o.error and o.label.grade == "A"])
    if args.csv:
        append_csv(args.csv, m, a, args, judge, mode)
    if args.dump:
        dump_rows(args.dump, outcomes, mode)

    # 종료 코드로 DoD 통과 여부를 알린다 (CI·반복 실행에서 유용).
    passed = a.n and a.accuracy >= DOD_A_ACCURACY and m.false_accept <= DOD_FALSE_ACCEPT
    return 0 if passed else 2


if __name__ == "__main__":
    sys.exit(main())
