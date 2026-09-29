import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** An in-memory relational algebra engine: hand-written scanner, parser, and operators. */
public final class RA {
    private RA() {}
    private static volatile long benchmarkSink;

    static final class Pos {
        final int line, column;
        Pos(int line, int column) { this.line = line; this.column = column; }
    }

    static final class RAError extends RuntimeException {
        final String category;
        final Pos pos;
        RAError(String category, String message, Pos pos) {
            super(message);
            this.category = category;
            this.pos = pos;
        }
    }
    static RAError error(String category, String message, Pos pos) {
        return new RAError(category, message, pos);
    }
    static boolean digit(char c) { return c >= '0' && c <= '9'; }
    static boolean letter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
    static boolean identStart(char c) { return letter(c) || c == '_'; }
    static boolean identPart(char c) { return identStart(c) || digit(c); }

    enum TK { WORD, NUMBER, STRING, LP, RP, LB, RB, LBRACE, RBRACE,
              COMMA, DOT, EQ, NE, LT, LE, GT, GE, END }
    static final class Token {
        final TK kind;
        final String text;
        final Pos pos;
        Token(TK kind, String text, Pos pos) { this.kind = kind; this.text = text; this.pos = pos; }
    }
    static String tokenName(TK kind) {
        switch (kind) {
            case WORD: return "identifier";
            case NUMBER: return "number";
            case STRING: return "string";
            case LP: return "("; case RP: return ")";
            case LB: return "["; case RB: return "]";
            case LBRACE: return "{"; case RBRACE: return "}";
            case COMMA: return ","; case DOT: return ".";
            case EQ: return "="; case NE: return "!=";
            case LT: return "<"; case LE: return "<=";
            case GT: return ">"; case GE: return ">=";
            default: return "end of input";
        }
    }

    /** Lexes one character at a time. Only whole-line // comments are skipped. */
    static final class Scanner {
        final String source;
        int index = 0;
        int line;
        int column = 1;
        boolean onlySpacesOnLine = true;
        Scanner(String text, int startLine) {
            source = text.replace("\r\n", "\n").replace('\r', '\n');
            line = startLine;
        }
        char peek(int ahead) {
            int p = index + ahead;
            return p < source.length() ? source.charAt(p) : '\0';
        }
        char take() {
            char c = source.charAt(index++);
            if (c == '\n') {
                line++; column = 1; onlySpacesOnLine = true;
            } else {
                column++;
                if (c != ' ' && c != '\t') onlySpacesOnLine = false;
            }
            return c;
        }
        List<Token> scan() {
            List<Token> result = new ArrayList<>();
            while (index < source.length()) {
                char c = peek(0);
                if (c == ' ' || c == '\t' || c == '\n') { take(); continue; }
                if (c == '/' && peek(1) == '/' && onlySpacesOnLine) {
                    while (index < source.length() && peek(0) != '\n') take();
                    continue;
                }
                Pos start = new Pos(line, column);
                if (identStart(c)) {
                    StringBuilder word = new StringBuilder();
                    do { word.append(take()); } while (identPart(peek(0)));
                    result.add(new Token(TK.WORD, word.toString(), start));
                    continue;
                }
                if (digit(c) || (c == '-' && digit(peek(1)))) {
                    StringBuilder num = new StringBuilder();
                    if (c == '-') num.append(take());
                    do { num.append(take()); } while (digit(peek(0)));
                    if (peek(0) == '.' && digit(peek(1))) {
                        num.append(take());
                        do { num.append(take()); } while (digit(peek(0)));
                    }
                    result.add(new Token(TK.NUMBER, num.toString(), start));
                    continue;
                }
                if (c == '\'') {
                    take();
                    StringBuilder decoded = new StringBuilder();
                    boolean closed = false;
                    while (index < source.length() && peek(0) != '\n') {
                        if (peek(0) == '\'') {
                            take();
                            if (peek(0) == '\'') { take(); decoded.append('\''); }
                            else { closed = true; break; }
                        } else decoded.append(take());
                    }
                    if (!closed) throw error("lexical error", "unterminated quoted string", start);
                    result.add(new Token(TK.STRING, decoded.toString(), start));
                    continue;
                }
                take();
                switch (c) {
                    case '(': result.add(new Token(TK.LP, "(", start)); break;
                    case ')': result.add(new Token(TK.RP, ")", start)); break;
                    case '[': result.add(new Token(TK.LB, "[", start)); break;
                    case ']': result.add(new Token(TK.RB, "]", start)); break;
                    case '{': result.add(new Token(TK.LBRACE, "{", start)); break;
                    case '}': result.add(new Token(TK.RBRACE, "}", start)); break;
                    case ',': result.add(new Token(TK.COMMA, ",", start)); break;
                    case '.': result.add(new Token(TK.DOT, ".", start)); break;
                    case '=': result.add(new Token(TK.EQ, "=", start)); break;
                    case '>':
                        if (peek(0) == '=') { take(); result.add(new Token(TK.GE, ">=", start)); }
                        else result.add(new Token(TK.GT, ">", start));
                        break;
                    case '<':
                        if (peek(0) == '=') { take(); result.add(new Token(TK.LE, "<=", start)); }
                        else result.add(new Token(TK.LT, "<", start));
                        break;
                    case '!':
                        if (peek(0) == '=') { take(); result.add(new Token(TK.NE, "!=", start)); }
                        else throw error("lexical error", "expected '=' after '!'", start);
                        break;
                    default:
                        throw error("lexical error", "unexpected character '" + c + "'", start);
                }
            }
            result.add(new Token(TK.END, "", new Pos(line, column)));
            return result;
        }
    }

    enum Type { UNKNOWN, NUMBER, STRING }
    static final class Value {
        final Type type;
        final BigDecimal number;
        final String text;
        final Long smallInteger;
        private Value(Type type, BigDecimal number, String text, Long smallInteger) {
            this.type = type; this.number = number; this.text = text; this.smallInteger = smallInteger;
        }
        static Value numeric(String spelling) {
            BigDecimal normalized = new BigDecimal(spelling).stripTrailingZeros();
            Long small = null;
            if (normalized.scale() <= 0) {
                try { small = normalized.longValueExact(); }
                catch (ArithmeticException ignored) { /* arbitrary precision remains supported */ }
            }
            return new Value(Type.NUMBER, normalized, spelling, small);
        }
        static Value quoted(String content) {
            return new Value(Type.STRING, null, content, null);
        }
        String output() {
            if (type == Type.NUMBER) return number.toPlainString();
            return "'" + text.replace("'", "''") + "'";
        }
    }
    static String typeName(Type t) { return t.name(); }
    static boolean sameValue(Value a, Value b) {
        if (a.type != b.type) return false;
        if (a.type == Type.STRING) return a.text.equals(b.text);
        if (a.smallInteger != null && b.smallInteger != null)
            return a.smallInteger.longValue() == b.smallInteger.longValue();
        return a.number.compareTo(b.number) == 0;
    }
    static int stringCodePointCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i), cb = b.codePointAt(j);
            if (ca != cb) return Integer.compare(ca, cb);
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
    static int compare(Value a, Value b, Pos p) {
        if (a.type != b.type)
            throw error("type error", "cannot compare " + typeName(a.type) +
                        " with " + typeName(b.type), p);
        return a.type == Type.NUMBER ? a.number.compareTo(b.number) :
               stringCodePointCompare(a.text, b.text);
    }
    static boolean tupleEqual(Value[] a, Value[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (!sameValue(a[i], b[i])) return false;
        return true;
    }
    static int tupleHash(Value[] row) {
        int hash = 1;
        for (Value v : row) {
            int part = v.type == Type.NUMBER ? v.number.hashCode() : v.text.hashCode();
            hash = 31 * hash + 31 * v.type.hashCode() + part;
        }
        return hash;
    }

    /** Hash buckets reduce candidates; tupleEqual is the duplicate rule. */
    static final class RowIndex {
        final List<Value[]> rows = new ArrayList<>();
        final Map<Integer, List<Integer>> buckets = new HashMap<>();
        boolean contains(Value[] row) {
            List<Integer> bucket = buckets.get(tupleHash(row));
            if (bucket != null)
                for (int idx : bucket) if (tupleEqual(rows.get(idx), row)) return true;
            return false;
        }
        boolean add(Value[] row) {
            int hash = tupleHash(row);
            List<Integer> bucket = buckets.computeIfAbsent(hash, ignored -> new ArrayList<>());
            for (int idx : bucket) if (tupleEqual(rows.get(idx), row)) return false;
            bucket.add(rows.size());
            rows.add(row);
            return true;
        }
    }
    static final class Column {
        final String qualifier, name;
        final Type type;
        Column(String qualifier, String name, Type type) {
            this.qualifier = qualifier; this.name = name; this.type = type;
        }
    }
    static final class Relation {
        final List<Column> schema = new ArrayList<>();
        final List<Value[]> rows = new ArrayList<>();
        Relation copy() {
            Relation r = new Relation();
            r.schema.addAll(schema);
            r.rows.addAll(rows); // rows and Value objects are never mutated
            return r;
        }
    }
    static String trim(String s) { return s.trim(); }
    static boolean ignoredLine(String line) {
        String s = trim(line);
        return s.isEmpty() || s.startsWith("//");
    }
    static boolean numericSpelling(String s) {
        int i = s.startsWith("-") ? 1 : 0;
        int first = i;
        while (i < s.length() && digit(s.charAt(i))) i++;
        if (i == first) return false;
        if (i < s.length() && s.charAt(i) == '.') {
            i++; int fraction = i;
            while (i < s.length() && digit(s.charAt(i))) i++;
            if (i == fraction) return false;
        }
        return i == s.length();
    }
    static final class Header {
        final String name;
        final List<String> attrs;
        Header(String name, List<String> attrs) { this.name = name; this.attrs = attrs; }
    }
    static Header parseHeader(String line, int lineNo) {
        List<Token> t = new Scanner(line, lineNo).scan();
        int i = 0;
        if (t.get(i).kind != TK.WORD)
            throw error("syntax error", "expected relation name in header", t.get(i).pos);
        String relationName = t.get(i++).text;
        if (t.get(i).kind != TK.LP)
            throw error("syntax error", "expected '(' in relation header", t.get(i).pos);
        i++;
        List<String> names = new ArrayList<>();
        if (t.get(i).kind != TK.WORD)
            throw error("syntax error", "expected attribute name in header", t.get(i).pos);
        names.add(t.get(i++).text);
        while (t.get(i).kind == TK.COMMA) {
            i++;
            if (t.get(i).kind != TK.WORD)
                throw error("syntax error", "expected attribute name in header", t.get(i).pos);
            names.add(t.get(i++).text);
        }
        TK[] expected = {TK.RP, TK.EQ, TK.LBRACE, TK.END};
        String[] descriptions = {"')'", "'='", "'{'", "end of header line"};
        for (int j = 0; j < expected.length; j++) {
            if (t.get(i).kind != expected[j])
                throw error("syntax error", "expected " + descriptions[j] + " in relation header", t.get(i).pos);
            i++;
        }
        for (int a = 0; a < names.size(); a++)
            for (int b = 0; b < a; b++)
                if (names.get(a).equals(names.get(b)))
                    throw error("schema error", "duplicate input attribute '" + names.get(a) + "'",
                                new Pos(lineNo, 1));
        return new Header(relationName, names);
    }
    static Value[] parseTupleLine(String line, int lineNo) {
        List<Value> values = new ArrayList<>();
        int i = 0;
        while (true) {
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
            int start = i;
            if (i == line.length())
                throw error("syntax error", "missing tuple value", new Pos(lineNo, i + 1));
            if (line.charAt(i) == '\'') {
                i++;
                StringBuilder decoded = new StringBuilder();
                boolean closed = false;
                while (i < line.length()) {
                    if (line.charAt(i) == '\'') {
                        i++;
                        if (i < line.length() && line.charAt(i) == '\'') { decoded.append('\''); i++; }
                        else { closed = true; break; }
                    } else decoded.append(line.charAt(i++));
                }
                if (!closed)
                    throw error("lexical error", "unterminated quoted string", new Pos(lineNo, start + 1));
                while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
                if (i < line.length() && line.charAt(i) != ',')
                    throw error("syntax error", "expected comma after quoted value", new Pos(lineNo, i + 1));
                values.add(Value.quoted(decoded.toString()));
            } else {
                while (i < line.length() && line.charAt(i) != ',') i++;
                String field = trim(line.substring(start, i));
                if (field.isEmpty())
                    throw error("syntax error", "empty tuple value", new Pos(lineNo, start + 1));
                for (int j = 0; j < field.length(); j++) {
                    char c = field.charAt(j);
                    if (c == ' ' || c == '\t' || c == '(' || c == ')' || c == '\'')
                        throw error("lexical error", "bare string needs single quotes",
                                    new Pos(lineNo, start + 1));
                }
                values.add(numericSpelling(field) ? Value.numeric(field) : Value.quoted(field));
            }
            if (i == line.length()) break;
            i++;
            if (i == line.length())
                throw error("syntax error", "missing tuple value after comma", new Pos(lineNo, i + 1));
        }
        return values.toArray(new Value[0]);
    }
    static Map<String, Relation> readCatalog(String file) {
        List<String> lines;
        try { lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8); }
        catch (IOException e) { throw error("input error", "cannot open relation file '" + file + "'", new Pos(1, 1)); }
        Map<String, Relation> catalog = new HashMap<>();
        Header header = null;
        Relation current = null;
        RowIndex unique = null;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int lineNo = index + 1;
            if (ignoredLine(line)) continue;
            if (header == null) {
                header = parseHeader(line, lineNo);
                if (catalog.containsKey(header.name))
                    throw error("schema error", "duplicate relation '" + header.name + "'", new Pos(lineNo, 1));
                current = new Relation();
                for (String attr : header.attrs)
                    current.schema.add(new Column(header.name, attr, Type.UNKNOWN));
                unique = new RowIndex();
            } else if (trim(line).equals("}")) {
                current.rows.addAll(unique.rows);
                catalog.put(header.name, current);
                header = null; current = null; unique = null;
            } else {
                Value[] row = parseTupleLine(line, lineNo);
                if (row.length != header.attrs.size())
                    throw error("schema error", "relation '" + header.name + "' expects " +
                                header.attrs.size() + " values per tuple, got " + row.length, new Pos(lineNo, 1));
                for (int j = 0; j < row.length; j++) {
                    Column col = current.schema.get(j);
                    if (col.type == Type.UNKNOWN)
                        current.schema.set(j, new Column(col.qualifier, col.name, row[j].type));
                    else if (col.type != row[j].type)
                        throw error("type error", "attribute '" + col.name + "' mixes " +
                                    typeName(col.type) + " and " + typeName(row[j].type), new Pos(lineNo, 1));
                }
                unique.add(row);
            }
        }
        if (header != null)
            throw error("syntax error", "missing closing '}' for relation '" + header.name + "'",
                        new Pos(lines.size() + 1, 1));
        return catalog;
    }

    static final class Name {
        String qualifier, attribute;
        boolean qualified;
        Pos pos;
        String display() { return qualified ? qualifier + "." + attribute : attribute; }
    }
    static final class Operand {
        boolean literal;
        Value value;
        Name name;
        Pos pos;
    }
    enum CKind { AND, OR, NOT, COMPARE }
    enum Cmp { EQ, NE, LT, LE, GT, GE }
    static final class Condition {
        CKind kind;
        Cmp cmp;
        Pos pos;
        Operand lhs, rhs;
        Condition left, right;
        Condition(CKind kind, Pos pos) { this.kind = kind; this.pos = pos; }
    }
    enum EKind { REF, SELECT, PROJECT, RENAME, TIMES, JOIN, UNION, INTERSECT, MINUS }
    static final class Expr {
        EKind kind;
        Pos pos;
        String name;
        List<Name> attrs;
        Condition condition;
        Expr left, right;
        Expr(EKind kind, Pos pos) { this.kind = kind; this.pos = pos; }
    }

    /** One function per precedence level; loops create left-associated binary ASTs. */
    static final class Parser {
        final List<Token> tokens;
        int at = 0;
        Parser(List<Token> tokens) { this.tokens = tokens; }
        Token now() { return tokens.get(at); }
        boolean check(TK k) { return now().kind == k; }
        boolean word(String w) { return check(TK.WORD) && now().text.equals(w); }
        boolean match(TK k) { if (check(k)) { at++; return true; } return false; }
        Token expect(TK k, String what) {
            if (!check(k))
                throw error("syntax error", "expected " + what + ", found " + tokenName(now().kind), now().pos);
            return tokens.get(at++);
        }
        Name parseName() {
            Token first = expect(TK.WORD, "attribute name");
            Name name = new Name();
            name.pos = first.pos;
            name.attribute = first.text;
            if (match(TK.DOT)) {
                name.qualifier = first.text;
                name.qualified = true;
                name.attribute = expect(TK.WORD, "attribute after '.'").text;
            }
            return name;
        }
        Operand parseOperand() {
            Operand o = new Operand();
            o.pos = now().pos;
            if (check(TK.NUMBER)) {
                o.literal = true; o.value = Value.numeric(tokens.get(at++).text);
            } else if (check(TK.STRING)) {
                o.literal = true; o.value = Value.quoted(tokens.get(at++).text);
            } else o.name = parseName();
            return o;
        }
        Condition booleanNode(CKind kind, Pos p, Condition left, Condition right) {
            Condition c = new Condition(kind, p);
            c.left = left; c.right = right;
            return c;
        }
        Condition parseCompare() {
            Condition c = new Condition(CKind.COMPARE, now().pos);
            c.lhs = parseOperand();
            switch (now().kind) {
                case EQ: c.cmp = Cmp.EQ; break;
                case NE: c.cmp = Cmp.NE; break;
                case LT: c.cmp = Cmp.LT; break;
                case LE: c.cmp = Cmp.LE; break;
                case GT: c.cmp = Cmp.GT; break;
                case GE: c.cmp = Cmp.GE; break;
                default:
                    throw error("syntax error", "expected comparison operator (=, !=, <, <=, >, >=)", now().pos);
            }
            at++;
            c.rhs = parseOperand();
            return c;
        }
        Condition parseAtom() {
            if (match(TK.LP)) {
                Condition c = parseOr();
                expect(TK.RP, "')' to close condition");
                return c;
            }
            return parseCompare();
        }
        Condition parseNot() {
            if (word("not") && (at + 1 >= tokens.size() || tokens.get(at+1).kind != TK.DOT)) {
                Pos p = now().pos;
                at++;
                return booleanNode(CKind.NOT, p, parseNot(), null);
            }
            return parseAtom();
        }
        Condition parseAnd() {
            Condition left = parseNot();
            while (word("and")) {
                Pos p = now().pos; at++;
                left = booleanNode(CKind.AND, p, left, parseNot());
            }
            return left;
        }
        Condition parseOr() {
            Condition left = parseAnd();
            while (word("or")) {
                Pos p = now().pos; at++;
                left = booleanNode(CKind.OR, p, left, parseAnd());
            }
            return left;
        }
        Expr binary(EKind kind, Pos pos, Expr left, Expr right, Condition condition) {
            Expr e = new Expr(kind, pos);
            e.left = left; e.right = right; e.condition = condition;
            return e;
        }
        Expr parsePrimary() {
            if (match(TK.LP)) {
                Expr e = parseSet();
                expect(TK.RP, "')' to close expression");
                return e;
            }
            Token t = expect(TK.WORD, "relation name or '('");
            Expr e = new Expr(EKind.REF, t.pos);
            e.name = t.text;
            return e;
        }
        Expr parseUnary() {
            if (check(TK.WORD) && at + 1 < tokens.size() && tokens.get(at+1).kind == TK.LB &&
                (word("select") || word("project") || word("rename"))) {
                Token t = tokens.get(at++);
                expect(TK.LB, "'['");
                Expr e;
                if (t.text.equals("select")) {
                    e = new Expr(EKind.SELECT, t.pos);
                    e.condition = parseOr();
                } else if (t.text.equals("project")) {
                    e = new Expr(EKind.PROJECT, t.pos);
                    e.attrs = new ArrayList<>();
                    e.attrs.add(parseName());
                    while (match(TK.COMMA)) e.attrs.add(parseName());
                } else {
                    e = new Expr(EKind.RENAME, t.pos);
                    e.name = expect(TK.WORD, "new relation name").text;
                }
                expect(TK.RB, "']'");
                expect(TK.LP, "'(' for unary input");
                e.left = parseSet();
                expect(TK.RP, "')' to close unary input");
                return e;
            }
            return parsePrimary();
        }
        Expr parseProduct() {
            Expr left = parseUnary();
            while (word("times") || word("join")) {
                Token op = tokens.get(at++);
                boolean join = op.text.equals("join");
                Condition c = null;
                if (join) {
                    expect(TK.LB, "'[' after join");
                    c = parseOr();
                    expect(TK.RB, "']' after join condition");
                }
                left = binary(join ? EKind.JOIN : EKind.TIMES, op.pos, left, parseUnary(), c);
            }
            return left;
        }
        Expr parseIntersection() {
            Expr left = parseProduct();
            while (word("intersect")) {
                Token op = tokens.get(at++);
                left = binary(EKind.INTERSECT, op.pos, left, parseProduct(), null);
            }
            return left;
        }
        Expr parseSet() {
            Expr left = parseIntersection();
            while (word("union") || word("minus")) {
                Token op = tokens.get(at++);
                left = binary(op.text.equals("union") ? EKind.UNION : EKind.MINUS,
                              op.pos, left, parseIntersection(), null);
            }
            return left;
        }
        Expr parse() {
            Expr result = parseSet();
            expect(TK.END, "end of query");
            return result;
        }
    }

    static String operandText(Operand o) {
        if (!o.literal) return "Attr(" + o.name.display() + ")";
        return o.value.type == Type.NUMBER ? "Num(" + o.value.text + ")" :
               "Str('" + o.value.text + "')";
    }
    static String conditionText(Condition c) {
        switch (c.kind) {
            case AND: return "And(" + conditionText(c.left) + "," + conditionText(c.right) + ")";
            case OR: return "Or(" + conditionText(c.left) + "," + conditionText(c.right) + ")";
            case NOT: return "Not(" + conditionText(c.left) + ")";
            default:
                String op = c.cmp.name().charAt(0) + c.cmp.name().substring(1).toLowerCase(Locale.ROOT);
                return op + "(" + operandText(c.lhs) + "," + operandText(c.rhs) + ")";
        }
    }
    static String expressionLabel(Expr e) {
        switch (e.kind) {
            case REF: return "Relation(" + e.name + ")";
            case SELECT: return "Select(cond=" + conditionText(e.condition) + ")";
            case PROJECT:
                List<String> names = new ArrayList<>();
                for (Name n : e.attrs) names.add(n.display());
                return "Project(attrs=[" + String.join(", ", names) + "])";
            case RENAME: return "Rename(name=" + e.name + ")";
            case TIMES: return "Times";
            case JOIN: return "Join(cond=" + conditionText(e.condition) + ")";
            case UNION: return "Union";
            case INTERSECT: return "Intersect";
            default: return "Minus";
        }
    }
    static void printTree(Expr e, int depth) {
        System.out.println("  ".repeat(depth) + expressionLabel(e));
        if (e.left != null) printTree(e.left, depth + 1);
        if (e.right != null) printTree(e.right, depth + 1);
    }

    static final class BoundOperand {
        final boolean literal;
        final Value value;
        final int index;
        final Type type;
        BoundOperand(boolean literal, Value value, int index, Type type) {
            this.literal = literal; this.value = value; this.index = index; this.type = type;
        }
    }
    static final class BoundCondition {
        CKind kind;
        Cmp cmp;
        Pos pos;
        BoundOperand lhs, rhs;
        BoundCondition left, right;
    }
    static int resolve(Name n, List<Column> schema) {
        int found = -1;
        for (int i = 0; i < schema.size(); i++) {
            Column c = schema.get(i);
            if (c.name.equals(n.attribute) && (!n.qualified || c.qualifier.equals(n.qualifier))) {
                if (found >= 0)
                    throw error("name error", "ambiguous attribute '" + n.display() +
                                "'; use a qualifier", n.pos);
                found = i;
            }
        }
        if (found < 0) throw error("name error", "unknown attribute '" + n.display() + "'", n.pos);
        return found;
    }
    static BoundOperand bindOperand(Operand o, List<Column> schema) {
        if (o.literal) return new BoundOperand(true, o.value, -1, o.value.type);
        int i = resolve(o.name, schema);
        return new BoundOperand(false, null, i, schema.get(i).type);
    }
    static BoundCondition bind(Condition c, List<Column> schema) {
        BoundCondition b = new BoundCondition();
        b.kind = c.kind; b.cmp = c.cmp; b.pos = c.pos;
        if (c.kind == CKind.COMPARE) {
            b.lhs = bindOperand(c.lhs, schema);
            b.rhs = bindOperand(c.rhs, schema);
            if (b.lhs.type != Type.UNKNOWN && b.rhs.type != Type.UNKNOWN &&
                b.lhs.type != b.rhs.type)
                throw error("type error", "cannot compare " + typeName(b.lhs.type) +
                            " with " + typeName(b.rhs.type), c.pos);
        }
        if (c.left != null) b.left = bind(c.left, schema);
        if (c.right != null) b.right = bind(c.right, schema);
        return b;
    }
    static Value boundValue(BoundOperand b, Value[] left, Value[] right, int width) {
        if (b.literal) return b.value;
        return b.index < width ? left[b.index] : right[b.index - width];
    }
    static boolean conditionTrue(BoundCondition c, Value[] left, Value[] right, int width) {
        switch (c.kind) {
            case AND: return conditionTrue(c.left, left, right, width) &&
                             conditionTrue(c.right, left, right, width);
            case OR: return conditionTrue(c.left, left, right, width) ||
                            conditionTrue(c.right, left, right, width);
            case NOT: return !conditionTrue(c.left, left, right, width);
            default:
                int order = compare(boundValue(c.lhs, left, right, width),
                                    boundValue(c.rhs, left, right, width), c.pos);
                switch (c.cmp) {
                    case EQ: return order == 0;
                    case NE: return order != 0;
                    case LT: return order < 0;
                    case LE: return order <= 0;
                    case GT: return order > 0;
                    case GE: return order >= 0;
                    default: throw new IllegalStateException("missing comparison operator");
                }
        }
    }
    static final class Counter {
        final String op;
        long count = 0;
        Counter(String op) { this.op = op; }
    }
    static List<Column> productSchema(Relation a, Relation b, Pos p) {
        List<Column> schema = new ArrayList<>(a.schema);
        schema.addAll(b.schema);
        for (int i = 0; i < schema.size(); i++)
            for (int j = 0; j < i; j++)
                if (schema.get(i).qualifier.equals(schema.get(j).qualifier) &&
                    schema.get(i).name.equals(schema.get(j).name))
                    throw error("schema error", "duplicate qualified attribute '" +
                                schema.get(i).qualifier + "." + schema.get(i).name + "'", p);
        return schema;
    }
    static void setCompatibility(Relation left, Relation right, Pos p) {
        if (left.schema.size() != right.schema.size())
            throw error("schema error", "set operands have different attribute counts", p);
        for (int i = 0; i < left.schema.size(); i++) {
            Column a = left.schema.get(i), b = right.schema.get(i);
            if (!a.name.equals(b.name))
                throw error("schema error", "set operands differ at attribute " + (i + 1) +
                            ": '" + a.name + "' versus '" + b.name + "'", p);
            if (a.type != Type.UNKNOWN && b.type != Type.UNKNOWN && a.type != b.type)
                throw error("schema error", "set operands have incompatible types at '" + a.name + "'", p);
        }
    }
    static Value[] combine(Value[] a, Value[] b) {
        Value[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
    static long[] smallIntegers(Relation rel, int col) {
        long[] keys = new long[rel.rows.size()];
        for (int i = 0; i < keys.length; i++) {
            Long n = rel.rows.get(i)[col].smallInteger;
            if (n == null) return null;
            keys[i] = n;
        }
        return keys;
    }

    /** Evaluate children before parents; no algebraic rewrites or indexed joins. */
    static Relation evaluate(Expr e, Map<String, Relation> catalog, List<Counter> metrics) {
        if (e.kind == EKind.REF) {
            Relation found = catalog.get(e.name);
            if (found == null) throw error("name error", "unknown relation '" + e.name + "'", e.pos);
            return found.copy();
        }
        Relation left = evaluate(e.left, catalog, metrics);
        if (e.kind == EKind.SELECT) {
            BoundCondition condition = bind(e.condition, left.schema);
            Counter counter = new Counter("select");
            metrics.add(counter);
            Relation result = new Relation();
            result.schema.addAll(left.schema);
            for (Value[] row : left.rows) {
                counter.count++;
                if (conditionTrue(condition, row, null, left.schema.size())) result.rows.add(row);
            }
            return result;
        }
        if (e.kind == EKind.PROJECT) {
            Relation result = new Relation();
            List<Integer> positions = new ArrayList<>();
            for (Name name : e.attrs) {
                int col = resolve(name, left.schema);
                if (positions.contains(col))
                    throw error("schema error", "duplicate projection attribute '" + name.display() + "'", name.pos);
                positions.add(col);
                result.schema.add(left.schema.get(col));
            }
            RowIndex unique = new RowIndex();
            for (Value[] row : left.rows) {
                Value[] projected = new Value[positions.size()];
                for (int i = 0; i < projected.length; i++) projected[i] = row[positions.get(i)];
                unique.add(projected);
            }
            result.rows.addAll(unique.rows);
            return result;
        }
        if (e.kind == EKind.RENAME) {
            for (int i = 0; i < left.schema.size(); i++) {
                Column old = left.schema.get(i);
                left.schema.set(i, new Column(e.name, old.name, old.type));
                for (int j = 0; j < i; j++)
                    if (old.name.equals(left.schema.get(j).name))
                        throw error("schema error", "rename creates duplicate qualified attribute '" +
                                    e.name + "." + old.name + "'", e.pos);
            }
            return left;
        }

        Relation right = evaluate(e.right, catalog, metrics);
        if (e.kind == EKind.TIMES || e.kind == EKind.JOIN) {
            Relation result = new Relation();
            result.schema.addAll(productSchema(left, right, e.pos));
            if (e.kind == EKind.TIMES) {
                for (Value[] a : left.rows) for (Value[] b : right.rows) result.rows.add(combine(a, b));
                return result;
            }
            BoundCondition condition = bind(e.condition, result.schema);
            Counter counter = new Counter("join");
            metrics.add(counter);
            int width = left.schema.size();
            boolean fast = condition.kind == CKind.COMPARE && condition.cmp == Cmp.EQ &&
                           !condition.lhs.literal && !condition.rhs.literal &&
                           ((condition.lhs.index < width && condition.rhs.index >= width) ||
                            (condition.rhs.index < width && condition.lhs.index >= width)) &&
                           condition.lhs.type == Type.NUMBER && condition.rhs.type == Type.NUMBER;
            long[] leftKeys = null, rightKeys = null;
            if (fast) {
                int leftCol = condition.lhs.index < width ? condition.lhs.index : condition.rhs.index;
                int rightCol = condition.lhs.index >= width ?
                               condition.lhs.index - width : condition.rhs.index - width;
                leftKeys = smallIntegers(left, leftCol);
                rightKeys = smallIntegers(right, rightCol);
                fast = leftKeys != null && rightKeys != null;
            }
            if (fast) {
                for (int i = 0; i < left.rows.size(); i++) {
                    long key = leftKeys[i];
                    for (int j = 0; j < right.rows.size(); j++) {
                        counter.count++;
                        if (key == rightKeys[j])
                            result.rows.add(combine(left.rows.get(i), right.rows.get(j)));
                    }
                }
            } else {
                for (Value[] a : left.rows) for (Value[] b : right.rows) {
                    counter.count++;
                    if (conditionTrue(condition, a, b, width)) result.rows.add(combine(a, b));
                }
            }
            return result;
        }
        setCompatibility(left, right, e.pos);
        Relation result = new Relation();
        for (int i = 0; i < left.schema.size(); i++) {
            Column col = left.schema.get(i);
            Type type = e.kind == EKind.UNION && col.type == Type.UNKNOWN ?
                        right.schema.get(i).type : col.type;
            result.schema.add(new Column(col.qualifier, col.name, type));
        }
        if (e.kind == EKind.UNION) {
            RowIndex unique = new RowIndex();
            for (Value[] row : left.rows) unique.add(row);
            for (Value[] row : right.rows) unique.add(row);
            result.rows.addAll(unique.rows);
            return result;
        }
        RowIndex rightIndex = new RowIndex();
        for (Value[] row : right.rows) rightIndex.add(row);
        for (Value[] row : left.rows) {
            boolean present = rightIndex.contains(row);
            if ((e.kind == EKind.INTERSECT && present) || (e.kind == EKind.MINUS && !present))
                result.rows.add(row);
        }
        return result;
    }
    static void printRelation(Relation r, boolean countOnly) {
        List<String> names = new ArrayList<>();
        for (Column c : r.schema) names.add(c.qualifier + "." + c.name);
        System.out.println("schema: [" + String.join(", ", names) + "]");
        System.out.println("rows: " + r.rows.size());
        if (countOnly) return;
        System.out.println("{");
        for (Value[] row : r.rows) {
            List<String> parts = new ArrayList<>();
            for (Value v : row) parts.add(v.output());
            System.out.println("  " + String.join(", ", parts));
        }
        System.out.println("}");
    }

    static int run(String[] args) {
        String data = null, query = null;
        boolean tree = false, metrics = false, countOnly = false;
        int warmup = 0, batch = 1;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ((arg.equals("--data") || arg.equals("--query") || arg.equals("--tree") ||
                 arg.equals("--warmup") || arg.equals("--batch")) && i + 1 < args.length) {
                String val = args[++i];
                if (arg.equals("--data")) data = val;
                else if (arg.equals("--warmup") || arg.equals("--batch")) {
                    int parsed;
                    try { parsed = Integer.parseInt(val); }
                    catch (NumberFormatException ex) {
                        throw error("input error", arg + " must be an integer", new Pos(1,1));
                    }
                    if (arg.equals("--warmup")) {
                        if (parsed < 0 || parsed > 10)
                            throw error("input error", "--warmup must be between 0 and 10", new Pos(1,1));
                        warmup = parsed;
                    } else {
                        if (parsed < 1 || parsed > 1000)
                            throw error("input error", "--batch must be between 1 and 1000", new Pos(1,1));
                        batch = parsed;
                    }
                } else {
                    if (query != null)
                        throw error("input error", "give exactly one query or tree expression", new Pos(1,1));
                    query = val;
                    tree = arg.equals("--tree");
                }
            } else if (arg.equals("--metrics")) metrics = true;
            else if (arg.equals("--count-only")) countOnly = true;
            else if (arg.equals("--help")) {
                System.out.println("Usage: java -cp build RA --data relations.ra --query 'EXPRESSION' " +
                                   "[--metrics] [--count-only] [--warmup N] [--batch N]");
                System.out.println("       java -cp build RA --tree 'EXPRESSION'");
                return 0;
            } else throw error("input error", "unrecognized or incomplete option '" + arg + "'", new Pos(1,1));
        }
        if (query == null || query.isEmpty())
            throw error("input error", "provide --query or --tree with an expression", new Pos(1,1));
        Expr ast = new Parser(new Scanner(query, 1).scan()).parse();
        if (tree) { printTree(ast, 0); return 0; }
        if (data == null)
            throw error("input error", "provide --data with relation definitions", new Pos(1,1));
        Map<String, Relation> catalog = readCatalog(data);
        for (int i = 0; i < warmup; i++) evaluate(ast, catalog, new ArrayList<>());
        List<Counter> counters = new ArrayList<>();
        long start = System.nanoTime();
        Relation result = null;
        long checksum = 0;
        for (int i = 0; i < batch; i++) {
            List<Counter> oneRun = new ArrayList<>();
            result = evaluate(ast, catalog, oneRun);
            checksum += result.rows.size();
            if (i == batch - 1) counters = oneRun;
        }
        benchmarkSink = checksum;
        long elapsed = System.nanoTime() - start;
        printRelation(result, countOnly);
        if (metrics) {
            int joins = 0, selects = 0;
            for (Counter c : counters) {
                if (c.op.equals("join"))
                    System.err.println("join_" + (++joins) + "_comparisons=" + c.count);
                else System.err.println("select_" + (++selects) + "_evaluations=" + c.count);
            }
            System.err.printf(Locale.US, "elapsed_seconds=%.9f%n", elapsed / 1e9 / batch);
        }
        return 0;
    }
    public static void main(String[] args) {
        try { System.exit(run(args)); }
        catch (RAError e) {
            System.err.println(e.category + " at " + e.pos.line + ":" + e.pos.column + ": " + e.getMessage());
            System.exit(2);
        }
        catch (Exception e) {
            System.err.println("internal error: " + e.getMessage());
            System.exit(3);
        }
    }
}
