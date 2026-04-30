import React from 'react';

export const Card: React.FC<{ children: React.ReactNode; className?: string; title?: React.ReactNode }> = ({ children, className = '', title }) => {
  return (
    <div className={`bg-white/80 backdrop-blur-md border border-white/20 rounded-2xl shadow-xl p-6 ${className}`}>
        {title && (
            <div className="mb-6 flex items-center gap-3">
                <div className="h-8 w-1.5 bg-gradient-to-b from-blue-400 to-cyan-400 rounded-full"></div>
                <h2 className="text-xl font-semibold text-slate-800">{title}</h2>
            </div>
        )}
      {children}
    </div>
  );
};