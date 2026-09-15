// Run: node --test services/cuttingLayout.test.ts (Node 22.18+)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { cuttingLayout } from './cuttingLayout.ts';

test('逐刀求和，区内余宽与区外宽差只各计一次，两种利用率分别计算', () => {
    const layout = cuttingLayout({ comboExpanded: [1100, '1100', 1000, 1000, 150], rollWidth: 4400, motherRollWidth: 4600 })!;
    assert.equal(layout.productWidth, 4350);
    assert.equal(layout.remainingWidth, 50);
    assert.equal(layout.outsideWidth, 200);
    assert.equal(layout.sideEdgeWidth, 100);
    assert.equal(layout.totalWaste, 250);
    assert.equal(layout.wholeUtilization, 4350 / 4600 * 100);
    assert.equal(layout.effectiveUtilization, 4350 / 4400 * 100);
    assert.equal(layout.productWidth + layout.remainingWidth + layout.outsideWidth!, layout.diagramWidth);
    assert.equal(layout.sideEdgeWidth! + layout.productWidth + layout.remainingWidth + layout.sideEdgeWidth!, 4600);
    assert.equal(layout.sideEdgeWidth! * 2 + layout.remainingWidth, layout.totalWaste);
    assert.equal(cuttingLayout({ comboExpanded: [1100, 1100, 1100], rollWidth: 3300, motherRollWidth: 3550 })!.effectiveUtilization, 100);
    assert.equal(cuttingLayout({ comboExpanded: [1100, 1100, 1100], rollWidth: 3300, motherRollWidth: 3551 })!.sideEdgeWidth, 125.5);
});

test('缺失母卷总宽时只展示有效区，不能猜原宽与整卷利用率', () => {
    const layout = cuttingLayout({ comboExpanded: [1000, 1000], rollWidth: 2100 })!;
    assert.equal(layout.motherWidth, undefined);
    assert.equal(layout.wholeUtilization, null);
    assert.equal(layout.outsideWidth, null);
    assert.equal(layout.sideEdgeWidth, null);
    assert.equal(layout.diagramWidth, 2100);
});

test('拒绝无效与超宽数据，不裁剪成看似正常的图', () => {
    for (const widths of [[], [0], [-1], ['bad'], ['10mm'], [Infinity], [4401]]) {
        assert.equal(cuttingLayout({ comboExpanded: widths, rollWidth: 4400, motherRollWidth: 4600 }), null);
    }
    assert.equal(cuttingLayout({ comboExpanded: [1000], rollWidth: 4400, motherRollWidth: 4000 }), null);
    assert.equal(cuttingLayout({ comboExpanded: [1000], rollWidth: 0 }), null);
});
