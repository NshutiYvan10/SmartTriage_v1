import React from 'react';
import { TriageCategory } from '@/types';
import { getCategoryColor } from '@/utils/tewsCalculator';

interface ScoreDisplayProps {
  score: number;
  maxScore?: number;
  category: TriageCategory;
  riskLevel: 'Low' | 'Moderate' | 'High' | 'Critical';
  size?: 'sm' | 'md' | 'lg';
  showLabel?: boolean;
}

export function ScoreDisplay({
  score,
  maxScore = 14,
  category,
  riskLevel,
  size = 'md',
  showLabel = true,
}: ScoreDisplayProps) {
  const getSize = () => {
    switch (size) {
      case 'sm':
        return 'w-24 h-24';
      case 'lg':
        return 'w-40 h-40';
      default:
        return 'w-32 h-32';
    }
  };

  const getTextSize = () => {
    switch (size) {
      case 'sm':
        return 'text-3xl';
      case 'lg':
        return 'text-6xl';
      default:
        return 'text-4xl';
    }
  };

  const categoryColor = getCategoryColor(category);

  return (
    <div className="flex flex-col items-center gap-4">
      <div
        className={`${getSize()} rounded-full flex flex-col items-center justify-center border-4 transition-all duration-300`}
        style={{ borderColor: categoryColor, backgroundColor: `${categoryColor}15` }}
      >
        <div className={`${getTextSize()} font-bold`} style={{ color: categoryColor }}>
          {score}
        </div>
        <div className="text-xs text-gray-600 uppercase tracking-wide">/ {maxScore}</div>
      </div>

      {showLabel && (
        <div className="text-center">
          <div className="flex items-center gap-2 mb-1">
            <span
              className="px-4 py-1 rounded-full text-white font-semibold text-sm"
              style={{ backgroundColor: categoryColor }}
            >
              {category}
            </span>
          </div>
          <div className="text-sm text-gray-600">
            Risk Level: <span className="font-semibold">{riskLevel}</span>
          </div>
        </div>
      )}
    </div>
  );
}
