import type { OptimizationResult, PreviewGroup } from '../types.ts';

// comboExpanded 已逐刀展开；usageCount 是重复用卷数，不参与横向宽度计算。
export function cuttingLayout(
    group: Pick<PreviewGroup, 'comboExpanded' | 'rollWidth' | 'motherRollWidth'>,
    fixedLeftWidth: number,
    fixedRightWidth: number,
) {
    const widths = (group.comboExpanded ?? []).map(value =>
        typeof value === 'number' || typeof value === 'string' ? Number(value) : NaN);
    const motherWidth = group.motherRollWidth;
    // 可切宽只扣业务规定的固定边，不能用求解器选宽 rollWidth 作分母。
    const cuttableWidth = motherWidth === undefined ? NaN : motherWidth - fixedLeftWidth - fixedRightWidth;
    const productWidth = widths.reduce((sum, width) => sum + width, 0);
    if (!widths.length || widths.some(width => !Number.isFinite(width) || width <= 0)
        || !Number.isFinite(fixedLeftWidth) || fixedLeftWidth < 0
        || !Number.isFinite(fixedRightWidth) || fixedRightWidth < 0
        || !Number.isFinite(cuttableWidth) || cuttableWidth <= 0 || productWidth > cuttableWidth) {
        return null;
    }
    return {
        widths, productWidth, cuttableWidth, motherWidth, fixedLeftWidth, fixedRightWidth,
        diagramWidth: motherWidth!,
        remainingWidth: cuttableWidth - productWidth,
        fixedWasteWidth: fixedLeftWidth + fixedRightWidth,
        totalWaste: motherWidth! - productWidth,
        wholeUtilization: productWidth / motherWidth! * 100,
        cuttableUtilization: productWidth / cuttableWidth * 100,
    };
}

// 可切区按用户规定以使用卷数累计宽度；原整卷面积得率仍直接使用后端指标。
export function summarizeCuttingUtilization(
    result: Pick<OptimizationResult, 'patterns' | 'totalWidth'>,
    fixedLeftWidth: number,
    fixedRightWidth: number,
    groupKey?: string | null,
) {
    let totalProductWidth = 0, totalCuttableWidth = 0;
    const patterns = groupKey ? result.patterns?.filter(pattern => pattern.groupKey === groupKey) : result.patterns;
    if (!patterns?.length) return null;
    for (const pattern of patterns) {
        let productWidth = 0;
        for (const [width, count] of Object.entries(pattern.subRolls ?? {})) {
            if (!Number.isFinite(Number(width)) || Number(width) <= 0 || !Number.isInteger(count) || count <= 0) return null;
            productWidth += Number(width) * count;
        }
        const layout = cuttingLayout({ comboExpanded: [productWidth], motherRollWidth: result.totalWidth, rollWidth: pattern.rollWidth ?? 0 }, fixedLeftWidth, fixedRightWidth);
        if (!layout || !Number.isInteger(pattern.usageCount) || pattern.usageCount <= 0) return null;
        totalProductWidth += layout.productWidth * pattern.usageCount;
        totalCuttableWidth += layout.cuttableWidth * pattern.usageCount;
    }
    if (!Number.isFinite(totalProductWidth) || !Number.isFinite(totalCuttableWidth) || totalCuttableWidth <= 0) return null;
    const totalRemainingWidth = totalCuttableWidth - totalProductWidth;
    return {
        totalProductWidth, totalCuttableWidth, totalRemainingWidth,
        cuttableUtilization: totalProductWidth / totalCuttableWidth * 100,
        remainingShare: totalRemainingWidth / totalCuttableWidth * 100,
    };
}
