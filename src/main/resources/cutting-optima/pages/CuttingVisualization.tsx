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
                <p>条带按实际宽度比例绘制；废边集中放在右侧，刀位顺序与左右位置均为示意。</p>
                <p>固定废边（左 / 右）：<strong>未提供</strong>。灰色区为母卷与方案有效宽度之差，不能据此认定固定废边；琥珀色区为有效区内剩余废边。</p>
                <p className="text-xs text-slate-500">整卷利用率 = 成品总宽 ÷ 母卷总宽；有效区利用率 = 成品总宽 ÷ 方案有效宽度。当前结果未提供固定扣边后的可切宽度，不能将有效区利用率标作固定扣边后的利用率。</p>
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
                            const layout = cuttingLayout(group);
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
                                            <div><span className="text-slate-500">有效区利用率 </span><strong className="text-teal-700">{layout.effectiveUtilization.toFixed(2)}%</strong></div>
                                        </div>}
                                    </div>
                                    {!layout ? <p role="alert" className="text-amber-800 bg-amber-50 p-3 rounded-lg">宽度数据缺失或不一致，无法绘制比例图。请核对本方案的母卷、有效宽度与逐刀宽度。</p> : <>
                                        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-sm">
                                            <div><p className="text-slate-500 text-xs">母卷总宽</p><p className="font-semibold mt-1">{mm(layout.motherWidth)}</p></div>
                                            <div><p className="text-slate-500 text-xs">方案有效宽度</p><p className="font-semibold mt-1">{mm(layout.effectiveWidth)}</p></div>
                                            <div><p className="text-slate-500 text-xs">成品总宽</p><p className="font-semibold mt-1">{mm(layout.productWidth)}</p></div>
                                            <div><p className="text-slate-500 text-xs">总废边</p><p className="font-semibold mt-1">{mm(layout.totalWaste)}</p></div>
                                        </div>
                                        {layout.motherWidth === undefined && <p className="text-xs text-amber-800">母卷总宽未提供：下图仅按方案有效宽度绘制。</p>}
                                        <div role="img" aria-label={`方案 ${group.sequenceNumber}：逐刀宽度 ${layout.widths.join('、')} 毫米；有效区内余宽 ${layout.remainingWidth} 毫米；区外宽差 ${mm(layout.outsideWidth)}`} className="flex h-20 sm:h-24 overflow-hidden rounded-lg bg-slate-100" style={{ outline: '1px solid #cbd5e1' }}>
                                            {layout.widths.map((width, index) => (
                                                <div key={index} title={`第 ${index + 1} 条：${width} mm`} className="flex-none min-w-0 flex items-center justify-center overflow-hidden text-white text-xs sm:text-sm font-semibold" style={{ width: `${width / layout.diagramWidth * 100}%`, backgroundColor: color(width), boxShadow: 'inset -1px 0 rgba(255,255,255,.65)' }}>
                                                    {width / layout.diagramWidth >= 0.08 && <span className="truncate px-1">{width}</span>}
                                                </div>
                                            ))}
                                            {layout.remainingWidth > 0 && <div title={`有效区内剩余废边：${layout.remainingWidth} mm`} className="flex-none" style={{ width: `${layout.remainingWidth / layout.diagramWidth * 100}%`, background: 'repeating-linear-gradient(135deg, #fcd34d 0 4px, #fef3c7 4px 8px)' }} />}
                                            {!!layout.outsideWidth && <div title={`有效区外宽差：${layout.outsideWidth} mm；固定边分配未知`} className="flex-none" style={{ width: `${layout.outsideWidth / layout.diagramWidth * 100}%`, background: 'repeating-linear-gradient(135deg, #94a3b8 0 4px, #e2e8f0 4px 8px)' }} />}
                                        </div>
                                        <div className="flex flex-wrap gap-x-5 gap-y-2 text-xs text-slate-600">
                                            {distinctWidths.map(width => <span key={width} className="inline-flex items-center gap-2"><span className="w-3 h-3 rounded-sm" style={{ backgroundColor: color(width) }} />{width} mm × {layout.widths.filter(value => value === width).length} 条 / 卷</span>)}
                                            <span className="inline-flex items-center gap-2"><span className="w-3 h-3 bg-amber-300 rounded-sm" />区内剩余废边 {mm(layout.remainingWidth)}</span>
                                            <span className="inline-flex items-center gap-2"><span className="w-3 h-3 bg-slate-400 rounded-sm" />区外宽差 {mm(layout.outsideWidth)}</span>
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
