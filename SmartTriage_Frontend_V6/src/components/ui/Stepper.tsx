import React from 'react';
import { Check } from 'lucide-react';

interface Step {
  label: string;
  description?: string;
}

interface StepperProps {
  steps: Step[];
  currentStep: number;
  onStepClick?: (stepIndex: number) => void;
  allowSkip?: boolean;
}

export function Stepper({ steps, currentStep, onStepClick, allowSkip = false }: StepperProps) {
  return (
    <div className="w-full py-4">
      <div className="flex items-center justify-between">
        {steps.map((step, index) => (
          <React.Fragment key={index}>
            <div className="flex flex-col items-center flex-1">
              <button
                onClick={() => allowSkip && onStepClick?.(index)}
                disabled={!allowSkip || index > currentStep}
                className={`
                  w-10 h-10 rounded-full flex items-center justify-center font-semibold text-sm transition-all
                  ${
                    index < currentStep
                      ? 'bg-primary-600 text-white'
                      : index === currentStep
                      ? 'bg-primary-600 text-white ring-4 ring-primary-200'
                      : 'bg-gray-200 text-gray-600'
                  }
                  ${allowSkip ? 'cursor-pointer hover:scale-110' : ''}
                `}
              >
                {index < currentStep ? <Check className="w-5 h-5" /> : index + 1}
              </button>
              <div className="mt-2 text-center">
                <div
                  className={`text-sm font-medium ${
                    index === currentStep ? 'text-primary-600' : 'text-gray-600'
                  }`}
                >
                  {step.label}
                </div>
                {step.description && (
                  <div className="text-xs text-gray-500 mt-1">{step.description}</div>
                )}
              </div>
            </div>

            {index < steps.length - 1 && (
              <div
                className={`h-1 flex-1 mx-2 rounded transition-all duration-300 ${
                  index < currentStep ? 'bg-primary-600' : 'bg-gray-200'
                }`}
              />
            )}
          </React.Fragment>
        ))}
      </div>
    </div>
  );
}
