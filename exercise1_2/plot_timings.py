# Exercise 1.2: plot the timings from exercise 1.1, items 4 and 5.
#
# Run from this folder:
#   python3 -m venv .venv
#   .venv/bin/pip install -r requirements.txt
#   .venv/bin/python plot_timings.py [path/to/timings.csv]
#
# Default input is the full slice run in ../exercise1/results/2026-10-18_1831/.
# The figures are written as PNG files to plots/.

import sys
from pathlib import Path

import matplotlib.pyplot as plt
import pandas as pd

HERE = Path(__file__).parent
DEFAULT_CSV = HERE.parent / "exercise1" / "results" / "2026-10-18_1831" / "timings.csv"
OUT = HERE / "plots"

csv = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_CSV
df = pd.read_csv(csv)
df = df[df["status"] == "ok"]
df["slice"] = df["file"].str.replace(r"^slice_|\.txt$", "", regex=True)  # slice_1M.txt -> 1M
df["buildS"] = df["buildMs"] / 1000
df["totalS"] = df["totalMs"] / 1000
slices = sorted(df["slice"].unique(), key=lambda s: int(s.rstrip("M")))

OUT.mkdir(exist_ok=True)
plt.rcParams.update({"figure.figsize": (8, 5), "axes.grid": True, "grid.alpha": 0.4})


def save(fig, name):
    fig.tight_layout()
    fig.savefig(OUT / name, dpi=150)
    plt.close(fig)
    print(f"wrote {OUT / name}")


# One scatter plot per slice, like the example in the exercise: build time against k
for s in slices:
    d = df[df["slice"] == s]
    fig, ax = plt.subplots()
    ax.scatter(d["k"], d["buildS"])
    ax.set(title=f"Model build time, {s} slice", xlabel="Context length k", ylabel="Build time (s)")
    ax.set_xticks(range(1, 13))
    ax.set_ylim(bottom=0)
    save(fig, f"build_time_{s}.png")


# All slices in one figure, for comparison
def compare(column, ylabel, title, name, log=False):
    fig, ax = plt.subplots()
    for s in slices:
        d = df[df["slice"] == s]
        ax.plot(d["k"], d[column], marker="o", label=s)
    ax.set(title=title, xlabel="Context length k", ylabel=ylabel)
    ax.set_xticks(range(1, 13))
    if log:
        ax.set_yscale("log")
    else:
        ax.set_ylim(bottom=0)
    ax.legend(title="Slice")
    save(fig, name)


compare("buildS", "Build time (s)", "Model build time by slice", "build_time_all.png")
compare("totalS", "Total time (s)", "Total run time (read + build + generate)", "total_time_all.png")
compare("heapUsedMB", "Heap after GC (MB)", "Memory used by the model", "heap_all.png")
compare("mapEntries", "Map entries (log scale)", "Number of distinct prefixes (contexts 0..k)",
        "entries_all.png", log=True)
