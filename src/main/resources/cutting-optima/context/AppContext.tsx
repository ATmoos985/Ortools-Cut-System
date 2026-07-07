import React, { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import { OrderItem, DiagnosisResult, OptimizationResult } from '../types';

// Storage keys
const CONFIG_KEY = 'cuttingOptimaConfig';  // localStorage for config (persistent)

// Config parameters (persistent across page refreshes)
interface ConfigState {
    mode: 'fixed' | 'flexible' | 'newsolver';
    fixedWidth: number;
    totalWidth: number;
    minWidth: number;
    maxWidth: number;
    stepSize: number;
    flexTotalWidth: number;
    totalOverCap: number;
    // NewSolver 专用参数
    newSolverTotalWidth: number;
    newSolverTopK: number;
    newSolverMaxIterations: number;
    newSolverTimeLimit: number;
    newSolverQualityMode: boolean;
    newSolverMaxPatterns: number;
    newSolverMaxDistinctWidths: number;
    newSolverStage4TimeLimit: number;
    newSolverSeqGroupAlpha: number;
    newSolverSeqGroupBeta: number;
    newSolverUseOptimizedAssignment: boolean;
    newSolverUnderPenalty: number;
}

// Business data (memory-only, cleared on refresh)
interface BusinessState {
    orderItems: OrderItem[];
    fileName: string | null;
    importSource: string | null;
    diagnosis: DiagnosisResult | null;
    optimizationResult: OptimizationResult | null;
    selectedGroupKey: string | null;
}

// Combined state
interface AppState extends ConfigState, BusinessState { }

// Default config
const defaultConfig: ConfigState = {
    mode: 'fixed',
    fixedWidth: 4400,
    totalWidth: 4600,
    minWidth: 4300,
    maxWidth: 4400,
    stepSize: 10,
    flexTotalWidth: 4600,
    totalOverCap: 30,
    // NewSolver 默认值
    newSolverTotalWidth: 4600,
    newSolverTopK: 3,
    newSolverMaxIterations: 300,
    newSolverTimeLimit: 240000,
    newSolverQualityMode: false,
    newSolverMaxPatterns: 800,
    newSolverMaxDistinctWidths: 4,
    newSolverStage4TimeLimit: 30000,
    newSolverSeqGroupAlpha: 1.0,
    newSolverSeqGroupBeta: 0.0,
    newSolverUseOptimizedAssignment: true,
    newSolverUnderPenalty: 1000000,
};

// Default business data
const defaultBusinessData: BusinessState = {
    orderItems: [],
    fileName: null,
    importSource: null,
    diagnosis: null,
    optimizationResult: null,
    selectedGroupKey: null,
};

// Default complete state
const defaultState: AppState = {
    ...defaultConfig,
    ...defaultBusinessData,
};

// Context interface with setters
interface AppContextType extends AppState {
    setOrderItems: (items: OrderItem[]) => void;
    setFileName: (name: string | null) => void;
    setImportSource: (source: string | null) => void;
    setMode: (mode: 'fixed' | 'flexible' | 'newsolver') => void;
    setFixedWidth: (width: number) => void;
    setTotalWidth: (width: number) => void;
    setMinWidth: (width: number) => void;
    setMaxWidth: (width: number) => void;
    setStepSize: (size: number) => void;
    setFlexTotalWidth: (width: number) => void;
    setTotalOverCap: (cap: number) => void;
    // NewSolver setters
    setNewSolverTotalWidth: (width: number) => void;
    setNewSolverTopK: (topK: number) => void;
    setNewSolverMaxIterations: (iterations: number) => void;
    setNewSolverTimeLimit: (timeLimit: number) => void;
    setNewSolverQualityMode: (enabled: boolean) => void;
    setNewSolverMaxPatterns: (maxPatterns: number) => void;
    setNewSolverMaxDistinctWidths: (maxDistinctWidths: number) => void;
    setNewSolverStage4TimeLimit: (timeLimit: number) => void;
    setNewSolverSeqGroupAlpha: (alpha: number) => void;
    setNewSolverSeqGroupBeta: (beta: number) => void;
    setNewSolverUseOptimizedAssignment: (enabled: boolean) => void;
    setNewSolverUnderPenalty: (penalty: number) => void;
    setDiagnosis: (result: DiagnosisResult | null) => void;
    setOptimizationResult: (result: OptimizationResult | null) => void;
    setSelectedGroupKey: (key: string | null) => void;
    clearBusinessData: () => void;  // Only clears business data, keeps config
    hasShownRefreshAlert: boolean;
    markRefreshAlertShown: () => void;
}

const AppContext = createContext<AppContextType | undefined>(undefined);

// Load config from localStorage
function loadConfig(): ConfigState {
    try {
        const saved = localStorage.getItem(CONFIG_KEY);
        if (saved) {
            return { ...defaultConfig, ...JSON.parse(saved) };
        }
    } catch (e) {
        console.error('Failed to load config from localStorage:', e);
    }
    return defaultConfig;
}

// Save config to localStorage
function saveConfig(config: ConfigState) {
    try {
        localStorage.setItem(CONFIG_KEY, JSON.stringify(config));
    } catch (e) {
        console.error('Failed to save config to localStorage:', e);
    }
}

// Extract config from state
function extractConfig(state: AppState): ConfigState {
    return {
        mode: state.mode,
        fixedWidth: state.fixedWidth,
        totalWidth: state.totalWidth,
        minWidth: state.minWidth,
        maxWidth: state.maxWidth,
        stepSize: state.stepSize,
        flexTotalWidth: state.flexTotalWidth,
        totalOverCap: state.totalOverCap,
        newSolverTotalWidth: state.newSolverTotalWidth,
        newSolverTopK: state.newSolverTopK,
        newSolverMaxIterations: state.newSolverMaxIterations,
        newSolverTimeLimit: state.newSolverTimeLimit,
        newSolverQualityMode: state.newSolverQualityMode,
        newSolverMaxPatterns: state.newSolverMaxPatterns,
        newSolverMaxDistinctWidths: state.newSolverMaxDistinctWidths,
        newSolverStage4TimeLimit: state.newSolverStage4TimeLimit,
        newSolverSeqGroupAlpha: state.newSolverSeqGroupAlpha,
        newSolverSeqGroupBeta: state.newSolverSeqGroupBeta,
        newSolverUseOptimizedAssignment: state.newSolverUseOptimizedAssignment,
        newSolverUnderPenalty: state.newSolverUnderPenalty,
    };
}

export function AppProvider({ children }: { children: ReactNode }) {
    // Initialize with saved config + empty business data
    const [state, setState] = useState<AppState>(() => ({
        ...loadConfig(),
        ...defaultBusinessData,
    }));

    // Save config to localStorage whenever it changes
    useEffect(() => {
        saveConfig(extractConfig(state));
    }, [
        state.mode,
        state.fixedWidth,
        state.totalWidth,
        state.minWidth,
        state.maxWidth,
        state.stepSize,
        state.flexTotalWidth,
        state.totalOverCap,
        state.newSolverTotalWidth,
        state.newSolverTopK,
        state.newSolverMaxIterations,
        state.newSolverTimeLimit,
        state.newSolverQualityMode,
        state.newSolverMaxPatterns,
        state.newSolverMaxDistinctWidths,
        state.newSolverStage4TimeLimit,
        state.newSolverSeqGroupAlpha,
        state.newSolverSeqGroupBeta,
        state.newSolverUseOptimizedAssignment,
        state.newSolverUnderPenalty,
    ]);

    // Create setter functions
    const setOrderItems = (items: OrderItem[]) =>
        setState(prev => ({ ...prev, orderItems: items }));

    const setFileName = (name: string | null) =>
        setState(prev => ({ ...prev, fileName: name }));

    const setImportSource = (source: string | null) =>
        setState(prev => ({ ...prev, importSource: source }));

    const setMode = (mode: 'fixed' | 'flexible' | 'newsolver') =>
        setState(prev => ({ ...prev, mode }));

    const setFixedWidth = (width: number) =>
        setState(prev => ({ ...prev, fixedWidth: width }));

    const setTotalWidth = (width: number) =>
        setState(prev => ({ ...prev, totalWidth: width }));

    const setMinWidth = (width: number) =>
        setState(prev => ({ ...prev, minWidth: width }));

    const setMaxWidth = (width: number) =>
        setState(prev => ({ ...prev, maxWidth: width }));

    const setStepSize = (size: number) =>
        setState(prev => ({ ...prev, stepSize: size }));

    const setFlexTotalWidth = (width: number) =>
        setState(prev => ({ ...prev, flexTotalWidth: width }));

    const setTotalOverCap = (cap: number) =>
        setState(prev => ({ ...prev, totalOverCap: cap }));

    // NewSolver setters
    const setNewSolverTotalWidth = (width: number) =>
        setState(prev => ({ ...prev, newSolverTotalWidth: width }));

    const setNewSolverTopK = (topK: number) =>
        setState(prev => ({ ...prev, newSolverTopK: topK }));

    const setNewSolverMaxIterations = (iterations: number) =>
        setState(prev => ({ ...prev, newSolverMaxIterations: iterations }));

    const setNewSolverTimeLimit = (timeLimit: number) =>
        setState(prev => ({ ...prev, newSolverTimeLimit: timeLimit }));

    const setNewSolverQualityMode = (enabled: boolean) =>
        setState(prev => ({
            ...prev,
            newSolverQualityMode: enabled,
            newSolverMaxDistinctWidths: enabled
                ? Math.max(prev.newSolverMaxDistinctWidths, 5)
                : prev.newSolverMaxDistinctWidths,
        }));

    const setNewSolverMaxPatterns = (maxPatterns: number) =>
        setState(prev => ({ ...prev, newSolverMaxPatterns: maxPatterns }));

    const setNewSolverMaxDistinctWidths = (maxDistinctWidths: number) =>
        setState(prev => ({ ...prev, newSolverMaxDistinctWidths: maxDistinctWidths }));

    const setNewSolverStage4TimeLimit = (timeLimit: number) =>
        setState(prev => ({ ...prev, newSolverStage4TimeLimit: timeLimit }));

    const setNewSolverSeqGroupAlpha = (alpha: number) =>
        setState(prev => ({ ...prev, newSolverSeqGroupAlpha: alpha }));

    const setNewSolverSeqGroupBeta = (beta: number) =>
        setState(prev => ({ ...prev, newSolverSeqGroupBeta: beta }));

    const setNewSolverUseOptimizedAssignment = (enabled: boolean) =>
        setState(prev => ({ ...prev, newSolverUseOptimizedAssignment: enabled }));

    const setNewSolverUnderPenalty = (penalty: number) =>
        setState(prev => ({ ...prev, newSolverUnderPenalty: penalty }));

    const setDiagnosis = (result: DiagnosisResult | null) =>
        setState(prev => ({ ...prev, diagnosis: result }));

    const setOptimizationResult = (result: OptimizationResult | null) =>
        setState(prev => ({ ...prev, optimizationResult: result }));

    const setSelectedGroupKey = (key: string | null) =>
        setState(prev => ({ ...prev, selectedGroupKey: key }));

    // Global UI state for refresh alert
    const [hasShownRefreshAlert, setHasShownRefreshAlert] = useState(false);
    const markRefreshAlertShown = () => setHasShownRefreshAlert(true);

    // Clear only business data, keep config
    const clearBusinessData = () => {
        setState(prev => ({
            ...prev,
            ...defaultBusinessData,
        }));
    };

    const value: AppContextType = {
        ...state,
        hasShownRefreshAlert,
        markRefreshAlertShown,
        setOrderItems,
        setFileName,
        setImportSource,
        setMode,
        setFixedWidth,
        setTotalWidth,
        setMinWidth,
        setMaxWidth,
        setStepSize,
        setFlexTotalWidth,
        setTotalOverCap,
        setNewSolverTotalWidth,
        setNewSolverTopK,
        setNewSolverMaxIterations,
        setNewSolverTimeLimit,
        setNewSolverQualityMode,
        setNewSolverMaxPatterns,
        setNewSolverMaxDistinctWidths,
        setNewSolverStage4TimeLimit,
        setNewSolverSeqGroupAlpha,
        setNewSolverSeqGroupBeta,
        setNewSolverUseOptimizedAssignment,
        setNewSolverUnderPenalty,
        setDiagnosis,
        setOptimizationResult,
        setSelectedGroupKey,
        clearBusinessData,
    };

    return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useAppContext() {
    const context = useContext(AppContext);
    if (context === undefined) {
        throw new Error('useAppContext must be used within an AppProvider');
    }
    return context;
}
