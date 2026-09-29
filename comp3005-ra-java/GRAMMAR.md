# GRAMMAR.md — Relational Algebra Engine

This document defines the concrete language for COMP 3005 Bonus Project 1. The ASCII spellings here are the required syntax. This grammar is intended to be the contract for a hand-written scanner, recursive-descent parser, tree printer, and evaluator.

## 1. Notation and entry points

The EBNF notation below uses:

- `X, Y` for concatenation, `X | Y` for alternatives.
- `[ X ]` for an optional occurrence and `{ X }` for zero or more occurrences.
- Double quotation marks for exact, case-sensitive terminal spellings.
- `? description ?` for a character class or lexical restriction stated in words.
- `EOF` and `EOL` for end of input and end of a physical line.

There are **two entry points**. Parse a relation-definition file with `catalog`; parse one query with `query`. Query input has no implicit relation declarations. The evaluator resolves relation references against a separately loaded catalog. Line endings CRLF and CR are normalized to EOL, and a file whose final physical line lacks a newline receives one virtual EOL before EOF. This makes a closing brace at end of file valid.

The scanner retains each token's starting offset and line/column. It ignores horizontal whitespace between query tokens and skips blank lines and whole-line comments. A whole-line comment begins with `//` after optional horizontal whitespace; `//` within a quoted string is data. Inline comments after a definition or tuple are not part of this language.

### 1.1 Lexical forms

~~~ebnf
identifier       = ( letter | "_" ), { letter | digit | "_" } ;
non_not_id       = ? identifier whose exact spelling is not "not" ? ;
letter           = ? one ASCII character A-Z or a-z ? ;
digit            = ? one ASCII character 0-9 ? ;

number           = [ "-" ], digit, { digit },
                   [ ".", digit, { digit } ] ;

quoted_string    = "'", { quoted_character | "''" }, "'" ;
quoted_character = ? any character except a single quote or EOL ? ;

bare_string      = bare_character, { bare_character } ;
bare_character   = ? any character except comma, single quote,
                     parenthesis, horizontal whitespace, or EOL ? ;

horizontal_ws    = { " " | ? horizontal tab ? } ;
comment          = "//", { ? any character except EOL ? } ;
ignored_line     = horizontal_ws, [ comment ], EOL ;
~~~

Identifiers and keywords are case-sensitive. For example, `union` is an operator in an infix-operator position and an identifier where a relation or attribute name is expected. In particular, `select[union=3](R)` compares the attribute named `union` with the number 3. The scanner may emit a single generic WORD token for every identifier spelling; the parser interprets operator spellings in context.

A query's numeric spelling is one NUMBER token. Thus `Age>=30` contains `>=`, while `Age>-30` contains `>` followed by NUMBER(`-30`). A decimal point belongs to a number only when it follows a digit and is followed by a digit. The grammar accepts `0`, `-30`, `3.14`, and `-0.5`, and does not accept scientific notation or `.5`. In an attribute reference, `.` separates identifiers. The scanner checks two-character operators `>=`, `<=`, and `!=` before emitting their single-character alternatives. A stray `!` or `-` outside a number is a lexical error.

A quoted string can contain spaces, commas, parentheses, and `//`. Within it, two consecutive single quotes decode to one literal quote: `'O''Brien'` denotes `O'Brien`. Empty quoted strings (`''`) are allowed. A quoted string cannot span lines; reaching EOL or EOF before its closing quote is a lexical error at its opening position.

**Definition values and query operands are different contexts.** In a tuple line, scan one entire unquoted field up to the next comma or EOL, trim surrounding horizontal whitespace, then classify the whole field as NUMBER if it exactly matches `number`, otherwise as BARE_STRING. For example, unquoted `1abc` is one string value; `1` is numeric and `'1'` is a string. A bare value cannot contain comma, horizontal whitespace, parentheses, or a quote; quote it if it does. Inside a query condition, an unquoted identifier is always an attribute reference; write string constants in single quotes.

### 1.2 Relation-definition file

A definition header and each tuple occupy one physical line. A tuple line contains one or more values separated by commas; the number of values must equal the number of attributes. The closing brace occupies its own line. Blank and whole-line comment lines may appear outside and within definitions. The whitespace shown here is permitted wherever `horizontal_ws` appears.

~~~ebnf
catalog          = { ignored_line | relation_definition }, EOF ;

relation_definition
                 = header_line,
                   { ignored_line | tuple_line },
                   closing_line ;

header_line      = horizontal_ws, identifier, horizontal_ws,
                   "(", horizontal_ws, attribute_names, horizontal_ws, ")",
                   horizontal_ws, "=", horizontal_ws, "{",
                   horizontal_ws, EOL ;

attribute_names  = identifier,
                   { horizontal_ws, ",", horizontal_ws, identifier } ;

tuple_line       = horizontal_ws, data_value,
                   { horizontal_ws, ",", horizontal_ws, data_value },
                   horizontal_ws, EOL ;

data_value       = number | quoted_string | bare_string ;

closing_line     = horizontal_ws, "}", horizontal_ws, EOL ;
~~~

A `}` that is the entire non-whitespace content of a line closes a definition. Quote the value `'}'` if it would otherwise be mistaken for that closing line. Similarly, a tuple line whose first non-whitespace characters are `//` is a comment; quote a first-column string beginning with those characters.

The following is valid:

~~~text
// employees and their departments
Employees (EID, Name, Age, DID) = {
  E1, John, 32, D1
  E2, Alice, 28, D2
  E3, Bob, 29, D1
}
~~~

### 1.3 Query expressions and conditions

Except inside quoted strings, query whitespace (including newlines) separates tokens and has no grammatical significance. The words in quotation marks below are exact keyword spellings, recognized in the indicated parser positions.

~~~ebnf
query            = expression, EOF ;

expression       = set_expression ;

set_expression   = intersection_expression,
                   { ( "union" | "minus" ), intersection_expression } ;

intersection_expression
                 = product_expression,
                   { "intersect", product_expression } ;

product_expression
                 = unary_expression,
                   { product_operator, unary_expression } ;

product_operator = "times"
                 | "join", "[", condition, "]" ;

unary_expression = "select", "[", condition, "]",
                   "(", expression, ")"
                 | "project", "[", attribute_list, "]",
                   "(", expression, ")"
                 | "rename", "[", identifier, "]",
                   "(", expression, ")"
                 | primary_expression ;

primary_expression
                 = identifier
                 | "(", expression, ")" ;

attribute_list   = attribute_reference,
                   { ",", attribute_reference } ;

attribute_reference
                 = identifier, [ ".", identifier ] ;

condition        = or_condition ;

or_condition     = and_condition, { "or", and_condition } ;

and_condition    = not_condition, { "and", not_condition } ;

not_condition    = "not", not_condition
                 | condition_atom ;

condition_atom   = "(", condition, ")"
                 | comparison ;

comparison       = leading_operand, comparison_operator, operand ;

comparison_operator
                 = "=" | "!=" | "<" | "<=" | ">" | ">=" ;

leading_operand  = number
                 | quoted_string
                 | leading_attribute ;

leading_attribute
                 = non_not_id, [ ".", identifier ]
                 | "not", ".", identifier ;

operand          = number
                 | quoted_string
                 | attribute_reference ;
~~~

The `leading_operand` restriction resolves the one keyword collision at the beginning of a condition: unqualified `not` is the prefix boolean operator there. To compare an attribute named `not` on the left, qualify it, as in `R.not=1`. On the right side of a comparison, `not` can be an ordinary unqualified attribute reference. All other keyword spellings, including `union`, may be unqualified attribute references. An identifier spelled `select`, `project`, or `rename` starts a unary expression only when followed by `[`; otherwise it can name a relation. After a completed left expression, the infix words are operators; after an infix operator, the next word may name a relation.

An empty projection list is impossible under `attribute_list`: `project[](R)` is a syntax error. Qualified projection attributes such as `project[Emp.DID](...)` are accepted. The unary input parentheses are required, so `select[a=1]R` is invalid.

## 2. Precedence and associativity

| Rank (highest first) | Operators or forms | Associativity / grouping | Enforcing production |
| --- | --- | --- | --- |
| 1 | Relation name; parenthesized expression | Parentheses override all default grouping | `primary_expression` |
| 2 | `select[...](...)`, `project[...](...)`, `rename[...](...)` | Explicitly parenthesized input; nested from inside out | `unary_expression` |
| 3 | `times`, `join[condition]` | Left | `product_expression` |
| 4 | `intersect` | Left | `intersection_expression` |
| 5 | `union`, `minus` | Left, with equal precedence | `set_expression` |

At each repeated binary level, the first operand is followed by zero or more (operator, next operand) pairs. Build each new binary tree node from the accumulated tree on the **left** and the newly parsed operand on the **right**. This left fold is the AST construction rule for those EBNF repetitions. For example, `A union B minus C` is `(A union B) minus C`, and `A minus B minus C` is `(A minus B) minus C`. The rule is the same for `times` and `join` when mixed at rank 3. Lower-ranked rules call the next higher-ranked rule, so `A union B intersect C` is `A union (B intersect C)`.

| Rank (highest first) | Condition form | Associativity / grouping | Enforcing production |
| --- | --- | --- | --- |
| 1 | Parenthesized condition; comparison `= != < <= > >=` | Comparisons have exactly two operands and cannot chain | `condition_atom`, `comparison` |
| 2 | `not` | Right: `not not p` is `not (not p)` | `not_condition` |
| 3 | `and` | Left | `and_condition` |
| 4 | `or` | Left | `or_condition` |

Thus `not (a=1 and b=2) or c>3` groups as `(not ((a=1) and (b=2))) or (c>3)`. Chained comparisons such as `a<b<c` are invalid.

## 3. Ambiguity in the deliberately naive grammar

Consider the assignment's naive grammar:

~~~ebnf
Expr = Expr, "union", Expr
     | Expr, "minus", Expr
     | "(", Expr, ")"
     | identifier ;
~~~

It admits **two different parse trees** for `A union B minus C`.

Tree 1, `A union (B minus C)`:

~~~mermaid
flowchart TD
  r["Expr"] --> aexpr["Expr"]
  r --> u["union"]
  r --> sub["Expr"]
  aexpr --> a["A"]
  sub --> bexpr["Expr"]
  sub --> m["minus"]
  sub --> cexpr["Expr"]
  bexpr --> b["B"]
  cexpr --> c["C"]
~~~

Tree 2, `(A union B) minus C`:

~~~mermaid
flowchart TD
  r["Expr"] --> sub["Expr"]
  r --> m["minus"]
  r --> cexpr["Expr"]
  sub --> aexpr["Expr"]
  sub --> u["union"]
  sub --> bexpr["Expr"]
  aexpr --> a["A"]
  bexpr --> b["B"]
  cexpr --> c["C"]
~~~

Give all three relations the one-column schema `(x)`: `A={(1)}`, `B={(2)}`, `C={(1)}`. Then:

| Tree | Evaluation | Result rows |
| --- | --- | --- |
| 1 | `A union (B minus C)` = `{(1)} union {(2)}` | `{(1), (2)}` |
| 2 | `(A union B) minus C` = `{(1), (2)} minus {(1)}` | `{(2)}` |

The stratified `set_expression` → `intersection_expression` → `product_expression` → `unary_expression` → `primary_expression` productions in Section 1.3 remove the ambiguity. Their left-fold rule **forces Tree 2**. Explicit parentheses still permit Tree 1 as a different input.

Subtraction also shows why associativity matters. Let `A={(1),(2)}`, `B={(2)}`, and `C={(2)}`, all with schema `(x)`. The specified `(A minus B) minus C` produces `{(1)}`; the other grouping `A minus (B minus C)` produces `{(1),(2)}`.

## 4. Parsing strategy

Use a hand-written character scanner followed by a predictive recursive-descent parser. Implement one parser function per nonterminal, especially one per precedence level. Parsing starts at `query`, descends through the lower precedence levels, and builds AST nodes on the way back. Each repeated binary production is a loop that consumes one operator and one right operand at a time, wrapping the accumulated left tree. Conditions have their own analogous functions. Token positions support clear lexical and syntax diagnostics and a tree printer can visit the AST without executing it.

A directly left-recursive rule such as `Expr = Expr, "union", Expr | identifier` makes a straightforward recursive-descent function call itself immediately, without consuming a token, until it overflows the call stack. Section 1.3 avoids that at exactly `set_expression`, `intersection_expression`, `product_expression`, `or_condition`, and `and_condition`: each starts with a higher-precedence operand and then iterates. Recursive calls inside the parenthesized and prefix forms consume an opening delimiter or `not` first, so they are not left recursion.

Report syntax errors at the current unexpected token's position; if an expected closing token is absent, report its position at EOF. Report an unterminated quoted string at the opening quote's position. A top-level error boundary should print the category and message rather than a stack trace.

## 5. Static and runtime meaning

These rules are part of the language contract; the EBNF alone determines syntax, not whether a query is meaningful against particular relations.

### 5.1 Schemas, values, and names

- Input relations are sets. A row is an ordered tuple of typed values. Two rows are equal if they have the same number of values and corresponding values are equal with the same type. Numeric spellings such as `1` and `1.0` denote equal numbers; numeric `1` and string `'1'` are different. Use an exact numeric representation such as decimal to avoid floating-point equality surprises.
- Attribute order is significant. An input column stores a base attribute name, its source relation qualifier, and its inferred type. Each initial column is qualified by the relation name for name resolution, though a simple initial schema may be printed with bare names.
- Infer each column's type from its data: NUMBER or STRING. A column containing both is a type error in the definition. A column in an empty relation has UNKNOWN type. UNKNOWN is compatible with either known type during set compatibility checks; a union that adds known values resolves the resulting internal type to that known type. Visible output names and order still come from the left schema.
- `R.x` resolves only the column whose qualifier is `R` and base name is `x`. Unqualified `x` must match exactly one column; zero matches is unknown attribute and multiple matches is ambiguous attribute. Name matching is case-sensitive.
- Compare numbers with numbers and strings with strings only. Numeric comparison is numeric; string ordering is lexicographic by Unicode code point. Mismatched types are a type error, even if equality would otherwise have returned false. Validate known types before tuple iteration; UNKNOWN may require validation when a value is encountered. Boolean conditions use ordinary two-valued logic because nulls are absent.

### 5.2 Operator results

| Operator | Result schema and behavior |
| --- | --- |
| `select[c](E)` | Same schema as `E`; retain rows satisfying `c`. Operands may refer to two attributes. |
| `project[attrs](E)` | Requested attributes in requested order, then remove duplicate resulting rows. A repeated resolved attribute is a schema error, even if one occurrence is qualified and the other is not. |
| `rename[N](E)` | Preserve base attribute names, types, and rows, but give every output column qualifier `N`. Reject qualified-name collisions caused by the rename. |
| `E times F` | All pairs of input rows. Output columns from both inputs retain their source qualifiers and are printed qualified, such as `Emp.DID` and `Dept.DID`. If any full `qualifier.name` repeats, raise a schema error. |
| `E join[c] F` | Theta join: the result is precisely `select[c](E times F)`, including both copies of same-named columns. A dedicated nested-loop implementation may avoid materializing the entire product but must preserve these semantics. |
| `E union F` | Distinct rows from either input; output uses the left schema. Require union compatibility first. |
| `E intersect F` | Distinct rows shared by both; output uses the left schema. Require union compatibility first. |
| `E minus F` | Rows in the left input absent from the right; output uses the left schema. Require union compatibility first. |

Union compatibility means equal attribute counts, identical **base attribute names in the same order**, and compatible types at each position. Qualifiers are not compared: `R(a,b) union S(a,b)` is valid even though the relations are named differently. The result preserves the left columns' names/order/qualifiers. An empty result still displays its schema and an empty body.

A self-product `Emp times Emp` collides on qualified names. Rename one occurrence to `E2` first. In the required example `rename[E2](Emp) join[Emp.MgrID=E2.EID] Emp`, the left columns have qualifier `E2` and the right columns have qualifier `Emp`, so the condition can identify both copies.

### 5.3 Errors

| Category | Example | Required response |
| --- | --- | --- |
| Lexical | `select[Name='Bob](R)` | Unterminated string, position of opening quote |
| Syntax | `select[Age>30](R` | Missing `)`, position at EOF |
| Syntax | `project[](R)` | Expected an attribute after `[` |
| Name | Unknown relation or attribute; ambiguous unqualified attribute | Name the missing or ambiguous name |
| Schema | Incompatible union; duplicate projection attribute; qualified-name collision | State incompatible columns or collision |
| Type | `select[Age>'30'](R)` when Age is numeric | State incompatible NUMBER/STRING comparison |

A malformed definition (wrong tuple arity, duplicate attribute names, inconsistent column types, duplicate relation name) also receives a controlled input/schema/type error. No invalid user query should produce a traceback.

## 6. Required-case traceability

The following checklist connects each assignment case to a grammar or semantic rule. Cases that reference unspecified `R`, `A`, or `B` concern parsing unless sample data is supplied for execution.

| Case | Required observation |
| --- | --- |
| 1 | `select[x1=3](R)` scans and parses without whitespace. |
| 2 | Spaces around `x1 = 3` produce the same AST as case 1. |
| 3 | `Age>=30` scans as one `>=` operator. |
| 4 | `Age>-30` scans as `>` and one negative NUMBER. |
| 5 | `'Bob)'` is one STRING; `)` inside it does not close a group. |
| 6 | `'a,b'` is one STRING; comma inside it is not a separator. |
| 7 | `'O''Brien'` decodes to the single string `O'Brien`. |
| 8 | `union` in `select[union=3](R)` is an attribute reference. |
| 9 | Unclosed `'Bob` is a lexical error with an opening-quote position. |
| 10 | `A union B minus C` produces `Minus(Union(A,B),C)`. |
| 11 | `A minus B minus C` produces `Minus(Minus(A,B),C)`; Section 3 supplies differing outputs for the other grouping. |
| 12 | `not (a=1 and b=2) or c>3` groups as `Or(Not(And(...)),Gt(...))`. |
| 13 | `a=1 and b=2 or c=3` groups as `Or(And(...),Eq(...))`. |
| 14 | Nested project/select/select has three unary nodes and evaluates from the inner select outward. |
| 15 | `(A union B) minus (C intersect D)` respects explicit parentheses. |
| 16 | A missing `)` is a syntax error with a position. |
| 17 | `project[](R)` is a syntax error because `attribute_list` is nonempty. |
| 18 | `select[A=B](R)` compares the two attributes named A and B. |
| 19 | `Emp join[Emp.DID=Dept.DID] Dept` resolves both qualified columns and preserves both DID columns. |
| 20 | The E2/Emp self join resolves each copy under its distinct qualifier; without rename the product collides. |
| 21 | Set operations on different schemas raise a schema error. |
| 22 | Numeric Age compared with quoted `'30'` raises a type error. |
| 23 | `project[DID](Employees)` on the sample data yields only D1 and D2. |
| 24 | `project[Name, Name](R)` is a clear duplicate-attribute schema error. |
| 25 | A zero-row result prints its schema and an empty body. |

## 7. Sources and AI assistance audit

References consulted for **this draft**:

1. The Carleton University COMP 3005 Fall 2026 Bonus Project 1 brief supplied with this conversation, especially Sections 4–7.
2. Robert Nystrom, [Scanning](https://craftinginterpreters.com/scanning.html), for scanning, token positions, strings, and maximal munch.
3. Robert Nystrom, [Representing Code](https://craftinginterpreters.com/representing-code.html), for the distinction between lexical and syntactic grammars.
4. Robert Nystrom, [Parsing Expressions](https://craftinginterpreters.com/parsing-expressions.html), for precedence-stratified recursive descent and the left-recursion issue.

Actual issues found while reviewing earlier AI assistance in this conversation:

- An earlier explanation loosely called the implementation “six operators.” Section 4.2 specifies **eight concrete operator forms**: three unary and five binary. The six-core phrasing does not remove `intersect` or `join` from the required syntax. This draft defines all eight.
- The earlier plan said set operations preserve the left schema but did not say whether differing relation qualifiers make `R union S` incompatible. Section 4.3 compares attribute names and types, and ordinary `R(a) union S(a)` needs to work; Section 5.2 now compares base names while retaining the left qualifier.
- The earlier plan did not define types for a relation with zero rows. Section 5.1 now assigns UNKNOWN and specifies compatibility and subsequent type resolution.
- The earlier plan allowed keyword-shaped attribute names without handling unqualified `not` at the beginning of a condition. The `leading_operand` production and the qualification rule now make that boundary explicit.

These are drafting notes, not a substitute for the student's own dated `DESIGN_LOG.md`. The student should review the cited material and update this document if the implemented parser makes a different documented choice.

