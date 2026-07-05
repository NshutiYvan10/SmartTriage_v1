import React from 'react';
import { Activity, TrendingUp, TrendingDown, Minus } from 'lucide-react';
import { VitalReading } from '@/types';

interface VitalCardProps {
  label: string;
  value: number;
  unit: string;
  icon?: React.ReactNode;
  trend?: VitalReading[];
  status?: 'normal' | 'warning' | 'critical';
  threshold?: { min?: number; max?: number };
}

export function VitalCard({
  label,
  value,
  unit,
  icon,
  trend = [],
  status = 'normal',
  threshold,
}: VitalCardProps) {
  const getStatusColor = () => {
    switch (status) {
      case 'critical':
        return 'border-red-500 bg-red-50';
      case 'warning':
        return 'border-yellow-500 bg-yellow-50';
      default:
        return 'border-green-500 bg-white';
    }
  };

  const getTrendIcon = () => {
    if (trend.length < 2) return <Minus className="w-4 h-4 text-gray-400" />;
    
    const first = trend[0].value;
    const last = trend[trend.length - 1].value;
    const change = ((last - first) / first) * 100;

    if (Math.abs(change) < 5) {
      return <Minus className="w-4 h-4 text-gray-400" />;
    } else if (change > 0) {
      return <TrendingUp className="w-4 h-4 text-red-500" />;
    } else {
      return <TrendingDown className="w-4 h-4 text-blue-500" />;
    }
  };

  return (
    <div className={`vital-card ${getStatusColor()} transition-all duration-300`}>
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          {icon || <Activity className="w-5 h-5 text-gray-600" />}
          <span className="text-sm font-medium text-gray-700">{label}</span>
        </div>
        {getTrendIcon()}
      </div>

      <div className="flex items-baseline gap-2">
        <span className="text-3xl font-bold text-gray-900">{value}</span>
        <span className="text-sm text-gray-600">{unit}</span>
      </div>

      {threshold && (
        <div className="mt-2 text-xs text-gray-500">
          Normal: {threshold.min ? `${threshold.min}-` : ''}
          {threshold.max || ''}
        </div>
      )}

      {trend.length > 0 && (
        <div className="mt-3">
          <div className="flex items-end gap-1 h-8">
            {trend.map((reading, i) => (
              <div
                key={i}
                className="flex-1 bg-primary-400 rounded-t"
                style={{
                  height: `${(reading.value / Math.max(...trend.map(r => r.value))) * 100}%`,
                  minHeight: '4px',
                }}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
