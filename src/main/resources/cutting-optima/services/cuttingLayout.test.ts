// Run: node --test services/cuttingLayout.test.ts (Node 22.18+)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { cuttingLayout } from './cuttingLayout.ts';

test('真实4260方案：固定边各100，可切宽4400，剩余140，利用率96.82%', () => {
    for (const rollWidth of [4260, 4300, 4400]) {
        const layout = cuttingLayout({ comboExpanded: [750, '750', 750, 750, 1260], rollWidth, motherRollWidth: 4600 }, 100, 100)!;
        assert.equal(layout.productWidth, 4260);
        assert.equal(layout.cuttableWidth, 4400);
        assert.equal(layout.remainingWidth, 140);
        assert.equal(layout.fixedLeftWidth, 100);
        assert.equal(layout.fixedRightWidth, 100);
        assert.equal(layout.totalWaste, 340);
        assert.equal(layout.cuttableUtilization.toFixed(2), '96.82');
        assert.equal(layout.wholeUtilization.toFixed(2), '92.61');
        assert.equal(layout.fixedLeftWidth + layout.productWidth + layout.remainingWidth + layout.fixedRightWidth, layout.diagramWidth);
        assert.equal(layout.fixedWasteWidth + layout.remainingWidth, layout.totalWaste);
    }
});

test('同原卷不同成品宽分母不变，全部用满可切区才100%', () => {
    for (const [productWidth, remaining, utilization] of [[3300, 1100, 75], [4400, 0, 100]]) {
        const layout = cuttingLayout({ comboExpanded: [productWidth], rollWidth: productWidth, motherRollWidth: 4600 }, 100, 100)!;
        assert.equal(layout.cuttableWidth, 4400);
        assert.equal(layout.remainingWidth, remaining);
        assert.equal(layout.cuttableUtilization, utilization);
        assert.equal(layout.fixedWasteWidth, 200);
    }
});

test('读取不同原卷宽并支持校准左右固定边，不反推或强制等分', () => {
    const layout = cuttingLayout({ comboExpanded: [1000, 1000, 1000], rollWidth: 3100, motherRollWidth: 3550 }, 50, 150)!;
    assert.equal(layout.cuttableWidth, 3350);
    assert.equal(layout.remainingWidth, 350);
    assert.equal(layout.fixedLeftWidth, 50);
    assert.equal(layout.fixedRightWidth, 150);
    assert.equal(layout.fixedLeftWidth + layout.productWidth + layout.remainingWidth + layout.fixedRightWidth, 3550);
});

test('拒绝缺失原卷宽、超宽、无效固定边和无效逐刀数据', () => {
    for (const widths of [[], [0], [-1], ['bad'], ['10mm'], [Infinity], [4401]]) {
        assert.equal(cuttingLayout({ comboExpanded: widths, rollWidth: 4400, motherRollWidth: 4600 }, 100, 100), null);
    }
    assert.equal(cuttingLayout({ comboExpanded: [1000], rollWidth: 2100 }, 100, 100), null);
    for (const [left, right] of [[NaN, 100], [100, -1], [Infinity, 100], [2300, 2300]]) {
        assert.equal(cuttingLayout({ comboExpanded: [1000], rollWidth: 4400, motherRollWidth: 4600 }, left, right), null);
    }
});
