export interface OrderItem {
  width: number;
  quantity: number;
  description?: string;
  [key: string]: any;
}

export interface Preset {
  name: string;
  minWidth: number;
  maxWidth: number;
  stepSize: number;
  totalWidth: number;
}

export interface GroupedDiagnosis {
  groupKey: string;
  length: number;
  surfaceTreatment: string;
  itemCount: number;
  totalDemand: number;
  widthStats: Record<string, number>;
}

export interface DiagnosisResult {
  success: boolean;
  totalItems: number;
  groupCount: number;
  groups: GroupedDiagnosis[];
  message?: string;
}

export interface DemandFulfillmentDetail {
  groupKey?: string;
  width: number;
  demand: number;
  actual: number;
  difference: number;
}

export interface DemandAnalysis {
  totalOverproduction: number;
  totalUnderproduction: number;
  allDemandsMet: boolean;
  fulfillmentDetails: DemandFulfillmentDetail[];
}

export interface OptimizationResultGroup {
  totalRolls: number;
  totalDemand: number;
  efficiency: number;
  totalWaste: number;
  demands: any;
}

export interface OptimizationInstruction {
  subRolls: Record<string, number>;
  usageCount: number;
  stationAssignments: any[];
  rollWidth?: number;
}

export interface PatternGroup {
  groupKey: string;
  instructions: OptimizationInstruction[];
}

export interface OptimizationResult {
  totalRolls: number;
  efficiency: number;
  totalWaste: number;
  executionTimeMs: number;
  iterations: number;
  groupedResults?: Record<string, OptimizationResultGroup>;
  demandAnalysis: DemandAnalysis;
  patterns: PatternGroup[];
}

export interface PreviewRow {
  rowId?: string;
  sequenceGroupId?: string;
  messageText: string;
  salesperson: string;
  width: number;
  length: number;
  rolls: number;
  stationCount: number;
  isOverproduction?: boolean;
  difference?: number;
}

export interface PreviewGroup {
  planId?: string;
  revisionId?: string;
  sequenceGroupId?: string;
  sequenceNumber: number;
  comboExpanded: string[];
  length: number;
  surfaceTreatment: string;
  thickness?: number;
  rollWidth: number;
  usageCount: number;
  groupKey?: string;
  isNewGroup?: boolean;
  rows: PreviewRow[];
}

export interface PreviewData {
  success: boolean;
  jobId?: string;
  planId?: string;
  revisionId?: string;
  totalRollsUsed: number;
  utilizationRate: number;
  totalWaste: number;
  totalGroups: number;
  preview: PreviewGroup[];
  message?: string;
  singleUsageGroups?: number;
}

export interface DataValidationResult {
  totalErrors: number;
  totalWarnings: number;
  totalValid: number;
  totalRecords: number;
  fieldMapping: Record<string, { status: string; columnIndex: number; columnName: string }>;
  sampleData: any[];
}
