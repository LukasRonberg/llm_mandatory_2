// sestoft@itu.dk * 2026-08-30
// Modified to compile on any JDK 11+ regardless of source-file encoding,
// and to take the context length k as an argument and log timings.
// Freq replaced by the compact version from BuildModel.01.java (fixes OutOfMemoryError),
// and typographic quotes/dashes folded to ASCII as in BuildModel.02.java.
//
// Compile and run with
//   javac BuildModel.java
//   java BuildModel fiction-en.txt              (k = 5, reads the text file as UTF-8)
//   java BuildModel slice_1M.txt 7              (k = 7)
//   java BuildModel ft-da.txt 7 ISO-8859-1      (k = 7, text file is Latin-1)
//
// The generated text is written (UTF-8) to <textfile>/out_k<k>.txt in the current directory.
// Each run appends one line to timings.csv in the current directory:
//   file,k,chars,mapEntries,readMs,buildMs,generateMs,totalMs,heapUsedMB,status

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

class BuildModel {
  static int maxContext = 5;  // 7 requires -Xms10G on ft to avoid OutOfMemoryError

  public static void main(String[] args) throws IOException {
    if (args.length < 1) {
      System.err.println("Usage: java BuildModel <textfile> [k, default 5] [charset, default UTF-8]");
      System.exit(1);
    }
    if (args.length >= 2) maxContext = Integer.parseInt(args[1]);
    Charset cs = args.length >= 3 ? Charset.forName(args[2]) : StandardCharsets.UTF_8;

    long t0 = System.nanoTime();
    String allText;
    try (BufferedReader rdr = new BufferedReader(
           new InputStreamReader(new FileInputStream(args[0]), cs))) {
      allText = rdr.lines().collect(Collectors.joining(" "));
    }
    // Fold typographic punctuation to ASCII and any remaining non-Latin-1 char to a space,
    // so both prefixes and next-chars fit the 0..255 range that Freq works with
    allText = allText.replace('‘', '\'').replace('’', '\'')
                     .replace('“', '"').replace('”', '"')
                     .replace('—', '-').replace('–', '-')
                     .replace("…", "...")
                     .replaceAll("[^\\x00-\\xFF]", " ");
    long t1 = System.nanoTime();
    String fileName = new File(args[0]).getName();

    Map<String,Freq> freqMap = new TreeMap<String,Freq>();  // HashMap<String,Freq>() or TreeMap<String,Freq>()
    try {
      for (int context=0; context<=maxContext; context++) {
        buildFreqMap(freqMap, allText, context);  // Build frequencies-in-context
        // System.out.println(showFreqs(freqMap, context, args[0]));  // Print frequencies-in-context
        // System.out.printf("Context length %d:\n%s\n\n", context, generate(freqMap, context, 400)); // Generate random text
      }
    } catch (OutOfMemoryError e) {
      long entries = freqMap.size();
      freqMap = null;  // release the map so we can still write the log line
      logTiming(fileName, maxContext, allText.length(), entries, ms(t0, t1), ms(t1, System.nanoTime()), -1, ms(t0, System.nanoTime()), -1, "OOM");
      System.err.printf("k=%d: OutOfMemoryError while building the model%n", maxContext);
      System.exit(2);
    }
    long t2 = System.nanoTime();

    String text = generate(freqMap, maxContext, 100000);
    long t3 = System.nanoTime();

    // Write the generated text ourselves, in UTF-8, so the shell's redirection
    // (PowerShell 5.1 '>' writes UTF-16) cannot garble it
    // One sub-folder per input file, e.g. slice_1M/out_k7.txt
    File outDir = new File(fileName.replaceFirst("\\.[^.]*$", ""));
    outDir.mkdirs();
    File outFile = new File(outDir, String.format("out_k%d.txt", maxContext));
    try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8))) {
      pw.printf("Context length %d:\n%s\n\n", maxContext, text);
    }

    // Measure heap after a GC, so only live data (the model) is counted, not garbage
    System.gc();
    Runtime rt = Runtime.getRuntime();
    long heapMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    logTiming(fileName, maxContext, allText.length(), freqMap.size(), ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t0, t3), heapMB, "ok");
    System.err.printf("k=%2d  entries=%,d  read=%d ms  build=%d ms  generate=%d ms  total=%d ms  heap=%d MB%n",
                      maxContext, freqMap.size(), ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t0, t3), heapMB);
  }

  private static long ms(long from, long to) { return (to - from) / 1_000_000; }

  private static void logTiming(String file, int k, long chars, long entries, long readMs, long buildMs,
                                long genMs, long totalMs, long heapMB, String status) throws IOException {
    File f = new File("timings.csv");
    boolean newFile = !f.exists();
    try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
      if (newFile) pw.println("file,k,chars,mapEntries,readMs,buildMs,generateMs,totalMs,heapUsedMB,status");
      pw.printf("%s,%d,%d,%d,%d,%d,%d,%d,%d,%s%n", file, k, chars, entries, readMs, buildMs, genMs, totalMs, heapMB, status);
    }
  }

  // Add to frequency maps from allText for given context length
  private static void buildFreqMap(Map<String,Freq> freqMap, String allText, int context) {
    for (int start=0; start<allText.length()-context-1; start++) {
      String prefix = allText.substring(start, start+context);
      char next = allText.charAt(start+context);
      freqMap.computeIfAbsent(prefix, k -> new Freq()).incr(next >= 256 ? ' ' : next);
    }
  }

  private static String generate(Map<String,Freq> freqMap, int context, int len) {
    char[] result = new char[len];
    for (int pos=0; pos<len; pos++)
      result[pos] = nextChar(freqMap, result, Math.min(pos, context), pos);
    return new String(result, 0, len);
  }

  private static char nextChar(Map<String,Freq> freqMap, char[] result, int context, int pos) {
    Freq freq = freqMap.get(new String(result, pos-context, context));
    return freq != null ? freq.sample() : nextChar(freqMap, result, context-1, pos);
  }

  // Generate Latex longtable code from a frequency map
  private static String showFreqs(Map<String,Freq> freqMap, int context, String fileName) {
    return
      String.format("\\begin{longtable}{@{'}l@{'}|*{%d}{@{\\ }r@{}}}\n", letters.length)
      + String.format("\\caption{Frequencies in %d-context for file %s}\\\\\\hline\\hline\n", context, fileName)
      + Arrays.stream(letters).mapToObj(x -> "000").collect(Collectors.joining(" & ", " & ", "\\kill\n"))
      + Arrays.stream(letters).mapToObj(Character::toString).collect(Collectors.joining(" & ", " & ", "\\\\\\hline\\endhead\n"))
      + freqMap.entrySet().stream().filter(kv -> kv.getKey().length() == context)
        .filter(kv -> kv.getKey().chars().allMatch(ch -> letterSet.contains(ch)))
        .map(kv -> {
            IntFunction<String> show = ch -> Integer.toString((int)Math.round(kv.getValue().freq(ch) * 999.0));
            return Arrays.stream(letters).mapToObj(ch -> show.apply(ch)).collect(Collectors.joining(" & ", kv.getKey() + " & ", "\\\\"));
          }).collect(Collectors.joining("\n", "", "\\hline\\hline\n\\end{longtable}\n\\newpage\n"));
  }

  // Unicode escapes instead of literal Æ Ø Å / æ ø å, so the source encoding no longer matters
  static final IntStream
    uppercase = IntStream.concat(IntStream.rangeClosed('A', 'Z'), IntStream.of('\u00C6', '\u00D8', '\u00C5')),
    lowercase = IntStream.concat(IntStream.rangeClosed('a', 'z'), IntStream.of('\u00E6', '\u00F8', '\u00E5'));
  static final int[] letters = IntStream.concat(IntStream.of(' '), lowercase).toArray();
  // static final int[] letters = IntStream.concat(IntStream.concat(IntStream.of(' '), uppercase), lowercase).toArray();
  static final Set<Integer> letterSet = Arrays.stream(letters).boxed().collect(Collectors.toSet());
}

// Compact Freq from BuildModel.01.java: stores only the characters actually seen
// (most prefixes have few successors), instead of a fixed int[256] (~1 KB) per prefix
class Freq {
  private char[] chars = new char[2];
  private int[] counts = new int[2];
  private int size = 0, sum = 0;

  public void incr(char c) {
    c = (char)(c & 0xFF);
    sum++;
    for (int i = 0; i < size; i++)
      if (chars[i] == c) { counts[i]++; return; }
    if (size == chars.length) {
      chars = Arrays.copyOf(chars, size * 2);
      counts = Arrays.copyOf(counts, size * 2);
    }
    chars[size] = c;
    counts[size++] = 1;
  }

  public double freq(int ch) {
    ch &= 0xFF;
    for (int i = 0; i < size; i++)
      if (chars[i] == ch) return (double) counts[i] / sum;
    return 0.0;
  }

  public char sample() {
    int r = (int) (Math.random() * sum);
    for (int i = 0; i < size; i++)
      if ((r -= counts[i]) < 0) return chars[i];
    return chars[size - 1];
  }
}