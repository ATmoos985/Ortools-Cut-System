import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, RefreshCw, Scissors } from 'lucide-react';
import { Button } from '../components/ui/Button';
import { fetchPreview } from '../services/api';
import { cuttingLayout } from '../services/cuttingLayout';
import type { PreviewData } from '../types';

const colors = ['#2563eb', '#0f766e', '#7c3aed', '#be185d', '#0369a1', '#4d7c0f'];
const mm = (value: number | null | undefined) => value == null ? '未提供' : `${value.toLocaleString()} mm`;

export default function CuttingVisualization() {
    const navigate = useNavigate();
    const [data, setData] = useState<PreviewData | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    // 当前业务规定左右各 100 mm，可在本页校准；不改变求解配置。
    const [fixedLeftWidth, setFixedLeftWidth] = useState(100);
    const [fixedRightWidth, setFixedRightWidth] = useState(100);
    const load = async () => {
        setLoading(true);
        setError('');
        try {
            const result = await fetchPreview();
            if (!result.success) throw new Error(result.message || '请先完成排版计算。');
            setData(result);
        } catch (e) {
            setError(e instanceof Error ? e.message : '加载失败，请重试。');
        } finally {
            setLoading(false);
        }
    };
    useEffect(() => { void load(); }, []);

    return (
        <div className="max-w-7xl mx-auto space-y-6">
            <header className="flex flex-wrap items-center justify-between gap-4">
                <div>
                    <h1 className="text-2xl font-bold text-slate-800 flex items-center gap-2"><Scissors className="w-6 h-6 text-blue-600" />切割可视化</h1>
                    <p className="text-sm text-slate-500 mt-2">本次全部方案 · 每张条带代表一卷的横向切割组合</p>
                </div>
                <div className="flex gap-2">
                    <Button variant="secondary" onClick={() => navigate('/sequence-groups')}><ArrowLeft className="w-4 h-4" />返回序号组</Button>
                    <Button variant="secondary" onClick={load} disabled={loading}><RefreshCw className="w-4 h-4" />刷新结果</Button>
                </div>
            </header>
            <div className="rounded-xl border border-slate-200 bg-white px-5 py-4 text-sm text-slate-600 space-y-2">
                <div className="flex flex-wrap items-center gap-4">
                    <strong>规定固定废边</strong>
                    <label className="flex items-center gap-2">左侧 (mm)<input aria-label="左侧固定废边 (mm)" type="number" min="0" step="1" value={Number.isFinite(fixedLeftWidth) ? fixedLeftWidth : ''} onChange={e => setFixedLeftWidth(e.currentTarget.valueAsNumber)} className="w-24 rounded-lg border border-slate-300 p-2" /></label>
                    <label className="flex items-center gap-2">右侧 (mm)<input aria-label="右侧固定废边 (mm)" type="number" min="0" step="1" value={Number.isFinite(fixedRightWidth) ? fixedRightWidth : ''} onChange={e => setFixedRightWidth(e.currentTarget.valueAsNumber)} className="w-24 rounded-lg border border-slate-300 p-2" /></label>
                </div>
                <p className="text-xs text-slate-500">可切宽 = 原卷宽 − 左右固定边；可切区利用率 = 成品总宽 ÷ 可切宽。琥珀色为全部未利用余边，余边位置与刀序仅为示意。本页调整仅用于图示核算，不重新排程。</p>
            </div>
            {loading ? <p role="status" className="py-12 text-center text-slate-500">正在加载切割方案…</p>
                : error ? <p role="alert" className="p-5 rounded-xl bg-amber-50 text-amber-800">{error}</p>
                : !data?.preview?.length ? <div className="py-16 text-center text-slate-500">暂无切割方案，请先在智能排版页面运行计算。</div>
                : <>
                    <div className="flex flex-wrap justify-between gap-2 text-sm text-slate-500">
                        <span>共 <strong className="text-slate-800">{data.preview.length}</strong> 个方案 · 用卷 <strong className="text-slate-800">{data.totalRollsUsed}</strong> 卷</span>
                        {data.revisionId && <span className="break-all">版本 {data.revisionId}</span>}
                    </div>
                    <div className="space-y-5">
                        {data.preview.map(group => {
                            const layout = cuttingLayout(group, fixedLeftWidth, fixedRightWidth);
                            const distinctWidths = [...new Set(layout?.widths ?? [])];
                            const color = (width: number) => colors[distinctWidths.indexOf(width) % colors.length];
                            return (
                                <article key={group.sequenceGroupId || group.sequenceNumber} className="bg-white border border-slate-200 rounded-2xl shadow-sm p-5 sm:p-6 space-y-5">
                                    <div className="flex flex-wrap items-start justify-between gap-3">
                                        <div>
                                            <h2 className="font-bold text-slate-800">方案 #{group.sequenceNumber} <span className="ml-2 font-normal text-sm text-slate-500">{group.length} m · {group.surfaceTreatment || '未标注工艺'}</span></h2>
                                            <p className="text-xs text-slate-500 mt-2">重复使用 {group.usageCount} 卷 · 每卷 {group.comboExpanded?.length ?? 0} 条</p>
                                        </div>
                                        {layout && <div className="flex flex-wrap gap-4 text-sm">
                                            <div><span className="text-slate-500">整卷利用率 </span><strong className="text-blue-700">{layout.wholeUtilization == null ? '未提供' : `${layout.wholeUtilization.toFixed(2)}%`}</strong></div>
                                            <div><span className="text-slate-500">可切区利用率 </span><strong className="text-teal-700">{layout.cuttableUtilization.toFixed(2)}%</strong></div>
                                        </div>}
                                    </div>
                                    {!layout ? <p role="alert" className="text-amber-800 bg-amber-50 p-3 rounded-lg">无法绘制比例图：请检查原卷宽和固定边是否有效，以及成品总宽是否超过扣边后的可切宽。</p> : <>
                                        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
                                            <div><p className="text-slate-500 text-xs">母卷总宽</p><p className="font-semibold mt-1">{mm(layout.motherWidth)}</p></div>
                                            <div><p className="text-slate-500 text-xs">扣固定边后可切宽</p><p className="font-semibold mt-1">{mm(layout.cuttableWidth)}</p></div>
                                            <div><p className="text-slate-500 text-xs">成品总宽</p><p className="font-semibold mt-1">{mm(layout.productWidth)}</p></div>
                                            <div><p className="text-slate-500 text-xs">总废边</p><p className="font-semibold mt-1">{mm(layout.totalWaste)}</p></div>
                                        </div>
                                        <div className="flex justify-between gap-3 text-xs text-slate-500">
                                            <span>左固定废边 {mm(layout.fixedLeftWidth)}</span>
                                            <span>右固定废边 {mm(layout.fixedRightWidth)}</span>
                                        </div>
                                        <div role="img" aria-label={`方案 ${group.sequenceNumber}：左固定废边 ${mm(layout.fixedLeftWidth)}；逐刀宽度 ${layout.widths.join('、')} 毫米；可切区剩余废边 ${layout.remainingWidth} 毫米；右固定废边 ${mm(layout.fixedRightWidth)}`} className="flex h-20 sm:h-24 overflow-hidden rounded-lg bg-slate-100" style={{ outline: '1px solid #cbd5e1' }}>
                                            {!!layout.fixedLeftWidth && <div title={`左固定废边：${layout.fixedLeftWidth} mm`} className="flex-none" style={{ width: `${layout.fixedLeftWidth / layout.diagramWidth * 100}%`, background: 'repeating-linear-gradient(135deg, #94a3b8 0 4px, #e2e8f0 4px 8px)' }} />}
                                            {layout.widths.map((width, index) => (
                                                <div key={index} title={`第 ${index + 1} 条：${width} mm`} className="flex-none min-w-0 flex items-center justify-center overflow-hidden text-white text-xs sm:text-sm font-semibold" style={{ width: `${width / layout.diagramWidth * 100}%`, backgroundColor: color(width), boxShadow: 'inset -1px 0 rgba(255,255,255,.65)' }}>
                                                    {width / layout.diagramWidth >= 0.08 && <span className="truncate px-1">{width}</span>}
                                                </div>
                                            ))}
                                            {layout.remainingWidth > 0 && <div title={`可切区剩余废边：${layout.remainingWidth} mm`} className="flex-none" style={{ width: `${layout.remainingWidth / layout.diagramWidth * 100}%`, background: 'repeating-linear-gradient(135deg, #fcd34d 0 4px, #fef3c7 4px 8px)' }} />}
                                            {!!layout.fixedRightWidth && <div title={`右固定废边：${layout.fixedRightWidth} mm`} className="flex-none" style={{ width: `${layout.fixedRightWidth / layout.diagramWidth * 100}%`, background: 'repeating-linear-gradient(135deg, #94a3b8 0 4px, #e2e8f0 4px 8px)' }} />}
                                        </div>
                                        <div className="flex flex-wrap gap-x-5 gap-y-2 text-xs text-slate-600">
                                            {distinctWidths.map(width => <span key={width} className="inline-flex items-center gap-2"><span className="w-3 h-3 rounded-sm" style={{ backgroundColor: color(width) }} />{width} mm × {layout.widths.filter(value => value === width).length} 条 / 卷</span>)}
                                            <span className="inline-flex items-center gap-2"><span className="w-3 h-3 bg-amber-300 rounded-sm" />可切区剩余废边 {mm(layout.remainingWidth)}</span>
                                            <span className="inline-flex items-center gap-2"><span className="w-3 h-3 bg-slate-400 rounded-sm" />固定废边合计 {mm(layout.fixedWasteWidth)}</span>
                                        </div>
                                    </>}
                                </article>
                            );
                        })}
                    </div>
                </>}
        </div>
    );
}
