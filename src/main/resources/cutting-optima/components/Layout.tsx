import React from 'react';
import { NavLink, useLocation } from 'react-router-dom';
import { LayoutDashboard, FileSpreadsheet, ShieldCheck, Scissors, Settings, TableProperties } from 'lucide-react';
import { useSettings } from '../context/SettingsContext';
import { SettingsPanel } from './SettingsPanel';

export const Layout: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const location = useLocation();
  const { openSettingsPanel } = useSettings();

  const navItems = [
    { path: '/', label: '智能排版', icon: Scissors },
    { path: '/sequence-groups', label: '搭切明细', icon: TableProperties },
    { path: '/preview', label: '方案预览', icon: FileSpreadsheet },
    { path: '/validation', label: '数据校验', icon: ShieldCheck },
  ];

  return (
    <div className="flex h-screen bg-slate-50">
      {/* Sidebar */}
      <aside className="w-64 bg-slate-900 text-white flex flex-col shadow-xl z-20">
        <div className="p-6 border-b border-slate-700 flex items-center gap-3">
          <div className="w-8 h-8 bg-gradient-to-br from-blue-500 to-cyan-400 rounded-lg flex items-center justify-center">
            <LayoutDashboard className="w-5 h-5 text-white" />
          </div>
          <span className="text-xl font-bold tracking-wide">Cutting Optima</span>
        </div>

        <nav className="flex-1 py-6 px-3 space-y-1">
          {navItems.map((item) => (
            <NavLink
              key={item.path}
              to={item.path}
              className={({ isActive }) =>
                `flex items-center gap-3 px-4 py-3 rounded-lg transition-all duration-200 group ${isActive
                  ? 'bg-blue-600 text-white shadow-lg shadow-blue-900/20'
                  : 'text-slate-400 hover:bg-slate-800 hover:text-white'
                }`
              }
            >
              <item.icon className="w-5 h-5 transition-transform group-hover:scale-110" />
              <span className="font-medium">{item.label}</span>
            </NavLink>
          ))}
        </nav>

        <div className="p-4 border-t border-slate-800">
          <div className="bg-slate-800/50 rounded-xl p-4">
            <p className="text-xs text-slate-400 mb-2">系统状态</p>
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 bg-emerald-500 rounded-full animate-pulse"></span>
              <span className="text-sm font-medium text-emerald-400">运行正常</span>
            </div>
          </div>
        </div>
      </aside>

      {/* Main Content */}
      <main className="flex-1 overflow-auto relative">
        <header className="bg-white/80 backdrop-blur-md sticky top-0 z-10 px-8 py-4 border-b border-slate-200 flex justify-between items-center">
          <h2 className="text-xl font-bold text-slate-800">
            {navItems.find(i => i.path === location.pathname)?.label || '控制台'}
          </h2>
          <div className="flex items-center gap-4">
            <span className="text-sm text-slate-500">
              专业切割优化解决方案
            </span>
            <button
              onClick={openSettingsPanel}
              className="p-2 hover:bg-slate-100 rounded-lg transition-colors group"
              title="系统设置"
            >
              <Settings className="w-5 h-5 text-slate-500 group-hover:text-blue-600 transition-colors" />
            </button>
          </div>
        </header>
        <div className="p-8 pb-20">
          {children}
        </div>
      </main>

      {/* Settings Panel */}
      <SettingsPanel />
    </div>
  );
};