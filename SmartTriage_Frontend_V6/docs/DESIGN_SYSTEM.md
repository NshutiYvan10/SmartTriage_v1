# SmartTriage Design System — V3 Premium
**King Faisal Hospital · OptiSource KFH**

> A comprehensive design reference for replicating the SmartTriage premium glassmorphism aesthetic across all project modules. Copy-paste ready — every value is production-exact.

---

## Table of Contents

1. [Design Philosophy](#design-philosophy)
2. [Color Palette](#color-palette)
3. [Triage Category Colors](#triage-category-colors)
4. [Glassmorphism System](#glassmorphism-system)
5. [Typography](#typography)
6. [Shadows](#shadows)
7. [Gradients](#gradients)
8. [Component Patterns](#component-patterns)
9. [Animations](#animations)
10. [Layout & Spacing](#layout--spacing)
11. [Icon System](#icon-system)
12. [Avatar System](#avatar-system)
13. [Tailwind Config](#tailwind-config)
14. [CSS Variables Quick Reference](#css-variables-quick-reference)

---

## Design Philosophy

- **Glassmorphism-first**: Frosted glass cards with `backdrop-filter: blur(24px)` layered over soft gradient backgrounds
- **Reduced typography**: Compact text sizes (`text-xs`, `text-[10px]`, `text-[11px]`) for information density
- **Premium feel**: Subtle shadows, inset highlights, smooth cubic-bezier transitions
- **Dark header / light body**: Dark slate-900 gradient headers contrasting with airy glassmorphic content
- **Clinical precision**: Clean layout with clear visual hierarchy; status colors used purposefully

---

## Color Palette

### Primary Colors (Slate)
Used for text, headers, sidebar, and structural elements.

| Token          | Hex       | Usage                              |
|----------------|-----------|-------------------------------------|
| `primary-900`  | `#0f172a` | Sidebar dark, header backgrounds    |
| `primary-800`  | `#1e293b` | Sidebar base, dark gradients        |
| `primary-700`  | `#334155` | Strong text, labels                 |
| `primary-600`  | `#475569` | Secondary text                      |
| `primary-500`  | `#64748b` | Muted text                          |
| `primary-400`  | `#94a3b8` | Placeholder text, icons             |
| `primary-300`  | `#cbd5e1` | Borders, dividers                   |
| `primary-200`  | `#e2e8f0` | Light borders, input borders        |
| `primary-100`  | `#f1f5f9` | Light backgrounds                   |
| `primary-50`   | `#f8fafc` | Page background base                |

### Accent Colors (Cyan/Teal)
The primary brand accent — used for interactive elements, focus states, and highlights.

| Token         | Hex       | Usage                               |
|---------------|-----------|--------------------------------------|
| `accent-900`  | `#164e63` | Deep accent (rare)                   |
| `accent-800`  | `#0c4a6e` | Heavy accent backgrounds             |
| `accent-700`  | `#0369a1` | Avatar backgrounds, strong CTA       |
| `accent-600`  | `#0284c7` | **Primary buttons**, active states   |
| `accent-500`  | `#06b6d4` | Focus rings, gradient endpoints      |
| `accent-400`  | `#22d3ee` | Hover accents, glow effects          |
| `accent-300`  | `#67e8f9` | Light accent highlights              |
| `accent-200`  | `#a5f3fc` | Accent backgrounds (light)           |
| `accent-100`  | `#cffafe` | Badges, info backgrounds             |
| `accent-50`   | `#f0f9fa` | Subtle accent tint                   |

### Status Colors

| Status    | Dark (600)  | Medium (500)  | Light (100) | Usage                        |
|-----------|-------------|---------------|-------------|-------------------------------|
| Success   | `#059669`   | `#10b981`     | `#d1fae5`   | Completed, normal vitals      |
| Warning   | `#d97706`   | `#f59e0b`     | `#fef3c7`   | Alerts, borderline vitals     |
| Danger    | `#dc2626`   | `#ef4444`     | `#fee2e2`   | Critical, emergency, RED      |
| Info      | `#0369a1`   | `#06b6d4`     | `#cffafe`   | Informational badges          |

### Neutral Colors

| Token          | Hex       | Usage                  |
|----------------|-----------|-------------------------|
| `neutral-900`  | `#0a0a0a` | Darkest text            |
| `neutral-800`  | `#1a1a1a` | Body text default       |
| `neutral-700`  | `#2a2a2a` | Heavy text              |
| `neutral-500`  | `#595959` | Muted content           |
| `neutral-400`  | `#858585` | Disabled text           |
| `neutral-200`  | `#d9d9d9` | Light borders           |
| `neutral-50`   | `#f5f5f5` | Lightest backgrounds    |

### Surface Colors

| Token              | Value      | Usage                |
|--------------------|------------|----------------------|
| `surface-primary`  | `#ffffff`  | Card backgrounds     |
| `surface-secondary`| `#f8fafc`  | Page background      |
| `surface-tertiary` | `#f1f5f9`  | Nested backgrounds   |

---

## Triage Category Colors

These are the four clinical triage severity levels. Used on category badges, cards, borders, and backgrounds.

| Category   | Background Class   | Hex       | Border Class          | Light BG Class   | Text Color   |
|------------|--------------------|-----------|-----------------------|------------------|--------------|
| **RED**    | `bg-red-600`       | `#dc2626` | `border-red-500`      | `bg-red-50`      | `#dc2626`    |
| **ORANGE** | `bg-orange-500`    | `#f97316` | `border-orange-400`   | `bg-orange-50`   | `#ea580c`    |
| **YELLOW** | `bg-yellow-400`    | `#facc15` | `border-yellow-400`   | `bg-yellow-50`   | `#ca8a04`    |
| **GREEN**  | `bg-green-500`     | `#22c55e` | `border-green-400`    | `bg-green-50`    | `#16a34a`    |

**Category pattern in code:**
```tsx
const categoryColor = { RED: 'bg-red-600', ORANGE: 'bg-orange-500', YELLOW: 'bg-yellow-400', GREEN: 'bg-green-500' };
const categoryBorder = { RED: 'border-red-500', ORANGE: 'border-orange-400', YELLOW: 'border-yellow-400', GREEN: 'border-green-400' };
const categoryBg = { RED: 'bg-red-50', ORANGE: 'bg-orange-50', YELLOW: 'bg-yellow-50', GREEN: 'bg-green-50' };
```

---

## Glassmorphism System

The core visual identity. Every card and container uses this layered glass effect.

### glassCard (Primary container)
```css
background: rgba(255, 255, 255, 0.55);
backdrop-filter: blur(24px);
-webkit-backdrop-filter: blur(24px);
border: 1px solid rgba(255, 255, 255, 0.6);
box-shadow: 0 8px 32px rgba(0, 0, 0, 0.04),
            inset 0 1px 0 rgba(255, 255, 255, 0.8);
```

### glassInner (Nested elements, alerts, sub-cards)
```css
background: rgba(255, 255, 255, 0.6);
border: 1px solid rgba(203, 213, 225, 0.4);
box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04),
            0 1px 2px rgba(0, 0, 0, 0.03);
```

### CSS Utility Classes
```css
.glass-card {
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: blur(24px);
  border: 1px solid rgba(255, 255, 255, 0.6);
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.03),
              inset 0 1px 0 rgba(255, 255, 255, 0.8);
}

.glass-card-dark {
  background: rgba(15, 23, 42, 0.05);
  backdrop-filter: blur(24px);
  border: 1px solid rgba(100, 116, 139, 0.1);
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.02),
              inset 0 1px 0 rgba(255, 255, 255, 0.4);
}
```

### React CSSProperties (copy-paste ready)
```tsx
const glassCard: React.CSSProperties = {
  background: 'rgba(255,255,255,0.55)',
  backdropFilter: 'blur(24px)',
  WebkitBackdropFilter: 'blur(24px)',
  border: '1px solid rgba(255,255,255,0.6)',
  boxShadow: '0 8px 32px rgba(0,0,0,0.04), inset 0 1px 0 rgba(255,255,255,0.8)',
};

const glassInner: React.CSSProperties = {
  background: 'rgba(255,255,255,0.6)',
  border: '1px solid rgba(203,213,225,0.4)',
  boxShadow: '0 2px 8px rgba(0,0,0,0.04), 0 1px 2px rgba(0,0,0,0.03)',
};
```

---

## Typography

### Font Stack
```
Primary: 'Plus Jakarta Sans', system-ui, Avenir, Helvetica, Arial, sans-serif
Fallback: 'Inter', system-ui, -apple-system, sans-serif
```

### Size Scale (Tailwind classes)
Reduced typography is a key design feature — we use compact sizes throughout.

| Class          | Size   | Usage                                      |
|----------------|--------|---------------------------------------------|
| `text-[9px]`   | 9px    | Micro labels, unit suffixes, score labels   |
| `text-[10px]`  | 10px   | Uppercase labels, sub-text, validation      |
| `text-[11px]`  | 11px   | Info bars, button text, step indicators      |
| `text-xs`      | 12px   | **Default body text**, form inputs, badges  |
| `text-sm`      | 14px   | Section headers, important labels           |
| `text-base`    | 16px   | Page titles (in header)                     |
| `text-lg`      | 18px   | Score displays                              |
| `text-xl`      | 20px   | Timer displays, category names              |

### Label Pattern
```tsx
const labelCls = 'block text-[10px] font-semibold text-slate-500 uppercase tracking-wider mb-0.5';
```

### Input Pattern
```tsx
const inputCls = 'w-full px-2.5 py-1.5 bg-white/80 border border-slate-200/60 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-400 transition-all placeholder:text-slate-400';
```

### Select Pattern
```tsx
const selectCls = 'w-full px-2.5 py-1.5 bg-white/80 border border-slate-200/60 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-400 appearance-none transition-all';
```

---

## Shadows

### CSS Variable Shadows
```css
--shadow-xs:  0 1px 2px 0 rgba(0, 0, 0, 0.05);
--shadow-sm:  0 1px 3px 0 rgba(0, 0, 0, 0.1), 0 1px 2px 0 rgba(0, 0, 0, 0.06);
--shadow-md:  0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
--shadow-lg:  0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05);
--shadow-xl:  0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
--shadow-2xl: 0 25px 50px -12px rgba(0, 0, 0, 0.15);
```

### Premium Tailwind Shadows
```
shadow-premium:    0 1px 3px rgba(0,0,0,0.04), 0 4px 12px rgba(0,0,0,0.06), 0 0 0 1px rgba(0,0,0,0.03)
shadow-premium-lg: 0 8px 30px rgba(0,0,0,0.08), 0 2px 8px rgba(0,0,0,0.04), inset 0 1px 0 rgba(255,255,255,0.6)
shadow-glow-green: 0 0 20px rgba(2, 132, 199, 0.3), 0 0 60px rgba(2, 132, 199, 0.1)
shadow-card-hover: 0 20px 40px -12px rgba(2, 132, 199, 0.15), 0 8px 20px -8px rgba(0, 0, 0, 0.1)
```

---

## Gradients

### Page Background
```css
background: linear-gradient(135deg, #fafbfd 0%, #f3f6fb 25%, #f0f4f9 50%, #f5f8fc 75%, #fafbfd 100%);
background-attachment: fixed;
```
Tailwind shorthand: `bg-gradient-to-br from-slate-50/80 via-cyan-50/30 to-slate-100/80`

### Dark Header Gradient
```
bg-gradient-to-r from-slate-900 via-slate-800 to-slate-900
```
Used on: Page headers, card title bars.

### Sidebar Gradient
```css
background: linear-gradient(180deg, #1e293b 0%, #0f172a 100%);
```

### Primary Button Gradient
```css
background: linear-gradient(135deg, var(--accent-600) 0%, var(--accent-500) 100%);
/* → linear-gradient(135deg, #0284c7 0%, #06b6d4 100%) */
```
Tailwind: `bg-gradient-to-r from-cyan-600 to-slate-800` (for finish/CTA buttons)

### Gradient Text
```css
.gradient-text {
  background: linear-gradient(135deg, #0284c7 0%, #06b6d4 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}
```

### Info Bar Gradients
- **Adult**: `bg-gradient-to-r from-cyan-50/80 to-cyan-100/60` with `text-cyan-700`
- **Pediatric**: `bg-gradient-to-r from-pink-50/80 to-pink-100/60` with `text-pink-700`

### Background Mesh (subtle ambient)
```css
radial-gradient(at 40% 20%, rgba(2, 132, 199, 0.08) 0px, transparent 50%),
radial-gradient(at 80% 0%, rgba(6, 182, 212, 0.06) 0px, transparent 50%),
radial-gradient(at 0% 50%, rgba(2, 132, 199, 0.04) 0px, transparent 50%)
```

---

## Component Patterns

### Card with Dark Header
The most common page-level component pattern:
```tsx
<div className="rounded-2xl overflow-hidden shadow-xl" style={glassCard}>
  {/* Dark header */}
  <div className="bg-gradient-to-r from-slate-900 via-slate-800 to-slate-900 px-5 py-4 flex items-center justify-between">
    <div className="flex items-center gap-3">
      <div className="w-10 h-10 bg-white/10 rounded-xl flex items-center justify-center backdrop-blur-sm border border-white/10">
        <Icon className="w-5 h-5 text-cyan-400" />
      </div>
      <div>
        <h1 className="text-base font-bold text-white tracking-wide">Title</h1>
        <p className="text-white/50 text-[11px]">Subtitle</p>
      </div>
    </div>
  </div>
  {/* Content */}
  <div className="p-4">
    {/* ... */}
  </div>
</div>
```

### Section Card (no dark header)
```tsx
<div className="rounded-2xl p-4" style={glassCard}>
  <div className="flex items-center gap-1.5 mb-3">
    <Icon className="w-3.5 h-3.5 text-cyan-600" />
    <h3 className="text-xs font-bold text-slate-800">Section Title</h3>
  </div>
  {/* Content */}
</div>
```

### Alert Strip
```tsx
<div className="flex items-center gap-2.5 rounded-xl px-3.5 py-2"
     style={{ ...glassInner, background: 'rgba(254,226,226,0.6)', border: '1px solid rgba(252,165,165,0.4)' }}>
  <AlertTriangle className="w-3.5 h-3.5 text-red-600 flex-shrink-0" />
  <span className="text-xs font-semibold text-red-800">{message}</span>
</div>
```

### Emergency Banner (animated)
```tsx
<div className="bg-red-600 text-white px-4 py-2.5 flex items-center gap-2.5 animate-pulse rounded-2xl shadow-lg shadow-red-500/20">
  <AlertTriangle className="w-5 h-5 flex-shrink-0" />
  <span className="font-bold text-xs">EMERGENCY — Message</span>
</div>
```

### Primary Button
```tsx
<button className="px-5 py-2 bg-gradient-to-r from-cyan-600 to-slate-800 text-white rounded-xl text-xs font-bold hover:shadow-xl transition-all hover:-translate-y-0.5">
  Action
</button>
```

### Secondary Button
```tsx
<button className="px-4 py-2 bg-white/80 border border-slate-200/60 text-slate-700 rounded-xl text-xs font-semibold hover:bg-white hover:shadow-md transition-all">
  Back
</button>
```

### Form Input
```tsx
<div>
  <label className="block text-[10px] font-semibold text-slate-500 uppercase tracking-wider mb-0.5">
    Field Label
  </label>
  <input
    type="text"
    className="w-full px-2.5 py-1.5 bg-white/80 border border-slate-200/60 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-400 transition-all placeholder:text-slate-400"
  />
</div>
```

### Badge / Tag
```tsx
<span className="text-[10px] bg-green-100 text-green-700 px-2 py-0.5 rounded-full font-medium">
  Pre-loaded
</span>
```

### Category Badge (in header)
```tsx
<div className={`rounded-xl px-3.5 py-1.5 flex items-center gap-2 border-2 shadow-md ${categoryBorder[cat]} bg-white/95`}>
  <span className={`w-2.5 h-2.5 rounded-full ${categoryColor[cat]}`} />
  <span className="text-xs font-bold text-gray-900">TEWS: {score}</span>
  <span className="text-xs font-bold" style={{ color: catTextColor }}>{cat}</span>
</div>
```

### Sidebar Navigation Item
```tsx
{/* Active state */}
<div className="sidebar-item sidebar-item-active">
  <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-cyan-500 to-cyan-600 shadow-lg shadow-cyan-500/30 flex items-center justify-center">
    <Icon className="w-4 h-4 text-white" />
  </div>
  <span>Label</span>
</div>

{/* Inactive state */}
<div className="sidebar-item">
  <div className="w-8 h-8 rounded-lg bg-white/8 flex items-center justify-center">
    <Icon className="w-4 h-4 text-white/60" />
  </div>
  <span>Label</span>
</div>
```

### Pagination Dots (for stepped content)
```tsx
<div className="flex items-center gap-1">
  {pages.map((_, i) => (
    <button
      key={i}
      onClick={() => setPage(i)}
      className={`w-6 h-6 rounded-full text-[9px] font-bold transition-all flex items-center justify-center ${
        i === currentPage
          ? 'bg-cyan-600 text-white shadow-md shadow-cyan-500/30 scale-110'
          : hasContent
            ? 'bg-cyan-100 text-cyan-700 hover:bg-cyan-200'
            : 'bg-slate-100 text-slate-400 hover:bg-slate-200'
      }`}
    >
      {i + 1}
    </button>
  ))}
</div>
```

---

## Animations

### Easing
All transitions use: `cubic-bezier(0.4, 0, 0.2, 1)` (default)  
Entrance animations use: `cubic-bezier(0.16, 1, 0.3, 1)` (smooth overshoot)

### Core Animations
| Class                   | Effect                     | Duration |
|-------------------------|----------------------------|----------|
| `animate-fade-in`       | Fade + slide down 8px      | 0.6s     |
| `animate-fade-up`       | Fade + slide up 16px       | 0.6s     |
| `animate-fade-down`     | Fade + slide down 16px     | 0.6s     |
| `animate-premium-pulse` | Subtle opacity pulse       | 2s loop  |
| `animate-bounce-gentle` | Gentle bounce 4px          | 2s loop  |
| `animate-float`         | Float up/down 8px          | 3s loop  |
| `animate-critical-pulse`| Red glow pulse (emergencies)| 2s loop |
| `animate-number-pop`    | Scale + slide number entry | 0.7s     |
| `animate-slide-up`      | Slide up 20px entrance     | 0.5s     |
| `animate-scale-in`      | Scale from 0.9 → 1         | 0.3s     |
| `shimmer`               | Loading shimmer sweep      | 2s loop  |

### Staggered Children
Add `stagger-children` to a parent to auto-animate children with 40ms stagger:
```html
<div class="stagger-children">
  <div>Item 1</div>  <!-- delay: 0.04s -->
  <div>Item 2</div>  <!-- delay: 0.08s -->
  <div>Item 3</div>  <!-- delay: 0.12s -->
</div>
```

### Hover Effects
```css
/* Lift on hover */
hover:-translate-y-0.5  /* buttons: subtle 2px lift */
hover:-translate-y-1    /* cards: 4px lift */

/* Scale on hover */
.hover-scale:hover { transform: scale(1.02); }
```

---

## Layout & Spacing

### Page Container
```tsx
<div className="min-h-full bg-gradient-to-br from-slate-50/80 via-cyan-50/30 to-slate-100/80">
  <div className="p-4 max-w-5xl mx-auto space-y-4">
    {/* Page content */}
  </div>
</div>
```

### Card Corner Radius
- Cards: `rounded-2xl` (16px)
- Buttons: `rounded-xl` (12px)
- Inputs: `rounded-lg` (8px)
- Badges: `rounded-full` or `rounded-lg`
- Pagination dots: `rounded-full`

### Standard Gaps
| Context          | Class     | Value |
|------------------|-----------|-------|
| Grid items       | `gap-3`   | 12px  |
| Section spacing  | `space-y-4` | 16px |
| Inner padding    | `p-4`    | 16px  |
| Header padding   | `px-5 py-4` | 20px/16px |
| Input padding    | `px-2.5 py-1.5` | 10px/6px |
| Button padding   | `px-5 py-2` | 20px/8px |

### Common Grid Layouts
```tsx
{/* Form fields — 4 columns on large, 2 columns on small */}
<div className="grid grid-cols-2 lg:grid-cols-4 gap-3">

{/* Vitals — 3 columns on medium */}
<div className="grid grid-cols-2 md:grid-cols-3 gap-3">

{/* Content + sidebar (2/3 + 1/3) */}
<div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
  <div className="lg:col-span-2">Main</div>
  <div>Side</div>
</div>
```

---

## Icon System

**Library**: [Lucide React](https://lucide.dev) (`lucide-react`)

### Standard Icons Used

| Icon              | Context                              |
|-------------------|---------------------------------------|
| `Shield`          | Header icon (triage forms)            |
| `Stethoscope`     | Vitals, queue header                  |
| `Activity`        | TEWS scoring, deterioration           |
| `AlertTriangle`   | Emergency, RED category               |
| `AlertCircle`     | ORANGE category, emergency signs      |
| `Clock`           | YELLOW category, time displays        |
| `CheckCircle`     | GREEN category, completed states      |
| `Heart`           | Circulation, heart rate               |
| `Wind`            | Airway, breathing                     |
| `Brain`           | Disability, neurological              |
| `Eye`             | Exposure section                      |
| `Timer`           | Treatment timer                       |
| `Bell`            | Doctor notification                   |
| `Save`            | Finish/submit actions                 |
| `ArrowLeft`       | Back navigation                       |
| `ChevronLeft/Right` | Pagination controls                 |
| `ChevronDown/Up`  | Collapsible sections                  |
| `FileText`        | Patient information                   |
| `User`            | Triage details, nurse info            |
| `Users`           | Adult patient indicator               |
| `Baby`            | Pediatric patient indicator           |
| `UserPlus`        | Register patient                      |

### Icon Sizing Convention
| Context                | Class              |
|------------------------|--------------------|
| Header icon (shield)   | `w-5 h-5`         |
| Section header icon    | `w-4 h-4`         |
| Inline label icon      | `w-3.5 h-3.5`     |
| Small/badge icon       | `w-3 h-3`         |
| Category result icon   | `w-6 h-6`         |

---

## Avatar System

**Provider**: [DiceBear Avataaars](https://dicebear.com)

```
https://api.dicebear.com/7.x/avataaars/svg?seed=DrAdmin&backgroundColor=0369a1&radius=12
```

| Parameter        | Value       | Notes                |
|------------------|-------------|----------------------|
| `seed`           | `DrAdmin`   | Consistent avatar    |
| `backgroundColor`| `0369a1`   | accent-700 (#0369a1) |
| `radius`         | `12`        | Rounded corners      |

Container:
```tsx
<img
  src="https://api.dicebear.com/7.x/avataaars/svg?seed=DrAdmin&backgroundColor=0369a1&radius=12"
  alt="User"
  className="w-9 h-9 rounded-xl border-2 border-white/20"
/>
```

---

## Tailwind Config

### Custom Colors (add to `tailwind.config.js` `theme.extend.colors`)
```js
colors: {
  primary: {
    50: '#f8fafc', 100: '#f1f5f9', 200: '#e2e8f0', 300: '#cbd5e1',
    400: '#94a3b8', 500: '#64748b', 600: '#475569', 700: '#334155',
    800: '#1e293b', 900: '#0f172a',
  },
  accent: {
    50: '#f0f9fa', 100: '#cffafe', 200: '#a5f3fc', 300: '#67e8f9',
    400: '#22d3ee', 500: '#06b6d4', 600: '#0284c7', 700: '#0369a1',
    800: '#0c4a6e', 900: '#164e63',
  },
  sidebar: {
    DEFAULT: '#1e293b', dark: '#0f172a', light: '#334155',
  },
  triage: {
    red: '#ef4444', orange: '#f97316', yellow: '#eab308', green: '#22c55e', blue: '#3b82f6',
  },
}
```

### Custom Shadows
```js
boxShadow: {
  'glow-green': '0 0 20px rgba(2, 132, 199, 0.3), 0 0 60px rgba(2, 132, 199, 0.1)',
  'card-hover': '0 20px 40px -12px rgba(2, 132, 199, 0.15), 0 8px 20px -8px rgba(0, 0, 0, 0.1)',
  'premium': '0 1px 3px rgba(0,0,0,0.04), 0 4px 12px rgba(0,0,0,0.06), 0 0 0 1px rgba(0,0,0,0.03)',
  'premium-lg': '0 8px 30px rgba(0,0,0,0.08), 0 2px 8px rgba(0,0,0,0.04), inset 0 1px 0 rgba(255,255,255,0.6)',
}
```

---

## CSS Variables Quick Reference

Copy this block into any module's root CSS to get the full palette:

```css
:root {
  /* Primary (Slate) */
  --primary-900: #0f172a;
  --primary-800: #1e293b;
  --primary-700: #334155;
  --primary-600: #475569;
  --primary-500: #64748b;
  --primary-400: #94a3b8;
  --primary-300: #cbd5e1;
  --primary-200: #e2e8f0;
  --primary-100: #f1f5f9;
  --primary-50:  #f8fafc;

  /* Accent (Cyan) */
  --accent-900: #164e63;
  --accent-800: #0c4a6e;
  --accent-700: #0369a1;
  --accent-600: #0284c7;
  --accent-500: #06b6d4;
  --accent-400: #22d3ee;
  --accent-300: #67e8f9;
  --accent-200: #a5f3fc;
  --accent-100: #cffafe;
  --accent-50:  #f0f9fa;

  /* Status */
  --success-600: #059669;
  --success-500: #10b981;
  --success-100: #d1fae5;
  --warning-600: #d97706;
  --warning-500: #f59e0b;
  --warning-100: #fef3c7;
  --danger-600:  #dc2626;
  --danger-500:  #ef4444;
  --danger-100:  #fee2e2;
  --info-600:    #0369a1;
  --info-500:    #06b6d4;
  --info-100:    #cffafe;

  /* Surfaces */
  --surface-primary:   #ffffff;
  --surface-secondary: #f8fafc;
  --surface-tertiary:  #f1f5f9;

  /* Glass */
  --glass-bg:     rgba(255, 255, 255, 0.8);
  --glass-border: rgba(255, 255, 255, 0.6);
  --glass-shadow: rgba(0, 0, 0, 0.02);

  /* Shadows */
  --shadow-xs:  0 1px 2px 0 rgba(0, 0, 0, 0.05);
  --shadow-sm:  0 1px 3px 0 rgba(0, 0, 0, 0.1), 0 1px 2px 0 rgba(0, 0, 0, 0.06);
  --shadow-md:  0 4px 6px -1px rgba(0, 0, 0, 0.1), 0 2px 4px -1px rgba(0, 0, 0, 0.06);
  --shadow-lg:  0 10px 15px -3px rgba(0, 0, 0, 0.1), 0 4px 6px -2px rgba(0, 0, 0, 0.05);
  --shadow-xl:  0 20px 25px -5px rgba(0, 0, 0, 0.1), 0 10px 10px -5px rgba(0, 0, 0, 0.04);
  --shadow-2xl: 0 25px 50px -12px rgba(0, 0, 0, 0.15);
}
```

---

## Key Design Rules

1. **Always use glassmorphism** — every card/container uses `glassCard` or `glass-card` class
2. **Dark headers** — page-level cards get `from-slate-900 via-slate-800 to-slate-900` gradient bars
3. **Compact typography** — default to `text-xs`; labels are `text-[10px] uppercase tracking-wider`
4. **Cyan accent** — interactive elements, focus rings, active states all use `cyan-500/600`
5. **Subtle shadows** — prefer `rgba(0,0,0,0.04)` over heavy shadows
6. **Inset top highlights** — `inset 0 1px 0 rgba(255,255,255,0.8)` on glass surfaces
7. **Rounded everything** — cards `rounded-2xl`, buttons `rounded-xl`, inputs `rounded-lg`
8. **Smooth transitions** — all with `transition-all` and `duration-300` or `duration-500`
9. **Hover lift** — buttons get `hover:-translate-y-0.5`, cards get `hover:-translate-y-1`
10. **No harsh borders** — borders use `/40` or `/60` opacity: `border-slate-200/60`

---

*Document generated February 27, 2026 — SmartTriage V3 Premium Design System*
