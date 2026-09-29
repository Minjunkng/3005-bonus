# COMP 3005 Bonus Project 1 — Java implementation plan

This is a step-by-step implementation contract for an AI assistant working with a student. The accompanying Java 17 project is a complete example of the steps. Follow the assignment brief and `GRAMMAR.md` if a detail is unclear. Keep grammar, implementation, tests, and report consistent. Before submission, the student must understand the code, verify the results on their own machine, and be ready to explain a function and a report number during the oral check.

## Rules, artifacts, and acceptance gates

Use Java 17 or later for the engine and data generator. Python 3 may run cross-language tests, orchestrate measurements, and plot the report; all parsing, data generation, and relational operations belong in Java. Use only a hand-written character scanner and parser: no regular-expression tokenizer/parser, ANTLR, `eval`, SQL engine, data-processing library, or library set operation that defines tuple equality for you. Execute the written operation tree without algebra rewrites, indexes, hash joins, or sort-merge joins. A nested-loop theta join is required for the measurements.

Keep these deliverables at the repository root: `GRAMMAR.md`, `REPORT.md`, `DESIGN_LOG.md`, `README.md`, `PLAN.md`, Java source, a data generator, and `tests/`. Include saved raw measurements, a log-log plot, and a demonstration video around five minutes. The assignment ultimately requests a GitHub link on Brightspace. Do not claim that a ZIP or a local artifact is a GitHub submission.

| Gate | Evidence required before the next phase |
| --- | --- |
| Language design | `GRAMMAR.md` contains all EBNF, two ambiguity trees with different outputs, precedence and sources. |
| Front end | Cases 1–17 work, including lexical/syntax errors with positions and visible trees. |
| Evaluator | Cases 18–25 work, including schema/type errors and an empty output. |
| Performance | All seven actual Java join runs, exact counters, same-size select/project runs, match-rate runs, raw CSV and plot exist. |
| Delivery | A clean Java build and all tests pass; docs and video use the same measurements. |

## Phase 1 — Design the language before coding

1. Read Sections 3–9 of the assignment and review its permitted tools and eight concrete forms: `select`, `project`, `rename`, `times`, `join`, `union`, `intersect`, `minus`. Try the Relax examples the brief suggests. Read a resource on EBNF, scanning, maximal munch, and recursive descent. Record what you actually read in `GRAMMAR.md`.
2. Write EBNF for two entry points: a relation-definition catalog and a single query. Include blank/comment lines, one-line headers, tuple fields, bare and quoted strings with doubled quotes, signed decimal numbers, identifiers, unary and infix expressions, comparisons, and Boolean conditions. Mark lexical behavior that cannot be expressed in simple context-free productions.
3. Fix operator precedence in grammar productions: primary > unary > product/join > intersect > union/minus. Repeat the binary productions and fold **left**. Conditions use comparison > `not` > `and` > `or`. Choose a documented rule for keyword-shaped identifiers and repeated projection attributes.
4. Draw both parse trees for the ambiguous naive grammar on `A union B minus C`; use `A={(1)}`, `B={(2)}`, `C={(1)}` with a common schema to show the two different results. Show that the chosen grammar forces `(A union B) minus C`. Demonstrate that `(A minus B) minus C` differs from `A minus (B minus C)` for suitable sets.
5. Define set-compatible schemas, ordered attributes, qualifiers, type inference for empty inputs, exact numeric values, explicit tuple equality, collisions under product/rename, and the five error categories. Manually trace the numbered grammar cases against these rules. **Gate:** revise the EBNF before writing parser code.

## Phase 2 — Build the Java front end

6. Create `src/RA.java`, `Makefile`, and `tools/java.sh` or equivalent Java files/build script. Keep scanner, catalog reader, syntax-tree types, parser, evaluator, output, and CLI separate as methods or classes, even if they live in one file. Use Java's standard library for files and `BigDecimal` for exact decimal values.
7. Implement a scanner that advances through one character at a time and emits token kind, value, and starting line/column. Check `>=`, `<=`, and `!=` before one-character operators. Tokenize `-30` as one number after `>`. Keep commas and parentheses inside quoted strings as data, decode doubled quotes, skip only documented whole-line comments, and report unterminated strings at their opening position. Never split source on whitespace to create tokens.
8. Parse catalog headers and tuple lines. Require exactly one tuple value per attribute, reject duplicate relation/attribute names, deduplicate repeated input rows through an explicit `tupleEqual` routine, and reject columns mixing numeric/string values. Preserve schema for empty relations with an UNKNOWN type. Treat an unquoted nonnumeric tuple field as a string, but a bare identifier in a query condition as an attribute reference.
9. Define syntax-tree nodes for relation, three unary operations, and five binary operations; define condition nodes for comparison, `not`, `and`, and `or`. Include relevant source positions in each node. Build recursive-descent functions corresponding exactly to each precedence level in `GRAMMAR.md`; for binary levels consume repetitions in a loop and wrap the accumulated left node. A directly left-recursive method would call itself indefinitely before consuming input.
10. Print a deterministic indented tree without evaluating. Compare trees for cases 1 and 2; verify root/children for cases 10–15. Check missing close parens and empty projection lists in cases 16–17. **Gate:** cases 1–17 pass from a clean compilation.

## Phase 3 — Implement typed relational algebra

11. Store every relation as ordered columns plus rows of typed values. Resolve `R.x` only by exact qualifier and name; reject unknown or ambiguous unqualified `x`. Bind condition attribute references to column positions before scanning rows, so wrong names and known type mismatches fail even for an empty result.
12. Implement comparison of like-typed values (`BigDecimal.compareTo` for numbers, lexicographic strings), six comparison operators, and `not`, `and`, `or`. Select scans every input row and increments a **select-specific counter** exactly once before evaluating its predicate; its output retains the input schema.
13. Project in the requested order, reject a repeated resolved attribute, and eliminate duplicate result tuples using explicit tuple equality. A hash bucket can locate candidate duplicates, but equality must be checked by your own routine. Rename changes each output qualifier and rejects new collisions.
14. Times builds all row pairs and a qualified combined schema; fail when any fully qualified name collides. Join constructs that same schema, binds its condition, iterates **every** left/right pair, increments its **join-specific counter** once per evaluated pair, and retains matching combined rows. A specialized comparison of two already-bound integer columns is allowed as long as it still tests and counts every pair.
15. For union, intersect, and minus, validate equal arity, equal base attribute names in the same order, and compatible types before processing rows. Keep the left output schema. Union deduplicates both inputs; intersection keeps shared left rows; minus keeps left rows absent on the right. Define how an UNKNOWN empty column is refined by known values in union.
16. Catch expected lexical, syntax, name, schema, type, and input errors at the CLI boundary and print a concise error (with position where applicable) rather than a stack trace. Support `--tree EXPR`; `--data FILE --query EXPR`; and `--metrics` for operator-local counters. Add `--count-only` to suppress large result bodies for experiments. **Gate:** cases 18–25 and additional edge tests pass.

## Phase 4 — Test the specified cases

17. Automate **each** of cases 1–9: no whitespace, spaces giving the same tree, `>=`, negative number, `)` inside a string, comma inside a string, doubled quote, keyword-shaped attribute `union`, and an unterminated string with position.
18. Automate cases 10–17: both left-associative set expressions, `not`/`and`/`or` grouping, three unary levels, parenthesized precedence, a missing `)`, and empty project list. Assert tree structure, not only whether parsing succeeds.
19. Automate cases 18–25: attribute-vs-attribute condition, qualified theta join preserving both `DID` columns, rename-based self join, incompatible union, numeric/string type mismatch, distinct projection result, repeated projection error, and empty output schema/body. Add tests for duplicate input rows, every set operation, missing and ambiguous names, product collisions, and malformed data. Run `make clean && make test`; retain command output for review.

## Phase 5 — Generate and measure real Java runs

20. Implement `src/GenerateData.java` to write R(a,b) and S(b,c), accepting separate sizes, seed, and mean matches per R row. Generate unique R and S rows, calculate expected join output exactly, and report the actual rate. Do not fake match counts by claiming a target rate was achieved without checking output.
21. Add `System.nanoTime()` around query **evaluation only**. Emit each select and join counter separately. Specify any warmup and batching method. Ensure the join comparison counter uses `long`: 64,000 × 64,000 = 4,096,000,000 exceeds a signed 32-bit integer.
22. Run `R join[R.b=S.b] S` with n = m = 1,000, 2,000, 4,000, 8,000, 16,000, 32,000, and 64,000. Capture comparison count, wall seconds, and output tuples. Confirm the counter equals n × m **for every run**. Save exact unrounded raw numbers to `docs/measurements.csv` and machine/runtime metadata to JSON.
23. At those same sizes measure standalone `select[a>=0](R)` and `project[a](R)`. Preserve the select operator's measured counter. Run an 8,000 × 8,000 match-rate experiment at rates 0, 0.05, and 1, recording wall time, exact pair count, and output size. Use a plotting library only for `docs/performance_loglog.png`.
24. Write `REPORT.md` from saved Java results. State CPU/OS details actually visible, Java/compiler versions, warmup, repetitions, timing boundaries, and the observed log-log slopes. Explain small-run JVM effects honestly if measured slope differs from the exact quadratic pair count. Predict the unexecuted million-by-million join using the measured 64,000-row time multiplied by `(1,000,000/64,000)^2`, with assumptions. Explain why match rate changes output work even if comparisons remain n × m, and describe a prospective hash join in one paragraph without implementing it.

## Phase 6 — Review and package

25. Check `GRAMMAR.md` against actual parser behavior, `README.md` against a clean run, every number in the report against the CSV, and the plot labels. Make `DESIGN_LOG.md` a dated record of actual work sessions and **three genuine occasions** where AI suggestions were wrong, slow, or incomplete, plus how you found and corrected each. Do not invent development history.
26. Record a roughly five-minute demonstration showing the running Java program, a case 10 or 11 tree and its grouping, one measured report number and what its counter means, and the source organization. Use `DEMO_VIDEO_SCRIPT.md` as an outline. A generated narrated demo can show functionality; review the instructor's expectations and prepare to demonstrate and explain the source personally.
27. Verify `make clean && make test` and a sample tree/query from `README.md`. Package source, docs, tests, generator, benchmark script, plot, raw data, and video together; exclude compiled `.class` files and generated large relation inputs. Inspect ZIP contents and integrity. If a GitHub repository is available, create it and submit its URL via Brightspace; the ZIP is a convenient handoff, not that submission step.

## Existing Java project commands

From the project root:

~~~sh
make clean && make test
sh tools/java.sh -cp build RA --tree "A union B minus C"
sh tools/java.sh -cp build RA --data examples/employees.ra --query "project[DID](Employees)"
python3 tools/benchmark.py  # writes docs/*; runs the 64k join
~~~

The included `REPORT.md` and CSV were measured from this Java implementation in the recorded environment. If the AI or student changes the evaluator or measurement method, rerun the experiment and revise every dependent number and statement.
