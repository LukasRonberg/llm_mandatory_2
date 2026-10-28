# Tegnbaserede sprogmodeller (LLM mandatory 2)

Opgaven bygger en simpel tegnbaseret sprogmodel, der lærer ved at tælle, hvilke tegn der følger efter de foregående *k* tegn i en engelsk fanfiction-samling. Vi måler tid og hukommelse for *k* = 1..12 på udsnit af teksten og diskuterer, hvad resultaterne fortæller om rigtige sprogmodeller.

Rapporten ligger i [`report/rapport.pdf`](report/rapport.pdf) (kilde: `report/rapport.tex`).

## Struktur

| Mappe | Indhold |
|---|---|
| `exercise1/` | `BuildModel.java` (modellen, med sparsom frekvenstabel og tidtagning), `run_all.sh` (bygger udsnit på 1M/2M/5M/10M tegn og kører *k* = 1..12), `CHANGES.md` (noter om kodeændringerne) og `results/` med `timings.csv` og genereret tekst (`out_k<k>.txt`). |
| `exercise1_2/` | `plot_timings.py`, der laver figurerne i `plots/` ud fra en `timings.csv`. |
| `report/` | Rapporten (dansk) som LaTeX og PDF. |

## Kør selv

```bash
# Eksperimenter (kræver Java, ~12 GB heap til k=12 på 10M)
cd exercise1 && bash run_all.sh

# Plots
cd exercise1_2
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python plot_timings.py ../exercise1/results/<kørsel>/timings.csv

# Rapport
cd report && pdflatex rapport.tex
```

## Hovedresultater

- Den udleverede kode gav `OutOfMemoryError` for *k* ≥ 7, fordi hver kontekst gemte et fast `int[256]`. En sparsom repræsentation gjorde modellen ca. 10 gange mindre uden at ændre sandsynlighederne.
- Antal kontekster, byggetid og hukommelse vokser kraftigt med *k* og omtrent lineært med tekstmængden (10M tegn, *k* = 12: ca. 25,7 mio. kontekster, 141 s, 4,4 GB).
- Teksten bliver mere realistisk med større *k*, men ved *k* = 12 kopierer modellen i praksis træningsteksten, fordi næsten hver 12-tegnskontekst kun findes ét sted.

Af Mahdi, Lukas og Frederik.
