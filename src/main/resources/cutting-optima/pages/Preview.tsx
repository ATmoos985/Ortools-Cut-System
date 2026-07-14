import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import * as api from '../services/api';
import { PreviewData } from '../types';
import { Button } from '../components/ui/Button';
import { ArrowLeft, Download, Layers, PieChart, AlertOctagon, CircleDot, LayoutDashboard } from 'lucide-react';
import { useAppContext } from '../context/AppContext';

export default function Preview() {
    const navigate = useNavigate();
    const { selectedGroupKey } = useAppContext();
    const [data, setData] = useState<PreviewData | null>(null);
    const [loading, setLoading] = useState(true);
    const [errorMsg, setErrorMsg] = useState<string>('');

    useEffect(() => {
        loadPreview();
    }, [selectedGroupKey]);

    const loadPreview = async () => {
        setLoading(true);
        setErrorMsg('');
        try {
            // 传递 selectedGroupKey 给 API，null 表示全部汇总
            const res = await api.fetchPreview(selectedGroupKey);
            if (res.success) {
                setData(res);
            } else {
                setErrorMsg(res.message || '请先在智能排版页面运行排版计算。');
            }
        } catch (e) {
            setErrorMsg('加载预览数据失败，请稍后重试。');
        } finally {
            setLoading(false);
        }
    };

    const handleExport = async () => {
        try {
            const res = await api.confirmExportV2();
            if (res.success) {
                const blob = await api.downloadFile(res.fileName);
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = res.fileName;
                document.body.appendChild(a);
                a.click();
                document.body.removeChild(a);
                alert("导出成功!");
            } else {
                alert(res.message);
            }
        } catch (e: any) {
            alert(e.message);
        }
    };

    if (loading) return <div className="h-full flex items-center justify-center text-slate-500">正在加载预览数据...</div>;

    if (!data || !data.preview || data.preview.length === 0) {
        return (
            <div className="h-full flex flex-col items-center justify-center gap-8 px-6">
                {/* 装饰性背景 */}
                <div className="relative">
                    <div className="absolute -inset-6 bg-gradient-to-br from-blue-50 via-indigo-50 to-purple-50 rounded-full blur-2xl opacity-60" />
                    <div className="relative w-28 h-28 bg-gradient-to-br from-slate-100 to-slate-200 rounded-full flex items-center justify-center shadow-inner">
                        <Layers className="w-14 h-14 text-slate-300" strokeWidth={1.5} />
                    </div>
                </div>

                <div className="text-center max-w-md space-y-3">
                    <h2 className="text-2xl font-bold text-slate-700">暂无预览数据</h2>
                    {errorMsg ? (
                        <div className="bg-amber-50 border border-amber-200 rounded-xl px-5 py-3 text-sm text-amber-700">
                            {errorMsg}
                        </div>
                    ) : (
                        <p className="text-slate-400 text-sm leading-relaxed">
                            尚未生成排版方案，请先前往智能排版页面导入数据并运行优化计算
                        </p>
                    )}
                </div>

                <div className="flex gap-3">
                    <Button
                        onClick={() => navigate('/')}
                        className="shadow-lg shadow-blue-200 gap-2"
                    >
                        <LayoutDashboard className="w-4 h-4" />
                        前往智能排版
                    </Button>
                </div>
            </div>
        );
    }

    return (
        <div className="max-w-7xl mx-auto">
            {/* Header Bar */}
            <div className="flex justify-between items-center mb-8">
                <div>
                    <div className="flex items-center gap-3">
                        <h1 className="text-2xl font-bold text-slate-800">导出预览</h1>
                        {selectedGroupKey && (
                            <span className="text-sm font-medium text-blue-600 bg-blue-50 px-3 py-1 rounded-full border border-blue-100">
                                {selectedGroupKey}
                            </span>
                        )}
                        {!selectedGroupKey && (
                            <span className="text-sm font-medium text-slate-500 bg-slate-100 px-3 py-1 rounded-full">
                                全部汇总
                            </span>
                        )}
                    </div>
                    <p className="text-slate-500 mt-1">请在导出 Excel 之前核对排版方案详情</p>
                </div>
                <div className="flex gap-3">
                    <Button variant="secondary" onClick={() => navigate('/')}>
                        <ArrowLeft className="w-4 h-4" /> 返回修改
                    </Button>
                    <Button onClick={handleExport} className="shadow-lg shadow-blue-200">
                        <Download className="w-4 h-4" /> 确认导出 Excel
                    </Button>
                </div>
            </div>

            {/* Stats Cards */}
            <div className="grid grid-cols-2 lg:grid-cols-5 gap-4 mb-8">
                <div className="bg-white p-5 rounded-2xl border border-slate-200 shadow-sm flex items-center gap-4 bg-gradient-to-br from-blue-500 to-blue-600 text-white">
                    <div className="p-3 bg-white/20 rounded-lg"><Layers className="w-6 h-6" /></div>
                    <div>
                        <div className="text-2xl font-bold">{data.totalRollsUsed}</div>
                        <div className="text-xs text-blue-100">总用卷数</div>
                    </div>
                </div>
                <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex items-center gap-3">
                    <div className="p-3 bg-emerald-50 text-emerald-600 rounded-lg flex-shrink-0"><PieChart className="w-6 h-6" /></div>
                    <div className="flex-1 min-w-0">
                        <div className="text-xs font-medium text-slate-400 mb-1.5">得率</div>
                        <div className="grid grid-cols-2 divide-x divide-slate-200">
                            <div className="pr-2">
                                <div className="text-lg font-bold text-slate-800 whitespace-nowrap">{data.utilizationRate.toFixed(2)}%</div>
                                <div className="text-[11px] text-slate-400 whitespace-nowrap">含废边</div>
                            </div>
                            <div className="pl-2">
                                <div className="text-lg font-bold text-emerald-700 whitespace-nowrap">{(data.effectiveUtilizationRate ?? data.utilizationRate).toFixed(2)}%</div>
                                <div className="text-[11px] text-slate-400 whitespace-nowrap">有效宽度</div>
                            </div>
                        </div>
                    </div>
                </div>
                <div className="bg-white p-5 rounded-2xl border border-slate-200 shadow-sm flex items-center gap-4">
                    <div className="p-3 bg-amber-50 text-amber-600 rounded-lg"><AlertOctagon className="w-6 h-6" /></div>
                    <div>
                        <div className="text-2xl font-bold text-slate-800">{data.oddUsageGroups ?? 0}</div>
                        <div className="text-xs text-slate-400">奇数车数</div>
                    </div>
                </div>
                <div className="bg-white p-5 rounded-2xl border border-slate-200 shadow-sm flex items-center gap-4">
                    <div className="p-3 bg-purple-50 text-purple-600 rounded-lg"><CircleDot className="w-6 h-6" /></div>
                    <div>
                        <div className="text-2xl font-bold text-slate-800">{data.singleUsageGroups || 0}</div>
                        <div className="text-xs text-slate-400">单次搭切</div>
                    </div>
                </div>
                <div className="bg-white p-5 rounded-2xl border border-slate-200 shadow-sm flex items-center gap-4">
                    <div className="p-3 bg-purple-50 text-purple-600 rounded-lg"><Layers className="w-6 h-6" /></div>
                    <div>
                        <div className="text-2xl font-bold text-slate-800">{data.totalGroups}</div>
                        <div className="text-xs text-slate-400">序号组数</div>
                    </div>
                </div>
            </div>

            {/* Legend */}
            <div className="flex items-center gap-6 mb-6 text-sm text-slate-500 bg-white px-4 py-2 rounded-lg border border-slate-100 inline-flex">
                <span className="font-medium mr-2">状态说明:</span>
                <span className="flex items-center gap-2"><span className="w-2.5 h-2.5 rounded-full bg-purple-500"></span> 单次搭切</span>
                <span className="flex items-center gap-2"><span className="w-2.5 h-2.5 rounded-full bg-orange-500"></span> 包含超产</span>
                <span className="flex items-center gap-2"><span className="w-2.5 h-2.5 rounded-full bg-amber-400"></span> 利用率偏低</span>
            </div>

            {/* List */}
            <div className="space-y-6">
                {data.preview.map((group, idx) => {
                    const patternWidth = (group.comboExpanded || []).reduce((a, b) => a + parseInt(b), 0);
                    const hasOver = group.rows.some(r => r.isOverproduction || (r.difference && r.difference > 0));
                    const isSingleUse = group.usageCount === 1;

                    // Determine border/header color priority: overproduction > single use > normal
                    const borderClass = hasOver
                        ? 'border-orange-200 ring-1 ring-orange-100'
                        : isSingleUse
                            ? 'border-purple-200 ring-1 ring-purple-100'
                            : 'border-slate-200';
                    const headerBgClass = hasOver
                        ? 'bg-orange-50/50'
                        : isSingleUse
                            ? 'bg-purple-50/50'
                            : 'bg-slate-50/50';

                    return (
                        <div key={idx} className={`bg-white rounded-xl overflow-hidden shadow-sm border ${borderClass}`}>
                            {/* Group Header */}
                            <div className={`px-6 py-4 flex justify-between items-center ${headerBgClass} border-b border-slate-100`}>
                                <div className="flex items-center gap-4">
                                    <span className={`text-white text-xs font-bold px-2 py-1 rounded ${isSingleUse ? 'bg-purple-600' : 'bg-slate-800'}`}>组 {group.sequenceNumber}</span>
                                    {isSingleUse && <span className="text-xs bg-purple-100 text-purple-700 px-2 py-1 rounded border border-purple-200 font-medium">🔹 单次搭切</span>}
                                    {hasOver && <span className="text-xs bg-orange-100 text-orange-700 px-2 py-1 rounded border border-orange-200 font-medium">⚠️ 包含超产</span>}
                                </div>
                                <div className="flex gap-6 text-sm text-slate-600">
                                    <div>长度: <span className="font-semibold text-slate-900">{group.length}m</span></div>
                                    <div>工艺: <span className="font-semibold text-slate-900">{group.surfaceTreatment}</span></div>
                                </div>
                            </div>

                            {/* Pattern Info */}
                            <div className="px-6 py-3 bg-white border-b border-slate-50 flex justify-between text-sm">
                                <div className="font-mono text-slate-600">
                                    切割方案: <span className="text-blue-600 font-bold">{(group.comboExpanded || []).join(' + ')}</span> = <span className="text-slate-900 font-bold">{patternWidth}mm</span>
                                </div>
                                <div className="text-slate-600">
                                    执行次数: <span className={`font-bold px-2 py-0.5 rounded ${isSingleUse ? 'bg-purple-100 text-purple-700' : 'bg-slate-100 text-slate-900'}`}>{group.usageCount}</span> 次
                                </div>
                            </div>

                            {/* Table */}
                            <div className="overflow-x-auto">
                                <table className="w-full text-sm">
                                    <thead className="bg-slate-50 text-slate-500 font-medium">
                                        <tr>
                                            <th className="px-6 py-3 text-left w-1/3">订单信息</th>
                                            <th className="px-6 py-3 text-left">业务员</th>
                                            <th className="px-6 py-3 text-center">幅宽 (mm)</th>
                                            <th className="px-6 py-3 text-center">卷数</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-slate-100">
                                        {group.rows.map((row, rIdx) => (
                                            <tr key={rIdx} className={`hover:bg-slate-50 transition-colors ${row.isOverproduction ? 'bg-orange-50/30' : ''}`}>
                                                <td className={`px-6 py-3 ${row.isOverproduction ? 'text-orange-700 font-medium' : 'text-slate-700'}`}>
                                                    {row.messageText}
                                                </td>
                                                <td className="px-6 py-3 text-slate-600">{row.salesperson}</td>
                                                <td className="px-6 py-3 text-center font-mono text-slate-700">{row.width}</td>
                                                <td className="px-6 py-3 text-center font-bold text-slate-800">{row.rolls}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        </div>
                    );
                })}
            </div>
        </div>
    );
}
