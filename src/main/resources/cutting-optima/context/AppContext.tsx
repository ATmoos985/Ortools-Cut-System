import React, { createContext, useContext, useEffect, useState, ReactNode } from 'react';
import { OrderItem, DiagnosisResult, OptimizationResult } from '../types';

const CONFIG_KEY = 'cuttingOptimaConfig';

export type SolverProfile = 'FAST' | 'QUALITY';

interface ConfigState {
    configVersion: 2;
    solverProfile: SolverProfile;
    totalWidth: number;
    minWidth: number;
    maxWidth: number;
    stepSize: number;
    totalOverCap: number;
    newSolverTopK: number;
    newSolverMaxIterations: number;
    newSolverTimeLimit: number;
    newSolverMaxPatterns: number;
    newSolverMaxDistinctWidths: number;
}

interface BusinessState {
    orderItems: OrderItem[];
    fileName: string | null;
    importSource: string | null;
    diagnosis: DiagnosisResult | null;
    optimizationResult: OptimizationResult | null;
    selectedGroupKey: string | null;
}

interface AppState extends ConfigState, BusinessState { }

const defaultConfig: ConfigState = {
    configVersion: 2,
    solverProfile: 'FAST',
    totalWidth: 4600,
    minWidth: 4300,
    maxWidth: 4400,
    stepSize: 10,
    totalOverCap: 30,
    newSolverTopK: 3,
    newSolverMaxIterations: 300,
    newSolverTimeLimit: 120000,
    newSolverMaxPatterns: 800,
    newSolverMaxDistinctWidths: 4,
};

const defaultBusinessData: BusinessState = {
    orderItems: [],
    fileName: null,
    importSource: null,
    diagnosis: null,
    optimizationResult: null,
    selectedGroupKey: null,
};

interface AppContextType extends AppState {
    setOrderItems: (items: OrderItem[]) => void;
    setFileName: (name: string | null) => void;
    setImportSource: (source: string | null) => void;
    setSolverProfile: (profile: SolverProfile) => void;
    setTotalWidth: (width: number) => void;
    setMinWidth: (width: number) => void;
    setMaxWidth: (width: number) => void;
    setStepSize: (size: number) => void;
    setTotalOverCap: (cap: number) => void;
    setNewSolverTopK: (topK: number) => void;
    setNewSolverMaxIterations: (iterations: number) => void;
    setNewSolverTimeLimit: (timeLimit: number) => void;
    setNewSolverMaxPatterns: (maxPatterns: number) => void;
    setNewSolverMaxDistinctWidths: (maxDistinctWidths: number) => void;
    resetSolverConfig: () => void;
    setDiagnosis: (result: DiagnosisResult | null) => void;
    setOptimizationResult: (result: OptimizationResult | null) => void;
    setSelectedGroupKey: (key: string | null) => void;
    clearBusinessData: () => void;
    hasShownRefreshAlert: boolean;
    markRefreshAlertShown: () => void;
}

const AppContext = createContext<AppContextType | undefined>(undefined);

function finiteNumber(value: unknown, fallback: number): number {
    return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function loadConfig(): ConfigState {
    try {
        const saved = localStorage.getItem(CONFIG_KEY);
        if (!saved) return defaultConfig;

        const parsed = JSON.parse(saved);
        return {
            configVersion: 2,
            solverProfile: parsed.solverProfile === 'QUALITY' ? 'QUALITY' : 'FAST',
            totalWidth: finiteNumber(
                parsed.totalWidth ?? parsed.newSolverTotalWidth ?? parsed.flexTotalWidth,
                defaultConfig.totalWidth,
            ),
            minWidth: finiteNumber(parsed.minWidth, defaultConfig.minWidth),
            maxWidth: finiteNumber(parsed.maxWidth, defaultConfig.maxWidth),
            stepSize: finiteNumber(parsed.stepSize, defaultConfig.stepSize),
            totalOverCap: finiteNumber(parsed.totalOverCap, defaultConfig.totalOverCap),
            newSolverTopK: finiteNumber(parsed.newSolverTopK, defaultConfig.newSolverTopK),
            newSolverMaxIterations: finiteNumber(
                parsed.newSolverMaxIterations,
                defaultConfig.newSolverMaxIterations,
            ),
            newSolverTimeLimit: finiteNumber(
                parsed.newSolverTimeLimit,
                defaultConfig.newSolverTimeLimit,
            ),
            newSolverMaxPatterns: finiteNumber(
                parsed.newSolverMaxPatterns,
                defaultConfig.newSolverMaxPatterns,
            ),
            newSolverMaxDistinctWidths: finiteNumber(
                parsed.newSolverMaxDistinctWidths,
                defaultConfig.newSolverMaxDistinctWidths,
            ),
        };
    } catch (e) {
        console.error('Failed to load config from localStorage:', e);
        return defaultConfig;
    }
}

function extractConfig(state: AppState): ConfigState {
    return {
        configVersion: 2,
        solverProfile: state.solverProfile,
        totalWidth: state.totalWidth,
        minWidth: state.minWidth,
        maxWidth: state.maxWidth,
        stepSize: state.stepSize,
        totalOverCap: state.totalOverCap,
        newSolverTopK: state.newSolverTopK,
        newSolverMaxIterations: state.newSolverMaxIterations,
        newSolverTimeLimit: state.newSolverTimeLimit,
        newSolverMaxPatterns: state.newSolverMaxPatterns,
        newSolverMaxDistinctWidths: state.newSolverMaxDistinctWidths,
    };
}

export function AppProvider({ children }: { children: ReactNode }) {
    const [state, setState] = useState<AppState>(() => ({
        ...loadConfig(),
        ...defaultBusinessData,
    }));
    const [hasShownRefreshAlert, setHasShownRefreshAlert] = useState(false);

    useEffect(() => {
        try {
            localStorage.setItem(CONFIG_KEY, JSON.stringify(extractConfig(state)));
        } catch (e) {
            console.error('Failed to save config to localStorage:', e);
        }
    }, [
        state.solverProfile,
        state.totalWidth,
        state.minWidth,
        state.maxWidth,
        state.stepSize,
        state.totalOverCap,
        state.newSolverTopK,
        state.newSolverMaxIterations,
        state.newSolverTimeLimit,
        state.newSolverMaxPatterns,
        state.newSolverMaxDistinctWidths,
    ]);

    const update = <K extends keyof AppState>(key: K, value: AppState[K]) =>
        setState(prev => ({ ...prev, [key]: value }));

    const value: AppContextType = {
        ...state,
        hasShownRefreshAlert,
        markRefreshAlertShown: () => setHasShownRefreshAlert(true),
        setOrderItems: items => update('orderItems', items),
        setFileName: name => update('fileName', name),
        setImportSource: source => update('importSource', source),
        setSolverProfile: profile => update('solverProfile', profile),
        setTotalWidth: width => update('totalWidth', width),
        setMinWidth: width => update('minWidth', width),
        setMaxWidth: width => update('maxWidth', width),
        setStepSize: size => update('stepSize', size),
        setTotalOverCap: cap => update('totalOverCap', cap),
        setNewSolverTopK: topK => update('newSolverTopK', topK),
        setNewSolverMaxIterations: iterations => update('newSolverMaxIterations', iterations),
        setNewSolverTimeLimit: timeLimit => update('newSolverTimeLimit', timeLimit),
        setNewSolverMaxPatterns: maxPatterns => update('newSolverMaxPatterns', maxPatterns),
        setNewSolverMaxDistinctWidths: maxDistinctWidths =>
            update('newSolverMaxDistinctWidths', maxDistinctWidths),
        resetSolverConfig: () => setState(prev => ({ ...prev, ...defaultConfig })),
        setDiagnosis: result => update('diagnosis', result),
        setOptimizationResult: result => update('optimizationResult', result),
        setSelectedGroupKey: key => update('selectedGroupKey', key),
        clearBusinessData: () => setState(prev => ({ ...prev, ...defaultBusinessData })),
    };

    return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useAppContext() {
    const context = useContext(AppContext);
    if (!context) throw new Error('useAppContext must be used within an AppProvider');
    return context;
}
