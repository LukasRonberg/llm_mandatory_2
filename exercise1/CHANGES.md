# Exercise 1.1 – code changes to BuildModel.java (notes for the report)

## Short version

1. **Problem:** the model stores one `Freq` object per distinct prefix, and each one holds a fixed `int[256]`, about 1 KB.
   On the full text, k = 7 gives more than 3.6 M prefixes, which is about 3.8 GB, so Java's default 4 GB heap runs out (`OutOfMemoryError`).
   The original "worked" only because it is hard-coded to k = 5, which needs about 1.3 GB.
2. **Fix (from 01):** `Freq` stores only the characters that actually followed the prefix, usually 1 to 3.
   That uses about 10× less memory, and the probabilities are the same.
3. **Clean-up (from 02):** curly quotes, dashes and "…" are converted to plain ASCII before training.
   Before, they became spaces, so `He’s` was learned as `He s`.
4. **Measuring:** k is a command-line argument. Read, build and generate are timed with `System.nanoTime()`.
   Heap is measured after `System.gc()`. Each run adds one row to `timings.csv`.
5. **Slices:** heap still grows with text size (1 MB at k = 12 is about 770 MB), so k = 12 on the full 54 MB text won't fit.
   That is why we time 1M, 2M, 5M and 10M slices (`bash run_all.sh`).
6. **Output:** Java writes the generated text as UTF-8 itself. PowerShell's `>` had saved it as UTF-16 (`C o n t e x t`).

Observation for the report: the higher k, the more real-looking the text, but at k = 12 on 1 MB it mostly
copies long runs of the training text and only switches source where two passages share a 12-char prefix
(e.g. "On arriving at his destination, he sent his first lieutenant" is copied verbatim, then continues "...and his sub did not sleep",
which is not in the text). The model memorises more than it generalises.

---

Starting point: the teacher's `BuildModel.java` (sestoft, 2026-08-30).
Reference fixes from the teacher: `BuildModel.01.java` and `BuildModel.02.java`.

## Round 1: make it runnable and measurable (done before the crash fix)

| Change | Why |
|---|---|
| `maxContext` is no longer `final`; it is read from `args[1]` | So we can run k = 1..12 without recompiling. |
| Optional charset in `args[2]`, default UTF-8, read via `InputStreamReader` in try-with-resources | `fiction_eng.txt` is UTF-8. The original `FileReader(args[0])` uses the platform default charset, and it never closed the file. |
| `Æ Ø Å æ ø å` literals replaced by `Æ` and similar escapes | The original only compiles with `-encoding latin1`. With escapes it compiles with any source encoding. |
| `_ -> ...` lambdas renamed to `k -> ...` / `x -> ...` | `_` as a lambda parameter needs JDK 22+ (it was a preview feature in 21). |
| Timing with `System.nanoTime()` split into read, build and generate. Heap measured as `totalMemory() - freeMemory()`. One CSV line appended per run | Required by the exercise (items 3 and 4). |
| `catch (OutOfMemoryError)` around the build loop, logged as `OOM` | So a crashing k is still recorded instead of the run just dying. |

**Result of round 1 on the full 54 M-char file** (kept in `results/original_run/`):
k = 1..6 succeeded, with build time going from 1.8 s to 216 s and heap from 170 MB to 3.4 GB.
**k = 7..12 all crashed with `OutOfMemoryError`**, at about 3.63 M map entries every time.
(The k = 9 build time of 146 732 467 ms is not a real measurement. The laptop most likely went to sleep during that run.)

### Why the original "worked" for the class
The original has `maxContext = 5`. On a 16 GB machine the JVM's default max heap is 1/4 of RAM, which is 4 GB.
k = 5 needs about 1.3 GB, so it fits. The crash only shows up from k = 7, which is also what the comment in the source says
(`// 7 requires -Xms10G`).

### Why it crashes
Every distinct prefix gets its own `Freq`. In the original, `Freq` holds `new int[256]`, which is 1 KB
plus object headers, about 1.05 KB per prefix, even though most prefixes are followed by only 1 to 3 different characters.
3.63 M prefixes × about 1.05 KB is about 3.8 GB, which is the 4 GB heap limit. The error appears in `String.substring`
inside `buildFreqMap` only because that is where the last allocation happens. The memory is actually used by the `Freq` arrays.

Also, the map holds **all** contexts 0..k together (the loop runs `context = 0..maxContext` into one map), so
both the entry count and the build time for k include all the smaller contexts.

## Round 2: the crash fix (from BuildModel.01.java)

`Freq` replaced by a compact, growable list of only the characters that were actually seen:

```java
class Freq {
  private char[] chars = new char[2];
  private int[] counts = new int[2];
  private int size = 0, sum = 0;

  public void incr(char c) {
    c = (char)(c & 0xFF);
    sum++;
    for (int i = 0; i < size; i++)
      if (chars[i] == c) { counts[i]++; return; }
    if (size == chars.length) {                 // grow by doubling
      chars = Arrays.copyOf(chars, size * 2);
      counts = Arrays.copyOf(counts, size * 2);
    }
    chars[size] = c;
    counts[size++] = 1;
  }
  public char sample() {
    int r = (int) (Math.random() * sum);
    for (int i = 0; i < size; i++)
      if ((r -= counts[i]) < 0) return chars[i];
    return chars[size - 1];
  }
}
```

| | Original `Freq` | Compact `Freq` (01) |
|---|---|---|
| Memory per prefix | about 1 050 B (fixed `int[256]`) | about 70–100 B for a prefix with 1–2 successors |
| `incr` | O(1) array index | O(successors) linear scan, which is short in practice |
| `sum` | `double`, computed lazily by summing 256 ints | `int`, kept up to date in `incr` |
| `sample` | scans up to 256 slots | scans only `size` slots |

The probability distribution is the same. Only the internal order of the characters differs.

## Round 3: text clean-up (from BuildModel.02.java)

```java
allText = allText.replace('‘', '\'').replace('’', '\'')   // ‘ ’  -> '
                 .replace('“', '"').replace('”', '"')     // “ ”  -> "
                 .replace('—', '-').replace('–', '-')     // — –  -> -
                 .replace("…", "...")                          // …    -> ...
                 .replaceAll("[^\\x00-\\xFF]", " ");                // anything else outside Latin-1 -> space
```

Why: `Freq` only handles chars 0..255. The original turned any next-char ≥ 256 into a space
(`next >= 256 ? ' ' : next`), so `He’s` was learned as `He s`, and the prefixes kept the curly quotes.
The fiction text uses curly quotes everywhere, so the generated text lost its apostrophes and quotes.
Folding them to ASCII **before** building the model keeps them, and it makes prefixes and next-chars consistent.

## Our own extra changes

| Change | Why |
|---|---|
| `System.gc()` before reading `totalMemory() - freeMemory()` | The exercise asks for it. Without it the number includes garbage that is not yet collected, so it is noisy. |
| Generated text written by Java to `<slice>/out_k<k>.txt` in UTF-8, inside a per-run folder `results/<date_time>/` made by `run_all.sh` | Our first outputs were made with PowerShell `> out_k1.txt`, which writes UTF-16 (`C o n t e x t ...`). Writing the file from Java avoids that. |
| `timings.csv` gets a `file` column first | We now run several slices (1M, 2M, 5M, 10M, full), and each row must say which input it came from. |

## Measured after the fix (smoke test, 1 MB slice)

```
k= 1  entries=98         build=75 ms     heap=3 MB
k= 3  entries=15,484     build=339 ms    heap=5 MB
k= 7  entries=855,328    build=3218 ms   heap=143 MB
k=12  entries=4,560,179  build=10586 ms  heap=766 MB
```
k = 7 output fragment (1 MB slice):
> You're a pack mule.' Sakura went along most barbarously at the place we visits to thrown him, snapping - Eddie had chosen a fever-dream, stirred around to change must be tempered along.

Note: heap grows roughly linearly with input size, so k = 12 on the **full** 54 MB file would need tens of GB
even with the compact `Freq`. That is why the exercise uses slices.
