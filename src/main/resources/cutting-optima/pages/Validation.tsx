import React, { useState } from 'react';
import { Upload, FileText, Check, AlertTriangle, XCircle, Download } from 'lucide-react';
import { Card } from '../components/ui/Card';
import { Button } from '../components/ui/Button';
import * as api from '../services/api';
import { DataValidationResult } from '../types';

export default function Validation() {
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<DataValidationResult | null>(null);

  const handleFile = async (selectedFile: File) => {
    setFile(selectedFile);
  };

  const handleValidate = async () => {
    if (!file) return;
    setLoading(true);
    try {
        const res = await api.validateDataFile(file);
        if (res.success) {
            setResult(res.validationResult);
        } else {
            alert(res.message);
        }
    } catch (e: any) {
        alert(e.message);
    } finally {
        setLoading(false);
    }
  };

  const handleExportReport = async () => {
    if (!result) return;
    setLoading(true);
    try {
        const timestamp = new Date().toISOString().slice(0, 19).replace(/:/g, '-');
        const fileName = `validation_report_${timestamp}.xlsx`;
        const res = await api.exportValidationReport({ validationResult: result, fileName });
        if (res.success) {
            const blob = await api.downloadFile(res.fileName);
            const url = window.URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = fileName;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
        } else {
            alert(res.message);
        }
    } catch (e: any) {
        alert(e.message);
    } finally {
        setLoading(false);
    }
  };

  return (
    <div className="max-w-6xl mx-auto">
        <div className="mb-8">
            <h1 className="text-2xl font-bold text-slate-800">数据源校验</h1>
            <p className="text-slate-500 mt-1">上传 Excel 检查列名识别、数据格式及潜在错误。</p>
        </div>

        <div className="grid grid-cols-1 lg:grid-cols-3 gap-8">
            {/* Upload Area */}
            <div className="lg:col-span-1 space-y-6">
                <Card>
                    <div 
                        className={`border-2 border-dashed rounded-xl p-8 text-center cursor-pointer transition-all ${file ? 'border-blue-500 bg-blue-50' : 'border-slate-300 hover:border-blue-400 hover:bg-slate-50'}`}
                        onClick={() => document.getElementById('vFileInput')?.click()}
                    >
                        <div className={`w-12 h-12 mx-auto rounded-full flex items-center justify-center mb-4 ${file ? 'bg-blue-200 text-blue-700' : 'bg-slate-100 text-slate-400'}`}>
                             <FileText className="w-6 h-6" />
                        </div>
                        <p className="text-sm font-medium text-slate-700 mb-1">
                            {file ? file.name : "选择文件进行校验"}
                        </p>
                        <input type="file" id="vFileInput" accept=".xlsx,.xls,.csv" className="hidden" onChange={(e) => e.target.files?.[0] && handleFile(e.target.files[0])} />
                    </div>
                    <div className="mt-4 flex flex-col gap-3">
                        <Button onClick={handleValidate} disabled={!file || loading} isLoading={loading} className="w-full">开始校验</Button>
                        <Button variant="secondary" onClick={handleExportReport} disabled={!result || loading} className="w-full">导出校验报告</Button>
                    </div>
                </Card>
            </div>

            {/* Results Area */}
            <div className="lg:col-span-2">
                {!result ? (
                    <div className="h-full bg-slate-100 rounded-2xl border border-dashed border-slate-300 flex items-center justify-center text-slate-400 min-h-[300px]">
                        <p>校验结果将在此处显示</p>
                    </div>
                ) : (
                    <div className="space-y-6 animate-in fade-in slide-in-from-right-4">
                        {/* Stats */}
                        <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
                             <div className={`p-4 rounded-xl border flex flex-col items-center justify-center ${result.totalErrors === 0 ? 'bg-emerald-50 border-emerald-200 text-emerald-700' : 'bg-red-50 border-red-200 text-red-700'}`}>
                                <div className="text-2xl font-bold">{result.totalErrors}</div>
                                <div className="text-xs">错误行数</div>
                             </div>
                             <div className={`p-4 rounded-xl border flex flex-col items-center justify-center ${result.totalWarnings === 0 ? 'bg-white border-slate-200 text-slate-600' : 'bg-amber-50 border-amber-200 text-amber-700'}`}>
                                <div className="text-2xl font-bold">{result.totalWarnings}</div>
                                <div className="text-xs">警告行数</div>
                             </div>
                             <div className="p-4 rounded-xl border bg-white border-slate-200 text-slate-700 flex flex-col items-center justify-center">
                                <div className="text-2xl font-bold">{result.totalValid}</div>
                                <div className="text-xs">有效行数</div>
                             </div>
                             <div className="p-4 rounded-xl border bg-white border-slate-200 text-slate-700 flex flex-col items-center justify-center">
                                <div className="text-2xl font-bold">{result.totalRecords}</div>
                                <div className="text-xs">总记录数</div>
                             </div>
                        </div>

                        {/* Field Mapping */}
                        <Card title="字段映射状态">
                            <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                                {Object.entries(result.fieldMapping).map(([field, info]: [string, any]) => (
                                    <div key={field} className={`p-3 rounded-lg border flex justify-between items-center ${info.status === 'success' ? 'bg-emerald-50/50 border-emerald-100' : info.status === 'warning' ? 'bg-amber-50/50 border-amber-100' : 'bg-red-50/50 border-red-100'}`}>
                                        <div className="flex items-center gap-3">
                                            {info.status === 'success' ? <Check className="text-emerald-500 w-4 h-4"/> : info.status === 'warning' ? <AlertTriangle className="text-amber-500 w-4 h-4"/> : <XCircle className="text-red-500 w-4 h-4"/>}
                                            <div>
                                                <div className="font-bold text-slate-700 text-sm">{field}</div>
                                                <div className="text-xs text-slate-500">列 {info.columnIndex + 1}: {info.columnName || '未找到'}</div>
                                            </div>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </Card>

                        {/* Preview */}
                        <Card title="数据预览 (前10行)">
                            <div className="overflow-x-auto rounded-lg border border-slate-100">
                                <table className="w-full text-sm">
                                    <thead className="bg-slate-50 text-slate-600">
                                        <tr>
                                            <th className="p-3 text-left">信息</th>
                                            <th className="p-3 text-center">幅宽</th>
                                            <th className="p-3 text-center">数量</th>
                                            <th className="p-3 text-center">长度</th>
                                            <th className="p-3 text-center">工艺</th>
                                        </tr>
                                    </thead>
                                    <tbody className="divide-y divide-slate-100">
                                        {result.sampleData.slice(0, 10).map((row: any, i) => (
                                            <tr key={i} className="hover:bg-slate-50/50">
                                                <td className="p-3 text-slate-700 max-w-[200px] truncate" title={row.messageText}>{row.messageText}</td>
                                                <td className="p-3 text-center font-mono">{row.width}</td>
                                                <td className="p-3 text-center">{row.quantity}</td>
                                                <td className="p-3 text-center">{row.length}</td>
                                                <td className="p-3 text-center">{row.surfaceTreatment}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        </Card>
                    </div>
                )}
            </div>
        </div>
    </div>
  );
}