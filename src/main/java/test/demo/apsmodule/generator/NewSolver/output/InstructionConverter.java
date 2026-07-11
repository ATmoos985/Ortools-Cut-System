package test.demo.apsmodule.generator.NewSolver.output;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import test.demo.apsmodule.generator.NewSolver.config.SolverParameters;
import test.demo.apsmodule.generator.NewSolver.config.SolverRuntimeProperties;
import test.demo.apsmodule.generator.NewSolver.mip.AssignmentMIPSolver;
import test.demo.apsmodule.generator.NewSolver.mip.Phase2SequenceGroupSolver;
import test.demo.apsmodule.generator.NewSolver.model.PatternCandidate;
import test.demo.apsmodule.service.CuttingInstruction;
import test.demo.apsmodule.service.SolverOrderItem;
import test.demo.apsmodule.service.StationAssignment;
import test.demo.apsmodule.util.OrderAssignmentOptimizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.stream.Collectors;

/**
 * Converts the selected patterns into executable instructions.
 *
 * Stage 5 MIP and greedy assignment are candidate generators. The final winner
 * is the candidate with the smallest real sequence-group count.
 */
public class InstructionConverter {

    private static final Logger log = LoggerFactory.getLogger(InstructionConverter.class);
    private static final int DEFAULT_MAX_WIDTH_MESSAGES_PER_REPACK = 4;
    private static final int DEFAULT_MAX_WIDTH_OPTIONS_PER_REPACK = 6;
    private static final int DEFAULT_MAX_FULL_CONFIGS_PER_REPACK = 96;
    private static final long DEFAULT_INSTRUCTION_REPACK_TIME_LIMIT_MS = 150L;
    private static final int LARGE_REPACK_MIN_BLOCKS = 6;
    private static final int LARGE_REPACK_MIN_USAGE_COUNT = 40;
    private static final int LARGE_MAX_WIDTH_MESSAGES_PER_REPACK = 6;
    private static final int LARGE_MAX_WIDTH_OPTIONS_PER_REPACK = 10;
    private static final int LARGE_MAX_FULL_CONFIGS_PER_REPACK = 192;
    private static final long LARGE_INSTRUCTION_REPACK_TIME_LIMIT_MS = 450L;
    private static final long INSTRUCTION_REPACK_HINT_TIME_LIMIT_MS = 60L;
    private static final int INSTRUCTION_REPACK_HINT_MIN_BLOCKS = 4;
    private static final int INSTRUCTION_REPACK_HINT_MIN_USAGE_COUNT = 8;
    private static boolean orToolsLoaded = false;

    private final SolverParameters params;

    /**
     * 同 groupKey 已见最好（最少）组数——供 set-partition 精修段的劣候选提前弃修使用。
     * 一个 CuttingSolver 请求内所有候选共用一个 converter 实例，故按 groupKey 累积；
     * 候选按序处理，首候选无参照必修。
     */
    private final Map<String, Integer> bestGroupsByKey = new HashMap<>();

    public InstructionConverter(SolverParameters params) {
        this.params = params;
    }

    private static synchronized boolean loadOrTools() {
        if (orToolsLoaded) {
            return true;
        }

        try {
            Loader.loadNativeLibraries();
            orToolsLoaded = true;
            return true;
        } catch (Exception e) {
            log.warn("OR-Tools load failed for instruction repack", e);
            return false;
        }
    }

    public List<CuttingInstruction> convert(Map<PatternCandidate, Integer> solution,
            String groupKey,
            List<SolverOrderItem> groupItems,
            Map<Integer, Integer> demands) {
        return convertWithDetails(solution, groupKey, groupItems, demands).instructions();
    }

    public ConversionResult convertWithDetails(Map<PatternCandidate, Integer> solution,
            String groupKey,
            List<SolverOrderItem> groupItems,
            Map<Integer, Integer> demands) {
        Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> mipAssignment = null;
        try {
            mipAssignment = solveAssignmentWithMip(solution, groupItems);
        } catch (Exception e) {
            log.warn("Stage5 assignment candidate failed, greedy candidate will still be evaluated", e);
        }

        List<ScoredInstructionPlan> candidates = new ArrayList<>();
        if (mipAssignment != null) {
            List<CuttingInstruction> mipInstructions = buildFromMIPAssignment(solution, groupKey, groupItems, mipAssignment);
            addCandidate(candidates, "stage5-mip", mipInstructions);
        }

        // Phase2 is an experimental second assignment candidate (~5s/group). It has not
        // beaten the Stage5+LNS production path on the measured T9EST cases, so keep it
        // off by default. Enable only for diagnostics: -Dcutting.phase2.enabled=true.
        if (phase2Enabled()) {
            try {
                log.info("Evaluating Phase2 sequence-group candidate");
                Phase2SequenceGroupSolver.SolveResult phase2Result =
                        solveSequenceGroupsWithPhase2(solution, groupItems, mipAssignment);
                if (phase2Result != null && !phase2Result.solution().isEmpty() && !phase2Result.assignments().isEmpty()) {
                    List<CuttingInstruction> phase2Instructions = buildFromMIPAssignment(
                            phase2Result.solution(), groupKey, groupItems, phase2Result.assignments());
                    addCandidate(candidates, "phase2-cg", phase2Instructions);
                } else {
                    log.info("Phase2 sequence-group candidate produced no usable plan");
                }
            } catch (Exception e) {
                log.warn("Phase2 sequence-group candidate failed, other candidates will still be evaluated", e);
            }
        } else {
            log.info("Phase2 sequence-group candidate disabled (cutting.phase2.enabled=false)");
        }

        // Greedy is a FALLBACK ONLY. Across every measured run it never beats the Stage5/Phase2
        // MIP candidates (typically 67-79 vs 47-55 groups), so building+scoring it when a MIP
        // candidate already exists is pure overhead (~10s/candidate). Only run it when nothing
        // else produced a plan, so it still guarantees a result if both MIP paths fail.
        if (candidates.isEmpty()) {
            log.info("No MIP candidate produced; falling back to greedy assignment");
            List<CuttingInstruction> greedyInstructions = buildFromGreedyAssignment(
                    solution, groupKey, groupItems, OrderAssignmentOptimizer.GreedyStrategy.BATCH_FIRST);
            addCandidate(candidates, "greedy", greedyInstructions);

            List<CuttingInstruction> reuseGreedyInstructions = buildFromGreedyAssignment(
                    solution, groupKey, groupItems, OrderAssignmentOptimizer.GreedyStrategy.REUSE_FIRST);
            addCandidate(candidates, "greedy-reuse", reuseGreedyInstructions);
        }

        if (candidates.isEmpty()) {
            return new ConversionResult(new ArrayList<>(), "none", 0, List.of());
        }

        ScoredInstructionPlan bestPlan = selectBestCandidate(candidates);
        List<CuttingInstruction> selectedInstructions = bestPlan.instructions();
        String selectedName = bestPlan.name();
        int selectedGroups = bestPlan.sequenceGroupCount();
        List<SequenceCandidateRow> candidateRows = new ArrayList<>(
                buildSequenceCandidateRows(candidates, selectedName));

        if (LocalNeighborhoodSequenceOptimizer.isEnabled()
                || test.demo.apsmodule.generator.NewSolver.CuttingSolver.qualityMode()) {
            LocalNeighborhoodSequenceOptimizer optimizer =
                    new LocalNeighborhoodSequenceOptimizer(params, this::arrangeForSequenceGroups);
            LocalNeighborhoodSequenceOptimizer.LnsResult lnsResult =
                    runLnsVariants(optimizer, selectedInstructions, groupItems);
            SolverRunColumnArchive.recordInstructions(lnsResult.instructions());
            if (lnsResult.improved()) {
                selectedInstructions = lnsResult.instructions();
                selectedName = selectedName + "+lns";
                selectedGroups = lnsResult.afterGroups();
                candidateRows = new ArrayList<>(candidateRows.stream()
                        .map(row -> new SequenceCandidateRow(
                                row.name(),
                                row.sequenceGroupCount(),
                                row.instructions(),
                                false))
                        .toList());
                candidateRows.add(new SequenceCandidateRow(
                        selectedName,
                        selectedGroups,
                        selectedInstructions.size(),
                        true));
                log.info("LNS improved selected sequence plan: groups {} -> {}",
                        lnsResult.beforeGroups(), lnsResult.afterGroups());

                // Phase O（组数封顶的奇偶修复）：LNS 的移动会破坏 build 阶段做过的 family
                // repack，所以 LNS 输出常带着本可避免的奇数车块。在 LNS 结果上重跑一次
                // repack MIP（权重 块10000 > 奇100 > 小10，结构上不可能增加块数），只接受
                // 字典序（组→奇→1车→小）严格变好且需求/车数/废边守恒的结果——组数地板绝不回吐。
                List<CuttingInstruction> repaired = oddRepairPass(selectedInstructions);
                if (repaired != null) {
                    selectedInstructions = repaired;
                    selectedName = selectedName + "+oddfix";
                    selectedGroups = SequenceGroupPostProcessor
                            .computeGroupStats(selectedInstructions).groups();
                }
            } else {
                log.info("LNS produced no accepted improvement: {}", lnsResult.reason());
            }
        }

        // 质量模式第三段：set-partition 微邻域精修（残差导向列注入，笔记13 L12）。
        // LNS/Phase O 是 B 层花型内重排；本段跨花型重组块（拆弱块+捐赠块，残差上比例
        // 匹配生成新列，小 MIP 精确重建），B6 L12c 实证 47/3→46/1 追平人工。
        if (test.demo.apsmodule.generator.NewSolver.CuttingSolver.qualityMode()
                && SetPartitionRefiner.isEnabled()) {
            // 劣候选提前弃修：落后同 groupKey 已见最好组数超过阈值时，精修追不平（实测
            // 最多追回 9 组），直接跳过——省质量模式约一半耗时且零质量风险。
            Integer bestSeen = bestGroupsByKey.get(groupKey);
            int gap = SetPartitionRefiner.skipGapThreshold();
            if (bestSeen != null && selectedGroups - bestSeen > gap) {
                log.info("Set-partition refine pass: skipped (candidate {} groups vs best {} > gap {})",
                        selectedGroups, bestSeen, gap);
            } else {
                List<CuttingInstruction> refined = setPartitionRefinePass(selectedInstructions);
                if (refined != null) {
                    selectedInstructions = refined;
                    selectedGroups = SequenceGroupPostProcessor
                            .computeGroupStats(selectedInstructions).groups();
                    selectedName = selectedName + "+spr";
                    candidateRows = new ArrayList<>(candidateRows.stream()
                            .map(row -> new SequenceCandidateRow(
                                    row.name(),
                                    row.sequenceGroupCount(),
                                    row.instructions(),
                                    false))
                            .toList());
                    candidateRows.add(new SequenceCandidateRow(
                            selectedName,
                            selectedGroups,
                            selectedInstructions.size(),
                            true));
                }
            }
            bestGroupsByKey.merge(groupKey, selectedGroups, Math::min);
        }

        log.info("Sequence-group selection: winner={} groups={} candidates={}",
                selectedName, selectedGroups, summarizeCandidates(candidates));
        SolverRunColumnArchive.recordInstructions(selectedInstructions);
        return new ConversionResult(
                selectedInstructions,
                selectedName,
                selectedGroups,
                candidateRows);
    }

    /**
     * B 层 LNS 变体评优。快路径：按全局属性单跑（现状不变）。质量模式
     * （cutting.quality=true）：默认邻域与扩大邻域（12/20/40/60）各跑一遍，
     * 按 组→odd→1车→small 字典序拣优——两套邻域参数在不同数据集上互有胜负
     * （sixian 扩大邻域胜，t9est188 默认邻域胜 69 vs 72，笔记13），
     * 单一调参路径不可靠，评优后结构上永不劣于任一单路径。
     */
    private LocalNeighborhoodSequenceOptimizer.LnsResult runLnsVariants(
            LocalNeighborhoodSequenceOptimizer optimizer,
            List<CuttingInstruction> instructions,
            List<SolverOrderItem> groupItems) {
        if (!test.demo.apsmodule.generator.NewSolver.CuttingSolver.qualityMode()) {
            return optimizer.improve(instructions, groupItems);
        }
        Map<String, String> defaultVariant = Map.of("cutting.lns.enabled", "true");
        // 扩大邻域变体收敛后长尾空转是新时间瓶颈（t9est188 实测末次改善后仍跑满
        // maxNoImprove=12 空迭代 ×~35s ≈ 6min）。该变体单迭代重（60 邻域/40 车），
        // 早停阈值收紧到 6（默认变体与快路径不变）——砍长尾 ~3min，改善多在前段命中。
        Map<String, String> qualityVariant = Map.of(
                "cutting.lns.enabled", "true",
                "cutting.lns.maxFreeOrders", "12",
                "cutting.lns.maxFreePatterns", "20",
                "cutting.lns.maxFreeCars", "40",
                "cutting.lns.maxNeighborhoods", "60",
                "cutting.lns.maxNoImprove", "6");
        LocalNeighborhoodSequenceOptimizer.LnsResult resultDefault =
                LocalNeighborhoodSequenceOptimizer.withPropertyOverrides(defaultVariant,
                        () -> optimizer.improve(instructions, groupItems));
        LocalNeighborhoodSequenceOptimizer.LnsResult resultQuality =
                LocalNeighborhoodSequenceOptimizer.withPropertyOverrides(qualityVariant,
                        () -> optimizer.improve(instructions, groupItems));
        SolverRunColumnArchive.recordInstructions(resultDefault.instructions());
        SolverRunColumnArchive.recordInstructions(resultQuality.instructions());
        SequenceGroupPostProcessor.GroupStats statsDefault =
                SequenceGroupPostProcessor.computeGroupStats(resultDefault.instructions());
        SequenceGroupPostProcessor.GroupStats statsQuality =
                SequenceGroupPostProcessor.computeGroupStats(resultQuality.instructions());
        boolean qualityWins = compareGroupStats(statsQuality, statsDefault) < 0;
        log.info("LNS variants: default={}/{}/{}/{} quality={}/{}/{}/{} -> winner={}",
                statsDefault.groups(), statsDefault.oddCarGroups(), statsDefault.oneCarGroups(),
                statsDefault.smallCarGroups(), statsQuality.groups(), statsQuality.oddCarGroups(),
                statsQuality.oneCarGroups(), statsQuality.smallCarGroups(),
                qualityWins ? "quality" : "default");
        return qualityWins ? resultQuality : resultDefault;
    }

    /**
     * Phase O：LNS 之后的奇偶修复。对 LNS 输出重跑 family repack（optimizeInstructionFamilies），
     * 用字典序（组数 → 奇数车组 → 1车组 → 小车组）+ 需求/车数/废边守恒做全局验收；任一维度回退即整体放弃。
     * 返回 null 表示未接受（保持原结果）。同时打印 odd 理论下界 = usage 为奇数的花型族个数
     * （每族块大小之和 = 族车数，奇数车数至少留一个奇块，B 层任何重排都破不了这个底）。
     */
    private List<CuttingInstruction> oddRepairPass(List<CuttingInstruction> instructions) {
        if (instructions == null || instructions.isEmpty()) {
            return null;
        }
        SequenceGroupPostProcessor.GroupStats before = SequenceGroupPostProcessor.computeGroupStats(instructions);
        int beforeCars = instructions.stream().mapToInt(CuttingInstruction::getUsageCount).sum();
        int beforeWaste = instructions.stream()
                .mapToInt(i -> i.getWaste() * i.getUsageCount()).sum();
        Map<String, Integer> beforeDemand = countAssignmentsByDemandKey(instructions);
        int oddFloor = oddCarFloor(instructions);

        List<CuttingInstruction> candidate = cloneInstructions(instructions);
        candidate = optimizeInstructionFamilies(candidate);
        compactInstructionRollOrder(candidate);
        reorderInstructionsForSequenceGroups(candidate);
        SolverRunColumnArchive.recordInstructions(candidate);

        SequenceGroupPostProcessor.GroupStats after = SequenceGroupPostProcessor.computeGroupStats(candidate);
        int afterCars = candidate.stream().mapToInt(CuttingInstruction::getUsageCount).sum();
        int afterWaste = candidate.stream()
                .mapToInt(i -> i.getWaste() * i.getUsageCount()).sum();
        boolean conserved = afterCars == beforeCars
                && afterWaste == beforeWaste
                && beforeDemand.equals(countAssignmentsByDemandKey(candidate));
        boolean lexBetter = compareGroupStats(after, before) < 0;
        log.info("Odd-repair pass: groups {}->{}, odd {}->{} (floor={}), one {}->{}, "
                        + "small {}->{}, conserved={}, accepted={}",
                before.groups(), after.groups(),
                before.oddCarGroups(), after.oddCarGroups(), oddFloor,
                before.oneCarGroups(), after.oneCarGroups(),
                before.smallCarGroups(), after.smallCarGroups(),
                conserved, conserved && lexBetter);
        return conserved && lexBetter ? candidate : null;
    }

    /**
     * 质量模式第三段验收（与 oddRepairPass 同款）：SetPartitionRefiner 产出候选指令后，
     * 走 compact+reorder，需求/车数/废边守恒 + （组→奇→1车→小）字典序严格变好才接受；
     * 任一维度回退即整体放弃（返回 null 保持原结果）。
     */
    private List<CuttingInstruction> setPartitionRefinePass(List<CuttingInstruction> instructions) {
        if (instructions == null || instructions.isEmpty()) {
            return null;
        }
        SequenceGroupPostProcessor.GroupStats before = SequenceGroupPostProcessor.computeGroupStats(instructions);
        int beforeCars = instructions.stream().mapToInt(CuttingInstruction::getUsageCount).sum();
        int beforeWaste = instructions.stream()
                .mapToInt(i -> i.getWaste() * i.getUsageCount()).sum();
        Map<String, Integer> beforeDemand = countAssignmentsByDemandKey(instructions);

        List<CuttingInstruction> candidate = SetPartitionRefiner.refine(instructions, params);
        if (candidate == null) {
            log.info("Set-partition refine pass: not applicable (structure) — kept original");
            return null;
        }
        List<CuttingInstruction> repaired = oddRepairPass(candidate);
        if (repaired != null) {
            candidate = repaired;
        }
        compactInstructionRollOrder(candidate);
        reorderInstructionsForSequenceGroups(candidate);

        SequenceGroupPostProcessor.GroupStats after = SequenceGroupPostProcessor.computeGroupStats(candidate);
        int afterCars = candidate.stream().mapToInt(CuttingInstruction::getUsageCount).sum();
        int afterWaste = candidate.stream()
                .mapToInt(i -> i.getWaste() * i.getUsageCount()).sum();
        boolean conserved = afterCars == beforeCars
                && afterWaste == beforeWaste
                && beforeDemand.equals(countAssignmentsByDemandKey(candidate));
        boolean lexBetter = compareGroupStats(after, before) < 0;
        log.info("Set-partition refine pass: groups {}->{}, odd {}->{}, one {}->{}, "
                        + "small {}->{}, conserved={}, accepted={}",
                before.groups(), after.groups(),
                before.oddCarGroups(), after.oddCarGroups(),
                before.oneCarGroups(), after.oneCarGroups(),
                before.smallCarGroups(), after.smallCarGroups(),
                conserved, conserved && lexBetter);
        return conserved && lexBetter ? candidate : null;
    }

    /** odd 理论下界：usage 合计为奇数的花型族（宽度多重集）个数。 */
    private int oddCarFloor(List<CuttingInstruction> instructions) {
        Map<String, Integer> carsByFamily = new LinkedHashMap<>();
        for (CuttingInstruction instruction : instructions) {
            carsByFamily.merge(instructionPatternSignature(instruction), instruction.getUsageCount(), Integer::sum);
        }
        return (int) carsByFamily.values().stream().filter(u -> u % 2 != 0).count();
    }

    /** Whether the Phase2 sequence-group candidate is evaluated. Default false. */
    private boolean phase2Enabled() {
        return SolverRuntimeProperties.getBoolean("cutting.phase2.enabled", false);
    }

    protected Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> solveAssignmentWithMip(
            Map<PatternCandidate, Integer> solution,
            List<SolverOrderItem> groupItems) {
        AssignmentMIPSolver assignmentSolver = new AssignmentMIPSolver(params);
        return assignmentSolver.solve(solution, groupItems);
    }

    protected Phase2SequenceGroupSolver.SolveResult solveSequenceGroupsWithPhase2(
            Map<PatternCandidate, Integer> solution,
            List<SolverOrderItem> groupItems) {
        return solveSequenceGroupsWithPhase2(solution, groupItems, null);
    }

    protected Phase2SequenceGroupSolver.SolveResult solveSequenceGroupsWithPhase2(
            Map<PatternCandidate, Integer> solution,
            List<SolverOrderItem> groupItems,
            Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> seedAssignments) {
        if (!loadOrTools()) {
            return null;
        }
        Phase2SequenceGroupSolver phase2Solver = new Phase2SequenceGroupSolver(params);
        return phase2Solver.solveWithSolution(solution, groupItems, seedAssignments);
    }

    protected void optimizeSequenceGroups(List<CuttingInstruction> instructions) {
        SequenceGroupPostProcessor.optimize(instructions);
    }

    private List<CuttingInstruction> buildFromMIPAssignment(
            Map<PatternCandidate, Integer> solution,
            String groupKey,
            List<SolverOrderItem> groupItems,
            Map<PatternCandidate, List<AssignmentMIPSolver.AssignmentBlock>> mipAssignment) {

        List<CuttingInstruction> instructions = new ArrayList<>();

        for (Map.Entry<PatternCandidate, Integer> entry : solution.entrySet()) {
            PatternCandidate pattern = entry.getKey();
            int usageCount = entry.getValue();

            CuttingInstruction instruction = new CuttingInstruction();
            instruction.setGroupKey(groupKey);
            instruction.setRollWidth(pattern.getRollWidth());
            instruction.setSubRolls(new LinkedHashMap<>(pattern.getPattern()));
            instruction.setUsageCount(usageCount);
            instruction.setPatternWidth(pattern.getPatternWidth());
            instruction.setWaste(params.getTotalWidth() - pattern.getPatternWidth());

            if (!groupItems.isEmpty()) {
                SolverOrderItem sample = groupItems.get(0);
                instruction.setLength(sample.getLength());
                instruction.setSurfaceTreatment(sample.getSurfaceTreatment());
                instruction.setThickness(sample.getThickness());
            }

            List<StationAssignment> assignments = new ArrayList<>();
            List<AssignmentMIPSolver.AssignmentBlock> blocks = mipAssignment.get(pattern);

            if (blocks != null) {
                for (AssignmentMIPSolver.AssignmentBlock block : blocks) {
                    Map<Integer, List<String>> stationConfig = block.getStationConfig();
                    Map<Integer, String> fallbackConfig = block.getConfig();
                    for (int roll = 0; roll < block.getCount(); roll++) {
                        for (Map.Entry<Integer, Integer> subRoll : pattern.getPattern().entrySet()) {
                            int width = subRoll.getKey();
                            int stationCount = subRoll.getValue();
                            for (int station = 0; station < stationCount; station++) {
                                String messageText = messageAtStation(
                                        stationConfig.get(width),
                                        station,
                                        fallbackConfig.getOrDefault(width, "UNKNOWN"));
                                assignments.add(new StationAssignment(width, messageText));
                            }
                        }
                    }
                }
            }

            instruction.setStationAssignments(assignments);
            instructions.add(instruction);
        }

        rebalanceAssignments(instructions, groupItems);
        optimizeInstructionBlocks(instructions);
        instructions = optimizeInstructionFamilies(instructions);
        compactInstructionRollOrder(instructions);
        reorderInstructionsForSequenceGroups(instructions);
        return instructions;
    }

    private String messageAtStation(List<String> messages, int station, String fallback) {
        if (messages == null || messages.isEmpty()) {
            return fallback;
        }
        if (station < messages.size()) {
            return messages.get(station);
        }
        return messages.get(messages.size() - 1);
    }

    private List<CuttingInstruction> buildFromGreedyAssignment(
            Map<PatternCandidate, Integer> solution,
            String groupKey,
            List<SolverOrderItem> groupItems) {
        return buildFromGreedyAssignment(
                solution, groupKey, groupItems, OrderAssignmentOptimizer.GreedyStrategy.BATCH_FIRST);
    }

    private List<CuttingInstruction> buildFromGreedyAssignment(
            Map<PatternCandidate, Integer> solution,
            String groupKey,
            List<SolverOrderItem> groupItems,
            OrderAssignmentOptimizer.GreedyStrategy strategy) {

        List<CuttingInstruction> instructions = new ArrayList<>();
        Map<Integer, List<SolverOrderItem>> widthToItems = groupItems.stream()
                .collect(Collectors.groupingBy(SolverOrderItem::getWidth));

        Map<String, Integer> remainingDemands = new HashMap<>();
        for (SolverOrderItem item : groupItems) {
            remainingDemands.merge(demandKey(item.getWidth(), item.getMessageText()), item.getDemand(), Integer::sum);
        }

        for (Map.Entry<PatternCandidate, Integer> entry : solution.entrySet()) {
            PatternCandidate pattern = entry.getKey();
            int usageCount = entry.getValue();

            CuttingInstruction instruction = new CuttingInstruction();
            instruction.setGroupKey(groupKey);
            instruction.setRollWidth(pattern.getRollWidth());
            instruction.setSubRolls(new LinkedHashMap<>(pattern.getPattern()));
            instruction.setUsageCount(usageCount);
            instruction.setPatternWidth(pattern.getPatternWidth());
            instruction.setWaste(params.getTotalWidth() - pattern.getPatternWidth());

            if (!groupItems.isEmpty()) {
                SolverOrderItem sample = groupItems.get(0);
                instruction.setLength(sample.getLength());
                instruction.setSurfaceTreatment(sample.getSurfaceTreatment());
                instruction.setThickness(sample.getThickness());
            }

            List<StationAssignment> assignments;
            if (params.isUseOptimizedAssignment()) {
                assignments = OrderAssignmentOptimizer.buildTupleBlockAssignments(
                        pattern.getPattern(), usageCount, groupItems, remainingDemands, strategy);
            } else {
                assignments = new ArrayList<>();
                for (Map.Entry<Integer, Integer> subRoll : pattern.getPattern().entrySet()) {
                    int width = subRoll.getKey();
                    int stationCount = subRoll.getValue();
                    int totalNeeded = stationCount * usageCount;
                    List<SolverOrderItem> items = widthToItems.getOrDefault(width, Collections.emptyList());

                    int remaining = totalNeeded;
                    for (SolverOrderItem item : items) {
                        if (remaining <= 0) {
                            break;
                        }
                        String key = demandKey(width, item.getMessageText());
                        int itemRemaining = remainingDemands.getOrDefault(key, 0);
                        if (itemRemaining <= 0) {
                            continue;
                        }
                        int allocated = Math.min(remaining, itemRemaining);
                        for (int i = 0; i < allocated; i++) {
                            assignments.add(new StationAssignment(width, item.getMessageText()));
                        }
                        remaining -= allocated;
                        remainingDemands.put(key, itemRemaining - allocated);
                    }

                    if (remaining > 0 && !items.isEmpty()) {
                        SolverOrderItem fallback = items.get(0);
                        for (int i = 0; i < remaining; i++) {
                            assignments.add(new StationAssignment(width, fallback.getMessageText()));
                        }
                    }
                }
            }

            instruction.setStationAssignments(assignments);
            instructions.add(instruction);
        }

        rebalanceAssignments(instructions, groupItems);
        optimizeInstructionBlocks(instructions);
        instructions = optimizeInstructionFamilies(instructions);
        compactInstructionRollOrder(instructions);
        reorderInstructionsForSequenceGroups(instructions);
        return instructions;
    }

    private void addCandidate(List<ScoredInstructionPlan> candidates, String name, List<CuttingInstruction> instructions) {
        if (instructions == null || instructions.isEmpty()) {
            return;
        }

        List<CuttingInstruction> candidate = cloneInstructions(instructions);
        Map<String, Integer> before = countAssignmentsByDemandKey(candidate);
        arrangeForSequenceGroups(candidate);
        Map<String, Integer> after = countAssignmentsByDemandKey(candidate);
        if (!before.equals(after)) {
            log.warn("Sequence-group candidate rejected for {} because width-message counts changed", name);
            return;
        }

        SolverRunColumnArchive.recordInstructions(candidate);
        SequenceGroupPostProcessor.GroupStats stats = SequenceGroupPostProcessor.computeGroupStats(candidate);
        candidates.add(new ScoredInstructionPlan(
                name, candidate, stats.groups(), stats.oddCarGroups(),
                stats.oneCarGroups(), stats.smallCarGroups()));
        log.info("Sequence-group candidate: {} groups={} oddCars={} oneCars={} "
                        + "smallCars={} instructions={}",
                name, stats.groups(), stats.oddCarGroups(), stats.oneCarGroups(),
                stats.smallCarGroups(), candidate.size());
    }

    private Map<String, Integer> countAssignmentsByDemandKey(List<CuttingInstruction> instructions) {
        Map<String, Integer> counts = new HashMap<>();
        for (CuttingInstruction instruction : instructions) {
            if (instruction.getStationAssignments() == null) {
                continue;
            }
            for (StationAssignment assignment : instruction.getStationAssignments()) {
                counts.merge(demandKey(assignment.getWidth(), assignment.getMessageText()), 1, Integer::sum);
            }
        }
        return counts;
    }

    private void rebalanceAssignments(List<CuttingInstruction> instructions, List<SolverOrderItem> groupItems) {
        Map<String, Integer> demandByKey = new HashMap<>();
        for (SolverOrderItem item : groupItems) {
            demandByKey.merge(demandKey(item.getWidth(), item.getMessageText()), item.getDemand(), Integer::sum);
        }

        Map<String, Integer> allocatedByKey = new HashMap<>();
        for (CuttingInstruction instruction : instructions) {
            if (instruction.getStationAssignments() == null) {
                continue;
            }
            for (StationAssignment assignment : instruction.getStationAssignments()) {
                allocatedByKey.merge(demandKey(assignment.getWidth(), assignment.getMessageText()), 1, Integer::sum);
            }
        }

        Map<Integer, List<String>> messagesByWidth = new HashMap<>();
        for (SolverOrderItem item : groupItems) {
            messagesByWidth.computeIfAbsent(item.getWidth(), key -> new ArrayList<>()).add(item.getMessageText());
        }
        for (List<String> messages : messagesByWidth.values()) {
            List<String> unique = new ArrayList<>(new LinkedHashSet<>(messages));
            messages.clear();
            messages.addAll(unique);
        }

        Map<String, Integer> overByKey = new HashMap<>();
        Map<String, Integer> underByKey = new HashMap<>();
        for (String key : demandByKey.keySet()) {
            int demand = demandByKey.get(key);
            int allocated = allocatedByKey.getOrDefault(key, 0);
            int diff = allocated - demand;
            if (diff > 0) {
                overByKey.put(key, diff);
            } else if (diff < 0) {
                underByKey.put(key, -diff);
            }
        }

        if (overByKey.isEmpty() || underByKey.isEmpty()) {
            return;
        }

        for (Map.Entry<Integer, List<String>> entry : messagesByWidth.entrySet()) {
            int width = entry.getKey();
            List<String> messages = entry.getValue();

            List<String> overMessages = new ArrayList<>();
            List<String> underMessages = new ArrayList<>();
            for (String message : messages) {
                String key = demandKey(width, message);
                if (overByKey.containsKey(key)) {
                    overMessages.add(message);
                }
                if (underByKey.containsKey(key)) {
                    underMessages.add(message);
                }
            }

            if (overMessages.isEmpty() || underMessages.isEmpty()) {
                continue;
            }

            for (String overMessage : overMessages) {
                String overKey = demandKey(width, overMessage);
                int overAmount = overByKey.getOrDefault(overKey, 0);
                if (overAmount <= 0) {
                    continue;
                }
                for (String underMessage : underMessages) {
                    String underKey = demandKey(width, underMessage);
                    int underAmount = underByKey.getOrDefault(underKey, 0);
                    if (underAmount <= 0) {
                        continue;
                    }
                    int transfer = Math.min(overAmount, underAmount);
                    if (transfer <= 0) {
                        continue;
                    }
                    int transferred = transferAssignments(instructions, width, overMessage, underMessage, transfer);
                    overAmount -= transferred;
                    underAmount -= transferred;
                    overByKey.put(overKey, overAmount);
                    underByKey.put(underKey, underAmount);
                    if (overAmount <= 0) {
                        break;
                    }
                }
            }
        }
    }

    private int transferAssignments(List<CuttingInstruction> instructions, int width,
            String fromMessage, String toMessage, int count) {
        int transferred = 0;
        for (int instructionIndex = instructions.size() - 1; instructionIndex >= 0; instructionIndex--) {
            CuttingInstruction instruction = instructions.get(instructionIndex);
            if (instruction.getStationAssignments() == null) {
                continue;
            }
            List<StationAssignment> assignments = instruction.getStationAssignments();
            for (int i = assignments.size() - 1; i >= 0 && transferred < count; i--) {
                StationAssignment assignment = assignments.get(i);
                if (assignment.getWidth() == width && fromMessage.equals(assignment.getMessageText())) {
                    assignments.set(i, new StationAssignment(width, toMessage));
                    transferred++;
                }
            }
            if (transferred >= count) {
                break;
            }
        }
        return transferred;
    }

    private List<StationAssignment> cloneAssignments(List<StationAssignment> assignments) {
        if (assignments == null) {
            return null;
        }
        List<StationAssignment> copy = new ArrayList<>(assignments.size());
        for (StationAssignment assignment : assignments) {
            copy.add(new StationAssignment(assignment.getWidth(), assignment.getMessageText()));
        }
        return copy;
    }

    private void optimizeInstructionBlocks(List<CuttingInstruction> instructions) {
        for (CuttingInstruction instruction : instructions) {
            optimizeInstructionBlocks(instruction);
        }
    }

    private List<CuttingInstruction> optimizeInstructionFamilies(List<CuttingInstruction> instructions) {
        if (instructions == null || instructions.size() <= 1) {
            return instructions;
        }

        Map<String, List<CuttingInstruction>> families = new LinkedHashMap<>();
        for (CuttingInstruction instruction : instructions) {
            families.computeIfAbsent(instructionFamilyKey(instruction), key -> new ArrayList<>()).add(instruction);
        }

        List<CuttingInstruction> optimized = new ArrayList<>();
        for (List<CuttingInstruction> family : families.values()) {
            optimized.addAll(optimizeInstructionFamily(family));
        }
        return optimized;
    }

    private List<CuttingInstruction> optimizeInstructionFamily(List<CuttingInstruction> family) {
        if (family == null || family.isEmpty()) {
            return Collections.emptyList();
        }
        if (family.size() == 1) {
            return family;
        }

        CuttingInstruction template = family.get(0);
        int totalUsageCount = family.stream().mapToInt(CuttingInstruction::getUsageCount).sum();
        if (totalUsageCount <= 1) {
            return family;
        }

        List<List<StationAssignment>> currentRolls = new ArrayList<>();
        for (CuttingInstruction instruction : family) {
            currentRolls.addAll(simulateRolls(instruction));
        }
        if (currentRolls.size() <= 1) {
            return family;
        }

        List<RollBlock> currentBlocks = buildRollBlocks(currentRolls);
        int currentBlockCount = currentBlocks.size();
        int currentOddBlockCount = countOddBlocks(currentBlocks);
        int currentSmallBlockCount = countSmallBlocks(currentBlocks);
        if (currentBlockCount <= 1 && currentOddBlockCount == 0) {
            return family;
        }

        Map<String, Integer> exactCounts = countAssignmentsByDemandKey(family);
        RepackProfile repackProfile = determineRepackProfile(totalUsageCount, currentBlockCount);
        List<RollConfig> candidateConfigs = buildInstructionCandidateConfigs(
                template, currentRolls, exactCounts, repackProfile);
        candidateConfigs = addHintConfigs(template, currentBlockCount, exactCounts, candidateConfigs);
        if (candidateConfigs.size() <= 1 || !loadOrTools()) {
            return family;
        }

        RepackPlan repackPlan = solveInstructionRepack(
                totalUsageCount,
                exactCounts,
                candidateConfigs,
                currentBlockCount,
                currentOddBlockCount,
                currentSmallBlockCount,
                repackProfile);
        if (repackPlan == null) {
            return family;
        }

        List<CuttingInstruction> rebuiltFamily = materializeFamilyInstructions(template, repackPlan.blocks());
        log.info("Instruction family repack @{}mm {}: blocks {} -> {}, oddBlocks {} -> {}, instructions {} -> {}",
                template.getRollWidth(),
                instructionPatternSignature(template),
                currentBlockCount,
                repackPlan.blockCount(),
                currentOddBlockCount,
                repackPlan.oddBlockCount(),
                family.size(),
                rebuiltFamily.size());
        return rebuiltFamily;
    }

    private void optimizeInstructionBlocks(CuttingInstruction instruction) {
        if (instruction == null
                || instruction.getUsageCount() <= 1
                || instruction.getSubRolls() == null
                || instruction.getSubRolls().isEmpty()
                || instruction.getStationAssignments() == null
                || instruction.getStationAssignments().isEmpty()) {
            return;
        }

        List<List<StationAssignment>> currentRolls = simulateRolls(instruction);
        if (currentRolls.size() <= 1) {
            return;
        }

        List<RollBlock> currentBlocks = buildRollBlocks(currentRolls);
        int currentBlockCount = currentBlocks.size();
        int currentOddBlockCount = countOddBlocks(currentBlocks);
        int currentSmallBlockCount = countSmallBlocks(currentBlocks);
        if (currentBlockCount <= 1 && currentOddBlockCount == 0) {
            return;
        }

        Map<String, Integer> exactCounts = countAssignmentsByDemandKey(List.of(instruction));
        RepackProfile repackProfile = determineRepackProfile(instruction.getUsageCount(), currentBlockCount);
        List<RollConfig> candidateConfigs = buildInstructionCandidateConfigs(
                instruction, currentRolls, exactCounts, repackProfile);
        candidateConfigs = addHintConfigs(instruction, currentBlockCount, exactCounts, candidateConfigs);
        if (candidateConfigs.size() <= 1 || !loadOrTools()) {
            return;
        }

        RepackPlan repackPlan = solveInstructionRepack(
                instruction.getUsageCount(),
                exactCounts,
                candidateConfigs,
                currentBlockCount,
                currentOddBlockCount,
                currentSmallBlockCount,
                repackProfile);
        if (repackPlan == null) {
            return;
        }

        instruction.setStationAssignments(materializeRepackedAssignments(repackPlan.blocks()));
        log.info("Instruction repack @{}mm: blocks {} -> {}, oddBlocks {} -> {}",
                instruction.getRollWidth(),
                currentBlockCount,
                repackPlan.blockCount(),
                currentOddBlockCount,
                repackPlan.oddBlockCount());
    }

    protected List<List<StationAssignment>> collectInstructionRepackHintRolls(
            CuttingInstruction instruction,
            int currentBlockCount) {
        if (instruction == null
                || currentBlockCount < INSTRUCTION_REPACK_HINT_MIN_BLOCKS
                || instruction.getUsageCount() < INSTRUCTION_REPACK_HINT_MIN_USAGE_COUNT) {
            return Collections.emptyList();
        }

        CuttingInstruction hintedInstruction = cloneInstruction(instruction);
        int groupsBefore = SequenceGroupPostProcessor.countGroups(hintedInstruction);
        List<CuttingInstruction> hintInstructions = new ArrayList<>(1);
        hintInstructions.add(hintedInstruction);
        SequenceGroupPostProcessor.optimize(hintInstructions, INSTRUCTION_REPACK_HINT_TIME_LIMIT_MS, false);
        int groupsAfter = SequenceGroupPostProcessor.countGroups(hintedInstruction);
        List<List<StationAssignment>> currentRolls = simulateRolls(instruction);
        List<List<StationAssignment>> hintedRolls = simulateRolls(hintedInstruction);
        int hintedBlockCount = buildRollBlocks(hintedRolls).size();
        boolean shapeChanged = hintedBlockCount < currentBlockCount
                || countDistinctRollSignatures(hintedRolls) > countDistinctRollSignatures(currentRolls);
        if (groupsAfter >= groupsBefore && !shapeChanged) {
            return Collections.emptyList();
        }

        return hintedRolls;
    }

    private List<RollConfig> addHintConfigs(
            CuttingInstruction instruction,
            int currentBlockCount,
            Map<String, Integer> exactCounts,
            List<RollConfig> candidateConfigs) {
        List<List<StationAssignment>> hintRolls = collectInstructionRepackHintRolls(instruction, currentBlockCount);
        if (hintRolls.isEmpty()) {
            return candidateConfigs;
        }

        LinkedHashMap<String, RollConfig> configsBySignature = new LinkedHashMap<>();
        for (RollConfig config : candidateConfigs) {
            configsBySignature.put(config.signature(), config);
        }

        int added = 0;
        for (List<StationAssignment> roll : hintRolls) {
            RollConfig config = createRollConfig(instruction.getSubRolls(), roll, exactCounts);
            if (config == null || configsBySignature.containsKey(config.signature())) {
                continue;
            }
            configsBySignature.put(config.signature(), config);
            added++;
        }

        if (added > 0) {
            log.info("Instruction repack hints @{}mm: +{} configs from low-group postprocess shape",
                    instruction.getRollWidth(), added);
        }

        return new ArrayList<>(configsBySignature.values());
    }

    private RepackPlan solveInstructionRepack(
            int usageCount,
            Map<String, Integer> exactCounts,
            List<RollConfig> candidateConfigs,
            int currentBlockCount,
            int currentOddBlockCount,
            int currentSmallBlockCount,
            RepackProfile repackProfile) {
        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            solver = MPSolver.createSolver("CBC");
        }
        if (solver == null) {
            return null;
        }

        List<RollConfig> activeConfigs = candidateConfigs.stream()
                .filter(config -> config.support() > 0)
                .toList();
        if (activeConfigs.isEmpty()) {
            return null;
        }

        List<MPVariable> countVars = new ArrayList<>(activeConfigs.size());
        List<MPVariable> useVars = new ArrayList<>(activeConfigs.size());
        List<MPVariable> oddVars = new ArrayList<>(activeConfigs.size());
        List<MPVariable> halfVars = new ArrayList<>(activeConfigs.size());
        List<MPVariable> smallVars = new ArrayList<>(activeConfigs.size());

        // A block with 1..SMALL_CAR_MAX_CARS cars is "small"; threshold is one above.
        int smallThreshold = SequenceGroupPostProcessor.SMALL_CAR_MAX_CARS + 1;

        for (int index = 0; index < activeConfigs.size(); index++) {
            RollConfig config = activeConfigs.get(index);
            int upperBound = Math.min(usageCount, config.support());
            MPVariable countVar = solver.makeIntVar(0, upperBound, "repack_count_" + index);
            MPVariable useVar = solver.makeBoolVar("repack_use_" + index);
            MPVariable oddVar = solver.makeBoolVar("repack_odd_" + index);
            MPVariable halfVar = solver.makeIntVar(0, upperBound, "repack_half_" + index);
            MPVariable smallVar = solver.makeBoolVar("repack_small_" + index);

            MPConstraint linkConstraint = solver.makeConstraint(-MPSolver.infinity(), 0, "repack_link_" + index);
            linkConstraint.setCoefficient(countVar, 1);
            linkConstraint.setCoefficient(useVar, -upperBound);

            MPConstraint parityConstraint = solver.makeConstraint(0, 0, "repack_parity_" + index);
            parityConstraint.setCoefficient(countVar, 1);
            parityConstraint.setCoefficient(halfVar, -2);
            parityConstraint.setCoefficient(oddVar, -1);

            // Force small=1 when 0 < count <= SMALL_CAR_MAX_CARS:
            //   smallThreshold*small + count >= smallThreshold*use
            MPConstraint smallConstraint = solver.makeConstraint(0, MPSolver.infinity(), "repack_small_c_" + index);
            smallConstraint.setCoefficient(smallVar, smallThreshold);
            smallConstraint.setCoefficient(countVar, 1);
            smallConstraint.setCoefficient(useVar, -smallThreshold);

            countVars.add(countVar);
            useVars.add(useVar);
            oddVars.add(oddVar);
            halfVars.add(halfVar);
            smallVars.add(smallVar);
        }

        MPConstraint rollCountConstraint = solver.makeConstraint(usageCount, usageCount, "repack_total_rolls");
        for (MPVariable countVar : countVars) {
            rollCountConstraint.setCoefficient(countVar, 1);
        }

        for (Map.Entry<String, Integer> exactEntry : exactCounts.entrySet()) {
            MPConstraint exactConstraint = solver.makeConstraint(
                    exactEntry.getValue(), exactEntry.getValue(), "repack_exact_" + exactEntry.getKey());
            for (int index = 0; index < activeConfigs.size(); index++) {
                int coefficient = activeConfigs.get(index).demandUsage().getOrDefault(exactEntry.getKey(), 0);
                if (coefficient > 0) {
                    exactConstraint.setCoefficient(countVars.get(index), coefficient);
                }
            }
        }

        // Weights enforce priority: fewer blocks (groups) >> fewer odd-car blocks > fewer small-car blocks.
        MPObjective objective = solver.objective();
        for (int index = 0; index < activeConfigs.size(); index++) {
            objective.setCoefficient(useVars.get(index), 10_000);
            objective.setCoefficient(oddVars.get(index), 100);
            objective.setCoefficient(smallVars.get(index), 10);
        }
        objective.setMinimization();

        solver.setTimeLimit(repackProfile.timeLimitMs());
        MPSolver.ResultStatus status = solver.solve();
        if (status != MPSolver.ResultStatus.OPTIMAL && status != MPSolver.ResultStatus.FEASIBLE) {
            return null;
        }

        List<RepackBlock> blocks = new ArrayList<>();
        for (int index = 0; index < activeConfigs.size(); index++) {
            int count = (int) Math.round(countVars.get(index).solutionValue());
            if (count > 0) {
                blocks.add(new RepackBlock(activeConfigs.get(index), count));
            }
        }

        if (blocks.isEmpty()) {
            return null;
        }

        blocks.sort(Comparator
                .comparingInt(RepackBlock::count).reversed()
                .thenComparing(block -> block.config().signature()));

        int optimizedBlockCount = blocks.size();
        int optimizedOddBlockCount = (int) blocks.stream().filter(block -> block.count() % 2 != 0).count();
        int optimizedSmallBlockCount = (int) blocks.stream()
                .filter(block -> block.count() > 0 && block.count() <= SequenceGroupPostProcessor.SMALL_CAR_MAX_CARS)
                .count();
        // Accept only a real improvement, ranked blocks > odd-blocks > small-blocks.
        if (optimizedBlockCount > currentBlockCount) {
            return null;
        }
        if (optimizedBlockCount == currentBlockCount) {
            if (optimizedOddBlockCount > currentOddBlockCount) {
                return null;
            }
            if (optimizedOddBlockCount == currentOddBlockCount
                    && optimizedSmallBlockCount >= currentSmallBlockCount) {
                return null;
            }
        }

        if (!exactCounts.equals(countAssignmentsByDemandKey(materializeRepackedInstructions(blocks)))) {
            return null;
        }

        return new RepackPlan(blocks, optimizedBlockCount, optimizedOddBlockCount);
    }

    private List<CuttingInstruction> materializeRepackedInstructions(List<RepackBlock> blocks) {
        CuttingInstruction instruction = new CuttingInstruction();
        instruction.setUsageCount(blocks.stream().mapToInt(RepackBlock::count).sum());
        List<StationAssignment> assignments = materializeRepackedAssignments(blocks);
        instruction.setStationAssignments(assignments);
        return List.of(instruction);
    }

    private List<CuttingInstruction> materializeFamilyInstructions(
            CuttingInstruction template,
            List<RepackBlock> blocks) {
        List<CuttingInstruction> rebuilt = new ArrayList<>();
        for (RepackBlock block : blocks) {
            CuttingInstruction instruction = cloneInstruction(template);
            instruction.setUsageCount(block.count());
            instruction.setStationAssignments(materializeRepackedAssignments(List.of(block)));
            rebuilt.add(instruction);
        }
        return rebuilt;
    }

    private List<StationAssignment> materializeRepackedAssignments(List<RepackBlock> blocks) {
        List<StationAssignment> assignments = new ArrayList<>();
        for (RepackBlock block : blocks) {
            for (int roll = 0; roll < block.count(); roll++) {
                for (Map.Entry<Integer, List<String>> entry : block.config().messagesByWidth().entrySet()) {
                    int width = entry.getKey();
                    for (String messageText : entry.getValue()) {
                        assignments.add(new StationAssignment(width, messageText));
                    }
                }
            }
        }
        return assignments;
    }

    private List<RollConfig> buildInstructionCandidateConfigs(
            CuttingInstruction instruction,
            List<List<StationAssignment>> currentRolls,
            Map<String, Integer> exactCounts,
            RepackProfile repackProfile) {
        LinkedHashMap<String, RollConfig> candidatesBySignature = new LinkedHashMap<>();

        for (List<StationAssignment> roll : currentRolls) {
            RollConfig config = createRollConfig(instruction.getSubRolls(), roll, exactCounts);
            if (config != null) {
                candidatesBySignature.putIfAbsent(config.signature(), config);
            }
        }

        Map<Integer, Map<String, Integer>> countsByWidth = buildWidthMessageCounts(currentRolls);
        List<Integer> widths = new ArrayList<>(instruction.getSubRolls().keySet());
        List<List<WidthOption>> optionsByWidth = new ArrayList<>(widths.size());
        for (int width : widths) {
            int slotsNeeded = instruction.getSubRolls().getOrDefault(width, 0);
            optionsByWidth.add(generateWidthOptions(
                    slotsNeeded,
                    countsByWidth.getOrDefault(width, Collections.emptyMap()),
                    repackProfile.maxWidthMessages(),
                    repackProfile.maxWidthOptions()));
        }

        List<RollConfig> generatedConfigs = new ArrayList<>();
        enumerateFullConfigs(
                widths,
                optionsByWidth,
                0,
                new LinkedHashMap<>(),
                exactCounts,
                generatedConfigs,
                repackProfile.maxFullConfigs());
        generatedConfigs.sort(Comparator
                .comparingInt(RollConfig::support).reversed()
                .thenComparingInt(RollConfig::oddMultiplicityCount)
                .thenComparingInt(RollConfig::distinctMessageCount)
                .thenComparing(RollConfig::signature));

        for (RollConfig config : generatedConfigs) {
            candidatesBySignature.putIfAbsent(config.signature(), config);
            if (candidatesBySignature.size() >= repackProfile.maxFullConfigs()) {
                break;
            }
        }

        return new ArrayList<>(candidatesBySignature.values());
    }

    private void enumerateFullConfigs(
            List<Integer> widths,
            List<List<WidthOption>> optionsByWidth,
            int index,
            LinkedHashMap<Integer, List<String>> currentConfig,
            Map<String, Integer> exactCounts,
            List<RollConfig> generatedConfigs,
            int maxFullConfigs) {
        if (generatedConfigs.size() >= maxFullConfigs * 2) {
            return;
        }

        if (index >= widths.size()) {
            RollConfig config = createRollConfig(currentConfig, exactCounts);
            if (config != null) {
                generatedConfigs.add(config);
            }
            return;
        }

        int width = widths.get(index);
        for (WidthOption option : optionsByWidth.get(index)) {
            currentConfig.put(width, option.messages());
            enumerateFullConfigs(widths, optionsByWidth, index + 1, currentConfig, exactCounts, generatedConfigs, maxFullConfigs);
            currentConfig.remove(width);
            if (generatedConfigs.size() >= maxFullConfigs * 2) {
                return;
            }
        }
    }

    private Map<Integer, Map<String, Integer>> buildWidthMessageCounts(List<List<StationAssignment>> rolls) {
        Map<Integer, Map<String, Integer>> countsByWidth = new LinkedHashMap<>();
        for (List<StationAssignment> roll : rolls) {
            for (StationAssignment assignment : roll) {
                countsByWidth
                        .computeIfAbsent(assignment.getWidth(), key -> new LinkedHashMap<>())
                        .merge(Objects.toString(assignment.getMessageText(), ""), 1, Integer::sum);
            }
        }
        return countsByWidth;
    }

    private List<WidthOption> generateWidthOptions(
            int slotsNeeded,
            Map<String, Integer> countsByMessage,
            int maxWidthMessages,
            int maxWidthOptions) {
        if (slotsNeeded <= 0 || countsByMessage.isEmpty()) {
            return Collections.emptyList();
        }

        List<MessageCount> messages = countsByMessage.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(maxWidthMessages)
                .map(entry -> new MessageCount(entry.getKey(), entry.getValue()))
                .toList();
        if (messages.isEmpty()) {
            return Collections.emptyList();
        }

        List<WidthOption> options = new ArrayList<>();
        int[] counts = new int[messages.size()];
        enumerateWidthOptions(messages, slotsNeeded, 0, slotsNeeded, counts, options);
        options.sort(Comparator
                .comparingInt(WidthOption::support).reversed()
                .thenComparingInt(WidthOption::oddMultiplicityCount)
                .thenComparingInt(WidthOption::distinctMessageCount)
                .thenComparing(option -> String.join(",", option.messages())));

        if (options.size() > maxWidthOptions) {
            return new ArrayList<>(options.subList(0, maxWidthOptions));
        }
        return options;
    }

    private void enumerateWidthOptions(
            List<MessageCount> messages,
            int slotsNeeded,
            int index,
            int remainingSlots,
            int[] counts,
            List<WidthOption> options) {
        if (index == messages.size() - 1) {
            counts[index] = remainingSlots;
            addWidthOption(messages, counts, options);
            counts[index] = 0;
            return;
        }

        for (int count = remainingSlots; count >= 0; count--) {
            counts[index] = count;
            enumerateWidthOptions(messages, slotsNeeded, index + 1, remainingSlots - count, counts, options);
        }
        counts[index] = 0;
    }

    private void addWidthOption(List<MessageCount> messages, int[] counts, List<WidthOption> options) {
        List<String> optionMessages = new ArrayList<>();
        int support = Integer.MAX_VALUE;
        int distinctMessageCount = 0;
        int oddMultiplicityCount = 0;

        for (int index = 0; index < counts.length; index++) {
            int count = counts[index];
            if (count <= 0) {
                continue;
            }

            MessageCount message = messages.get(index);
            support = Math.min(support, message.count() / count);
            distinctMessageCount++;
            if (count % 2 != 0) {
                oddMultiplicityCount++;
            }
            for (int slot = 0; slot < count; slot++) {
                optionMessages.add(message.messageText());
            }
        }

        if (optionMessages.isEmpty() || support <= 0) {
            return;
        }

        optionMessages.sort(String::compareTo);
        options.add(new WidthOption(optionMessages, support, distinctMessageCount, oddMultiplicityCount));
    }

    private RollConfig createRollConfig(
            Map<Integer, Integer> subRolls,
            List<StationAssignment> roll,
            Map<String, Integer> exactCounts) {
        LinkedHashMap<Integer, List<String>> messagesByWidth = new LinkedHashMap<>();
        Map<Integer, List<String>> rollMessagesByWidth = new HashMap<>();
        for (StationAssignment assignment : roll) {
            rollMessagesByWidth
                    .computeIfAbsent(assignment.getWidth(), key -> new ArrayList<>())
                    .add(Objects.toString(assignment.getMessageText(), ""));
        }

        for (Map.Entry<Integer, Integer> entry : subRolls.entrySet()) {
            int width = entry.getKey();
            List<String> messages = new ArrayList<>(rollMessagesByWidth.getOrDefault(width, Collections.emptyList()));
            if (messages.isEmpty()) {
                return null;
            }
            messages.sort(String::compareTo);
            messagesByWidth.put(width, messages);
        }

        return createRollConfig(messagesByWidth, exactCounts);
    }

    private RollConfig createRollConfig(
            Map<Integer, List<String>> messagesByWidth,
            Map<String, Integer> exactCounts) {
        LinkedHashMap<Integer, List<String>> normalized = new LinkedHashMap<>();
        List<Integer> widths = new ArrayList<>(messagesByWidth.keySet());
        Collections.sort(widths);

        Map<String, Integer> demandUsage = new LinkedHashMap<>();
        int oddMultiplicityCount = 0;
        int distinctMessageCount = 0;
        List<String> signatureParts = new ArrayList<>();

        for (int width : widths) {
            List<String> messages = new ArrayList<>(messagesByWidth.getOrDefault(width, Collections.emptyList()));
            if (messages.isEmpty()) {
                return null;
            }

            messages.sort(String::compareTo);
            normalized.put(width, messages);
            signatureParts.add(width + "=" + String.join(",", messages));

            Map<String, Integer> multiplicities = new LinkedHashMap<>();
            for (String messageText : messages) {
                String key = demandKey(width, messageText);
                multiplicities.merge(key, 1, Integer::sum);
            }
            distinctMessageCount += multiplicities.size();
            for (Map.Entry<String, Integer> entry : multiplicities.entrySet()) {
                demandUsage.put(entry.getKey(), entry.getValue());
                if (entry.getValue() % 2 != 0) {
                    oddMultiplicityCount++;
                }
            }
        }

        int support = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : demandUsage.entrySet()) {
            int available = exactCounts.getOrDefault(entry.getKey(), 0);
            if (available < entry.getValue()) {
                return null;
            }
            support = Math.min(support, available / entry.getValue());
        }
        if (support <= 0) {
            return null;
        }

        return new RollConfig(
                String.join("|", signatureParts),
                normalized,
                demandUsage,
                support,
                distinctMessageCount,
                oddMultiplicityCount);
    }

    private int countOddBlocks(List<RollBlock> blocks) {
        return (int) blocks.stream().filter(block -> block.rolls().size() % 2 != 0).count();
    }

    private int countSmallBlocks(List<RollBlock> blocks) {
        return (int) blocks.stream()
                .filter(block -> !block.rolls().isEmpty()
                        && block.rolls().size() <= SequenceGroupPostProcessor.SMALL_CAR_MAX_CARS)
                .count();
    }

    private void compactInstructionRollOrder(List<CuttingInstruction> instructions) {
        for (CuttingInstruction instruction : instructions) {
            compactInstructionRollOrder(instruction);
        }
    }

    private void compactInstructionRollOrder(CuttingInstruction instruction) {
        if (instruction == null || instruction.getStationAssignments() == null || instruction.getStationAssignments().isEmpty()) {
            return;
        }
        if (instruction.getSubRolls() == null || instruction.getSubRolls().isEmpty() || instruction.getUsageCount() <= 1) {
            return;
        }

        List<List<StationAssignment>> rolls = simulateRolls(instruction);
        if (rolls.size() <= 1) {
            return;
        }

        List<RollBlock> blocks = buildRollBlocks(rolls);
        List<StationAssignment> compacted = new ArrayList<>(instruction.getStationAssignments().size());
        for (RollBlock block : blocks) {
            for (List<StationAssignment> roll : block.rolls()) {
                compacted.addAll(roll);
            }
        }
        instruction.setStationAssignments(compacted);
    }

    /**
     * Canonical sequence-group arrangement used before counting groups: compact each
     * instruction's rolls into content blocks, then globally cluster identical-content
     * instructions adjacently. Exposed so the LNS measures candidates the same way the
     * production candidates are measured (otherwise it compares a reordered baseline against
     * un-reordered candidates and rejects real improvements).
     */
    void arrangeForSequenceGroups(List<CuttingInstruction> instructions) {
        compactInstructionRollOrder(instructions);
        reorderInstructionsForSequenceGroups(instructions);
    }

    private void reorderInstructionsForSequenceGroups(List<CuttingInstruction> instructions) {
        instructions.sort(Comparator
                .comparing(this::instructionPrimaryRollSignature)
                .thenComparing(this::instructionRollProfile)
                .thenComparing(Comparator.comparingInt(this::instructionPrimaryRollCount).reversed())
                .thenComparingInt(this::instructionRollBlockCount)
                .thenComparingInt(CuttingInstruction::getRollWidth)
                .thenComparing(this::instructionPatternSignature));
    }

    private List<List<StationAssignment>> simulateRolls(CuttingInstruction instruction) {
        List<List<StationAssignment>> rolls = new ArrayList<>();
        Map<Integer, Queue<StationAssignment>> buckets = new LinkedHashMap<>();
        for (StationAssignment assignment : instruction.getStationAssignments()) {
            buckets.computeIfAbsent(assignment.getWidth(), key -> new LinkedList<>()).add(assignment);
        }

        for (int i = 0; i < instruction.getUsageCount(); i++) {
            List<StationAssignment> rollAssignments = new ArrayList<>();
            for (Map.Entry<Integer, Integer> entry : instruction.getSubRolls().entrySet()) {
                int width = entry.getKey();
                int count = entry.getValue();
                Queue<StationAssignment> bucket = buckets.get(width);
                for (int j = 0; j < count; j++) {
                    if (bucket != null && !bucket.isEmpty()) {
                        rollAssignments.add(bucket.poll());
                    }
                }
            }
            if (!rollAssignments.isEmpty()) {
                rolls.add(rollAssignments);
            }
        }
        return rolls;
    }

    private String rollSignature(List<StationAssignment> roll) {
        return roll.stream()
                .map(assignment -> assignment.getWidth() + "|" + Objects.toString(assignment.getMessageText(), ""))
                .sorted()
                .collect(Collectors.joining(","));
    }

    private List<RollBlock> buildRollBlocks(List<List<StationAssignment>> rolls) {
        Map<String, List<List<StationAssignment>>> rollsBySignature = new LinkedHashMap<>();
        for (List<StationAssignment> roll : rolls) {
            rollsBySignature.computeIfAbsent(rollSignature(roll), key -> new ArrayList<>()).add(roll);
        }

        return rollsBySignature.entrySet().stream()
                .map(entry -> new RollBlock(entry.getKey(), entry.getValue()))
                .sorted(Comparator
                        .comparingInt((RollBlock block) -> block.rolls().size()).reversed()
                        .thenComparing(RollBlock::signature))
                .toList();
    }

    private String instructionPrimaryRollSignature(CuttingInstruction instruction) {
        List<RollBlock> blocks = buildRollBlocks(simulateRolls(instruction));
        return blocks.isEmpty() ? "" : blocks.get(0).signature();
    }

    private int instructionPrimaryRollCount(CuttingInstruction instruction) {
        List<RollBlock> blocks = buildRollBlocks(simulateRolls(instruction));
        return blocks.isEmpty() ? 0 : blocks.get(0).rolls().size();
    }

    private int instructionRollBlockCount(CuttingInstruction instruction) {
        return buildRollBlocks(simulateRolls(instruction)).size();
    }

    private String instructionRollProfile(CuttingInstruction instruction) {
        return buildRollBlocks(simulateRolls(instruction)).stream()
                .map(block -> block.signature() + "#" + block.rolls().size())
                .collect(Collectors.joining("||"));
    }

    private int countDistinctRollSignatures(List<List<StationAssignment>> rolls) {
        return (int) rolls.stream()
                .map(this::rollSignature)
                .distinct()
                .count();
    }

    private String instructionPatternSignature(CuttingInstruction instruction) {
        if (instruction.getSubRolls() == null || instruction.getSubRolls().isEmpty()) {
            return "";
        }
        return instruction.getSubRolls().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "x" + entry.getValue())
                .collect(Collectors.joining(","));
    }

    private RepackProfile determineRepackProfile(int usageCount, int currentBlockCount) {
        if (usageCount >= LARGE_REPACK_MIN_USAGE_COUNT || currentBlockCount >= LARGE_REPACK_MIN_BLOCKS) {
            return new RepackProfile(
                    LARGE_MAX_WIDTH_MESSAGES_PER_REPACK,
                    LARGE_MAX_WIDTH_OPTIONS_PER_REPACK,
                    LARGE_MAX_FULL_CONFIGS_PER_REPACK,
                    LARGE_INSTRUCTION_REPACK_TIME_LIMIT_MS);
        }
        return new RepackProfile(
                DEFAULT_MAX_WIDTH_MESSAGES_PER_REPACK,
                DEFAULT_MAX_WIDTH_OPTIONS_PER_REPACK,
                DEFAULT_MAX_FULL_CONFIGS_PER_REPACK,
                DEFAULT_INSTRUCTION_REPACK_TIME_LIMIT_MS);
    }

    private String instructionFamilyKey(CuttingInstruction instruction) {
        return Objects.toString(instruction.getGroupKey(), "") + "||"
                + instruction.getRollWidth() + "||"
                + instruction.getLength() + "||"
                + Objects.toString(instruction.getSurfaceTreatment(), "") + "||"
                + instruction.getThickness() + "||"
                + instructionPatternSignature(instruction);
    }

    private List<CuttingInstruction> cloneInstructions(List<CuttingInstruction> instructions) {
        List<CuttingInstruction> copy = new ArrayList<>(instructions.size());
        for (CuttingInstruction instruction : instructions) {
            copy.add(cloneInstruction(instruction));
        }
        return copy;
    }

    private CuttingInstruction cloneInstruction(CuttingInstruction instruction) {
        CuttingInstruction cloned = new CuttingInstruction();
        cloned.setGroupKey(instruction.getGroupKey());
        cloned.setRollWidth(instruction.getRollWidth());
        cloned.setLength(instruction.getLength());
        cloned.setSurfaceTreatment(instruction.getSurfaceTreatment());
        cloned.setThickness(instruction.getThickness());
        cloned.setSubRolls(new LinkedHashMap<>(instruction.getSubRolls()));
        cloned.setUsageCount(instruction.getUsageCount());
        cloned.setPatternWidth(instruction.getPatternWidth());
        cloned.setWaste(instruction.getWaste());
        cloned.setStationAssignments(cloneAssignments(instruction.getStationAssignments()));
        return cloned;
    }

    private ScoredInstructionPlan selectBestCandidate(List<ScoredInstructionPlan> candidates) {
        candidates.sort(Comparator.comparingInt(ScoredInstructionPlan::sequenceGroupCount)
                .thenComparingInt(ScoredInstructionPlan::oddCarGroups)
                .thenComparingInt(ScoredInstructionPlan::oneCarGroups)
                .thenComparingInt(ScoredInstructionPlan::smallCarGroups)
                .thenComparingInt(candidate -> candidatePreference(candidate.name())));
        return candidates.get(0);
    }

    private static int compareGroupStats(SequenceGroupPostProcessor.GroupStats first,
            SequenceGroupPostProcessor.GroupStats second) {
        int groups = Integer.compare(first.groups(), second.groups());
        if (groups != 0) {
            return groups;
        }
        int odd = Integer.compare(first.oddCarGroups(), second.oddCarGroups());
        if (odd != 0) {
            return odd;
        }
        int one = Integer.compare(first.oneCarGroups(), second.oneCarGroups());
        return one != 0 ? one : Integer.compare(first.smallCarGroups(), second.smallCarGroups());
    }

    private String summarizeCandidates(List<ScoredInstructionPlan> candidates) {
        return candidates.stream()
                .sorted(Comparator.comparingInt(candidate -> candidatePreference(candidate.name())))
                .map(candidate -> candidate.name() + "=" + candidate.sequenceGroupCount())
                .collect(Collectors.joining(", "));
    }

    private List<SequenceCandidateRow> buildSequenceCandidateRows(
            List<ScoredInstructionPlan> candidates,
            String selectedName) {
        return candidates.stream()
                .sorted(Comparator.comparingInt((ScoredInstructionPlan candidate) -> candidatePreference(candidate.name()))
                        .thenComparingInt(ScoredInstructionPlan::sequenceGroupCount))
                .map(candidate -> new SequenceCandidateRow(
                        candidate.name(),
                        candidate.sequenceGroupCount(),
                        candidate.instructions().size(),
                        candidate.name().equals(selectedName)))
                .toList();
    }

    private int candidatePreference(String name) {
        return switch (name) {
            case "stage5-mip" -> 0;
            case "phase2-cg" -> 1;
            case "greedy" -> 2;
            case "greedy-reuse" -> 3;
            default -> Integer.MAX_VALUE;
        };
    }

    private String demandKey(int width, String messageText) {
        return width + "|" + Objects.toString(messageText, "");
    }

    private record ScoredInstructionPlan(
            String name,
            List<CuttingInstruction> instructions,
            int sequenceGroupCount,
            int oddCarGroups,
            int oneCarGroups,
            int smallCarGroups) {
    }

    public record ConversionResult(
            List<CuttingInstruction> instructions,
            String selectedName,
            int selectedSequenceGroups,
            List<SequenceCandidateRow> candidateRows) {
    }

    public record SequenceCandidateRow(
            String name,
            int sequenceGroupCount,
            int instructions,
            boolean selected) {
    }

    private record MessageCount(String messageText, int count) {
    }

    private record WidthOption(
            List<String> messages,
            int support,
            int distinctMessageCount,
            int oddMultiplicityCount) {
    }

    private record RollConfig(
            String signature,
            LinkedHashMap<Integer, List<String>> messagesByWidth,
            Map<String, Integer> demandUsage,
            int support,
            int distinctMessageCount,
            int oddMultiplicityCount) {
    }

    private record RepackBlock(RollConfig config, int count) {
    }

    private record RepackPlan(
            List<RepackBlock> blocks,
            int blockCount,
            int oddBlockCount) {
    }

    private record RepackProfile(
            int maxWidthMessages,
            int maxWidthOptions,
            int maxFullConfigs,
            long timeLimitMs) {
    }

    private record RollBlock(String signature, List<List<StationAssignment>> rolls) {
    }
}
