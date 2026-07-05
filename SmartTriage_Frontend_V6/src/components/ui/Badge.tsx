import React from 'react';
import { TriageCategory } from '@/types';
import { getCategoryColor } from '@/utils/tewsCalculator';
import { useTheme } from '@/hooks/useTheme';

interface BadgeProps {
  category?: TriageCategory;
  label?: string;
  variant?: 'red' | 'orange' | 'yellow' | 'green' | 'blue' | 'gray';
  size?: 'sm' | 'md' | 'lg';
  icon?: React.ReactNode;
}

export function Badge({ category, label, variant, size = 'md', icon }: BadgeProps) {
  const { isDark } = useTheme();

  const getVariantClass = () => {
    if (category) {
      if (isDark) {
        switch (category) {
          case 'RED':
            return 'bg-red-500/15 text-red-300 border-red-500/30';
          case 'ORANGE':
            return 'bg-orange-500/15 text-orange-300 border-orange-500/30';
          case 'YELLOW':
            return 'bg-yellow-500/15 text-yellow-300 border-yellow-500/30';
          case 'GREEN':
            return 'bg-green-500/15 text-green-300 border-green-500/30';
          case 'BLUE':
            return 'bg-blue-500/15 text-blue-300 border-blue-500/30';
        }
      }
      switch (category) {
        case 'RED':
          return 'bg-red-100 text-red-800 border-red-300';
        case 'ORANGE':
          return 'bg-orange-100 text-orange-800 border-orange-300';
        case 'YELLOW':
          return 'bg-yellow-100 text-yellow-800 border-yellow-300';
        case 'GREEN':
          return 'bg-green-100 text-green-800 border-green-300';
        case 'BLUE':
          return 'bg-blue-100 text-blue-800 border-blue-300';
      }
    }

    if (isDark) {
      switch (variant) {
        case 'red':
          return 'bg-red-500/15 text-red-300 border-red-500/30';
        case 'orange':
          return 'bg-orange-500/15 text-orange-300 border-orange-500/30';
        case 'yellow':
          return 'bg-yellow-500/15 text-yellow-300 border-yellow-500/30';
        case 'green':
          return 'bg-green-500/15 text-green-300 border-green-500/30';
        case 'blue':
          return 'bg-blue-500/15 text-blue-300 border-blue-500/30';
        default:
          return 'bg-slate-500/15 text-slate-300 border-slate-500/30';
      }
    }

    switch (variant) {
      case 'red':
        return 'bg-red-100 text-red-800 border-red-300';
      case 'orange':
        return 'bg-orange-100 text-orange-800 border-orange-300';
      case 'yellow':
        return 'bg-yellow-100 text-yellow-800 border-yellow-300';
      case 'green':
        return 'bg-green-100 text-green-800 border-green-300';
      case 'blue':
        return 'bg-blue-100 text-blue-800 border-blue-300';
      default:
        return 'bg-gray-100 text-gray-800 border-gray-300';
    }
  };

  const getSizeClass = () => {
    switch (size) {
      case 'sm':
        return 'px-2 py-0.5 text-xs';
      case 'lg':
        return 'px-4 py-2 text-base';
      default:
        return 'px-2.5 py-1 text-sm';
    }
  };

  const displayLabel = category || label || '';

  return (
    <span
      className={`inline-flex items-center gap-1 rounded-full font-medium border ${getVariantClass()} ${getSizeClass()}`}
    >
      {icon && <span>{icon}</span>}
      {displayLabel}
    </span>
  );
}
