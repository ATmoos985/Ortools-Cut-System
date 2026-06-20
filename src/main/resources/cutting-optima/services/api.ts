import { DiagnosisResult, OptimizationResult, OrderItem, PreviewData, DataValidationResult, ParseExcelResponse } from "../types";

const API_BASE = '/api/cutting';

export const parseExcel = async (file: File): Promise<ParseExcelResponse> => {
  const formData = new FormData();
  formData.append('file', file);
  const response = await fetch(`${API_BASE}/parse-excel`, { method: 'POST', body: formData });
  return response.json();
};

export const diagnoseGrouping = async (orderItems: OrderItem[]): Promise<DiagnosisResult> => {
  const response = await fetch(`${API_BASE}/diagnose-grouping`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(orderItems)
  });
  return response.json();
};

export const optimizeCutting = async (payload: any): Promise<{ success: boolean; result: OptimizationResult; message?: string }> => {
  const response = await fetch(`${API_BASE}/optimize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  return response.json();
};

export const fetchPreview = async (groupKey?: string | null): Promise<PreviewData> => {
  const response = await fetch(`${API_BASE}/v2/preview`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ groupKey: groupKey || null })
  });
  return response.json();
};

// 删除单行（扣减需求量 + 剩余行进待搭切池）
export const deleteRow = async (
  sequenceNumber: number,
  messageText: string,
  width: number,
  groupKey?: string | null,
  rowId?: string | null,
  planId?: string | null,
  revisionId?: string | null,
): Promise<any> => {
  const response = await fetch(`${API_BASE}/v2/sequence-groups/delete-row`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      sequenceNumber,
      messageText,
      width,
      rowId: rowId || null,
      planId: planId || null,
      baseRevisionId: revisionId || null,
      groupKey: groupKey || null,
    })
  });
  return response.json();
};


// 撤销上一步操作
export const undo = async (): Promise<any> => {
  const response = await fetch(`${API_BASE}/v2/sequence-groups/undo`, { method: 'POST' });
  return response.json();
};

// 获取待搭切池
// 重新搭切（传入勾选的序号组 → 触发二次求解器）
export const reCut = async (
  selectedSequenceNumbers: number[],
  planId?: string | null,
  revisionId?: string | null,
  selectedSequenceGroupIds?: string[],
): Promise<any> => {
  const response = await fetch(`${API_BASE}/v2/sequence-groups/re-cut`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      planId: planId || null,
      baseRevisionId: revisionId || null,
      selectedSequenceNumbers,
      selectedSequenceGroupIds: selectedSequenceGroupIds || [],
    })
  });
  return response.json();
};



export const confirmExportV2 = async (): Promise<{ success: boolean; fileName: string; message?: string }> => {
  const response = await fetch(`${API_BASE}/v2/export`, { method: 'POST' });
  return response.json();
};

export const validateDataFile = async (file: File): Promise<{ success: boolean; validationResult: DataValidationResult; message?: string }> => {
  const formData = new FormData();
  formData.append('file', file);
  const response = await fetch(`${API_BASE}/validate-data`, { method: 'POST', body: formData });
  return response.json();
};

export const exportValidationReport = async (payload: any): Promise<{ success: boolean; fileName: string; message?: string }> => {
  const response = await fetch(`${API_BASE}/export-validation-report`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  return response.json();
};

export const downloadFile = async (fileName: string): Promise<Blob> => {
  const response = await fetch(`${API_BASE}/download/${fileName}`);
  if (!response.ok) throw new Error("Download failed");
  return response.blob();
};
