#!/usr/bin/env python3
"""Run the exact nested-loop experiment and save measured CSV, metadata, and plot."""
import csv
import json
import math
import platform
import statistics
import subprocess
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SIZES = (1000, 2000, 4000, 8000, 16000, 32000, 64000)
JOIN_QUERY = "R join[R.b=S.b] S"
SELECT_QUERY = "select[a>=0](R)"
PROJECT_QUERY = "project[a](R)"
MATCH_RATE = 0.05
SEED = 3005


def generate(n: int, m: int, match_rate: float, seed: int, destination: Path) -> dict:
    process = subprocess.run(["sh", str(ROOT / "tools" / "java.sh"), "-cp",
                              str(ROOT / "build"), "GenerateData", "--n", str(n),
                              "--m", str(m), "--match-rate", str(match_rate),
                              "--seed", str(seed), "--out", str(destination)],
                             cwd=ROOT, capture_output=True, text=True, check=True)
    info = dict(line.split("=", 1) for line in process.stdout.splitlines())
    return {"expected_join_output": int(info["expected_join_output"]),
            "actual_mean_matches_per_R": float(info["actual_mean_matches_per_R"])}


def execute(data: Path, query: str, warmup: int = 1, batch: int = 1) -> tuple[float, int, dict]:
    p = subprocess.run(["sh", str(ROOT / "tools" / "java.sh"), "-cp", str(ROOT / "build"),
                        "RA", "--data", str(data), "--query", query,
                        "--warmup", str(warmup), "--batch", str(batch),
                        "--metrics", "--count-only"], cwd=ROOT, capture_output=True,
                       text=True, check=False, timeout=1800)
    if p.returncode:
        raise RuntimeError(f"query failed: {query}\n{p.stderr}")
    metrics = {}
    for line in p.stderr.splitlines():
        key, value = line.split("=", 1)
        metrics[key] = float(value) if key == "elapsed_seconds" else int(value)
    row_line = next(line for line in p.stdout.splitlines() if line.startswith("rows: "))
    return metrics["elapsed_seconds"], int(row_line.split(":", 1)[1].strip()), metrics


def slope(points: list[tuple[int, float]]) -> float:
    x = [math.log(n) for n, t in points]
    y = [math.log(max(t, 1e-9)) for n, t in points]
    xmean = statistics.mean(x)
    ymean = statistics.mean(y)
    return sum((a-xmean)*(b-ymean) for a, b in zip(x, y)) / sum((a-xmean)**2 for a in x)


def machine() -> dict:
    cpu = "unknown"
    try:
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.startswith("model name"):
                cpu = line.split(":", 1)[1].strip()
                break
    except OSError:
        pass
    java_version = subprocess.check_output(["sh", str(ROOT / "tools" / "java.sh"), "-version"],
                                           cwd=ROOT, stderr=subprocess.STDOUT, text=True).splitlines()[0]
    compiler_version = subprocess.check_output(["sh", str(ROOT / "tools" / "java.sh"),
                                                "-m", "jdk.compiler/com.sun.tools.javac.Main", "-version"],
                                               cwd=ROOT, stderr=subprocess.STDOUT, text=True).splitlines()[0]
    return {
        "UTC_time": datetime.now(timezone.utc).isoformat(),
        "platform": platform.platform(), "architecture": platform.machine(),
        "CPU_model": cpu, "logical_CPU_count": __import__("os").cpu_count(),
        "language_runtime": java_version, "compiler": compiler_version,
        "flags": "javac -encoding UTF-8 -d build; OpenJDK 17 HotSpot default JIT",
        "generator": "src/GenerateData.java (Java 17)",
        "python_benchmark_driver_version": platform.python_version(),
        "timing": "System.nanoTime around evaluation only; excludes loading and printing",
        "size_repetitions": "join: median of three timed runs per size, each after one warmup evaluation; select/project: median of seven per-query batch means of 50 executions after three warmups each",
        "match_rate_repetitions": "median of three timed runs after one warmup evaluation each",
        "generator_seed": SEED, "target_match_rate": MATCH_RATE,
    }


def main() -> None:
    subprocess.run(["make"], cwd=ROOT, check=True)
    docs = ROOT / "docs"
    work = ROOT / ".bench"
    docs.mkdir(exist_ok=True)
    work.mkdir(exist_ok=True)
    rows = []
    for n in SIZES:
        data = work / f"n_{n}.ra"
        generated = generate(n, n, MATCH_RATE, SEED, data)
        joins = [execute(data, JOIN_QUERY) for _ in range(3)]
        join_time = statistics.median(run[0] for run in joins)
        output_count = joins[0][1]
        join_metrics = joins[0][2]
        if any(run[2].get("join_1_comparisons") != n*n or run[1] != output_count
               for run in joins):
            raise AssertionError(f"join count for {n} is not n*m")
        if output_count != generated["expected_join_output"]:
            raise AssertionError(f"unexpected join output for {n}")
        selects = [execute(data, SELECT_QUERY, warmup=3, batch=50) for _ in range(7)]
        projects = [execute(data, PROJECT_QUERY, warmup=3, batch=50) for _ in range(7)]
        if any(p[2].get("select_1_evaluations") != n for p in selects):
            raise AssertionError("selection counter mismatch")
        row = {
            "n": n, "m": n, "match_rate": MATCH_RATE,
            "comparisons": join_metrics["join_1_comparisons"],
            "join_wall_seconds": join_time, "output_tuples": output_count,
            "select_evaluations": n,
            "select_wall_seconds": statistics.median(p[0] for p in selects),
            "project_wall_seconds": statistics.median(p[0] for p in projects),
        }
        rows.append(row)
        print(f"n={n:5d} pairs={n*n:10d} join={join_time:.6f}s "
              f"out={output_count:5d} select={row['select_wall_seconds']:.6f}s "
              f"project={row['project_wall_seconds']:.6f}s", flush=True)
        data.unlink()

    with (docs / "measurements.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=rows[0].keys())
        writer.writeheader()
        writer.writerows(rows)

    rate_rows = []
    data = work / "match_rate.ra"
    for rate in (0, 0.05, 1):
        generated = generate(8000, 8000, rate, SEED, data)
        observations = [execute(data, JOIN_QUERY) for _ in range(3)]
        count_set = {m["join_1_comparisons"] for t, count, m in observations}
        output_set = {count for t, count, m in observations}
        if count_set != {8000*8000} or output_set != {generated["expected_join_output"]}:
            raise AssertionError("match-rate study counters/output mismatch")
        rate_rows.append({
            "n": 8000, "m": 8000, "target_match_rate": rate,
            "measured_mean_matches_per_R": generated["actual_mean_matches_per_R"],
            "comparisons": 8000*8000, "output_tuples": output_set.pop(),
            "median_wall_seconds": statistics.median(t for t, count, m in observations),
        })
        print(f"match_rate={rate} pairs={8000*8000} "
              f"median={rate_rows[-1]['median_wall_seconds']:.6f}s "
              f"out={rate_rows[-1]['output_tuples']}", flush=True)
    data.unlink()
    with (docs / "match_rate.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=rate_rows[0].keys())
        writer.writeheader()
        writer.writerows(rate_rows)

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    fig, ax = plt.subplots(figsize=(8.5, 5.5))
    for key, label, marker in (("join_wall_seconds", "Nested-loop join", "o"),
                               ("select_wall_seconds", "Select", "s"),
                               ("project_wall_seconds", "Project", "^")):
        ax.loglog([r["n"] for r in rows], [r[key] for r in rows],
                  marker=marker, linewidth=2, label=label)
    ax.set_xlabel("Rows in each relation, n (= m)")
    ax.set_ylabel("Evaluation wall time (seconds)")
    ax.set_title("Relational algebra operator timing, log-log axes")
    ax.grid(True, which="both", alpha=0.25)
    ax.legend()
    fig.tight_layout()
    fig.savefig(docs / "performance_loglog.png", dpi=180)
    plt.close(fig)

    info = machine()
    info["slopes"] = {
        key: slope([(r["n"], r[key]) for r in rows])
        for key in ("join_wall_seconds", "select_wall_seconds", "project_wall_seconds")
    }
    (docs / "benchmark_metadata.json").write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    print("measured slopes:", info["slopes"], flush=True)


if __name__ == "__main__":
    main()
