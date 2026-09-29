"""All 25 required cases, plus a few edge cases for data and counters."""
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_RUNNER = ROOT / "tools" / "java.sh"
EMPLOYEES = (ROOT / "examples" / "employees.ra").read_text(encoding="utf-8")


def call(query, data=None, tree=False, metrics=False, count_only=False):
    args = ["sh", str(JAVA_RUNNER), "-cp", str(ROOT / "build"), "RA",
            "--tree" if tree else "--query", query]
    if data is not None:
        args += ["--data", str(data)]
    if metrics:
        args.append("--metrics")
    if count_only:
        args.append("--count-only")
    return subprocess.run(args, cwd=ROOT, text=True, capture_output=True, check=False)


class RequiredCases(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not (ROOT / "build" / "RA.class").exists():
            subprocess.run(["make"], cwd=ROOT, check=True, capture_output=True)

    def query(self, text, definitions, metrics=False, count_only=False):
        with tempfile.TemporaryDirectory(dir=ROOT) as folder:
            path = Path(folder) / "relations.ra"
            path.write_text(definitions, encoding="utf-8")
            return call(text, data=path, metrics=metrics, count_only=count_only)

    def tree(self, text):
        p = call(text, tree=True)
        self.assertEqual(p.returncode, 0, p.stderr)
        return p.stdout

    def assert_error(self, p, category, term=None):
        self.assertEqual(p.returncode, 2, (p.stdout, p.stderr))
        self.assertIn(category, p.stderr)
        self.assertRegex(p.stderr, r"at \d+:\d+:")
        self.assertNotIn("Traceback", p.stderr)
        if term is not None:
            self.assertIn(term, p.stderr)

    def test_01_adjacent_tokens(self):
        self.assertIn("Select(cond=Eq(Attr(x1),Num(3)))", self.tree("select[x1=3](R)"))

    def test_02_spacing_same_tree(self):
        self.assertEqual(self.tree("select[x1=3](R)"), self.tree("select[ x1 = 3 ](R)"))

    def test_03_maximal_munch(self):
        self.assertIn("Ge(Attr(Age),Num(30))", self.tree("select[Age>=30](R)"))

    def test_04_negative_number(self):
        self.assertIn("Gt(Attr(Age),Num(-30))", self.tree("select[Age>-30](R)"))

    def test_05_parenthesis_inside_string(self):
        self.assertIn("Str('Bob)')", self.tree("select[Name='Bob)'](R)"))

    def test_06_comma_inside_string(self):
        self.assertIn("Str('a,b')", self.tree("select[Name='a,b'](R)"))

    def test_07_doubled_quote(self):
        self.assertIn("Str('O'Brien')", self.tree("select[Name='O''Brien'](R)"))

    def test_08_keyword_named_attribute(self):
        self.assertIn("Attr(union)", self.tree("select[union=3](R)"))

    def test_09_unterminated_string(self):
        self.assert_error(call("select[Name='Bob](R)", tree=True), "lexical error", "unterminated")

    def test_10_set_grouping(self):
        lines = self.tree("A union B minus C").splitlines()
        self.assertEqual(lines, ["Minus", "  Union", "    Relation(A)",
                                 "    Relation(B)", "  Relation(C)"])

    def test_11_minus_associativity_changes_answer(self):
        defs = "A (x) = {\n  1\n  2\n}\nB (x) = {\n  2\n}\nC (x) = {\n  2\n}\n"
        actual = self.query("A minus B minus C", defs)
        other = self.query("A minus (B minus C)", defs)
        self.assertEqual(actual.returncode, 0, actual.stderr)
        self.assertIn("rows: 1", actual.stdout)
        self.assertIn("rows: 2", other.stdout)
        self.assertEqual(self.tree("A minus B minus C").splitlines()[:3],
                         ["Minus", "  Minus", "    Relation(A)"])

    def test_12_not_and_or(self):
        t = self.tree("select[not (a=1 and b=2) or c>3](R)")
        self.assertIn("Or(Not(And(Eq(Attr(a),Num(1)),Eq(Attr(b),Num(2)))),Gt(Attr(c),Num(3)))", t)

    def test_13_and_before_or(self):
        t = self.tree("select[a=1 and b=2 or c=3](R)")
        self.assertIn("Or(And(Eq(Attr(a),Num(1)),Eq(Attr(b),Num(2))),Eq(Attr(c),Num(3)))", t)

    def test_14_nested_unary(self):
        t = self.tree("project[Name](select[Age>30](select[DID='D1'](Employees)))")
        self.assertEqual(t.splitlines()[0], "Project(attrs=[Name])")
        self.assertEqual(t.count("Select("), 2)
        p = self.query("project[Name](select[Age>30](select[DID='D1'](Employees)))", EMPLOYEES)
        self.assertIn("'John'", p.stdout)
        self.assertIn("rows: 1", p.stdout)

    def test_15_explicit_parentheses(self):
        t = self.tree("(A union B) minus (C intersect D)").splitlines()
        self.assertEqual(t[0:2], ["Minus", "  Union"])
        self.assertIn("  Intersect", t)

    def test_16_missing_parenthesis(self):
        self.assert_error(call("select[Age>30](R", tree=True), "syntax error", "')'")

    def test_17_empty_project(self):
        self.assert_error(call("project[](R)", tree=True), "syntax error", "attribute name")

    def test_18_compare_columns(self):
        defs = "R (A, B) = {\n  1, 1\n  1, 2\n  2, 2\n}\n"
        p = self.query("select[A=B](R)", defs)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("rows: 2", p.stdout)
        self.assertNotIn("  1, 2\n", p.stdout)

    def test_19_theta_join_qualified_did(self):
        p = self.query("Emp join[Emp.DID=Dept.DID] Dept", EMPLOYEES)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("Emp.DID", p.stdout)
        self.assertIn("Dept.DID", p.stdout)
        self.assertIn("rows: 2", p.stdout)

    def test_20_self_join_using_rename(self):
        p = self.query("rename[E2](Emp) join[Emp.MgrID=E2.EID] Emp", EMPLOYEES)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("E2.EID", p.stdout)
        self.assertIn("Emp.EID", p.stdout)
        self.assertIn("rows: 2", p.stdout)
        self.assert_error(self.query("Emp times Emp", EMPLOYEES), "schema error", "duplicate qualified")

    def test_21_incompatible_union(self):
        defs = "R (a) = {\n  1\n}\nS (b) = {\n  1\n}\n"
        self.assert_error(self.query("R union S", defs), "schema error", "differ at attribute")

    def test_22_number_string_comparison_error(self):
        self.assert_error(self.query("select[Age>'30'](Employees)", EMPLOYEES),
                          "type error", "NUMBER with STRING")

    def test_23_project_deduplication(self):
        p = self.query("project[DID](Employees)", EMPLOYEES)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("rows: 2", p.stdout)
        self.assertEqual(p.stdout.count("'D1'"), 1)

    def test_24_repeated_projection_error(self):
        self.assert_error(self.query("project[Name, Name](Employees)", EMPLOYEES),
                          "schema error", "duplicate projection")

    def test_25_empty_result_shows_schema(self):
        p = self.query("select[Age>100](Employees)", EMPLOYEES)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("schema: [Employees.EID, Employees.Name, Employees.Age, Employees.DID]", p.stdout)
        self.assertIn("rows: 0\n{\n}\n", p.stdout)

    def test_set_types_numeric_canonicalization(self):
        defs = "R (x) = {\n  1\n  1.0\n}\nS (x) = {\n  1.00\n  2\n}\n"
        p = self.query("R union S", defs)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("rows: 2", p.stdout)
        self.assertIn("schema: [R.x]", p.stdout)

    def test_unknown_and_ambiguous_names(self):
        self.assert_error(self.query("select[bad=2](Employees)", EMPLOYEES), "name error", "bad")
        self.assert_error(self.query("select[DID='D1'](Emp times Dept)", EMPLOYEES),
                          "name error", "ambiguous")

    def test_definition_arity_and_mixed_types(self):
        self.assert_error(self.query("R", "R (a, b) = {\n  1\n}\n"),
                          "schema error", "expects 2")
        self.assert_error(self.query("R", "R (a) = {\n  1\n  'x'\n}\n"),
                          "type error", "mixes")

    def test_join_and_select_counters(self):
        defs = "R (a, b) = {\n  1, 5\n  2, 6\n}\nS (b, c) = {\n  5, 7\n  8, 9\n  6, 10\n}\n"
        p = self.query("select[R.a>0](R join[R.b=S.b] S)", defs, metrics=True)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("join_1_comparisons=6", p.stderr)
        self.assertIn("select_1_evaluations=2", p.stderr)

    def test_keyword_not_and_data_strings(self):
        defs = "R (not, Name) = {\n  1, 'O''Brien'\n  2, 'a,b'\n}\n"
        p = self.query("select[R.not=1](R)", defs)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("'O''Brien'", p.stdout)
        self.assertIn("rows: 1", p.stdout)

    def test_decimal_order_and_empty_relation_unknown(self):
        defs = "R (x) = {\n  -0.05\n  -0.5\n  0.005\n}\nS (x) = {\n}\n"
        p = self.query("select[x<0](R)", defs)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("rows: 2", p.stdout)
        self.assertEqual(self.query("S union R", defs).returncode, 0)

    def test_all_set_operators_and_left_schema(self):
        defs = "R (x) = {\n  1\n  2\n}\nS (x) = {\n  2\n  3\n}\n"
        for expr, count in (("R union S", 3), ("R intersect S", 1), ("R minus S", 1)):
            p = self.query(expr, defs)
            self.assertEqual(p.returncode, 0, p.stderr)
            self.assertIn(f"rows: {count}", p.stdout)
            self.assertIn("schema: [R.x]", p.stdout)

    def test_boolean_truth_values(self):
        defs = "R (a, b, c) = {\n  1, 2, 3\n  1, 9, 4\n  0, 2, 4\n}\n"
        p = self.query("select[not (a=1 and b=2) or c>3](R)", defs)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("rows: 2", p.stdout)

    def test_non_equality_theta_join(self):
        defs = "R (a) = {\n  1\n  2\n}\nS (b) = {\n  2\n  3\n}\n"
        p = self.query("R join[R.a<S.b] S", defs, metrics=True)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("rows: 3", p.stdout)
        self.assertIn("join_1_comparisons=4", p.stderr)

    def test_input_duplicates_and_rename_collision(self):
        defs = "R (a, b) = {\n  1, x\n  1, x\n  2, y\n}\n"
        p = self.query("R", defs)
        self.assertEqual(p.returncode, 0, p.stderr)
        self.assertIn("rows: 2", p.stdout)
        self.assert_error(self.query("rename[X](R times rename[S](R))", defs),
                          "schema error", "rename creates duplicate")


if __name__ == "__main__":
    unittest.main()
