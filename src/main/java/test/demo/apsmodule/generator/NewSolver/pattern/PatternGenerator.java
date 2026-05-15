package test.demo.apsmodule.generator.NewSolver.pattern;

import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;

import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模式生成器
 *
 * 负责生成初始模式池（与 PatternSolverFour 对齐）。
 */
public class PatternGenerator {

    private static final Logger log = LoggerFactory.getLogger(PatternGenerator.class);
    private static final int TRIPLE_PATTERN_LIMIT = 400;
    private static final int QUAD_PATTERN_LIMIT = 500;
    private static final int QUAD_WIDTH_LIMIT = 15;

    private final SolverParameters params;

    public PatternGenerator(SolverParameters params) {
        this.params = params;
    }

    /**
     * 生成初始模式池
     *
     * @param demands 需求映射 width -> count
     * @return 模式候选列表
     */
    public List<PatternCandidate> generate(Map<Integer, Integer> demands) {
        List<PatternCandidate> patterns = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Integer> widths = new ArrayList<>(demands.keySet());

        // 按需求量降序排列，大需求优先参与组合
        widths.sort((a, b) -> Integer.compare(demands.get(b), demands.get(a)));

        int minRw = params.getMinRollWidth();
        int maxRw = params.getMaxRollWidth();
        int step = params.getStepSize();
        int maxDistinct = params.getMaxDistinctWidths();

        double minUtilization = (double) minRw / maxRw;
        log.info("利用率下限 " + String.format("%.2f%%", minUtilization * 100) +
                " (minRollWidth=" + minRw + " / maxRollWidth=" + maxRw + ")");

        // ========== 策略1：双宽度组合（主力模式，占比最大）==========
        log.info("生成双宽度混合模式..");
        int dualCount = 0;
        for (int i = 0; i < widths.size(); i++) {
            int w1 = widths.get(i);
            for (int j = i; j < widths.size(); j++) { // 允许同宽度
                int w2 = widths.get(j);

                int maxC1 = maxRw / w1;
                int maxC2 = maxRw / w2;

                for (int c1 = 1; c1 <= maxC1; c1++) {
                    int startC2 = (w1 == w2) ? c1 : 1; // 同宽度避免重复
                    for (int c2 = startC2; c2 <= maxC2; c2++) {
                        int patternWidth = w1 * c1 + w2 * c2;
                        if (patternWidth < minRw)
                            continue;
                        if (patternWidth > maxRw)
                            break;

                        int bestRw = ceilToStep(patternWidth, step);
                        bestRw = Math.max(bestRw, minRw);
                        bestRw = Math.min(bestRw, maxRw);

                        if (patternWidth <= bestRw) {
                            Map<Integer, Integer> pattern = new HashMap<>();
                            if (w1 == w2) {
                                pattern.put(w1, c1 + c2);
                            } else {
                                pattern.put(w1, c1);
                                pattern.put(w2, c2);
                            }
                            if (addPatternWithResult(patterns, seen, pattern, bestRw)) {
                                dualCount++;
                            }
                        }
                    }
                }
            }
        }
        log.info("  双宽度模式 " + dualCount + " 个");

        // ========== 策略2：三宽度组合（补充模式，增加灵活性）==========
        log.info("生成三宽度混合模式..");
        PriorityQueue<RankedPattern> tripleCandidates = new PriorityQueue<>(this::compareRankedPatterns);
        Set<String> tripleSeen = new HashSet<>();
        for (int i = 0; i < widths.size(); i++) {
            int w1 = widths.get(i);
            for (int j = i; j < widths.size(); j++) {
                int w2 = widths.get(j);
                for (int k = j; k < widths.size(); k++) {
                    int w3 = widths.get(k);

                    for (int c1 = 1; c1 <= 4; c1++) {
                        for (int c2 = 1; c2 <= 4; c2++) {
                            for (int c3 = 1; c3 <= 4; c3++) {
                                int patternWidth = w1 * c1 + w2 * c2 + w3 * c3;
                                if (patternWidth < minRw)
                                    continue;
                                if (patternWidth > maxRw)
                                    continue;

                                int bestRw = ceilToStep(patternWidth, step);
                                bestRw = Math.max(bestRw, minRw);
                                bestRw = Math.min(bestRw, maxRw);

                                if (patternWidth <= bestRw) {
                                    Map<Integer, Integer> pattern = new HashMap<>();
                                    pattern.merge(w1, c1, Integer::sum);
                                    pattern.merge(w2, c2, Integer::sum);
                                    pattern.merge(w3, c3, Integer::sum);

                                    if (pattern.size() <= maxDistinct) {
                                        offerRankedPattern(tripleCandidates, tripleSeen, seen,
                                                pattern, bestRw, demands, TRIPLE_PATTERN_LIMIT);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        int tripleCount = addRankedPatterns(patterns, seen, tripleCandidates);
        log.info("  三宽度模式 " + tripleCount + " 个");

        // ========== 策略2.5：四宽度组合（增强探索）==========
        log.info("生成四宽度混合模式..");
        PriorityQueue<RankedPattern> quadCandidates = new PriorityQueue<>(this::compareRankedPatterns);
        Set<String> quadSeen = new HashSet<>();
        List<Integer> topWidths = widths.subList(0, Math.min(QUAD_WIDTH_LIMIT, widths.size()));
        for (int i = 0; i < topWidths.size(); i++) {
            int w1 = topWidths.get(i);
            for (int j = i; j < topWidths.size(); j++) {
                int w2 = topWidths.get(j);
                for (int k = j; k < topWidths.size(); k++) {
                    int w3 = topWidths.get(k);
                    for (int l = k; l < topWidths.size(); l++) {
                        int w4 = topWidths.get(l);

                        for (int c1 = 1; c1 <= 3; c1++) {
                            for (int c2 = 1; c2 <= 3; c2++) {
                                for (int c3 = 1; c3 <= 3; c3++) {
                                    for (int c4 = 1; c4 <= 3; c4++) {
                                        int patternWidth = w1 * c1 + w2 * c2 + w3 * c3 + w4 * c4;
                                        if (patternWidth < minRw)
                                            continue;
                                        if (patternWidth > maxRw)
                                            continue;

                                        int bestRw = ceilToStep(patternWidth, step);
                                        bestRw = Math.max(bestRw, minRw);
                                        bestRw = Math.min(bestRw, maxRw);

                                        if (patternWidth <= bestRw) {
                                            Map<Integer, Integer> pattern = new HashMap<>();
                                            pattern.merge(w1, c1, Integer::sum);
                                            pattern.merge(w2, c2, Integer::sum);
                                            pattern.merge(w3, c3, Integer::sum);
                                            pattern.merge(w4, c4, Integer::sum);

                                            if (pattern.size() <= maxDistinct) {
                                                offerRankedPattern(quadCandidates, quadSeen, seen,
                                                        pattern, bestRw, demands, QUAD_PATTERN_LIMIT);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        int quadCount = addRankedPatterns(patterns, seen, quadCandidates);
        log.info("  四宽度模式 " + quadCount + " 个");

        // ========== 策略2.6：单宽度高系数模式（处理难组合宽度）==========
        log.info("生成单宽度高系数模式（处理难组合宽度）..");
        int singleHighCount = 0;
        for (int w : widths) {
            for (int count = 3; count <= maxRw / w && count <= 5; count++) {
                int patternWidth = w * count;
                if (patternWidth < minRw)
                    continue;
                if (patternWidth > maxRw)
                    break;

                int bestRw = ceilToStep(patternWidth, step);
                bestRw = Math.max(bestRw, minRw);
                bestRw = Math.min(bestRw, maxRw);

                if (patternWidth <= bestRw) {
                    Map<Integer, Integer> pattern = new HashMap<>();
                    pattern.put(w, count);
                    if (addPatternWithResult(patterns, seen, pattern, bestRw)) {
                        singleHighCount++;
                        if (singleHighCount <= 5) {
                            log.info("    单宽度模式 " + w + "x" + count + "=" +
                                    patternWidth + "mm @" + bestRw + "mm");
                        }
                    }
                }
            }
        }
        if (singleHighCount > 5) {
            log.info("    ... (共" + singleHighCount + " 个单宽度模式)");
        }
        log.info("  单宽度高系数模式: " + singleHighCount + " 个");

        // ========== 策略3：检查覆盖并生成兜底模式 ==========
        Set<Integer> coveredWidths = new HashSet<>();
        for (PatternCandidate pc : patterns) {
            coveredWidths.addAll(pc.getPattern().keySet());
        }

        List<Integer> uncoveredWidths = new ArrayList<>();
        for (int w : widths) {
            if (!coveredWidths.contains(w)) {
                uncoveredWidths.add(w);
            }
        }

        if (!uncoveredWidths.isEmpty()) {
            log.info("⚠️ 发现 " + uncoveredWidths.size() + " 个未覆盖宽度: " + uncoveredWidths);
            for (int targetW : uncoveredWidths) {
                for (int fillW : widths) {
                    if (fillW == targetW)
                        continue;
                    for (int fillC = 1; fillC <= 5; fillC++) {
                        int patternWidth = targetW + fillW * fillC;
                        if (patternWidth < minRw)
                            continue;
                        if (patternWidth > maxRw)
                            break;

                        int bestRw = ceilToStep(patternWidth, step);
                        bestRw = Math.max(bestRw, minRw);
                        bestRw = Math.min(bestRw, maxRw);

                        if (patternWidth <= bestRw) {
                            Map<Integer, Integer> pattern = new HashMap<>();
                            pattern.put(targetW, 1);
                            pattern.put(fillW, fillC);
                            addPattern(patterns, seen, pattern, bestRw);
                            log.info("  兜底模式: " + pattern + " @" + bestRw + "mm");
                            break;
                        }
                    }
                }
            }
        }

        // ========== 策略4：Seed模式生成（确保每个宽度有系数=1的模式，解决GCD问题）==========
        int seedStartIndex = patterns.size();
        generateSeedPatterns(patterns, seen, demands, widths);

        int rawSize = patterns.size();
        int maxPatterns = params.getMaxPatterns();
        if (rawSize > maxPatterns) {
            List<PatternCandidate> nonSeeds = new ArrayList<>(patterns.subList(0, seedStartIndex));
            List<PatternCandidate> seeds = new ArrayList<>(patterns.subList(seedStartIndex, rawSize));
            
            nonSeeds.sort(Comparator.comparingInt(PatternCandidate::getWaste));
            
            patterns = new ArrayList<>();
            patterns.addAll(seeds); // 强制保留所有 Seed 模式（防止因为废边稍大被剔除，导致无法凑齐 0 超产的尾数）
            int remainingSlots = maxPatterns - seeds.size();
            if (remainingSlots > 0) {
                patterns.addAll(nonSeeds.subList(0, Math.min(remainingSlots, nonSeeds.size())));
            }
            log.info("初始模式池截断: {} -> {} (强制保留 {} 个 Seed 模式防止超产，其余按废边升序保留最优)", 
                    rawSize, patterns.size(), seeds.size());
        }

        log.info("初始模式池总数: " + patterns.size());
        return patterns;
    }

    /**
     * 生成Seed模式（强制覆盖所有宽度，确保有系数=1的模式）
     * 与 PatternSolverFour.generateSeedPatterns() 对齐
     */
    private void generateSeedPatterns(List<PatternCandidate> patterns, Set<String> seen,
            Map<Integer, Integer> demands, List<Integer> widths) {
        log.info("\n--- 生成Seed模式（强制覆盖所有宽度）---");

        int minRw = params.getMinRollWidth();
        int maxRw = params.getMaxRollWidth();
        int step = params.getStepSize();
        int maxDistinct = params.getMaxDistinctWidths();

        int seedsAdded = 0;

        for (int targetW : widths) {
            // 对每个宽度，确保有系数=1的模式（解决GCD问题）
            for (int targetCoeff = 1; targetCoeff <= 2; targetCoeff++) {
                int baseWidth = targetW * targetCoeff;
                if (baseWidth >= maxRw)
                    continue;

                // 尝试用其他宽度填充到 [minRollWidth, maxRollWidth]
                int remaining = minRw - baseWidth;
                if (remaining <= 0) {
                    // 已经达到下界，直接生成
                    if (baseWidth <= maxRw) {
                        int rw = ceilToStep(baseWidth, step);
                        rw = Math.max(rw, minRw);
                        rw = Math.min(rw, maxRw);
                        if (baseWidth <= rw) {
                            Map<Integer, Integer> pattern = new HashMap<>();
                            pattern.put(targetW, targetCoeff);
                            if (addPatternWithResult(patterns, seen, pattern, rw)) {
                                seedsAdded++;
                            }
                        }
                    }
                } else {
                    // 需要用其他宽度填充
                    boolean found = false;
                    for (int fillW : widths) {
                        if (found)
                            break;
                        if (fillW == targetW)
                            continue;

                        for (int fillC = 1; fillC <= maxRw / fillW; fillC++) {
                            int totalWidth = baseWidth + fillW * fillC;
                            if (totalWidth < minRw)
                                continue;
                            if (totalWidth > maxRw)
                                break;

                            int rw = ceilToStep(totalWidth, step);
                            rw = Math.max(rw, minRw);
                            rw = Math.min(rw, maxRw);

                            if (totalWidth <= rw) {
                                Map<Integer, Integer> pattern = new HashMap<>();
                                pattern.put(targetW, targetCoeff);
                                pattern.put(fillW, fillC);
                                if (addPatternWithResult(patterns, seen, pattern, rw)) {
                                    seedsAdded++;
                                    found = true;
                                    break;
                                }
                            }
                        }
                    }

                    // 如果单个填充宽度不够，尝试双填充
                    if (!found) {
                        for (int fillW1 : widths) {
                            if (found)
                                break;
                            if (fillW1 == targetW)
                                continue;
                            for (int fillW2 : widths) {
                                if (found)
                                    break;
                                if (fillW2 < fillW1)
                                    continue;

                                for (int c1 = 1; c1 <= 3; c1++) {
                                    if (found)
                                        break;
                                    for (int c2 = 1; c2 <= 3; c2++) {
                                        int totalWidth = baseWidth + fillW1 * c1 + fillW2 * c2;
                                        if (totalWidth < minRw)
                                            continue;
                                        if (totalWidth > maxRw)
                                            break;

                                        Map<Integer, Integer> pattern = new HashMap<>();
                                        pattern.put(targetW, targetCoeff);
                                        pattern.merge(fillW1, c1, Integer::sum);
                                        pattern.merge(fillW2, c2, Integer::sum);

                                        if (pattern.size() > maxDistinct)
                                            continue;

                                        int rw = ceilToStep(totalWidth, step);
                                        rw = Math.max(rw, minRw);
                                        rw = Math.min(rw, maxRw);

                                        if (totalWidth <= rw) {
                                            if (addPatternWithResult(patterns, seen, pattern, rw)) {
                                                seedsAdded++;
                                                found = true;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        log.info("Seed模式新增: " + seedsAdded + " 个，模式池总数: " + patterns.size());
    }

    private boolean addPatternWithResult(List<PatternCandidate> patterns, Set<String> seen,
            Map<Integer, Integer> pattern, int rollWidth) {
        PatternCandidate pc = new PatternCandidate(pattern, rollWidth);
        return addPatternCandidateWithResult(patterns, seen, pc);
    }

    private boolean addPatternCandidateWithResult(List<PatternCandidate> patterns, Set<String> seen,
            PatternCandidate pc) {
        String key = pc.signature();
        if (!seen.contains(key)) {
            patterns.add(pc);
            seen.add(key);
            return true;
        }
        return false;
    }

    private void addPattern(List<PatternCandidate> patterns, Set<String> seen,
            Map<Integer, Integer> pattern, int rollWidth) {
        addPatternWithResult(patterns, seen, pattern, rollWidth);
    }

    private void offerRankedPattern(PriorityQueue<RankedPattern> queue,
            Set<String> candidateSeen,
            Set<String> existingPatterns,
            Map<Integer, Integer> pattern,
            int rollWidth,
            Map<Integer, Integer> demands,
            int limit) {
        PatternCandidate candidate = new PatternCandidate(pattern, rollWidth);
        String signature = candidate.signature();
        if (existingPatterns.contains(signature) || !candidateSeen.add(signature)) {
            return;
        }

        RankedPattern ranked = rankPattern(candidate, demands);
        if (queue.size() < limit) {
            queue.offer(ranked);
            return;
        }

        RankedPattern worst = queue.peek();
        if (worst != null && compareRankedPatterns(ranked, worst) > 0) {
            queue.poll();
            queue.offer(ranked);
        }
    }

    private int addRankedPatterns(List<PatternCandidate> patterns, Set<String> seen,
            PriorityQueue<RankedPattern> queue) {
        List<RankedPattern> rankedPatterns = new ArrayList<>(queue);
        rankedPatterns.sort((left, right) -> compareRankedPatterns(right, left));

        int added = 0;
        for (RankedPattern ranked : rankedPatterns) {
            if (addPatternCandidateWithResult(patterns, seen, ranked.candidate())) {
                added++;
            }
        }
        return added;
    }

    private RankedPattern rankPattern(PatternCandidate candidate, Map<Integer, Integer> demands) {
        double weightedDemandCoverage = candidate.getPattern().entrySet().stream()
                .mapToDouble(entry -> (double) demands.getOrDefault(entry.getKey(), 0) * entry.getValue())
                .sum();
        return new RankedPattern(
                candidate,
                weightedDemandCoverage,
                candidate.getUtilization(),
                candidate.getWaste(),
                candidate.getWidthCount(),
                candidate.signature());
    }

    private int compareRankedPatterns(RankedPattern left, RankedPattern right) {
        int compare = Double.compare(left.weightedDemandCoverage(), right.weightedDemandCoverage());
        if (compare != 0) {
            return compare;
        }
        compare = Double.compare(left.utilization(), right.utilization());
        if (compare != 0) {
            return compare;
        }
        compare = Integer.compare(right.waste(), left.waste());
        if (compare != 0) {
            return compare;
        }
        compare = Integer.compare(right.widthCount(), left.widthCount());
        if (compare != 0) {
            return compare;
        }
        return right.signature().compareTo(left.signature());
    }

    private int ceilToStep(int value, int step) {
        return ((value + step - 1) / step) * step;
    }

    private record RankedPattern(
            PatternCandidate candidate,
            double weightedDemandCoverage,
            double utilization,
            int waste,
            int widthCount,
            String signature) {
    }
}
