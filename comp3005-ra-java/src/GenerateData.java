import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Generate R(a,b) and S(b,c) with a chosen mean number of matches per R row. */
public final class GenerateData {
    private GenerateData() {}

    private static final class Options {
        int n = -1, m = -1;
        double rate = Double.NaN;
        long seed = 3005;
        Path out;
    }

    private static Options parse(String[] args) {
        Options options = new Options();
        for (int i = 0; i < args.length; i++) {
            String key = args[i];
            if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for " + key);
            String value = args[++i];
            try {
                switch (key) {
                    case "--n": options.n = Integer.parseInt(value); break;
                    case "--m": options.m = Integer.parseInt(value); break;
                    case "--match-rate": options.rate = Double.parseDouble(value); break;
                    case "--seed": options.seed = Long.parseLong(value); break;
                    case "--out": options.out = Path.of(value); break;
                    default: throw new IllegalArgumentException("unknown option: " + key);
                }
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("invalid value for " + key + ": " + value);
            }
        }
        if (options.n <= 0 || options.m <= 0 || options.out == null ||
            !Double.isFinite(options.rate) || options.rate < 0)
            throw new IllegalArgumentException("required: --n POSITIVE --m POSITIVE " +
                                               "--match-rate NONNEGATIVE --out FILE [--seed INTEGER]");
        return options;
    }

    private static void generate(Options options) throws IOException {
        int n = options.n, m = options.m;
        double rate = options.rate;
        // This fixes approximately n*rate of the S keys inside the R key domain.
        // Unmatched S keys lie outside that domain. Every S tuple has its own c.
        int matched = rate == 0 ? 0 : (int)Math.min(m, Math.rint(n * rate));
        int domain = rate == 0 ? n :
            (int)Math.max(1, Math.min(n, Math.rint(matched / rate)));
        List<Integer> sKeys = new ArrayList<>(m);
        for (int j = 0; j < matched; j++) sKeys.add(1 + j % domain);
        for (int j = matched; j < m; j++) sKeys.add(domain + 1 + j - matched);
        Collections.shuffle(sKeys, new Random(options.seed));

        Map<Integer,Integer> frequencies = new HashMap<>();
        for (int key : sKeys) frequencies.merge(key, 1, Integer::sum);
        long expected = 0;
        for (int i = 0; i < n; i++) expected += frequencies.getOrDefault(1 + i % domain, 0);

        Path parent = options.out.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (BufferedWriter file = Files.newBufferedWriter(options.out, StandardCharsets.UTF_8)) {
            file.write("R (a, b) = {\n");
            for (int i = 0; i < n; i++) file.write("  " + (i + 1) + ", " + (1 + i % domain) + "\n");
            file.write("}\n\nS (b, c) = {\n");
            for (int j = 0; j < m; j++) file.write("  " + sKeys.get(j) + ", " + (j + 1) + "\n");
            file.write("}\n");
        }
        System.out.println("n=" + n);
        System.out.println("m=" + m);
        System.out.println("seed=" + options.seed);
        System.out.println("target_match_rate=" + rate);
        System.out.printf(Locale.US, "actual_mean_matches_per_R=%.12f%n", (double)expected / n);
        System.out.println("expected_join_output=" + expected);
    }

    public static void main(String[] args) {
        try { generate(parse(args)); }
        catch (IllegalArgumentException | IOException ex) {
            System.err.println("data generator error: " + ex.getMessage());
            System.exit(2);
        }
    }
}
