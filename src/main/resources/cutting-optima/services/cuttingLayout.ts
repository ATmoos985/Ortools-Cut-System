import type { PreviewGroup } from '../types.ts';

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
