# SmartTriage Frontend - Emergency Department Triage System

A comprehensive hospital emergency department triage system built with React, TypeScript, and Zustand. This system implements the **Rwanda mSAT (Modified South African Triage)** protocol with IoT vital monitoring and AI-powered dynamic re-triage.

## 🏥 Features

### Core Modules

1. **Entry & Registration**
   - Patient demographic capture
   - Immutable arrival timestamp
   - Automatic pediatric mode detection (age < 15)
   - Referral tracking with document upload
   - Chief complaint with quick-pick options

2. **mSAT Triage Assessment**
   - Step-by-step guided triage workflow
   - Emergency signs checklist (hard stop)
   - TEWS (Triage Early Warning Score) calculator
   - Automatic category assignment (RED/ORANGE/YELLOW/GREEN/BLUE)
   - Real-time score visualization

3. **IoT Vital Monitoring**
   - Simulated continuous vital sign monitoring (updates every 2 seconds)
   - Live trend graphs (last 10 readings)
   - Vital cards with status indicators
   - Threshold breach alerts
   - Device connection status

4. **Dynamic Re-triage AI**
   - Rate-of-change detection
   - Automatic escalation recommendations
   - AI alert panel with acknowledgment
   - Contributing factors analysis

5. **Pediatric Logic Engine**
   - Age-adjusted vital sign thresholds
   - Weight tracking (mandatory)
   - Visual pediatric mode indicators
   - Age-specific TEWS scoring

6. **Dashboard**
   - Real-time patient queue
   - Category distribution overview
   - Department-level statistics
   - Active alerts feed
   - Timer tracking per category

## 🎨 Design

The UI follows the provided energy management system design structure with:
- Sidebar navigation (green color scheme)
- Card-based layouts
- Live graphs and charts
- Clean, clinical aesthetic
- Real-time data visualization

## 🚀 Getting Started

### Prerequisites

- Node.js 18+ 
- npm or yarn

### Installation

```bash
# Install dependencies
npm install

# Start development server
npm run dev

# Build for production
npm run build

# Preview production build
npm run preview
```

The development server will start at `http://localhost:5173`

## 📁 Project Structure

```
src/
├── components/
│   ├── ui/              # Reusable UI components
│   │   ├── Badge.tsx
│   │   ├── VitalCard.tsx
│   │   ├── AlertPanel.tsx
│   │   ├── Stepper.tsx
│   │   └── ScoreDisplay.tsx
│   └── Sidebar.tsx
├── modules/
│   ├── entry/           # Patient registration
│   ├── triage/          # mSAT triage workflow
│   ├── vitals/          # Vital monitoring
│   └── dashboard/       # Main dashboard
├── store/
│   ├── patientStore.ts  # Patient state management
│   ├── vitalStore.ts    # Vital signs state
│   └── alertStore.ts    # AI alerts state
├── hooks/
│   ├── useVitalSimulator.ts
│   ├── useTEWSCalculator.ts
│   └── useDynamicRetriage.ts
├── utils/
│   ├── tewsCalculator.ts
│   ├── pediatricAdjustments.ts
│   └── emergencySigns.ts
├── types/
│   └── index.ts         # TypeScript definitions
├── App.tsx
├── main.tsx
└── index.css
```

## 🔐 mSAT Protocol Rules

### Emergency Signs (Immediate RED)
If ANY present, patient gets **RED** category immediately:
- Airway compromise
- Coma (AVPU = P/U)
- Severe respiratory distress
- Severe burns
- Shock signs
- Active convulsions
- Hypoglycemia with altered consciousness

### TEWS Scoring
**Critical Rules:**
- **SpO₂ < 92%** → Automatic RED (regardless of TEWS)
- **TEWS ≥ 7** → RED
- **TEWS 5-6** → ORANGE
- **TEWS 0-4** → Proceed to urgent/very urgent checklists

**Scoring Parameters:**
- Mobility (0-2 points)
- Temperature (0-2 points)
- Respiratory Rate (0-2 points)
- AVPU consciousness level (0-2 points)
- Pulse (0-2 points)
- Trauma (0-1 point)
- Systolic BP (0-2 points)

### Pediatric Adjustments
- **Infants (<1 year):** SpO₂ <94% → ORANGE minimum
- Age-specific vital ranges applied automatically
- Weight tracking mandatory
- Adjusted HR, RR, BP thresholds

## 🎯 Category Time Limits
- **RED:** Immediate (0 minutes)
- **ORANGE:** 15 minutes
- **YELLOW:** 60 minutes
- **GREEN:** 120 minutes
- **BLUE:** 240 minutes

## 🛡️ Safety Features

- **No manual category override** without senior clinician approval
- **Hard stops** on required fields
- **Immutable timestamps**
- **Audit trail** for all overrides
- **AI monitoring** for deterioration
- **Real-time threshold alerts**

## 🧪 Technology Stack

- **React 18** - UI framework
- **TypeScript** - Type safety
- **Zustand** - State management
- **React Router** - Navigation
- **Tailwind CSS** - Styling
- **Recharts** - Data visualization (included but ready for expansion)
- **date-fns** - Date utilities
- **Lucide React** - Icon library
- **Vite** - Build tool

## 📊 Future Enhancements

- Historical reports and analytics
- ECG monitoring integration
- Multi-language support
- Print/export functionality
- Staff authentication and roles
- Electronic health record (EHR) integration
- SMS/notification system for family
- Bed management integration

## 📝 License

This is a demonstration project for educational purposes.

## 👥 Contributors

Built following the Rwanda mSAT clinical protocol guidelines.

---

**Note:** This system is designed for demonstration purposes. For production use, ensure proper clinical validation, testing, and compliance with local healthcare regulations.
