# COMP 3005 relational algebra engine — Java 17

An in-memory relational algebra engine with a hand-written character scanner, recursive-descent parser, visible operation tree, eight named operators, typed values and schemas, and controlled errors. The engine uses Java's standard library only. It does not use regular expressions in the scanner/parser, a parser generator, expression evaluation, SQL, or a data-processing library.

## Build and run

Install a Java 17 or newer JDK. The engine and data generator are Java. Python 3 is used for the test/benchmark drivers and video assembly; matplotlib is needed only to redraw the performance plot.

~~~sh
make
make test
sh tools/java.sh -cp build RA --tree "A union B minus C"
sh tools/java.sh -cp build RA --tree "project[Name](select[Age>30](Employees))"
sh tools/java.sh -cp build RA --data examples/employees.ra --query "project[DID](Employees)"
sh tools/java.sh -cp build RA --data examples/employees.ra --query \
    "rename[E2](Emp) join[Emp.MgrID=E2.EID] Emp"
~~~

On a standard JDK installation, `java -cp build RA` works in place of `sh tools/java.sh -cp build RA`. The wrapper supports this execution environment, which has Java 17's compiler module but lacks the `javac` launcher and a default dynamic library search path. The Makefile uses `javac` when present and the compiler module otherwise. Build output goes into `build/`; the ZIP contains Java source, not compiled class files.

Tree mode needs no relation file and never executes the query. Query mode needs `--data` and one `--query`. A successful query prints its schema, row count, and rows; an empty result prints an empty body. Invalid input reports a category, line/column position, and message on stderr, with no stack trace.

Add `--metrics` to print a separate join-pair counter for every join node and a separate examined-tuple counter for every select node. `--count-only` suppresses the printed row body for a benchmark. `--warmup N` evaluates N times before measurement, and `--batch N` reports the average of N timed evaluations while keeping the last evaluation's counters. Both default to no warmups and one timed evaluation.

## Relation definitions and query language

A data file uses one header and one tuple per line, with the closing brace on its own line:

~~~text
R (a, b) = {
  1, 10
  2, 20
}
S (b, c) = {
  10, 'x,y'
  20, 'O''Brien'
}
~~~

Whole-line `//` comments and blank lines are ignored. An unquoted nonnumeric tuple value is a string. Quote values containing a space, comma, parenthesis, or quote; doubled single quotes mean one literal quote. Strings in query conditions must be quoted. Decimal numbers are stored as arbitrary-precision BigDecimal values, so numeric `1` and `1.0` compare equal while `'1'` remains a string. Identifiers consist of ASCII letters, digits and underscores and must start with a letter or underscore. Scientific notation and nulls are outside scope.

Unary forms are `select[condition](expr)`, `project[attributes](expr)`, and `rename[name](expr)`. Binary forms are `union`, `intersect`, `minus`, `times`, and `join[condition]`. Conditions allow attribute-to-attribute and attribute-to-literal comparisons (`= != < <= > >=`) with `not`, `and`, `or`, and parentheses. Qualified attributes such as `Emp.DID` disambiguate joins. Full precedence, associativity, identifier handling, EBNF, and errors are in [GRAMMAR.md](GRAMMAR.md).

The join is a theta join equivalent to product followed by select. Both same-named columns remain, qualified by their source relation names. A self join requires `rename`, because `Emp times Emp` creates duplicate fully qualified attributes. Input and output relations have set semantics; `RA.tupleEqual` defines equality and hash buckets only find candidates for explicit equality checking.

## Tests and performance

~~~sh
make test
sh tools/java.sh -cp build GenerateData --n 8000 --m 8000 --match-rate 0.05 \
    --seed 3005 --out examples/generated.ra
sh tools/java.sh -cp build RA --data examples/generated.ra \
    --query "R join[R.b=S.b] S" --metrics --count-only
python3 tools/benchmark.py
~~~

All 25 required cases and 10 additional cases are automated. The generator controls the mean number of S matches per R row and reports the exact observed output count. The benchmark regenerates CSV files, metadata, and a plot under `docs/`. It deliberately never runs a million-by-million join. [REPORT.md](REPORT.md) explains the measured Java results and the million-row prediction. Regenerating measurements does not overwrite the report prose; update it if you use different results.

## Files

| Path | Contents |
| --- | --- |
| src/RA.java | Scanner, definition reader, parser, AST printer, values, schemas, operators, metrics, CLI |
| src/GenerateData.java | Java R/S data generator with controllable sizes and match rate |
| tools/java.sh, Makefile | Java 17 runtime compatibility and compilation |
| tools/benchmark.py | Required seven-size runs, select/project and match-rate experiments, log-log plot |
| tests/test_assignment.py | All 25 numbered assignment cases plus 10 edge cases |
| examples/employees.ra | Small catalog to try interactively |
| GRAMMAR.md, REPORT.md, DESIGN_LOG.md | Required written submissions |
| PLAN.md | Step-by-step Java implementation and verification plan |
| docs/ | Raw Java measurements, metadata, plot, and narrated demonstration video |
| DEMO_VIDEO_SCRIPT.md | Five-minute recording outline |
| tools/create_video.py | Regenerates the synthetic-voice demo with Pillow and FFmpeg/flite |

## Limits and future work

Data is loaded into memory. The program evaluates the tree exactly as written with nested-loop product and join, no indexes, hash join, sort-merge join, query rewriting, SQL, nulls, or three-valued logic. A direct `long` comparison is used for a single integer equality join after binding attribute positions, but the two nested loops still test and count every pair. Other conditions use the general typed evaluator. Large products can exhaust memory; the report's million-row runtime is an estimate, not a measured run.

This project and its synthetic-voice video were prepared with AI assistance. Read the code, rerun measurements on the machine you will describe in your submission, and be able to explain the grammar, a chosen function, and every report number during the oral check.
