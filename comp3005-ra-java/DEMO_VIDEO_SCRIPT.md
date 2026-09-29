# DEMO_VIDEO_SCRIPT.md — Java engine, about five minutes

The included captioned MP4 uses actual Java command outputs and synthetic narration. For a personal video, record the commands below in your own terminal and explain the parts you understand in your own voice.

| Time | Show | Explain |
| --- | --- | --- |
| 0:00–0:40 | `make test`, repository files | Show that 35 tests pass, including all 25 required cases. Locate `src/RA.java`, the generator, and report. |
| 0:40–1:25 | `sh tools/java.sh -cp build RA --tree "A union B minus C"` | Minus is the root; Union is its left child, giving `(A union B) minus C`. Relate this to the EBNF left fold. |
| 1:25–2:00 | Print `project[Name](select[Age>30](Employees))` as a tree | Explain that parsing constructs a tree without running a query, and evaluation visits the child first. |
| 2:00–2:50 | Run `project[DID](Employees)` against the example file | Point out two distinct output rows from three input rows and show how tuple equality removes duplicates. |
| 2:50–3:30 | Run the E2/Emp self join and `Emp times Emp` | Explain qualifier changes under rename and the controlled duplicate-schema error without rename. |
| 3:30–4:20 | Show `REPORT.md`, the plot, and 64,000-row count | State that Java measured 4,096,000,000 comparisons and a 6.730319568-second median. Explain why the largest-size slope is near two and why HotSpot affects small runs. |
| 4:20–5:00 | Show Scanner, Parser.parseSet, and evaluate in `src/RA.java` | Explain token positions, no left recursion, join counter inside its inner loop, and the arithmetic of the 1,000,000-row prediction. |

Commands:

~~~sh
make test
sh tools/java.sh -cp build RA --tree "A union B minus C"
sh tools/java.sh -cp build RA --tree "project[Name](select[Age>30](Employees))"
sh tools/java.sh -cp build RA --data examples/employees.ra \
  --query "project[DID](Employees)"
sh tools/java.sh -cp build RA --data examples/employees.ra \
  --query "rename[E2](Emp) join[Emp.MgrID=E2.EID] Emp"
sh tools/java.sh -cp build RA --data examples/employees.ra \
  --query "Emp times Emp"
~~~

The assignment also has an oral check. Review the source and measure again on your own machine before citing local timing numbers.

