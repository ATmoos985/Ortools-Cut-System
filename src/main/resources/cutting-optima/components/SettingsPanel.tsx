import React from 'react';
import { X, Settings, RotateCcw, Eye, Cpu } from 'lucide-react';
import { useSettings, CardVisibility } from '../context/SettingsContext';
import { useAppContext } from '../context/AppContext';

const cardConfigs: { key: keyof CardVisibility; label: string; description: string }[] = [
    { key: 'totalRolls', label: '总用卷数', description: '显示使用的母卷总数' },
    { key: 'efficiency', label: '利用率', description: '显示材料利用率百分比' },
    { key: 'totalWaste', label: '总废料', description: '显示废料总量(mm)' },
    { key: 'overproduction', label: '超产', description: '显示超产卷数' },
    { key: 'executionTime', label: '计算耗时', description: '显示算法执行时间' },
];

const numberValue = (value: string, fallback: number) => {
    const parsed = Number.parseInt(value, 10);
    return Number.isFinite(parsed) ? parsed : fallback;
};

export const SettingsPanel: React.FC = () => {
    const {
        cardVisibility,
        setCardVisibility,
        resetToDefaults,
        isSettingsPanelOpen,
        closeSettingsPanel,
    } = useSettings();
    const {
        solverProfile,
        newSolverTopK,
        setNewSolverTopK,
        newSolverMaxIterations,
        setNewSolverMaxIterations,
        newSolverTimeLimit,
        setNewSolverTimeLimit,
        newSolverMaxPatterns,
        setNewSolverMaxPatterns,
        newSolverMaxDistinctWidths,
        setNewSolverMaxDistinctWidths,
        resetSolverConfig,
    } = useAppContext();

    if (!isSettingsPanelOpen) return null;

    const handleReset = () => {
        resetToDefaults();
        resetSolverConfig();
    };

    return (
        <>
            <div
                className="fixed inset-0 bg-black/40 backdrop-blur-sm z-40"
                onClick={closeSettingsPanel}
            />

            <div className="fixed right-0 top-0 h-full w-full max-w-md bg-white shadow-2xl z-50 flex flex-col animate-in slide-in-from-right duration-300">
                <div className="flex items-center justify-between p-6 border-b border-slate-200 bg-gradient-to-r from-slate-50 to-white">
                    <div className="flex items-center gap-3">
                        <div className="w-10 h-10 bg-blue-100 rounded-xl flex items-center justify-center">
                            <Settings className="w-5 h-5 text-blue-600" />
                        </div>
                        <div>
                            <h2 className="text-lg font-bold text-slate-800">特殊配置</h2>
                            <p className="text-xs text-slate-500">求解器技术参数与结果显示</p>
                        </div>
                    </div>
                    <button onClick={closeSettingsPanel} className="p-2 hover:bg-slate-100 rounded-lg transition-colors">
                        <X className="w-5 h-5 text-slate-500" />
                    </button>
                </div>

                <div className="flex-1 overflow-y-auto p-6 space-y-8">
                    <section>
                        <div className="flex items-center gap-2 mb-3">
                            <Cpu className="w-4 h-4 text-emerald-600" />
                            <h3 className="font-semibold text-slate-700">当前求解档位</h3>
                        </div>
                        <div className={`rounded-xl border p-4 ${solverProfile === 'QUALITY' ? 'bg-amber-50 border-amber-200' : 'bg-emerald-50 border-emerald-200'}`}>
                            <p className={`font-semibold ${solverProfile === 'QUALITY' ? 'text-amber-800' : 'text-emerald-800'}`}>
                                {solverProfile === 'QUALITY' ? '精确解' : '快捷解'}
                            </p>
                            <p className="text-xs text-slate-600 mt-1">
                                {solverProfile === 'QUALITY'
                                    ? '启用多候选、扩展 LNS、奇数组修复和集合划分精修，优先结果质量。'
                                    : '启用单候选和快速峰值修复，优先在较短时间内得到稳定好解。'}
                            </p>
                        </div>
                    </section>

                    <section>
                        <div className="flex items-center gap-2 mb-3">
                            <Settings className="w-4 h-4 text-slate-600" />
                            <h3 className="font-semibold text-slate-700">求解器高级参数</h3>
                        </div>
                        <p className="text-sm text-slate-500 mb-4">一般无需修改；两种档位都会读取这些基础边界。</p>
                        <div className="grid grid-cols-2 gap-4">
                            <div>
                                <label className="block text-sm font-medium text-slate-600 mb-1.5">TOP K 超产</label>
                                <input type="number" min={0} value={newSolverTopK} onChange={e => setNewSolverTopK(numberValue(e.target.value, 3))} className="w-full p-3 bg-slate-50 border border-slate-200 rounded-xl font-mono" />
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-600 mb-1.5">列生成迭代</label>
                                <input type="number" min={1} value={newSolverMaxIterations} onChange={e => setNewSolverMaxIterations(numberValue(e.target.value, 300))} className="w-full p-3 bg-slate-50 border border-slate-200 rounded-xl font-mono" />
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-600 mb-1.5">最大花型池</label>
                                <input type="number" min={1} value={newSolverMaxPatterns} onChange={e => setNewSolverMaxPatterns(numberValue(e.target.value, 800))} className="w-full p-3 bg-slate-50 border border-slate-200 rounded-xl font-mono" />
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-600 mb-1.5">不同宽幅上限</label>
                                <input type="number" min={1} value={newSolverMaxDistinctWidths} onChange={e => setNewSolverMaxDistinctWidths(numberValue(e.target.value, 4))} className="w-full p-3 bg-slate-50 border border-slate-200 rounded-xl font-mono" />
                                {solverProfile === 'QUALITY' && newSolverMaxDistinctWidths < 5 && (
                                    <p className="text-xs text-amber-600 mt-1">精确解运行时自动提升到 5</p>
                                )}
                            </div>
                        </div>
                        <div className="mt-4">
                            <label className="block text-sm font-medium text-slate-600 mb-1.5">MIP 单阶段安全时限（毫秒）</label>
                            <input type="number" min={1000} step={1000} value={newSolverTimeLimit} onChange={e => setNewSolverTimeLimit(numberValue(e.target.value, 120000))} className="w-full p-3 bg-slate-50 border border-slate-200 rounded-xl font-mono" />
                            <p className="text-xs text-slate-400 mt-1">这是单个 MIP 阶段的安全上限，不是整个求解任务的总时限。</p>
                        </div>
                    </section>

                    <section>
                        <div className="flex items-center gap-2 mb-4">
                            <Eye className="w-4 h-4 text-slate-600" />
                            <h3 className="font-semibold text-slate-700">结果显示</h3>
                        </div>
                        <div className="space-y-3">
                            {cardConfigs.map(({ key, label, description }) => (
                                <label key={key} className="flex items-center justify-between p-3 bg-slate-50 rounded-xl hover:bg-slate-100 cursor-pointer group">
                                    <div className="flex-1">
                                        <div className="font-medium text-slate-700">{label}</div>
                                        <div className="text-xs text-slate-400">{description}</div>
                                    </div>
                                    <div className="relative">
                                        <input type="checkbox" checked={cardVisibility[key]} onChange={e => setCardVisibility(key, e.target.checked)} className="sr-only peer" />
                                        <div className="w-11 h-6 bg-slate-300 rounded-full peer peer-checked:bg-blue-600 transition-colors" />
                                        <div className="absolute left-0.5 top-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform peer-checked:translate-x-5" />
                                    </div>
                                </label>
                            ))}
                        </div>
                    </section>
                </div>

                <div className="p-6 border-t border-slate-200 bg-slate-50">
                    <button onClick={handleReset} className="w-full flex items-center justify-center gap-2 px-4 py-3 text-slate-600 hover:text-slate-800 hover:bg-white border border-slate-200 rounded-xl transition-colors">
                        <RotateCcw className="w-4 h-4" />
                        恢复默认设置
                    </button>
                </div>
            </div>
        </>
    );
};
