import React from 'react';
import { X, Settings, RotateCcw, Eye, Cpu } from 'lucide-react';
import { useSettings, CardVisibility } from '../context/SettingsContext';

// 卡片配置信息
const cardConfigs: { key: keyof CardVisibility; label: string; description: string }[] = [
    { key: 'totalRolls', label: '总用卷数', description: '显示使用的母卷总数' },
    { key: 'efficiency', label: '利用率', description: '显示材料利用率百分比' },
    { key: 'totalWaste', label: '总废料', description: '显示废料总量(mm)' },
    { key: 'overproduction', label: '超产', description: '显示超产卷数' },
    { key: 'executionTime', label: '计算耗时', description: '显示算法执行时间' },
];

export const SettingsPanel: React.FC = () => {
    const {
        cardVisibility,
        algorithmParams,
        setCardVisibility,
        setAlgorithmParams,
        resetToDefaults,
        isSettingsPanelOpen,
        closeSettingsPanel,
    } = useSettings();

    if (!isSettingsPanelOpen) return null;

    return (
        <>
            {/* 遮罩层 */}
            <div
                className="fixed inset-0 bg-black/40 backdrop-blur-sm z-40"
                onClick={closeSettingsPanel}
            />

            {/* 侧边栏面板 */}
            <div className="fixed right-0 top-0 h-full w-96 bg-white shadow-2xl z-50 flex flex-col animate-in slide-in-from-right duration-300">
                {/* 头部 */}
                <div className="flex items-center justify-between p-6 border-b border-slate-200 bg-gradient-to-r from-slate-50 to-white">
                    <div className="flex items-center gap-3">
                        <div className="w-10 h-10 bg-blue-100 rounded-xl flex items-center justify-center">
                            <Settings className="w-5 h-5 text-blue-600" />
                        </div>
                        <div>
                            <h2 className="text-lg font-bold text-slate-800">系统设置</h2>
                            <p className="text-xs text-slate-500">自定义界面和算法参数</p>
                        </div>
                    </div>
                    <button
                        onClick={closeSettingsPanel}
                        className="p-2 hover:bg-slate-100 rounded-lg transition-colors"
                    >
                        <X className="w-5 h-5 text-slate-500" />
                    </button>
                </div>

                {/* 内容区域 */}
                <div className="flex-1 overflow-y-auto p-6 space-y-8">
                    {/* 界面设置 */}
                    <section>
                        <div className="flex items-center gap-2 mb-4">
                            <Eye className="w-4 h-4 text-slate-600" />
                            <h3 className="font-semibold text-slate-700">界面显示</h3>
                        </div>
                        <p className="text-sm text-slate-500 mb-4">
                            选择在结果页面显示哪些统计卡片
                        </p>
                        <div className="space-y-3">
                            {cardConfigs.map(({ key, label, description }) => (
                                <label
                                    key={key}
                                    className="flex items-center justify-between p-3 bg-slate-50 rounded-xl hover:bg-slate-100 transition-colors cursor-pointer group"
                                >
                                    <div className="flex-1">
                                        <div className="font-medium text-slate-700 group-hover:text-slate-900">
                                            {label}
                                        </div>
                                        <div className="text-xs text-slate-400">
                                            {description}
                                        </div>
                                    </div>
                                    <div className="relative">
                                        <input
                                            type="checkbox"
                                            checked={cardVisibility[key]}
                                            onChange={(e) => setCardVisibility(key, e.target.checked)}
                                            className="sr-only peer"
                                        />
                                        <div className="w-11 h-6 bg-slate-300 peer-focus:ring-2 peer-focus:ring-blue-300 rounded-full peer peer-checked:bg-blue-600 transition-colors"></div>
                                        <div className="absolute left-0.5 top-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform peer-checked:translate-x-5"></div>
                                    </div>
                                </label>
                            ))}
                        </div>
                    </section>

                    {/* 算法参数 */}
                    <section>
                        <div className="flex items-center gap-2 mb-4">
                            <Cpu className="w-4 h-4 text-slate-600" />
                            <h3 className="font-semibold text-slate-700">高级参数</h3>
                        </div>
                        <p className="text-sm text-slate-500 mb-4">
                            调整算法运行参数（谨慎修改）
                        </p>
                        <div className="space-y-4">
                            <div>
                                <label className="block text-sm font-medium text-slate-600 mb-1.5">
                                    最大迭代次数
                                </label>
                                <input
                                    type="number"
                                    value={algorithmParams.maxIterations}
                                    onChange={(e) =>
                                        setAlgorithmParams({ maxIterations: parseInt(e.target.value) || 200 })
                                    }
                                    min={50}
                                    max={1000}
                                    className="w-full p-3 bg-slate-50 border border-slate-200 rounded-xl focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none font-mono text-slate-700"
                                />
                                <p className="text-xs text-slate-400 mt-1">
                                    建议范围: 50 - 1000，默认 200
                                </p>
                            </div>
                            <div>
                                <label className="block text-sm font-medium text-slate-600 mb-1.5">
                                    超时时间 (毫秒)
                                </label>
                                <input
                                    type="number"
                                    value={algorithmParams.timeoutMs}
                                    onChange={(e) =>
                                        setAlgorithmParams({ timeoutMs: parseInt(e.target.value) || 60000 })
                                    }
                                    min={10000}
                                    max={300000}
                                    step={1000}
                                    className="w-full p-3 bg-slate-50 border border-slate-200 rounded-xl focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none font-mono text-slate-700"
                                />
                                <p className="text-xs text-slate-400 mt-1">
                                    建议范围: 10000 - 300000 ms，默认 60000 ms (60秒)
                                </p>
                            </div>
                        </div>
                    </section>
                </div>

                {/* 底部操作 */}
                <div className="p-6 border-t border-slate-200 bg-slate-50">
                    <button
                        onClick={resetToDefaults}
                        className="w-full flex items-center justify-center gap-2 px-4 py-3 text-slate-600 hover:text-slate-800 hover:bg-white border border-slate-200 rounded-xl transition-colors"
                    >
                        <RotateCcw className="w-4 h-4" />
                        恢复默认设置
                    </button>
                </div>
            </div>
        </>
    );
};
