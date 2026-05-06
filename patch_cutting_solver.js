const fs = require('fs');
const path = 'd:/BigBase/Solartron-Cut/src/main/java/test/demo/apsmodule/generator/NewSolver/CuttingSolver.java';
let c = fs.readFileSync(path, 'utf8').replace(/\r\n/g, '\n');

// ── Add import ────────────────────────────────────────────────────────────────
const importInsert = 'import test.demo.apsmodule.generator.NewSolver.report.SolveReportWriter;\n';
const importAnchor = 'import test.demo.apsmodule.service.CuttingInstruction;';
if (!c.includes('SolveReportWriter')) {
    c = c.replace(importAnchor, importInsert + importAnchor);
    console.log('Import added');
} else {
    console.log('Import already present');
}

// ── Replace inner solve loop ──────────────────────────────────────────────────
// Anchor: unique text just before the for-loop over groups
const OLD_INIT = '        List<CuttingInstruction> allInstructions = new ArrayList<>();\n\n        for (Map.Entry<String, List<SolverOrderItem>> group : groups.entrySet()) {';
const NEW_INIT = '        List<CuttingInstruction> allInstructions = new ArrayList<>();\n\n        // One report file per solve request\n        try (SolveReportWriter report = SolveReportWriter.create()) {\n            log.info("Solve report: {}", report.getFilePath());\n\n            int totalGroups = groups.size();\n            for (Map.Entry<String, List<SolverOrderItem>> group : groups.entrySet()) {';
if (!c.includes(OLD_INIT)) { console.error('ERR: init anchor not found'); process.exit(1); }
c = c.replace(OLD_INIT, NEW_INIT);

// Add groupStart timing after groupKey extraction
const OLD_GROUPKEY = '            String groupKey = group.getKey();\n            List<SolverOrderItem> groupItems = group.getValue();\n\n            log.info("--- Processing group: {} ({} items) ---", groupKey, groupItems.size());';
const NEW_GROUPKEY = '                String groupKey = group.getKey();\n                List<SolverOrderItem> groupItems = group.getValue();\n                long groupStart = System.currentTimeMillis();\n\n                log.info("--- Processing group: {} ({} items) ---", groupKey, groupItems.size());';
if (!c.includes(OLD_GROUPKEY)) { console.error('ERR: groupKey anchor not found'); process.exit(1); }
c = c.replace(OLD_GROUPKEY, NEW_GROUPKEY);

// Fix indentation of demands and add report.beginGroup()
const OLD_DEMANDS = '            Map<Integer, Integer> demands = groupItems.stream()\n                    .collect(Collectors.groupingBy(\n                            SolverOrderItem::getWidth,\n                            Collectors.summingInt(SolverOrderItem::getDemand)));\n\n            Set<Integer> allowOverSet = buildAllowOverSet(demands, params);';
const NEW_DEMANDS = '                Map<Integer, Integer> demands = groupItems.stream()\n                        .collect(Collectors.groupingBy(\n                                SolverOrderItem::getWidth,\n                                Collectors.summingInt(SolverOrderItem::getDemand)));\n\n                report.beginGroup(groupKey, groupItems, demands, totalGroups);\n\n                Set<Integer> allowOverSet = buildAllowOverSet(demands, params);';
if (!c.includes(OLD_DEMANDS)) { console.error('ERR: demands anchor not found'); process.exit(1); }
c = c.replace(OLD_DEMANDS, NEW_DEMANDS);

// Fix indentation of allowOverSet log
c = c.replace('            log.debug("Allow-over widths: {}", allowOverSet);\n\n            List<PatternCandidate> patterns',
               '                log.debug("Allow-over widths: {}", allowOverSet);\n\n                List<PatternCandidate> patterns');

// Fix indentation of patterns and solveCandidates
c = c.replace('            List<PatternCandidate> patterns = patternGenerator.generate(demands);\n            patterns = colGenSolver.solve(patterns, demands, allowOverSet);\n\n            List<MultiStageMIPSolver.SolveCandidate> solveCandidates = mipSolver.solveCandidates(patterns, demands, allowOverSet);\n            if (solveCandidates.isEmpty()) {\n                log.warn("Solve failed for group: {}", groupKey);\n                continue;\n            }\n\n            GroupSolvePlan bestPlan = null;\n            for (int candidateIndex',
               '                List<PatternCandidate> patterns = patternGenerator.generate(demands);\n                patterns = colGenSolver.solve(patterns, demands, allowOverSet);\n\n                List<MultiStageMIPSolver.SolveCandidate> solveCandidates =\n                        mipSolver.solveCandidates(patterns, demands, allowOverSet);\n                if (solveCandidates.isEmpty()) {\n                    log.warn("Solve failed for group: {}", groupKey);\n                    continue;\n                }\n\n                GroupSolvePlan bestPlan = null;\n                List<SolveReportWriter.CandidateRow> reportRows = new ArrayList<>();\n\n                for (int candidateIndex');

// Fix indentation of candidate loop body
c = c.replace('                MultiStageMIPSolver.SolveCandidate solveCandidate = solveCandidates.get(candidateIndex);\n                SolverResult result = solveCandidate.result();\n                printSolutionSummary(result, demands);\n\n                List<CuttingInstruction> instructions = converter.convert(\n                        result.getSolution(), groupKey, groupItems, demands);\n                int sequenceGroups = SequenceGroupPostProcessor.countTotalGroups(instructions);\n\n                log.info("Pattern candidate {}: patterns={}, waste={}mm, over={}, groups={}",\n                        solveCandidate.name(),\n                        result.getPatternCount(),\n                        result.getTotalWaste(),\n                        result.getTotalOverProduction(),\n                        sequenceGroups);\n\n                GroupSolvePlan plan = new GroupSolvePlan(\n                        solveCandidate.name(),\n                        result,\n                        instructions,\n                        sequenceGroups,\n                        candidateIndex);\n                if (bestPlan == null || isBetterPlan(plan, bestPlan)) {\n                    bestPlan = plan;\n                }\n            }',
               '                    MultiStageMIPSolver.SolveCandidate solveCandidate = solveCandidates.get(candidateIndex);\n                    SolverResult result = solveCandidate.result();\n                    printSolutionSummary(result, demands);\n\n                    List<CuttingInstruction> instructions = converter.convert(\n                            result.getSolution(), groupKey, groupItems, demands);\n                    int sequenceGroups = SequenceGroupPostProcessor.countTotalGroups(instructions);\n\n                    log.info("Pattern candidate {}: patterns={}, waste={}mm, over={}, groups={}",\n                            solveCandidate.name(),\n                            result.getPatternCount(),\n                            result.getTotalWaste(),\n                            result.getTotalOverProduction(),\n                            sequenceGroups);\n\n                    reportRows.add(new SolveReportWriter.CandidateRow(\n                            solveCandidate.name(), result, sequenceGroups, params.getTotalWidth()));\n\n                    GroupSolvePlan plan = new GroupSolvePlan(\n                            solveCandidate.name(),\n                            result,\n                            instructions,\n                            sequenceGroups,\n                            candidateIndex);\n                    if (bestPlan == null || isBetterPlan(plan, bestPlan)) {\n                        bestPlan = plan;\n                    }\n                }');

// Fix bestPlan null check and final logging
c = c.replace('            if (bestPlan == null) {\n                log.warn("No usable instruction plan produced for group: {}", groupKey);\n                continue;\n            }\n\n            log.info("Selected pattern candidate for group {}: {} (groups={}, patterns={}, waste={}mm)",\n                    groupKey,\n                    bestPlan.name(),\n                    bestPlan.sequenceGroupCount(),\n                    bestPlan.result().getPatternCount(),\n                    bestPlan.result().getTotalWaste());\n\n            allInstructions.addAll(bestPlan.instructions());\n            log.debug("Generated instructions: {}", bestPlan.instructions().size());\n        }',
               '                if (bestPlan == null) {\n                    log.warn("No usable instruction plan produced for group: {}", groupKey);\n                    continue;\n                }\n\n                log.info("Selected pattern candidate for group {}: {} (groups={}, patterns={}, waste={}mm)",\n                        groupKey,\n                        bestPlan.name(),\n                        bestPlan.sequenceGroupCount(),\n                        bestPlan.result().getPatternCount(),\n                        bestPlan.result().getTotalWaste());\n\n                report.writeGroupResult(\n                        reportRows,\n                        bestPlan.name(),\n                        bestPlan.result().getSolution(),\n                        bestPlan.sequenceGroupCount(),\n                        System.currentTimeMillis() - groupStart);\n\n                allInstructions.addAll(bestPlan.instructions());\n                log.debug("Generated instructions: {}", bestPlan.instructions().size());\n            }\n\n            report.writeSummary();\n        }');

fs.writeFileSync(path, c.replace(/\n/g, '\r\n'), 'utf8');
console.log('CuttingSolver patched OK');
