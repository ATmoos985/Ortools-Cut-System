import React, { useState, useEffect } from 'react';
import { Upload, FileSpreadsheet, Settings, Trash2, Save, Play, CheckCircle, Search, Download, BarChart3, Layers, AlertCircle, RefreshCw, X, ArrowUpDown, ArrowUp, ArrowDown } from 'lucide-react';
import { Card } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
import * as api from '../services/api';
import { OrderItem, Preset, DiagnosisResult, OptimizationRequestPayload, OptimizationResult, OptimizationResultGroup } from '../types';
import { useNavigate } from 'react-router-dom';
import { useAppContext } from '../context/AppContext';
import { useSettings } from '../context/SettingsContext';

const PRESET_KEY = 'flexibleWidthPresets';

export default function Home() {
    const navigate = useNavigate();
    const [loading, setLoading] = useState<string | null>(null);

    // Use global context for persistent state
    const {
        orderItems, setOrderItems,
        fileName, setFileName,
        importSource, setImportSource,
        solverProfile, setSolverProfile,
        totalWidth, setTotalWidth,
        minWidth, setMinWidth,
        maxWidth, setMaxWidth,
        stepSize, setStepSize,
        totalOverCap, setTotalOverCap,
        newSolverTopK,
        newSolverMaxIterations,
        newSolverTimeLimit,
        newSolverMaxPatterns,
        newSolverMaxDistinctWidths,
        diagnosis, setDiagnosis,
        optimizationResult, setOptimizationResult,
        selectedGroupKey, setSelectedGroupKey,
        clearBusinessData,
        hasShownRefreshAlert,
        markRefreshAlertShown
    } = useAppContext();

    // Local-only state (not persisted)
    const [presets, setPresets] = useState<Preset[]>([]);
    const [selectedPresetIndex, setSelectedPresetIndex] = useState<string>("");
    const [showClearConfirm, setShowClearConfirm] = useState(false);
    const [showRefreshNotification, setShowRefreshNotification] = useState(false);
    const [demandSortOrder, setDemandSortOrder] = useState<'none' | 'asc' | 'desc'>('none');

    // 获取设置上下文
    const { cardVisibility } = useSettings();

    // Load Presets from localStorage
    useEffect(() => {
        const saved = localStorage.getItem(PRESET_KEY);
        if (saved) setPresets(JSON.parse(saved));
    }, []);

    // Detect actual page refresh (F5 or browser refresh button)
    // Detect actual page refresh (F5 or browser refresh button)
    /*
    useEffect(() => {
        // Prevent showing twice or on client-side navigation back to home
        if (hasShownRefreshAlert) return;

        // Use Performance API to detect reload type
        const navigationType = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming;

        // Only show notification if it's an actual reload (not back/forward navigation)
        if (navigationType && navigationType.type === 'reload') {
            setShowRefreshNotification(true);
            markRefreshAlertShown();

            // Auto-hide after 3 seconds
            const timer = setTimeout(() => {
                setShowRefreshNotification(false);
            }, 3000);
            return () => clearTimeout(timer);
        }
    }, [hasShownRefreshAlert, markRefreshAlertShown]);
    */

    const handleFileDrop = async (e: React.DragEvent | React.ChangeEvent<HTMLInputElement>) => {
        e.preventDefault();
        let selectedFile: File | null = null;

        if ('dataTransfer' in e) {
            if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                selectedFile = e.dataTransfer.files[0];
            }
        } else if (e.target.files && e.target.files.length > 0) {
            selectedFile = e.target.files[0];
        }

        if (selectedFile) {
            setFileName(selectedFile.name);
            setImportSource(null);
            setLoading("正在导入数据中...");
            try {
                const res = await api.parseExcel(selectedFile);
                if (res.success) {
                    setOrderItems(res.orderItems);
                    setImportSource(res.templateSource || '线下模板');
                    setDiagnosis(null);
                    setOptimizationResult(null);
                    // Auto diagnose on upload
                    diagnoseData(res.orderItems);
                } else {
                    alert(res.message);
                }
            } catch (err: any) {
                alert("解析失败: " + err.message);
            } finally {
                setLoading(null);
            }
        }
    };

    const handleSavePreset = () => {
        const name = prompt('请输入预设名称:', `预设方案 ${presets.length + 1}`);
        if (!name) return;
        const newPreset: Preset = { name, minWidth, maxWidth, stepSize, totalWidth };
        const newPresets = [...presets, newPreset];
        setPresets(newPresets);
        localStorage.setItem(PRESET_KEY, JSON.stringify(newPresets));
        setSelectedPresetIndex((newPresets.length - 1).toString());
    };

    const handleLoadPreset = (idx: string) => {
        setSelectedPresetIndex(idx);
        if (idx === "") return;
        const p = presets[parseInt(idx)];
        if (p) {
            setMinWidth(p.minWidth);
            setMaxWidth(p.maxWidth);
            setStepSize(p.stepSize);
            setTotalWidth(p.totalWidth);
        }
    };

    const handleDeletePreset = () => {
        if (selectedPresetIndex === "") return;
        const idx = parseInt(selectedPresetIndex);
        const newPresets = presets.filter((_, i) => i !== idx);
        setPresets(newPresets);
        localStorage.setItem(PRESET_KEY, JSON.stringify(newPresets));
        setSelectedPresetIndex("");
    };

    const diagnoseData = async (items: OrderItem[]) => {
        if (!items.length) return;
        try {
            const res = await api.diagnoseGrouping(items);
            if (res.success) setDiagnosis(res);
        } catch (err) {
            console.error(err);
        }
    };

    const handleOptimize = async () => {
        if (!orderItems.length) return;
        if (minWidth <= 0 || maxWidth < minWidth || stepSize <= 0 || totalWidth <= 0 || totalOverCap < 0) {
            alert('请检查宽度设置：最小幅宽、步长和总宽必须大于 0，最大幅宽不能小于最小幅宽，超产上限不能为负数。');
            return;
        }
        setLoading("智能运算中，请稍候...");
        const effectiveMaxDistinctWidths = solverProfile === 'QUALITY'
            ? Math.max(newSolverMaxDistinctWidths, 5)
            : newSolverMaxDistinctWidths;
        const payload: OptimizationRequestPayload = {
            orderId: 'ORDER_' + Date.now(),
            orderName: 'Optimization',
            customerName: 'Customer',
            description: 'Optimization',
            flexibleWidth: true,
            fixedWidth: 0,
            totalWidth,
            minWidth,
            maxWidth,
            stepSize,
            totalOverCap,
            maxIterations: newSolverMaxIterations,
            timeoutMs: newSolverTimeLimit,
            useNewSolver: true,
            solverProfile,
            newSolverTopK: newSolverTopK,
            newSolverMaxPatterns: newSolverMaxPatterns,
            newSolverMaxDistinctWidths: effectiveMaxDistinctWidths,
            newSolverUseOptimizedAssignment: true,
            newSolverUnderPenalty: 1000000,
            lnsEnabled: true,
            lnsEnrichPatterns: false,
            qualityMode: solverProfile === 'QUALITY',
            orderItems: orderItems
        };

        try {
            const res = await api.optimizeCutting(payload);
            if (res.success) {
                setOptimizationResult(res.result);
                if (res.result.groupedResults) {
                    const keys = Object.keys(res.result.groupedResults);
                    if (keys.length > 0) setSelectedGroupKey(keys[0]);
                }
            } else {
                alert(res.message);
            }
        } catch (err: any) {
            alert(err.message);
        } finally {
            setLoading(null);
        }
    };



    const handleExport = async () => {
        if (!optimizationResult) return;
        setLoading("正在导出文件...");
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
            } else {
                alert(res.message);
            }
        } catch (err: any) {
            alert(err.message);
        } finally {
            setLoading(null);
        }
    };

    const handleClearData = () => {
        clearBusinessData();
        setShowClearConfirm(false);
    };

    // UI Components helpers
    const renderStats = (data: OptimizationResultGroup | OptimizationResult | undefined, groupName?: string | null) => {
        if (!data) return null;

        // 计算超产信息 - 始终从 optimizationResult.demandAnalysis 获取
        let totalOverproduction = 0;
        let hasOverproduction = false;
        if (optimizationResult?.demandAnalysis) {
            const analysis = optimizationResult.demandAnalysis;
            if (groupName) {
                // 按分组计算超产
                const details = analysis.fulfillmentDetails.filter(d => d.groupKey === groupName);
                totalOverproduction = details.reduce((sum, d) => sum + (d.difference > 0 ? d.difference : 0), 0);
            } else {
                // 全部汇总时使用总超产
                totalOverproduction = analysis.totalOverproduction ||
                    analysis.fulfillmentDetails.reduce((sum, d) => sum + (d.difference > 0 ? d.difference : 0), 0);
            }
            hasOverproduction = totalOverproduction > 0;
        }

        return (
            <div className="space-y-3">
                {/* Group indicator */}
                {groupName && (
                    <div className="flex items-center gap-2 mb-2">
                        <span className="text-xs font-medium text-blue-600 bg-blue-50 px-3 py-1 rounded-full border border-blue-100">
                            当前分组: {groupName}
                        </span>
                    </div>
                )}
                <div className="grid grid-cols-2 lg:grid-cols-5 gap-4 mb-6">
                    {/* 总用卷数卡片 */}
                    {cardVisibility.totalRolls && (
                        <div className="bg-gradient-to-br from-blue-500 to-blue-600 p-5 rounded-2xl text-white shadow-lg shadow-blue-500/20">
                            <div className="flex justify-between items-start mb-2">
                                <span className="opacity-80 text-sm font-medium">{groupName ? '分组卷数' : '总用卷数'}</span>
                                <Layers className="w-5 h-5 opacity-60" />
                            </div>
                            <div className="text-3xl font-bold">{data.totalRolls ?? 0} <span className="text-sm opacity-60 font-normal">卷</span></div>
                        </div>
                    )}
                    {/* 利用率卡片 */}
                    {cardVisibility.efficiency && (
                        <div className="bg-white p-5 rounded-2xl border border-slate-200 shadow-sm">
                            <div className="flex justify-between items-start mb-2">
                                <span className="text-slate-500 text-sm font-medium">利用率</span>
                                <BarChart3 className="w-5 h-5 text-emerald-500" />
                            </div>
                            <div className="text-3xl font-bold text-slate-800">{(data.efficiency ?? 0).toFixed(2)}<span className="text-lg text-slate-400">%</span></div>
                        </div>
                    )}
                    {/* 总废料卡片 */}
                    {cardVisibility.totalWaste && (
                        <div className="bg-white p-5 rounded-2xl border border-slate-200 shadow-sm">
                            <div className="flex justify-between items-start mb-2">
                                <span className="text-slate-500 text-sm font-medium">总废料</span>
                                <Trash2 className="w-5 h-5 text-red-400" />
                            </div>
                            <div className="text-3xl font-bold text-slate-800">{data.totalWaste ?? 0} <span className="text-sm text-slate-400 font-normal">mm</span></div>
                        </div>
                    )}
                    {/* 超产卡片 */}
                    {cardVisibility.overproduction && (
                        <div className={`p-5 rounded-2xl border shadow-sm ${hasOverproduction ? 'bg-amber-50 border-amber-200' : 'bg-white border-slate-200'}`}>
                            <div className="flex justify-between items-start mb-2">
                                <span className={`text-sm font-medium ${hasOverproduction ? 'text-amber-600' : 'text-slate-500'}`}>超产</span>
                                <AlertCircle className={`w-5 h-5 ${hasOverproduction ? 'text-amber-500' : 'text-slate-300'}`} />
                            </div>
                            <div className={`text-3xl font-bold ${hasOverproduction ? 'text-amber-600' : 'text-slate-800'}`}>
                                {hasOverproduction ? '+' : ''}{totalOverproduction} <span className={`text-sm font-normal ${hasOverproduction ? 'text-amber-500' : 'text-slate-400'}`}>卷</span>
                            </div>
                        </div>
                    )}
                    {/* 计算耗时卡片 */}
                    {cardVisibility.executionTime && (
                        <div className="bg-white p-5 rounded-2xl border border-slate-200 shadow-sm">
                            <div className="flex justify-between items-start mb-2">
                                <span className="text-slate-500 text-sm font-medium">计算耗时</span>
                                <RefreshCw className="w-5 h-5 text-purple-400" />
                            </div>
                            <div className="text-3xl font-bold text-slate-800">{(data as any).executionTimeMs || 0} <span className="text-sm text-slate-400 font-normal">ms</span></div>
                        </div>
                    )}
                </div>
            </div>
        );
    };

    return (
        <>
            {/* Header Toolbar */}
            <div className="bg-white border-b border-slate-200 px-6 py-4 flex items-center justify-between sticky top-0 z-10 shadow-sm">
                <h1 className="text-2xl font-bold text-slate-800">智能排版</h1>
                <div className="flex items-center gap-3">
                    <button
                        onClick={() => setShowClearConfirm(true)}
                        disabled={!fileName && !optimizationResult}
                        className="flex items-center gap-2 px-4 py-2 text-sm font-medium text-slate-600 hover:text-red-600 hover:bg-red-50 rounded-lg transition-colors disabled:opacity-40 disabled:cursor-not-allowed disabled:hover:bg-transparent disabled:hover:text-slate-600"
                        title="清空上传数据和求解结果"
                    >
                        <Trash2 className="w-4 h-4" />
                        清空数据
                    </button>
                </div>
            </div>

            {/* Clear Confirmation Modal */}
            {showClearConfirm && (
                <div className="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm animate-in fade-in">
                    <div className="bg-white rounded-2xl w-full max-w-md shadow-2xl">
                        <div className="p-6">
                            <div className="flex items-center gap-3 mb-4">
                                <div className="w-12 h-12 rounded-full bg-red-100 flex items-center justify-center">
                                    <AlertCircle className="w-6 h-6 text-red-600" />
                                </div>
                                <div>
                                    <h3 className="text-lg font-bold text-slate-800">确认清空数据</h3>
                                    <p className="text-sm text-slate-500">此操作将清空所有业务数据</p>
                                </div>
                            </div>
                            <div className="bg-slate-50 rounded-lg p-4 mb-6">
                                <p className="text-sm text-slate-600 mb-2">将清空以下内容:</p>
                                <ul className="text-sm text-slate-700 space-y-1">
                                    <li className="flex items-center gap-2">
                                        <div className="w-1.5 h-1.5 rounded-full bg-blue-500"></div>
                                        上传的订单文件
                                    </li>
                                    <li className="flex items-center gap-2">
                                        <div className="w-1.5 h-1.5 rounded-full bg-blue-500"></div>
                                        诊断结果
                                    </li>
                                    <li className="flex items-center gap-2">
                                        <div className="w-1.5 h-1.5 rounded-full bg-blue-500"></div>
                                        优化求解结果
                                    </li>
                                </ul>
                                <p className="text-xs text-emerald-600 mt-3 flex items-center gap-1">
                                    <CheckCircle className="w-3 h-3" />
                                    配置参数将保留
                                </p>
                            </div>
                            <div className="flex gap-3">
                                <Button variant="secondary" onClick={() => setShowClearConfirm(false)} className="flex-1">
                                    取消
                                </Button>
                                <button
                                    onClick={handleClearData}
                                    className="flex-1 px-4 py-2.5 bg-red-600 hover:bg-red-700 text-white font-medium rounded-lg transition-colors flex items-center justify-center gap-2"
                                >
                                    <Trash2 className="w-4 h-4" />
                                    确认清空
                                </button>
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {/* Refresh Notification Modal */}
            {showRefreshNotification && (
                <div className="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-black/40 backdrop-blur-sm animate-in fade-in">
                    <div className="bg-white rounded-2xl w-full max-w-md shadow-2xl animate-in slide-in-from-top-4">
                        <div className="p-6">
                            <div className="flex items-center gap-3 mb-4">
                                <div className="w-12 h-12 rounded-full bg-blue-100 flex items-center justify-center">
                                    <RefreshCw className="w-6 h-6 text-blue-600" />
                                </div>
                                <div>
                                    <h3 className="text-lg font-bold text-slate-800">页面已刷新</h3>
                                    <p className="text-sm text-slate-500">业务数据已清空</p>
                                </div>
                            </div>
                            <div className="bg-blue-50 rounded-lg p-4 mb-4">
                                <p className="text-sm text-slate-700 mb-2">已清空以下内容:</p>
                                <ul className="text-sm text-slate-600 space-y-1">
                                    <li className="flex items-center gap-2">
                                        <div className="w-1.5 h-1.5 rounded-full bg-blue-500"></div>
                                        上传的订单文件
                                    </li>
                                    <li className="flex items-center gap-2">
                                        <div className="w-1.5 h-1.5 rounded-full bg-blue-500"></div>
                                        诊断结果
                                    </li>
                                    <li className="flex items-center gap-2">
                                        <div className="w-1.5 h-1.5 rounded-full bg-blue-500"></div>
                                        优化求解结果
                                    </li>
                                </ul>
                                <p className="text-xs text-emerald-600 mt-3 flex items-center gap-1">
                                    <CheckCircle className="w-3 h-3" />
                                    配置参数已保留
                                </p>
                            </div>
                            <button
                                onClick={() => setShowRefreshNotification(false)}
                                className="w-full px-4 py-2.5 bg-blue-600 hover:bg-blue-700 text-white font-medium rounded-lg transition-colors flex items-center justify-center gap-2"
                            >
                                <CheckCircle className="w-4 h-4" />
                                知道了
                            </button>
                        </div>
                    </div>
                </div>
            )}

            <div className="grid grid-cols-1 lg:grid-cols-12 gap-8 h-full px-6 py-6">
                {/* Loading Overlay */}
                {loading && (
                    <div className="fixed inset-0 bg-white/80 backdrop-blur-sm z-50 flex flex-col items-center justify-center text-slate-800">
                        <div className="animate-spin rounded-full h-12 w-12 border-t-4 border-b-4 border-blue-500 mb-4"></div>
                        <p className="text-lg font-medium">{loading}</p>
                    </div>
                )}



                {/* Left Column: Input & Config */}
                <div className="lg:col-span-4 space-y-6 flex flex-col h-full">
                    <Card title="第一步：导入数据" className="flex-shrink-0">
                        <div
                            className={`border-2 border-dashed rounded-xl p-8 text-center cursor-pointer transition-all duration-200 group ${fileName ? 'border-blue-500 bg-blue-50/50' : 'border-slate-300 hover:border-blue-400 hover:bg-slate-50'}`}
                            onClick={() => document.getElementById('fileInput')?.click()}
                            onDragOver={(e) => { e.preventDefault(); }}
                            onDrop={handleFileDrop}
                        >
                            <div className={`w-14 h-14 mx-auto rounded-full flex items-center justify-center mb-3 transition-colors ${fileName ? 'bg-blue-100 text-blue-600' : 'bg-slate-100 text-slate-400 group-hover:bg-blue-50 group-hover:text-blue-500'}`}>
                                {fileName ? <FileSpreadsheet className="w-7 h-7" /> : <Upload className="w-7 h-7" />}
                            </div>
                            <p className="font-medium text-slate-700">
                                {fileName ? fileName : "点击或拖拽上传 Excel"}
                            </p>
                            {fileName && importSource && (
                                <p className="text-xs text-blue-600 bg-blue-100 border border-blue-200 rounded-full px-3 py-1 inline-flex mt-2">
                                    来源：{importSource}
                                </p>
                            )}
                            {!fileName && <p className="text-xs text-slate-400 mt-2">支持 .xlsx, .xls 格式</p>}
                            <input type="file" id="fileInput" accept=".xlsx,.xls,.csv" className="hidden" onChange={handleFileDrop} />
                        </div>
                    </Card>

                    <Card title="第二步:参数配置" className="flex-1 flex flex-col min-h-0">
                        <div className="flex-1 overflow-y-auto min-h-0 space-y-5">
                            <div className="bg-emerald-50 border border-emerald-200 rounded-xl p-4">
                                <p className="text-sm text-emerald-800 font-semibold">NewSolver 已启用</p>
                                <p className="text-xs text-emerald-700 mt-1">无需选择算法；只需确认宽度、超产和求解档位即可开始。</p>
                            </div>

                            <div>
                                <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-2">求解档位</label>
                                <div className="grid grid-cols-2 gap-3">
                                    <button type="button" onClick={() => setSolverProfile('FAST')} className={`text-left rounded-xl border p-3 transition-all ${solverProfile === 'FAST' ? 'border-emerald-500 bg-emerald-50 ring-2 ring-emerald-100' : 'border-slate-200 hover:border-slate-300'}`}>
                                        <span className="block text-sm font-semibold text-slate-800">快捷解</span>
                                        <span className="block text-xs text-slate-500 mt-1">推荐 · 快速稳定</span>
                                    </button>
                                    <button type="button" onClick={() => setSolverProfile('QUALITY')} className={`text-left rounded-xl border p-3 transition-all ${solverProfile === 'QUALITY' ? 'border-amber-500 bg-amber-50 ring-2 ring-amber-100' : 'border-slate-200 hover:border-slate-300'}`}>
                                        <span className="block text-sm font-semibold text-slate-800">精确解</span>
                                        <span className="block text-xs text-slate-500 mt-1">深度搜索 · 质量优先</span>
                                    </button>
                                </div>
                                <p className="text-xs text-slate-400 mt-2">{solverProfile === 'FAST' ? '适合日常单据，目标是在较短时间内得到稳定好解。' : '适合最终定稿，会运行更多候选与精修阶段，耗时更长。'}</p>
                            </div>

                            <div>
                                <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-1.5">宽度预设</label>
                                <div className="flex gap-2">
                                    <select value={selectedPresetIndex} onChange={e => handleLoadPreset(e.target.value)} className="flex-1 p-2.5 bg-slate-50 border border-slate-200 rounded-lg focus:ring-2 focus:ring-blue-500 outline-none text-sm">
                                        <option value="">-- 选择预设配置 --</option>
                                        {presets.map((preset, index) => <option key={index} value={index}>{preset.name}</option>)}
                                    </select>
                                    <button onClick={handleSavePreset} className="p-2.5 text-blue-600 hover:bg-blue-50 rounded-lg" title="保存预设"><Save className="w-5 h-5" /></button>
                                    <button onClick={handleDeletePreset} className="p-2.5 text-red-500 hover:bg-red-50 rounded-lg" title="删除预设"><Trash2 className="w-5 h-5" /></button>
                                </div>
                            </div>

                            <div className="grid grid-cols-2 gap-4">
                                <div>
                                    <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-1.5">最小幅宽</label>
                                    <input type="number" min={1} value={minWidth} onChange={e => setMinWidth(parseInt(e.target.value) || 0)} className="w-full p-2.5 bg-slate-50 border border-slate-200 rounded-lg font-mono text-sm" />
                                </div>
                                <div>
                                    <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-1.5">最大幅宽</label>
                                    <input type="number" min={1} value={maxWidth} onChange={e => setMaxWidth(parseInt(e.target.value) || 0)} className="w-full p-2.5 bg-slate-50 border border-slate-200 rounded-lg font-mono text-sm" />
                                </div>
                                <div>
                                    <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-1.5">步长</label>
                                    <input type="number" min={1} value={stepSize} onChange={e => setStepSize(parseInt(e.target.value) || 0)} className="w-full p-2.5 bg-slate-50 border border-slate-200 rounded-lg font-mono text-sm" />
                                </div>
                                <div>
                                    <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-1.5">标准总宽</label>
                                    <input type="number" min={1} value={totalWidth} onChange={e => setTotalWidth(parseInt(e.target.value) || 0)} className="w-full p-2.5 bg-slate-50 border border-slate-200 rounded-lg font-mono text-sm" />
                                </div>
                            </div>

                            <div>
                                <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-1.5">超产上限（卷）</label>
                                <input type="number" min={0} value={totalOverCap} onChange={e => setTotalOverCap(parseInt(e.target.value) || 0)} className="w-full p-2.5 bg-slate-50 border border-slate-200 rounded-lg font-mono text-sm" />
                                <p className="text-xs text-slate-400 mt-1">允许的总超产卷数上限；设置为 0 表示不允许超产。</p>
                            </div>
                        </div>

                        <div className="pt-6 mt-6 border-t border-slate-100 flex-shrink-0">
                            <Button onClick={handleOptimize} disabled={!orderItems.length} className="w-full shadow-xl shadow-blue-200">
                                <Play className="w-5 h-5" />
                                开始智能排版
                            </Button>
                        </div>
                    </Card>
                </div>

                {/* Right Column: Output & Stats */}
                <div className="lg:col-span-8 flex flex-col gap-6">
                    {!diagnosis && !optimizationResult ? (
                        <div className="flex-1 bg-white/50 border-2 border-dashed border-slate-200 rounded-2xl flex flex-col items-center justify-center text-slate-400 p-10 min-h-[400px]">
                            <div className="w-20 h-20 bg-slate-100 rounded-full flex items-center justify-center mb-6">
                                <BarChart3 className="w-10 h-10 text-slate-300" />
                            </div>
                            <h3 className="text-xl font-medium text-slate-600 mb-2">等待数据输入</h3>
                            <p>请在左侧上传 Excel 文件并点击开始排版</p>
                        </div>
                    ) : (
                        <>
                            {/* Diagnosis Summary (if not optimized yet or as header) */}
                            {diagnosis && !optimizationResult && (
                                <div className="bg-white p-6 rounded-2xl border border-slate-200 shadow-sm animate-in fade-in">
                                    <h3 className="font-bold text-slate-800 mb-4 flex items-center gap-2">
                                        <Search className="w-5 h-5 text-blue-500" /> 数据诊断报告
                                    </h3>
                                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
                                        <div className="p-4 bg-slate-50 rounded-xl">
                                            <div className="text-sm text-slate-500">订单行数</div>
                                            <div className="text-2xl font-bold text-slate-800">{diagnosis.totalItems}</div>
                                        </div>
                                        <div className="p-4 bg-slate-50 rounded-xl">
                                            <div className="text-sm text-slate-500">识别分组</div>
                                            <div className="text-2xl font-bold text-blue-600">{diagnosis.groupCount}</div>
                                        </div>
                                    </div>
                                    <div className="space-y-2">
                                        {diagnosis.groups.map((g, i) => (
                                            <div key={i} className="flex items-center justify-between p-3 bg-white border border-slate-100 rounded-lg hover:border-blue-200 transition-colors">
                                                <div className="flex items-center gap-3">
                                                    <span className="w-6 h-6 rounded-full bg-slate-100 text-slate-600 flex items-center justify-center text-xs font-bold">{i + 1}</span>
                                                    <div>
                                                        <div className="font-medium text-slate-700">{g.groupKey}</div>
                                                        <div className="text-xs text-slate-400">长度: {g.length}m • 工艺: {g.surfaceTreatment}</div>
                                                    </div>
                                                </div>
                                                <div className="text-right">
                                                    <div className="font-bold text-slate-800">{g.totalDemand} <span className="text-xs font-normal text-slate-400">卷</span></div>
                                                    <div className="text-xs text-slate-400">{g.itemCount} 个规格</div>
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            )}

                            {/* 订单明细预览 - 导入后求解前显示（不含生产量） */}
                            {orderItems.length > 0 && !optimizationResult && (
                                <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden animate-in fade-in">
                                    <div className="p-5 border-b border-slate-100 flex justify-between items-center bg-slate-50/50">
                                        <h3 className="font-bold text-slate-800 flex items-center gap-2">
                                            <FileSpreadsheet className="w-5 h-5 text-emerald-500" /> 订单明细预览
                                        </h3>
                                        <div className="flex items-center gap-2">
                                            {importSource && (
                                                <span className="text-xs text-blue-600 bg-blue-50 px-2 py-1 rounded border border-blue-100">
                                                    来源：{importSource}
                                                </span>
                                            )}
                                            <span className="text-xs text-slate-500 bg-slate-100 px-2 py-1 rounded">
                                                共 {orderItems.length} 条记录
                                            </span>
                                        </div>
                                    </div>
                                    <div className="overflow-x-auto max-h-[400px] overflow-y-auto">
                                        <table className="w-full text-sm">
                                            <thead className="bg-slate-50 text-slate-500 font-medium sticky top-0">
                                                <tr>
                                                    <th className="px-4 py-3 text-left">#</th>
                                                    <th className="px-4 py-3 text-left">业务员</th>
                                                    <th className="px-4 py-3 text-left">消息文本</th>
                                                    <th className="px-4 py-3 text-left">幅宽 (mm)</th>
                                                    <th className="px-4 py-3 text-center">需求量</th>
                                                    <th className="px-4 py-3 text-left">长度 (m)</th>
                                                </tr>
                                            </thead>
                                            <tbody className="divide-y divide-slate-100">
                                                {orderItems.map((item, i) => (
                                                    <tr key={i} className="hover:bg-slate-50/80 transition-colors">
                                                        <td className="px-4 py-2.5 text-slate-400 text-xs">{i + 1}</td>
                                                        <td className="px-4 py-2.5 text-slate-600">{item.salesperson || '-'}</td>
                                                        <td className="px-4 py-2.5 text-slate-600 truncate max-w-[200px]" title={item.messageText}>{item.messageText || '-'}</td>
                                                        <td className="px-4 py-2.5 font-mono text-slate-700 font-medium">{item.width}</td>
                                                        <td className="px-4 py-2.5 text-center font-bold text-blue-600">{item.quantity}</td>
                                                        <td className="px-4 py-2.5 text-slate-600">{item.length || '-'}</td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>
                                </div>
                            )}

                            {/* Optimization Results */}
                            {optimizationResult && (
                                <div className="animate-in slide-in-from-bottom-4 space-y-6">
                                    {/* Toolbar */}
                                    <div className="flex flex-wrap items-center justify-between gap-4 bg-white p-4 rounded-xl border border-slate-200 shadow-sm">
                                        <div className="flex flex-wrap gap-2 items-center">
                                            {/* Show group count badge */}
                                            {optimizationResult.groupedResults && Object.keys(optimizationResult.groupedResults).length > 0 && (
                                                <span className="text-xs text-slate-500 bg-slate-100 px-2 py-1 rounded mr-2">
                                                    {Object.keys(optimizationResult.groupedResults).length} 个分组
                                                </span>
                                            )}
                                            {/* Group selector buttons */}
                                            {optimizationResult.groupedResults && Object.keys(optimizationResult.groupedResults).map(key => (
                                                <button
                                                    key={key}
                                                    onClick={() => setSelectedGroupKey(key)}
                                                    className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${selectedGroupKey === key ? 'bg-blue-600 text-white shadow-md' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'}`}
                                                >
                                                    {key}
                                                </button>
                                            ))}
                                            {/* "All" button - only show when there are multiple groups */}
                                            {optimizationResult.groupedResults && Object.keys(optimizationResult.groupedResults).length > 1 && (
                                                <button onClick={() => setSelectedGroupKey(null)} className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${!selectedGroupKey ? 'bg-blue-600 text-white shadow-md' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'}`}>全部汇总</button>
                                            )}
                                        </div>
                                        <div className="flex gap-2">
                                            <Button variant="secondary" onClick={() => navigate('/preview')} className="h-10 text-sm px-4"><FileSpreadsheet className="w-4 h-4" /> 预览</Button>
                                            <Button onClick={handleExport} className="h-10 text-sm px-4 shadow-lg shadow-blue-100"><Download className="w-4 h-4" /> 导出结果</Button>
                                        </div>
                                    </div>

                                    {/* Main Stats Panel */}
                                    {selectedGroupKey && optimizationResult.groupedResults ? (
                                        renderStats(optimizationResult.groupedResults[selectedGroupKey], selectedGroupKey)
                                    ) : (
                                        renderStats(optimizationResult, null)
                                    )}

                                    {/* Demand Analysis Panel */}
                                    {optimizationResult.demandAnalysis && (
                                        <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
                                            <div className="p-5 border-b border-slate-100 flex justify-between items-center bg-slate-50/50">
                                                <h3 className="font-bold text-slate-800 flex items-center gap-2">
                                                    <AlertCircle className="w-5 h-5 text-amber-500" /> 需求达成分析
                                                </h3>
                                                <div className="flex items-center gap-2">
                                                    <button
                                                        onClick={() => setDemandSortOrder(prev => prev === 'none' ? 'desc' : prev === 'desc' ? 'asc' : 'none')}
                                                        className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg border transition-colors ${demandSortOrder !== 'none'
                                                            ? 'bg-blue-50 text-blue-600 border-blue-200'
                                                            : 'bg-white text-slate-500 border-slate-200 hover:bg-slate-50'
                                                            }`}
                                                        title="按需求量排序"
                                                    >
                                                        {demandSortOrder === 'desc' ? (
                                                            <><ArrowDown className="w-3.5 h-3.5" /> 需求量降序</>
                                                        ) : demandSortOrder === 'asc' ? (
                                                            <><ArrowUp className="w-3.5 h-3.5" /> 需求量升序</>
                                                        ) : (
                                                            <><ArrowUpDown className="w-3.5 h-3.5" /> 排序</>
                                                        )}
                                                    </button>
                                                    {(() => {
                                                        let analysis = optimizationResult.demandAnalysis;
                                                        if (selectedGroupKey && optimizationResult.groupedResults) {
                                                            const details = analysis.fulfillmentDetails.filter(d => d.groupKey === selectedGroupKey);
                                                            const allMet = details.every(d => d.difference >= 0);
                                                            return <span className={`text-sm font-bold px-3 py-1 rounded-full ${allMet ? 'bg-green-100 text-green-700' : 'bg-amber-100 text-amber-700'}`}>{allMet ? '全部满足' : '存在偏差'}</span>
                                                        }
                                                        return <span className={`text-sm font-bold px-3 py-1 rounded-full ${analysis.allDemandsMet ? 'bg-green-100 text-green-700' : 'bg-amber-100 text-amber-700'}`}>{analysis.allDemandsMet ? '全部满足' : '存在偏差'}</span>
                                                    })()}
                                                </div>
                                            </div>
                                            <div className="overflow-x-auto">
                                                <table className="w-full text-sm">
                                                    <thead className="bg-slate-50 text-slate-500 font-medium">
                                                        <tr>
                                                            <th className="px-6 py-3 text-left">幅宽 (Width)</th>
                                                            <th className="px-6 py-3 text-center">需求量</th>
                                                            <th className="px-6 py-3 text-center">实际产量</th>
                                                            <th className="px-6 py-3 text-center">差值</th>
                                                            <th className="px-6 py-3 text-center">状态</th>
                                                        </tr>
                                                    </thead>
                                                    <tbody className="divide-y divide-slate-100">
                                                        {(() => {
                                                            let details = [...optimizationResult.demandAnalysis.fulfillmentDetails];
                                                            if (selectedGroupKey) {
                                                                details = details.filter(d => d.groupKey === selectedGroupKey);
                                                            }
                                                            // 应用排序
                                                            if (demandSortOrder === 'desc') {
                                                                details.sort((a, b) => b.demand - a.demand);
                                                            } else if (demandSortOrder === 'asc') {
                                                                details.sort((a, b) => a.demand - b.demand);
                                                            }
                                                            return details.map((d, i) => (
                                                                <tr key={i} className="hover:bg-slate-50/80 transition-colors">
                                                                    <td className="px-6 py-3 font-mono text-slate-700">{d.width}</td>
                                                                    <td className="px-6 py-3 text-center text-slate-600">{d.demand}</td>
                                                                    <td className="px-6 py-3 text-center font-medium text-slate-800">{d.actual}</td>
                                                                    <td className={`px-6 py-3 text-center font-bold ${d.difference >= 0 ? 'text-emerald-500' : 'text-red-500'}`}>
                                                                        {d.difference > 0 ? '+' : ''}{d.difference}
                                                                    </td>
                                                                    <td className="px-6 py-3 text-center">
                                                                        {d.difference > 0 ?
                                                                            <span className="text-xs px-2 py-0.5 rounded bg-amber-50 text-amber-600 border border-amber-100">超产</span> :
                                                                            d.difference < 0 ?
                                                                                <span className="text-xs px-2 py-0.5 rounded bg-red-50 text-red-600 border border-red-100">不足</span> :
                                                                                <span className="text-xs px-2 py-0.5 rounded bg-emerald-50 text-emerald-600 border border-emerald-100">完美</span>
                                                                        }
                                                                    </td>
                                                                </tr>
                                                            ));
                                                        })()}
                                                    </tbody>
                                                </table>
                                                {(!optimizationResult.demandAnalysis.fulfillmentDetails.length) && (
                                                    <div className="p-8 text-center text-slate-400">暂无数据</div>
                                                )}
                                            </div>
                                        </div>
                                    )}

                                    {/* 订单明细预览 - 求解后显示，带生产量对比 */}
                                    {orderItems.length > 0 && (
                                        <div className="bg-white rounded-2xl border border-slate-200 shadow-sm overflow-hidden">
                                            <div className="p-5 border-b border-slate-100 flex justify-between items-center bg-slate-50/50">
                                                <h3 className="font-bold text-slate-800 flex items-center gap-2">
                                                    <FileSpreadsheet className="w-5 h-5 text-emerald-500" /> 订单明细预览
                                                </h3>
                                                <div className="flex items-center gap-2">
                                                    {importSource && (
                                                        <span className="text-xs text-blue-600 bg-blue-50 px-2 py-1 rounded border border-blue-100">
                                                            来源：{importSource}
                                                        </span>
                                                    )}
                                                    <span className="text-xs text-slate-500 bg-slate-100 px-2 py-1 rounded">
                                                        共 {orderItems.length} 条记录
                                                    </span>
                                                </div>
                                            </div>
                                            <div className="overflow-x-auto max-h-[400px] overflow-y-auto">
                                                <table className="w-full text-sm">
                                                    <thead className="bg-slate-50 text-slate-500 font-medium sticky top-0">
                                                        <tr>
                                                            <th className="px-4 py-3 text-left">#</th>
                                                            <th className="px-4 py-3 text-left">业务员</th>
                                                            <th className="px-4 py-3 text-left">消息文本</th>
                                                            <th className="px-4 py-3 text-left">幅宽 (mm)</th>
                                                            <th className="px-4 py-3 text-center">需求量</th>
                                                            <th className="px-4 py-3 text-center">生产量</th>
                                                            <th className="px-4 py-3 text-left">长度 (m)</th>
                                                        </tr>
                                                    </thead>
                                                    <tbody className="divide-y divide-slate-100">
                                                        {orderItems.map((item, i) => {
                                                            // 从 demandAnalysis.fulfillmentDetails 中获取该宽度的实际生产量
                                                            let actual = 0;
                                                            let difference = 0;
                                                            if (optimizationResult?.demandAnalysis?.fulfillmentDetails) {
                                                                // 按宽度匹配（同一宽度的需求会被聚合）
                                                                const detail = optimizationResult.demandAnalysis.fulfillmentDetails.find(
                                                                    d => d.width === item.width
                                                                );
                                                                if (detail) {
                                                                    // 按比例计算该订单的实际产量
                                                                    // 该宽度总需求中，该订单占的比例
                                                                    const totalDemandForWidth = orderItems
                                                                        .filter(o => o.width === item.width)
                                                                        .reduce((sum, o) => sum + o.quantity, 0);
                                                                    const proportion = item.quantity / totalDemandForWidth;
                                                                    actual = Math.round(detail.actual * proportion);
                                                                    difference = actual - item.quantity;
                                                                }
                                                            }
                                                            return (
                                                                <tr key={i} className="hover:bg-slate-50/80 transition-colors">
                                                                    <td className="px-4 py-2.5 text-slate-400 text-xs">{i + 1}</td>
                                                                    <td className="px-4 py-2.5 text-slate-600">{item.salesperson || '-'}</td>
                                                                    <td className="px-4 py-2.5 text-slate-600 truncate max-w-[200px]" title={item.messageText}>{item.messageText || '-'}</td>
                                                                    <td className="px-4 py-2.5 font-mono text-slate-700 font-medium">{item.width}</td>
                                                                    <td className="px-4 py-2.5 text-center font-bold text-blue-600">{item.quantity}</td>
                                                                    <td className={`px-4 py-2.5 text-center font-bold ${difference > 0 ? 'bg-amber-50 text-amber-600' :
                                                                        difference < 0 ? 'bg-red-50 text-red-600' :
                                                                            'text-emerald-600'
                                                                        }`}>
                                                                        {actual}
                                                                        {difference !== 0 && (
                                                                            <span className="text-xs ml-1">({difference > 0 ? '+' : ''}{difference})</span>
                                                                        )}
                                                                    </td>
                                                                    <td className="px-4 py-2.5 text-slate-600">{item.length || '-'}</td>
                                                                </tr>
                                                            );
                                                        })}
                                                    </tbody>
                                                </table>
                                            </div>
                                        </div>
                                    )}
                                </div>
                            )}
                        </>
                    )}
                </div>
            </div>
        </>
    );
}

// Helper component for the Validation Modal Icon
function ShieldCheckIcon(props: any) {
    return (
        <svg
            {...props}
            xmlns="http://www.w3.org/2000/svg"
            width="24"
            height="24"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
        >
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10" />
            <path d="m9 12 2 2 4-4" />
        </svg>
    );
}
