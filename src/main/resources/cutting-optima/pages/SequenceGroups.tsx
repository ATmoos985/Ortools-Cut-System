import React, { useEffect, useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import * as api from '../services/api';
import { PreviewData, PreviewGroup, PreviewRow } from '../types';
import { Button } from '../components/ui/Button';
import {
    Layers, LayoutDashboard, ChevronDown, ChevronRight,
    Hash, Users, Ruler, Package, Scissors, BarChart3,
    Replace, Plus, Trash2, Pencil, X, AlertTriangle, ArrowRight, Check
} from 'lucide-react';
import { useAppContext } from '../context/AppContext';

// 按 length+surfaceTreatment 分组的数据结构
interface GroupCluster {
    groupKey: string;
    length: number;
    surfaceTreatment: string;
    groups: PreviewGroup[];
}

export default function SequenceGroups() {
    const navigate = useNavigate();
    const { selectedGroupKey } = useAppContext();
    const [data, setData] = useState<PreviewData | null>(null);
    const [loading, setLoading] = useState(true);
    const [errorMsg, setErrorMsg] = useState('');
    const [collapsedClusters, setCollapsedClusters] = useState<Set<string>>(new Set());
    // 选中的序号组（优先按 sequenceGroupId 追踪，兼容旧 sequenceNumber）
    const [selectedGroups, setSelectedGroups] = useState<Set<string>>(new Set());
    // 编辑模式（点击「修改方案」后开启）
    const [editMode, setEditMode] = useState(false);
    // 删除确认对话框
    const [deleteConfirm, setDeleteConfirm] = useState<{ show: boolean; targets: number[]; info: string; mode: 'row' | 'group'; rowData?: any }>({ show: false, targets: [], info: '', mode: 'group' });
    const [deleting, setDeleting] = useState(false);
    // 已删除的行标记，优先使用 rowId
    const [deletedRows, setDeletedRows] = useState<Set<string>>(new Set());
    // 重新搭切中
    const [reCutting, setReCutting] = useState(false);
    // 撤销
    const [undoCount, setUndoCount] = useState(0);
    // 待搭切 popover
    const [showPendingPopover, setShowPendingPopover] = useState(false);

    useEffect(() => {
        loadData();
    }, [selectedGroupKey]);

    const loadData = async () => {
        setLoading(true);
        setErrorMsg('');
        try {
            const res = await api.fetchPreview(selectedGroupKey);
            if (res.success) {
                setData(res);
            } else {
                setErrorMsg(res.message || '请先在智能排版页面运行排版计算。');
            }
        } catch (e) {
            setErrorMsg('加载数据失败，请稍后重试。');
        } finally {
            setLoading(false);
        }
    };

    // 将序号组按 length+surfaceTreatment 再次聚合
    const buildClusters = (groups: PreviewGroup[]): GroupCluster[] => {
        const clusterMap = new Map<string, GroupCluster>();
        for (const g of groups) {
            const key = `${g.length}m+${g.surfaceTreatment}`;
            if (!clusterMap.has(key)) {
                clusterMap.set(key, {
                    groupKey: key,
                    length: g.length,
                    surfaceTreatment: g.surfaceTreatment,
                    groups: [],
                });
            }
            clusterMap.get(key)!.groups.push(g);
        }
        return Array.from(clusterMap.values());
    };

    const getGroupSelectionKey = (group: PreviewGroup) =>
        group.sequenceGroupId || `seq-${group.sequenceNumber}`;

    const getRowDeleteKey = (group: PreviewGroup, row: PreviewRow) =>
        row.rowId || `${getGroupSelectionKey(group)}-${row.messageText}-${row.width}`;

    const toggleCluster = (key: string) => {
        setCollapsedClusters(prev => {
            const next = new Set(prev);
            next.has(key) ? next.delete(key) : next.add(key);
            return next;
        });
    };

    // 勾选序号组 → 剩余宽幅自动进待搭切池（useMemo 动态计算）
    const pendingItems = useMemo(() => {
        if (!data || !data.preview || selectedGroups.size === 0) return [];
        const items: Array<{ sequenceNumber: number; salesperson: string; messageText: string; width: number; rolls: number; isOverproduction?: boolean }> = [];
        for (const group of data.preview) {
            if (!selectedGroups.has(getGroupSelectionKey(group))) continue;
            for (const row of group.rows) {
                const rowKey = getRowDeleteKey(group, row);
                if (deletedRows.has(rowKey)) continue; // 已删除的不计入
                items.push({
                    sequenceNumber: group.sequenceNumber,
                    salesperson: row.salesperson || '-',
                    messageText: row.messageText || '-',
                    width: row.width,
                    rolls: row.rolls || group.usageCount,
                    isOverproduction: row.isOverproduction,
                });
            }
        }
        return items;
    }, [data, selectedGroups, deletedRows]);

    // 选择/取消选择序号组
    const toggleGroupSelection = (group: PreviewGroup) => {
        const groupKey = getGroupSelectionKey(group);
        setSelectedGroups((prev) => {
            const next = new Set(prev);
            if (next.has(groupKey)) next.delete(groupKey);
            else next.add(groupKey);
            return next;
        });
    };

    // 全选/反选当前分组下的所有序号组
    const toggleClusterSelection = (cluster: GroupCluster) => {
        const clusterGroupKeys = cluster.groups.map((group) => getGroupSelectionKey(group));
        const allSelected = clusterGroupKeys.every((groupKey) => selectedGroups.has(groupKey));
        setSelectedGroups((prev) => {
            const next = new Set(prev);
            if (allSelected) {
                clusterGroupKeys.forEach((groupKey) => next.delete(groupKey));
            } else {
                clusterGroupKeys.forEach((groupKey) => next.add(groupKey));
            }
            return next;
        });
    };

    // 操作按钮处理
    const handleReplace = (group: PreviewGroup, rowIdx: number) => {
        console.log('替换操作', { sequenceNumber: group.sequenceNumber, rowIdx, row: group.rows[rowIdx] });
    };
    const handleInsert = (group: PreviewGroup, rowIdx: number) => {
        console.log('插入操作', { sequenceNumber: group.sequenceNumber, rowIdx, row: group.rows[rowIdx] });
    };

    // 单行删除（释放槽位进入待搭切）
    const handleDeleteRow = (group: PreviewGroup, row: any) => {
        setDeleteConfirm({
            show: true,
            targets: [group.sequenceNumber],
            info: `序号组 #${group.sequenceNumber} 中 ${row.salesperson} 的 ${row.width}mm 宽幅`,
            mode: 'row',
            rowData: {
                sequenceNumber: group.sequenceNumber,
                sequenceGroupId: group.sequenceGroupId,
                rowId: row.rowId,
                messageText: row.messageText,
                width: row.width,
            }
        });
    };

    // 移除了整组删除和批量删除

    // 执行删除
    const confirmDelete = async () => {
        setDeleting(true);
        try {
            if (deleteConfirm.mode === 'row' && deleteConfirm.rowData) {
                const { sequenceNumber, messageText, width, rowId } = deleteConfirm.rowData;
                const res = await api.deleteRow(
                    sequenceNumber,
                    messageText,
                    width,
                    selectedGroupKey,
                    rowId,
                    data?.planId,
                    data?.revisionId,
                );

                if (res.success) {
                    // 本地标记删除行（不刷新整个数据）
                    const key = rowId || `${sequenceNumber}-${messageText}-${width}`;
                    setDeletedRows(prev => new Set(prev).add(key));
                    setData(prev => prev ? {
                        ...prev,
                        planId: res.planId ?? prev.planId,
                        revisionId: res.revisionId ?? prev.revisionId,
                    } : prev);
                    if (res.undoCount !== undefined) setUndoCount(res.undoCount);
                } else {
                    alert('删除失败: ' + (res.message || '未知错误'));
                }
            }
        } catch (e) {
            alert('删除请求失败，请检查服务连接。');
        } finally {
            setDeleting(false);
            setDeleteConfirm({ show: false, targets: [], info: '', mode: 'group' });
        }
    };

    // 重新搭切
    const handleReCut = async () => {
        if (reCutting) return;
        setReCutting(true);
        try {
            const selectedGroupsForRequest = data?.preview
                .filter((group) => selectedGroups.has(getGroupSelectionKey(group))) ?? [];
            const selectedSequenceGroupIds = selectedGroupsForRequest
                .map((group) => group.sequenceGroupId)
                .filter((value): value is string => !!value) ?? [];
            const res = await api.reCut(
                selectedGroupsForRequest.map((group) => group.sequenceNumber),
                data?.planId,
                data?.revisionId,
                selectedSequenceGroupIds,
            );
            if (res.success) {
                setData(res);
                setDeletedRows(new Set<string>());
                setSelectedGroups(new Set<string>());
                setShowPendingPopover(false);
                if (res.undoCount !== undefined) setUndoCount(res.undoCount);
            } else {
                alert('重新搭切失败: ' + (res.message || '未知错误'));
            }
        } catch (e) {
            alert('重新搭切请求失败，请检查服务连接。');
        } finally {
            setReCutting(false);
        }
    };

    // 撤销
    const handleUndo = async () => {
        try {
            const res = await api.undo();
            if (res.success) {
                setData(res);
                setDeletedRows(new Set<string>());
                setUndoCount(res.undoRemaining || 0);
            } else {
                alert(res.message || '无法撤销');
            }
        } catch { alert('撤销请求失败'); }
    };

    // 加载中
    if (loading) {
        return (
            <div className="h-full flex items-center justify-center text-slate-500">
                <div className="flex items-center gap-3">
                    <div className="w-5 h-5 border-2 border-blue-400 border-t-transparent rounded-full animate-spin" />
                    正在加载搭切明细数据...
                </div>
            </div>
        );
    }

    // 空状态
    if (!data || !data.preview || data.preview.length === 0) {
        return (
            <div className="h-full flex flex-col items-center justify-center gap-8 px-6">
                <div className="relative">
                    <div className="absolute -inset-6 bg-gradient-to-br from-blue-50 via-indigo-50 to-purple-50 rounded-full blur-2xl opacity-60" />
                    <div className="relative w-28 h-28 bg-gradient-to-br from-slate-100 to-slate-200 rounded-full flex items-center justify-center shadow-inner">
                        <Scissors className="w-14 h-14 text-slate-300" strokeWidth={1.5} />
                    </div>
                </div>
                <div className="text-center max-w-md space-y-3">
                    <h2 className="text-2xl font-bold text-slate-700">暂无搭切明细</h2>
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
                <Button onClick={() => navigate('/')} className="shadow-lg shadow-blue-200 gap-2">
                    <LayoutDashboard className="w-4 h-4" />
                    前往智能排版
                </Button>
            </div>
        );
    }

    const clusters = buildClusters(data.preview);

    // 汇总统计
    const totalSequences = data.preview.length;
    const totalRows = data.preview.reduce((acc, g) => acc + g.rows.length, 0);

    return (
        <div className="max-w-7xl mx-auto">
            {/* 页面头部 */}
            <div className="flex justify-between items-center mb-6">
                <div>
                    <div className="flex items-center gap-3">
                        <h1 className="text-2xl font-bold text-slate-800">搭切明细</h1>
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
                    <p className="text-slate-500 mt-1 text-sm">
                        展示所有序号组的搭切明细
                    </p>
                    {data.planId && data.revisionId && (
                        <div className="mt-2 inline-flex items-center gap-2 text-xs text-slate-500 bg-slate-100 border border-slate-200 rounded-full px-3 py-1">
                            <span>方案 {data.planId}</span>
                            <span className="text-slate-300">/</span>
                            <span>版本 {data.revisionId}</span>
                        </div>
                    )}
                </div>
                <div className="flex items-center gap-3">
                    {editMode && undoCount > 0 && (
                        <button
                            onClick={handleUndo}
                            className="inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium rounded-lg bg-amber-50 text-amber-700 border border-amber-200 hover:bg-amber-100 transition-all"
                        >
                            ↩ 撤销 ({undoCount})
                        </button>
                    )}
                    {/* 待搭切按钮 + popover */}
                    {editMode && (
                        <div className="relative">
                            <button
                                onClick={() => setShowPendingPopover(!showPendingPopover)}
                                className={`inline-flex items-center gap-1.5 px-4 py-2 text-sm font-medium rounded-lg transition-all border ${pendingItems.length > 0
                                    ? 'bg-orange-50 text-orange-700 border-orange-200 hover:bg-orange-100'
                                    : 'bg-slate-50 text-slate-400 border-slate-200 cursor-default'
                                    }`}
                                disabled={pendingItems.length === 0}
                            >
                                <Package className="w-4 h-4" />
                                待搭切
                                {pendingItems.length > 0 && (
                                    <span className="ml-1 bg-orange-500 text-white text-xs font-bold px-1.5 py-0.5 rounded-full min-w-[18px] text-center">
                                        {pendingItems.length}
                                    </span>
                                )}
                            </button>
                            {/* Popover 弹出层 */}
                            {showPendingPopover && pendingItems.length > 0 && (
                                <div className="absolute right-0 top-full mt-2 w-80 bg-white border border-slate-200 rounded-xl shadow-2xl z-50 overflow-hidden">
                                    <div className="px-4 py-3 bg-gradient-to-r from-orange-50 to-amber-50 border-b border-slate-200">
                                        <div className="flex items-center justify-between">
                                            <h3 className="text-sm font-bold text-slate-800">待搭切宽幅</h3>
                                            <span className="text-xs bg-orange-100 text-orange-700 px-2 py-0.5 rounded-full font-semibold">
                                                {pendingItems.length} 项
                                            </span>
                                        </div>
                                        <div className="text-[11px] text-slate-500 mt-1">勾选的序号组中剩余宽幅</div>
                                    </div>
                                    <div className="max-h-[300px] overflow-y-auto p-3 space-y-2">
                                        {pendingItems.map((item, idx) => (
                                            <div key={idx} className="p-2.5 rounded-lg border border-slate-200 bg-amber-50/30 flex items-center justify-between">
                                                <div>
                                                    <div className="text-xs font-semibold text-slate-700">{item.salesperson}</div>
                                                    <div className="text-[11px] text-slate-500">
                                                        #{item.sequenceNumber} · {item.messageText}
                                                    </div>
                                                </div>
                                                <div className="text-right">
                                                    <span className="text-xs font-bold text-orange-600 bg-orange-100 px-1.5 py-0.5 rounded">
                                                        {item.width}mm
                                                    </span>
                                                    <div className="text-[10px] text-slate-400 mt-0.5">×{item.rolls}</div>
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            )}
                        </div>
                    )}
                    {editMode && pendingItems.length > 0 && (
                        <button
                            onClick={handleReCut}
                            disabled={reCutting}
                            className="inline-flex items-center gap-1.5 px-4 py-2 text-sm font-bold rounded-lg bg-blue-600 text-white hover:bg-blue-700 transition-all shadow-md disabled:opacity-50"
                        >
                            {reCutting ? (
                                <><div className="w-3.5 h-3.5 border-2 border-white border-t-transparent rounded-full animate-spin" /> 求解中...</>
                            ) : (
                                <><Scissors className="w-4 h-4" /> 重新搭切</>
                            )}
                        </button>
                    )}
                    <button
                        onClick={() => { setEditMode(!editMode); if (editMode) { setSelectedGroups(new Set<string>()); setDeletedRows(new Set<string>()); setShowPendingPopover(false); } }}
                        className={`inline-flex items-center gap-1.5 px-4 py-2 text-sm font-medium rounded-lg transition-all ${editMode
                            ? 'bg-slate-700 text-white hover:bg-slate-800 shadow-md'
                            : 'bg-white text-slate-700 border border-slate-300 hover:bg-slate-50 hover:border-slate-400'
                            }`}
                    >
                        {editMode ? <X className="w-4 h-4" /> : <Pencil className="w-4 h-4" />}
                        {editMode ? '退出编辑' : '修改方案'}
                    </button>
                </div>
            </div>

            {/* 顶部统计卡片 */}
            <div className="grid grid-cols-2 lg:grid-cols-5 gap-4 mb-6">
                <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex items-center gap-3 bg-gradient-to-br from-blue-500 to-blue-600 text-white">
                    <div className="p-2.5 bg-white/20 rounded-lg"><Hash className="w-5 h-5" /></div>
                    <div>
                        <div className="text-2xl font-bold">{totalSequences}</div>
                        <div className="text-xs text-blue-100">序号组数</div>
                    </div>
                </div>
                <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex items-center gap-3">
                    <div className="p-2.5 bg-indigo-50 text-indigo-600 rounded-lg"><Layers className="w-5 h-5" /></div>
                    <div>
                        <div className="text-2xl font-bold text-slate-800">{clusters.length}</div>
                        <div className="text-xs text-slate-400">分组数</div>
                    </div>
                </div>
                <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex items-center gap-3">
                    <div className="p-2.5 bg-emerald-50 text-emerald-600 rounded-lg"><Users className="w-5 h-5" /></div>
                    <div>
                        <div className="text-2xl font-bold text-slate-800">{totalRows}</div>
                        <div className="text-xs text-slate-400">明细行数</div>
                    </div>
                </div>
                <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex items-center gap-3">
                    <div className="p-2.5 bg-amber-50 text-amber-600 rounded-lg"><Package className="w-5 h-5" /></div>
                    <div>
                        <div className="text-2xl font-bold text-slate-800">{data.totalRollsUsed}</div>
                        <div className="text-xs text-slate-400">总用卷数</div>
                    </div>
                </div>
                <div className="bg-white p-4 rounded-2xl border border-slate-200 shadow-sm flex items-center gap-3">
                    <div className="p-2.5 bg-purple-50 text-purple-600 rounded-lg"><BarChart3 className="w-5 h-5" /></div>
                    <div>
                        <div className="text-2xl font-bold text-slate-800">{data.utilizationRate.toFixed(1)}%</div>
                        <div className="text-xs text-slate-400">利用率</div>
                    </div>
                </div>
            </div>

            {/* 主内容区：左侧集群列表 + 右侧待搭切面板 */}
            <div className="flex gap-4 items-start">
                {/* 左侧：各集群 */}
                <div className="flex-1 min-w-0">
                    <div className="space-y-4">
                        {clusters.map((cluster) => {
                            const isCollapsed = collapsedClusters.has(cluster.groupKey);
                            const clusterRolls = cluster.groups.reduce((a, g) => a + g.usageCount, 0);
                            const maxRollWidth = Math.max(...cluster.groups.map(g => g.rollWidth));

                            return (
                                <div key={cluster.groupKey} className="bg-white rounded-xl overflow-hidden shadow-sm border border-slate-200">
                                    {/* 分组表头（可折叠） */}
                                    <button
                                        onClick={() => toggleCluster(cluster.groupKey)}
                                        className="w-full px-5 py-3.5 flex items-center justify-between bg-gradient-to-r from-slate-50 to-white border-b border-slate-100 hover:from-slate-100 hover:to-slate-50 transition-colors"

                                    >
                                        <div className="flex items-center gap-3">
                                            {isCollapsed
                                                ? <ChevronRight className="w-4 h-4 text-slate-400" />
                                                : <ChevronDown className="w-4 h-4 text-slate-400" />}
                                            <span className="font-bold text-slate-800 text-sm">分组: {cluster.groupKey}</span>
                                            <span className="text-xs bg-blue-50 text-blue-600 px-2 py-0.5 rounded-full border border-blue-100">
                                                {cluster.groups.length} 个序号组
                                            </span>
                                            <span className="text-xs bg-slate-100 text-slate-600 px-2 py-0.5 rounded-full">
                                                共 {clusterRolls} 卷
                                            </span>
                                        </div>
                                        <div className="flex items-center gap-4 text-xs text-slate-500">
                                            <span>长度: <span className="font-semibold text-slate-700">{cluster.length}m</span></span>
                                            <span>工艺: <span className="font-semibold text-slate-700">{cluster.surfaceTreatment}</span></span>
                                            <span className="text-slate-300">|</span>
                                            <span>宽幅范围: <span className="font-semibold text-slate-700">
                                                {(() => {
                                                    const widths = cluster.groups.map(g => g.rollWidth);
                                                    const minW = Math.min(...widths);
                                                    const maxW = Math.max(...widths);
                                                    return minW === maxW ? `${minW}mm` : `${minW}-${maxW}mm`;
                                                })()}
                                            </span></span>
                                        </div>
                                    </button>

                                    {/* 表格内容 */}
                                    {!isCollapsed && (
                                        <div className="overflow-x-auto">
                                            <table className="w-full text-sm table-fixed">
                                                <thead>
                                                    <tr className="bg-slate-50/80 text-slate-500 text-xs uppercase tracking-wider">
                                                        {editMode && (
                                                            <th className="px-3 py-2.5 text-center w-10 border-r border-slate-100">
                                                                <input
                                                                    type="checkbox"
                                                                            checked={cluster.groups.every((g) => selectedGroups.has(getGroupSelectionKey(g)))}
                                                                    onChange={() => toggleClusterSelection(cluster)}
                                                                    className="w-3.5 h-3.5 rounded border-slate-300 text-blue-600 focus:ring-blue-500 cursor-pointer"
                                                                />
                                                            </th>
                                                        )}
                                                        <th className="px-4 py-2.5 text-center w-14 border-r border-slate-100">序号</th>
                                                        <th className="px-4 py-2.5 text-left w-52 border-r border-slate-100">切割方案</th>
                                                        <th className="px-4 py-2.5 text-left w-20 border-r border-slate-100">业务员</th>
                                                        <th className="px-4 py-2.5 text-left w-24 border-r border-slate-100">订单信息</th>
                                                        <th className="px-4 py-2.5 text-center w-24 border-r border-slate-100">幅宽 (mm)</th>
                                                        <th className="px-4 py-2.5 text-center w-16 border-r border-slate-100">数量</th>
                                                        {editMode && <th className="px-4 py-2.5 text-center w-36">操作</th>}
                                                    </tr>
                                                </thead>
                                                <tbody>
                                                    {cluster.groups.map((group, gIdx) => {
                                                        const comboWidths = (group.comboExpanded || []).map(s => parseInt(s, 10) || 0);
                                                        const patternStr = (group.comboExpanded || []).join(' + ');
                                                        const totalWidth = comboWidths.reduce((a, b) => a + b, 0);
                                                        const utilization = maxRollWidth > 0 ? (totalWidth / maxRollWidth * 100) : 0;
                                                        const rowCount = group.rows.length;
                                                        const isSingleUse = group.usageCount === 1;

                                                        return group.rows.map((row, rIdx) => {
                                                            const isFirstRow = rIdx === 0;
                                                            const isLastRow = rIdx === rowCount - 1;
                                                            const isSelected = selectedGroups.has(getGroupSelectionKey(group));
                                                            const isNewGroup = !!(group as any).isNewGroup;
                                                            const rowDeleteKey = getRowDeleteKey(group, row);
                                                            const isRowDeleted = deletedRows.has(rowDeleteKey);

                                                            if (isRowDeleted) {
                                                                // 已删除行：显示红虚线空位
                                                                return (
                                                                    <tr
                                                                        key={`${group.sequenceNumber}-${rIdx}`}
                                                                        className={`border-b border-slate-200/60 ${isLastRow ? 'border-b-2 border-slate-200' : ''}`}
                                                                    >
                                                                        {editMode && isFirstRow && (
                                                                            <td rowSpan={rowCount} className="px-3 py-2.5 text-center border-r border-slate-100 align-middle">
                                                                                <input type="checkbox" checked={isSelected} onChange={() => toggleGroupSelection(group)} className="w-3.5 h-3.5 rounded border-slate-300 text-blue-600 focus:ring-blue-500 cursor-pointer" />
                                                                            </td>
                                                                        )}
                                                                        {isFirstRow && (
                                                                            <td rowSpan={rowCount} className="px-4 py-2.5 text-center font-bold text-slate-700 border-r border-slate-100 align-middle">
                                                                                <span className="inline-flex items-center justify-center w-8 h-8 rounded-lg text-sm font-bold bg-slate-100 text-slate-700 border border-slate-200">{group.sequenceNumber}</span>
                                                                            </td>
                                                                        )}
                                                                        {isFirstRow && (
                                                                            <td rowSpan={rowCount} className="px-4 py-2.5 border-r border-slate-100 align-middle">
                                                                                <div className="font-mono text-xs text-blue-600 font-semibold">{patternStr} = {totalWidth}</div>
                                                                            </td>
                                                                        )}
                                                                        <td colSpan={editMode ? 4 : 3} className="px-4 py-3 text-center">
                                                                            <div className="border-2 border-dashed border-red-300 rounded-lg px-4 py-2 bg-red-50/30">
                                                                                <span className="text-xs text-red-400 font-medium">已删除 · {row.salesperson} · {row.width}mm</span>
                                                                            </div>
                                                                        </td>
                                                                    </tr>
                                                                );
                                                            }

                                                            return (
                                                                <tr
                                                                    key={`${group.sequenceNumber}-${rIdx}`}
                                                                    className={`
                                hover:bg-blue-50/30 transition-colors
                                ${isSelected ? 'bg-blue-50/40' : ''}
                                ${isLastRow ? 'border-b-2 border-slate-200' : 'border-b border-slate-200/60'}
                                ${row.isOverproduction ? 'bg-orange-50/40' : ''}
                                ${isNewGroup ? 'bg-blue-100/60 border-l-4 border-l-blue-400' : ''}
                              `}
                                                                >
                                                                    {/* 复选框 - 合并行（仅编辑模式） */}
                                                                    {editMode && isFirstRow && (
                                                                        <td
                                                                            rowSpan={rowCount}
                                                                            className="px-3 py-2.5 text-center border-r border-slate-100 align-middle"
                                                                        >
                                                                            <input
                                                                                type="checkbox"
                                                                                checked={isSelected}
                                                                                onChange={() => toggleGroupSelection(group)}
                                                                                className="w-3.5 h-3.5 rounded border-slate-300 text-blue-600 focus:ring-blue-500 cursor-pointer"
                                                                            />
                                                                        </td>
                                                                    )}

                                                                    {/* 序号 - 合并行 */}
                                                                    {isFirstRow && (
                                                                        <td
                                                                            rowSpan={rowCount}
                                                                            className="px-4 py-2.5 text-center font-bold text-slate-700 border-r border-slate-100 align-middle"
                                                                        >
                                                                            <span className={`
                                    inline-flex items-center justify-center w-8 h-8 rounded-lg text-sm font-bold
                                    ${isNewGroup
                                                                                    ? 'bg-blue-100 text-blue-700 border border-blue-300'
                                                                                    : isSingleUse
                                                                                        ? 'bg-purple-100 text-purple-700 border border-purple-200'
                                                                                        : 'bg-slate-100 text-slate-700 border border-slate-200'}
                                  `}>
                                                                                {group.sequenceNumber}
                                                                            </span>
                                                                            {isNewGroup && (
                                                                                <span className="ml-1 text-[10px] font-bold text-blue-600 bg-blue-50 px-1 py-0.5 rounded border border-blue-200">新</span>
                                                                            )}
                                                                        </td>
                                                                    )}



                                                                    {/* 切割方案 - 合并行 */}
                                                                    {isFirstRow && (
                                                                        <td
                                                                            rowSpan={rowCount}
                                                                            className="px-4 py-2.5 border-r border-slate-100 align-middle"
                                                                        >
                                                                            <div className="space-y-1.5">
                                                                                <div className="font-mono text-xs text-blue-600 font-semibold">
                                                                                    {patternStr} = {totalWidth}
                                                                                </div>
                                                                                <div className="flex items-center gap-1.5 flex-wrap">
                                                                                    <span className={`inline-flex items-center px-2 py-0.5 rounded-md text-xs font-bold ${isSingleUse
                                                                                        ? 'bg-purple-100 text-purple-700 border border-purple-200'
                                                                                        : 'bg-amber-50 text-amber-700 border border-amber-200'
                                                                                        }`}>
                                                                                        ×{group.usageCount} 车
                                                                                    </span>
                                                                                    <span className={`inline-flex items-center px-1.5 py-0.5 rounded-md text-[11px] font-bold ${utilization >= 99 ? 'bg-emerald-50 text-emerald-700 border border-emerald-200'
                                                                                        : utilization >= 95 ? 'bg-blue-50 text-blue-700 border border-blue-200'
                                                                                            : 'bg-orange-50 text-orange-600 border border-orange-200'
                                                                                        }`}>
                                                                                        {utilization.toFixed(1)}%
                                                                                    </span>
                                                                                </div>
                                                                            </div>
                                                                        </td>
                                                                    )}

                                                                    {/* 业务员 */}
                                                                    <td className="px-4 py-2.5 text-slate-600 border-r border-slate-100">
                                                                        {row.salesperson || '-'}
                                                                    </td>

                                                                    {/* 订单信息 */}
                                                                    <td className={`px-4 py-2.5 border-r border-slate-100 ${row.isOverproduction ? 'text-orange-700 font-medium' : 'text-slate-700'}`}>
                                                                        {row.messageText || '-'}
                                                                        {row.isOverproduction && (
                                                                            <span className="ml-1.5 text-[10px] bg-orange-100 text-orange-600 px-1.5 py-0.5 rounded font-medium">
                                                                                超产
                                                                            </span>
                                                                        )}
                                                                    </td>

                                                                    {/* 幅宽 */}
                                                                    <td className="px-4 py-2.5 text-center font-mono text-slate-700 border-r border-slate-100">
                                                                        {row.width}
                                                                    </td>

                                                                    {/* 数量 */}
                                                                    <td className="px-4 py-2.5 text-center font-bold text-slate-800 border-r border-slate-100">
                                                                        {row.rolls}
                                                                    </td>

                                                                    {/* 操作列（仅编辑模式） */}
                                                                    {editMode && (
                                                                        <td className="px-2 py-2 text-center">
                                                                            <div className="flex items-center justify-center gap-1">
                                                                                <button
                                                                                    onClick={() => handleReplace(group, rIdx)}
                                                                                    className="inline-flex items-center gap-0.5 px-2 py-1 text-[11px] font-medium text-blue-600 hover:bg-blue-50 rounded transition-colors border border-transparent hover:border-blue-200"
                                                                                    title="替换此行"
                                                                                >
                                                                                    <Replace className="w-3 h-3" />
                                                                                    替换
                                                                                </button>
                                                                                <button
                                                                                    onClick={() => handleInsert(group, rIdx)}
                                                                                    className="inline-flex items-center gap-0.5 px-2 py-1 text-[11px] font-medium text-emerald-600 hover:bg-emerald-50 rounded transition-colors border border-transparent hover:border-emerald-200"
                                                                                    title="在此行后插入"
                                                                                >
                                                                                    <Plus className="w-3 h-3" />
                                                                                    插入
                                                                                </button>
                                                                                <button
                                                                                    onClick={() => handleDeleteRow(group, group.rows[rIdx])}
                                                                                    className="inline-flex items-center gap-0.5 px-2 py-1 text-[11px] font-medium text-red-500 hover:bg-red-50 rounded transition-colors border border-transparent hover:border-red-200"
                                                                                    title="删除此行（释放宽幅）"
                                                                                >
                                                                                    <Trash2 className="w-3 h-3" />
                                                                                    删行
                                                                                </button>

                                                                            </div>
                                                                        </td>
                                                                    )}
                                                                </tr>
                                                            );
                                                        });
                                                    })}
                                                </tbody>
                                            </table>
                                        </div>
                                    )}
                                </div>
                            );
                        })}
                    </div>
                </div>{/* end flex-1 */}


            </div>{/* end flex container */}

            {/* 删除确认对话框 */}
            {deleteConfirm.show && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm">
                    <div className="bg-white rounded-2xl shadow-2xl p-6 max-w-md w-full mx-4">
                        <div className="flex items-center gap-3 mb-4">
                            <div className="p-2.5 bg-orange-100 text-orange-600 rounded-xl">
                                <AlertTriangle className="w-5 h-5" />
                            </div>
                            <h3 className="text-lg font-bold text-slate-800">
                                删除此行
                            </h3>
                        </div>
                        <p className="text-sm text-slate-600 mb-1">即将删除以下宽幅：</p>
                        <p className="text-sm font-semibold text-slate-800 bg-slate-50 px-3 py-2 rounded-lg mb-4">
                            {deleteConfirm.info}
                        </p>
                        <p className="text-xs text-blue-600 mb-5">
                            💡 删除后该宽幅将进入待搭切池，可点击右上角「重新搭切」自动搭配。
                        </p>
                        <div className="flex justify-end gap-3">
                            <button
                                onClick={() => setDeleteConfirm({ show: false, targets: [], info: '', mode: 'group' })}
                                className="px-4 py-2 text-sm font-medium text-slate-600 bg-slate-100 hover:bg-slate-200 rounded-lg transition-colors"
                                disabled={deleting}
                            >
                                取消
                            </button>
                            <button
                                onClick={confirmDelete}
                                className="px-4 py-2 text-sm font-medium text-white bg-orange-500 hover:bg-orange-600 rounded-lg transition-colors disabled:opacity-50"
                                disabled={deleting}
                            >
                                {deleting ? '处理中...' : '确认删除'}
                            </button>
                        </div>
                    </div>
                </div>
            )}


        </div>
    );
}
