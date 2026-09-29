# DESIGN_LOG.md — AI-assisted development sessions

This log records actual work performed in this conversation. The student should add their own later sessions and verify the explanations before submitting or giving the oral check.

### 2026-09-27 — Read the brief and plan the engine

I turned the assignment into an implementation plan and identified the scanner, parser, set semantics, schema, error, and benchmark requirements. An early AI explanation called the project “six operators,” but Section 4.2 lists three unary plus five binary forms, so the final grammar and engine cover all eight.

### 2026-09-27 — Write the grammar and ambiguity example

I chose equal precedence and left association for union/minus, stratified the grammar, and drew the two trees for A union B minus C. The first AI plan was incomplete about union of differently qualified relations such as R(a) union S(a), so I specified base-name compatibility and the left output qualifier before implementing set operators.

### 2026-09-27 — Clarify data with no rows

The earlier AI plan required type compatibility but gave no type to an empty input relation. I found that omission by tracing an empty S(x) union numeric R(x), then documented UNKNOWN input type and the type refinement that union performs when values enter its result.

### 2026-09-29 — Rewrite the engine in Java

I ported the scanner, recursive-descent parser, tree printer, typed values, eight operators, and counters into one documented Java 17 source file. I first expected `javac` to be available; this sandbox has neither that command nor a working default `java` launcher path, but the Java 17 compiler module and runtime library exist. I added a wrapper and Makefile fallback, compiled, and passed all 35 tests.

### 2026-09-29 — Benchmark Java, then fix the measurement method

My first AI-assisted benchmark design timed very short selection/project evaluations in separate JVM processes; their times varied enough to yield unstable scaling. I added a `--batch` option and timed 50 executions per invocation after warmups, keeping the counter from one run. The Java join still had a seven-point fitted slope of 1.645 because short-run/JIT effects changed the lower sizes; the largest three sizes gave about 2.02, consistent with the exact n² count. I reported both observations instead of presenting the full-range fit as quadratic.

### 2026-09-29 — Write the Java report and verify provenance

I measured n = m from 1,000 through 64,000, confirmed all join counts equal n × m, and retained the CSV files and plot. The 64,000 Java join counted 4,096,000,000 pairs and took 6.694206091 seconds in this run. I could not read the host CPU model from /proc or lscpu, so the report states the observable x86_64 Linux container and Java version rather than inventing hardware details.

### 2026-09-29 — Assemble and review the Java demonstration

I adapted the prior demonstration outline to the Java commands and new report measurements, then generated a captioned, synthetic-narration video from those Java outputs. I checked frames from the tree, code, and performance sections against the artifacts; the prior language's commands and measurements would have misrepresented this rewrite.

### 2026-09-29 — Finish the data generator in Java and repeat timings

The assignment counts the data generator as source, so I rewrote it in Java and had the benchmark driver invoke that Java class. A first rerun showed volatile single join timings under JVM compilation; I changed the driver to take the median of three independently warmed runs for each join size. The final CSV, report, plot, and regenerated narration use the repeated Java measurements, including the 6.730319568-second median at 64,000 rows per side.
