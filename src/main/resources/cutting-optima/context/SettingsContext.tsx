import React, { createContext, useContext, useState, useEffect, ReactNode } from 'react';

// 存储键
const SETTINGS_KEY = 'cuttingOptimaSettings';

// 卡片可见性配置
export interface CardVisibility {
    totalRolls: boolean;      // 总用卷数
    efficiency: boolean;      // 利用率
    totalWaste: boolean;      // 总废料
    overproduction: boolean;  // 超产
    executionTime: boolean;   // 计算耗时
}

// 算法参数配置
export interface AlgorithmParams {
    maxIterations: number;    // 最大迭代次数
    timeoutMs: number;        // 超时时间（毫秒）
}

// 设置状态
interface SettingsState {
    cardVisibility: CardVisibility;
    algorithmParams: AlgorithmParams;
}

// 默认设置
const defaultSettings: SettingsState = {
    cardVisibility: {
        totalRolls: true,
        efficiency: true,
        totalWaste: true,
        overproduction: true,
        executionTime: true,
    },
    algorithmParams: {
        maxIterations: 300,
        timeoutMs: 240000,
    },
};

// 上下文类型
interface SettingsContextType extends SettingsState {
    setCardVisibility: (key: keyof CardVisibility, visible: boolean) => void;
    setAlgorithmParams: (params: Partial<AlgorithmParams>) => void;
    resetToDefaults: () => void;
    isSettingsPanelOpen: boolean;
    openSettingsPanel: () => void;
    closeSettingsPanel: () => void;
}

const SettingsContext = createContext<SettingsContextType | undefined>(undefined);

// 从 localStorage 加载设置
function loadSettings(): SettingsState {
    try {
        const saved = localStorage.getItem(SETTINGS_KEY);
        if (saved) {
            const parsed = JSON.parse(saved);
            return {
                cardVisibility: { ...defaultSettings.cardVisibility, ...parsed.cardVisibility },
                algorithmParams: { ...defaultSettings.algorithmParams, ...parsed.algorithmParams },
            };
        }
    } catch (e) {
        console.error('加载设置失败:', e);
    }
    return defaultSettings;
}

// 保存设置到 localStorage
function saveSettings(settings: SettingsState) {
    try {
        localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
    } catch (e) {
        console.error('保存设置失败:', e);
    }
}

export function SettingsProvider({ children }: { children: ReactNode }) {
    const [settings, setSettings] = useState<SettingsState>(loadSettings);
    const [isSettingsPanelOpen, setIsSettingsPanelOpen] = useState(false);

    // 当设置变化时保存到 localStorage
    useEffect(() => {
        saveSettings(settings);
    }, [settings]);

    // 设置单个卡片可见性
    const setCardVisibility = (key: keyof CardVisibility, visible: boolean) => {
        setSettings(prev => ({
            ...prev,
            cardVisibility: {
                ...prev.cardVisibility,
                [key]: visible,
            },
        }));
    };

    // 设置算法参数
    const setAlgorithmParams = (params: Partial<AlgorithmParams>) => {
        setSettings(prev => ({
            ...prev,
            algorithmParams: {
                ...prev.algorithmParams,
                ...params,
            },
        }));
    };

    // 重置为默认值
    const resetToDefaults = () => {
        setSettings(defaultSettings);
    };

    // 打开/关闭设置面板
    const openSettingsPanel = () => setIsSettingsPanelOpen(true);
    const closeSettingsPanel = () => setIsSettingsPanelOpen(false);

    const value: SettingsContextType = {
        ...settings,
        setCardVisibility,
        setAlgorithmParams,
        resetToDefaults,
        isSettingsPanelOpen,
        openSettingsPanel,
        closeSettingsPanel,
    };

    return <SettingsContext.Provider value={value}>{children}</SettingsContext.Provider>;
}

export function useSettings() {
    const context = useContext(SettingsContext);
    if (context === undefined) {
        throw new Error('useSettings 必须在 SettingsProvider 内使用');
    }
    return context;
}
