import type { PreviewGroup } from '../types.ts';

// comboExpanded 已逐刀展开；usageCount 是重复用卷数，不参与横向宽度计算。
export function cuttingLayout(group: Pick<PreviewGroup, 'comboExpanded' | 'rollWidth' | 'motherRollWidth'>) {
    const widths = (group.comboExpanded ?? []).map(value =>
        typeof value === 'number' || typeof value === 'string' ? Number(value) : NaN);
    const effectiveWidth = group.rollWidth;
    const motherWidth = group.motherRollWidth;
    const productWidth = widths.reduce((sum, width) => sum + width, 0);
    if (!widths.length || widths.some(width => !Number.isFinite(width) || width <= 0)
        || !Number.isFinite(effectiveWidth) || effectiveWidth <= 0 || productWidth > effectiveWidth
        || (motherWidth !== undefined && (!Number.isFinite(motherWidth) || motherWidth < effectiveWidth))) {
        return null;
    }
    return {
        widths, productWidth, effectiveWidth, motherWidth,
        diagramWidth: motherWidth ?? effectiveWidth,
        remainingWidth: effectiveWidth - productWidth,
        outsideWidth: motherWidth === undefined ? null : motherWidth - effectiveWidth,
        totalWaste: motherWidth === undefined ? null : motherWidth - productWidth,
        wholeUtilization: motherWidth === undefined ? null : productWidth / motherWidth * 100,
        effectiveUtilization: productWidth / effectiveWidth * 100,
    };
}
