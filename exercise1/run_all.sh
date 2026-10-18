#!/bin/bash
# Exercise 1.1, items 4 and 5: compile, make the slices, and time k = 1..12 on each.
# Run from Git Bash:  bash run_all.sh
#
# Each run gets its own folder:
#   results/<date_time>/timings.csv           one row per (slice, k)
#   results/<date_time>/slice_1M/out_k7.txt   generated text
cd "$(dirname "$0")"
javac BuildModel.java || exit 1

mkdir -p slices
for s in 1M 2M 5M 10M; do
  [ -f slices/slice_$s.txt ] || head -c $s fiction-en.txt > slices/slice_$s.txt
done

run=results/$(date +%Y-%m-%d_%H%M)
mkdir -p $run
echo "Writing results to $run"
cd $run
for s in 1M 2M 5M 10M; do
  for k in $(seq 1 12); do
    java -Xmx12g -cp ../.. BuildModel ../../slices/slice_$s.txt $k
  done
done
