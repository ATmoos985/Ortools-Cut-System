package test.demo.apsmodule.generator.NewSolver;

import com.google.ortools.Loader;
import org.junit.jupiter.api.Test;
import test.demo.apsmodule.generator.NewSolver.colgen.ColumnGenerationSolver;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.mip.MultiStageMIPSolver;
import test.demo.apsmodule.generator.NewSolver.mip.PatternAlignmentContext;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.SolverConfig;
import test.demo.apsmodule.service.SolverOrderItem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fast A-layer-only determinism probe.
 *
 * Runs PatternGenerator → ColumnGeneration → MultiStageMIPSolver TWICE in the
 * SAME JVM and compares the candidate花型集 signatures. ~1 min (no slow B-layer).
 * If candidates differ between the two passes, the A-layer MIP selection is
 * genuinely non-deterministic in-process.
 */
class B5ALayerTest {

    @Test
    void aLayerDeterminism() throws Exception {
        Loader.loadNativeLibraries();
        List<SolverOrderItem> items = loadItems();
        SolverConfig config = buildConfig();
        SolverParameters params = SolverParameters.createDefault();
        params.mergeFrom(config);

        // Hypothesis: deterministic width-ascending order (TreeMap) removes the
        // tie structure that SCIP's RNG breaks differently each solve.
        Map<Integer, Integer> demands = new java.util.TreeMap<>();
        for (SolverOrderItem it : items) {
            demands.merge(it.getWidth(), it.getDemand(), Integer::sum);
        }
        Set<Integer> allowOver = demands.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(params.getTopK())
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> run1 = aLayerSignatures(params, demands, allowOver, items);
        List<String> run2 = aLayerSignatures(params, demands, allowOver, items);

        System.out.println("\n##### A-LAYER DETERMINISM PROBE #####");
        int n = Math.max(run1.size(), run2.size());
        for (int i = 0; i < n; i++) {
            String a = i < run1.size() ? run1.get(i) : "(none)";
            String b = i < run2.size() ? run2.get(i) : "(none)";
            System.out.println("cand" + i + ": " + (a.equals(b) ? "SAME" : "DIFF") + "  r1=" + a + "  r2=" + b);
        }
        System.out.println("IDENTICAL=" + run1.equals(run2));
        System.out.println("#####################################\n");
    }

    @Test
    void aLayerAlignmentLambdaSweep() throws Exception {
        Loader.loadNativeLibraries();
        List<SolverOrderItem> items = loadItems();
        SolverConfig config = buildConfig();
        SolverParameters params = SolverParameters.createDefault();
        params.mergeFrom(config);

        Map<Integer, Integer> demands = new java.util.TreeMap<>();
        for (SolverOrderItem it : items) {
            demands.merge(it.getWidth(), it.getDemand(), Integer::sum);
        }
        Set<Integer> allowOver = demands.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(params.getTopK())
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        PatternGeneratorWrap pg = new PatternGeneratorWrap(params);
        List<PatternCandidate> generated = pg.generate(demands);
        ColumnGenerationSolver cg = new ColumnGenerationSolver(params);
        List<PatternCandidate> patterns = cg.solve(generated, demands, allowOver);
        PatternAlignmentContext alignmentContext = PatternAlignmentContext.from(items);

        System.out.println("\n##### A-LAYER ALIGNMENT LAMBDA SWEEP #####");
        for (double lambda : List.of(0.0, 0.2, 0.5, 1.0)) {
            MultiStageMIPSolver mip = new MultiStageMIPSolver(params);
            MultiStageMIPSolver.SolveCandidate candidate = mip.solvePrimaryOnly(
                    new ArrayList<>(patterns), demands, allowOver, 1, lambda, alignmentContext);
            if (candidate == null) {
                System.out.printf("lambda=%.1f no-candidate%n", lambda);
                continue;
            }
            String sig = solutionSignature(candidate);
            System.out.printf("lambda=%.1f name=%s patterns=%d rolls=%d waste=%d sig#%d%n",
                    lambda,
                    candidate.name(),
                    candidate.result().getPatternCount(),
                    candidate.result().getTotalRolls(),
                    candidate.result().getTotalWaste(),
                    sig.hashCode());
        }
        System.out.println("###########################################\n");
    }

    private List<String> aLayerSignatures(SolverParameters params,
            Map<Integer, Integer> demands, Set<Integer> allowOver, List<SolverOrderItem> items) {
        PatternGeneratorWrap pg = new PatternGeneratorWrap(params);
        List<PatternCandidate> patterns = pg.generate(demands);
        ColumnGenerationSolver cg = new ColumnGenerationSolver(params);
        patterns = cg.solve(patterns, demands, allowOver);
        MultiStageMIPSolver mip = new MultiStageMIPSolver(params);
        int seed = Integer.getInteger("cutting.test.aLayerSeed", params.getALayerScipSeed());
        // Production path is solvePrimaryOnly (the diverse solveCandidates path was removed).
        MultiStageMIPSolver.SolveCandidate primary = mip.solvePrimaryOnly(
                patterns, demands, allowOver,
                seed, params.getALayerAlignmentLambda(),
                PatternAlignmentContext.from(items));
        List<MultiStageMIPSolver.SolveCandidate> cands = primary == null ? List.of() : List.of(primary);
        List<String> sigs = new ArrayList<>();
        for (MultiStageMIPSolver.SolveCandidate c : cands) {
            String sig = solutionSignature(c);
            sigs.add(c.name() + " patterns=" + c.result().getSolution().size() + " sig#" + sig.hashCode());
        }
        return sigs;
    }

    private String solutionSignature(MultiStageMIPSolver.SolveCandidate candidate) {
        return candidate.result().getSolution().entrySet().stream()
                .map(e -> e.getKey().signature() + "x" + e.getValue())
                .sorted()
                .collect(Collectors.joining("|"));
    }

    // PatternGenerator lives in a sibling package; alias via import-free wrapper.
    static final class PatternGeneratorWrap {
        private final test.demo.apsmodule.generator.NewSolver.pattern.PatternGenerator inner;
        PatternGeneratorWrap(SolverParameters p) {
            this.inner = new test.demo.apsmodule.generator.NewSolver.pattern.PatternGenerator(p);
        }
        List<PatternCandidate> generate(Map<Integer, Integer> demands) {
            return inner.generate(demands);
        }
    }

    private List<SolverOrderItem> loadItems() throws IOException {
        List<SolverOrderItem> items = new ArrayList<>();
        String fixture = System.getProperty("cutting.test.aLayerFixture", "t9est188.csv");
        InputStream in = getClass().getResourceAsStream("/" + fixture);
        if (in == null) {
            throw new IllegalStateException(fixture + " not found");
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] p = line.split(",", -1);
                SolverOrderItem item = new SolverOrderItem();
                item.setMessageText(p[0].trim());
                item.setWidth(Integer.parseInt(p[1].trim()));
                item.setDemand(Integer.parseInt(p[2].trim()));
                item.setLength(Integer.parseInt(p[3].trim()));
                item.setSurfaceTreatment(p[4].trim());
                item.setGroupKey(p[3].trim() + "m+" + p[4].trim());
                items.add(item);
            }
        }
        return items;
    }

    private SolverConfig buildConfig() {
        SolverConfig c = new SolverConfig();
        c.setMode("variable");
        c.setMinWidth(4300);
        c.setMaxWidth(4400);
        c.setStepSize(10);
        c.setTotalWidth(4600);
        c.setTotalOverCap(30);
        c.setMaxIterations(300);
        c.setTimeoutMs(120000L);
        c.setUseNewSolver(true);
        c.setNewSolverTopK(3);
        c.setNewSolverMaxPatterns(800);
        c.setNewSolverMaxDistinctWidths(4);
        c.setNewSolverStage4TimeLimit(30000L);
        c.setNewSolverSeqGroupAlpha(1.0);
        c.setNewSolverSeqGroupBeta(0.0);
        c.setNewSolverUseOptimizedAssignment(true);
        c.setNewSolverUnderPenalty(1e6);
        return c;
    }
}
