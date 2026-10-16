// sestoft@itu.dk * 2026-08-30
// Modified to compile on any JDK 11+ regardless of source-file encoding,
// and to take the context length k as an argument and log timings.
//
// Compile and run with
//   javac BuildModel.java
//   java BuildModel ft-da.txt                 (k = 5, reads the text file as UTF-8)
//   java BuildModel ft-da.txt 7               (k = 7)
//   java BuildModel ft-da.txt 7 ISO-8859-1    (k = 7, text file is Latin-1)
//
// Each run appends one line to timings.csv:
//   k,chars,mapEntries,readMs,buildMs,generateMs,totalMs,heapUsedMB,status

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
    long t1 = System.nanoTime();

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
      logTiming(maxContext, allText.length(), entries, ms(t0, t1), ms(t1, System.nanoTime()), -1, ms(t0, System.nanoTime()), -1, "OOM");
      System.err.printf("k=%d: OutOfMemoryError while building the model%n", maxContext);
      System.exit(2);
    }
    long t2 = System.nanoTime();

    String text = generate(freqMap, maxContext, 100000);
    long t3 = System.nanoTime();

    System.out.printf("Context length %d:\n%s\n\n", maxContext, text); // Generate random text

    Runtime rt = Runtime.getRuntime();
    long heapMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    logTiming(maxContext, allText.length(), freqMap.size(), ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t0, t3), heapMB, "ok");
    System.err.printf("k=%2d  entries=%,d  read=%d ms  build=%d ms  generate=%d ms  total=%d ms  heap=%d MB%n",
                      maxContext, freqMap.size(), ms(t0, t1), ms(t1, t2), ms(t2, t3), ms(t0, t3), heapMB);
  }

  private static long ms(long from, long to) { return (to - from) / 1_000_000; }

  private static void logTiming(int k, long chars, long entries, long readMs, long buildMs,
                                long genMs, long totalMs, long heapMB, String status) throws IOException {
    File f = new File("timings.csv");
    boolean newFile = !f.exists();
    try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
      if (newFile) pw.println("k,chars,mapEntries,readMs,buildMs,generateMs,totalMs,heapUsedMB,status");
      pw.printf("%d,%d,%d,%d,%d,%d,%d,%d,%s%n", k, chars, entries, readMs, buildMs, genMs, totalMs, heapMB, status);
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

class Freq {
  private final int[] counts = new int[256];
  private double sum = -1.0;

  public void incr(char c) { counts[c & 0xFF]++; }

  public double freq(int ch) {
    if (sum < 0.0)
      sum = Arrays.stream(counts).sum();
    return counts[ch & 0xFF] / sum;
  }

  public char sample() {
    if (sum < 0.0)
      sum = Arrays.stream(counts).sum();
    int res = 0;
    double rank = Math.random() * sum;
    while (counts[res] < rank)
      rank -= counts[res++];
    return (char)res;
  }
}