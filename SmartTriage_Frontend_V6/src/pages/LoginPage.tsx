import { useState, useRef, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Eye, EyeOff, AlertCircle, Loader2,
  Activity, Heart, Zap, Monitor,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';

/* ═══════════════════════════════════════════════════════════════════
   EcgBackdrop — animated ECG trace that sweeps across the full
   background behind the login card for that "hospital monitor" feel.
   ═══════════════════════════════════════════════════════════════════ */
function EcgBackdrop() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animRef = useRef(0);
  const headRef = useRef(0);
  const lastRef = useRef(0);
  const bufRef = useRef<Float32Array>(new Float32Array(0));
  const cIdxRef = useRef(0);
  const cAccRef = useRef(0);
  const cycleRef = useRef<{ t: number; y: number }[]>([]);

  const buildCycle = useCallback(() => {
    const pts: { t: number; y: number }[] = [];
    const dt = 2;
    let t = 0;
    // P wave
    for (; t < 80; t += dt) {
      pts.push({ t, y: 0.12 * Math.exp(-0.5 * ((t - 40) / 20) ** 2) });
    }
    // PR
    for (; t < 120; t += dt) pts.push({ t, y: 0 });
    // Q
    const qs = t;
    for (; t < qs + 12; t += dt) pts.push({ t, y: -0.10 * Math.sin(((t - qs) / 12) * Math.PI) });
    // R
    const rs = t;
    for (; t < rs + 20; t += dt) pts.push({ t, y: -0.10 + 1.1 * Math.sin(((t - rs) / 20) * Math.PI) });
    // S
    const ss = t;
    for (; t < ss + 16; t += dt) pts.push({ t, y: -0.20 * Math.sin(((t - ss) / 16) * Math.PI) });
    // return
    const ret = t;
    for (; t < ret + 32; t += dt) pts.push({ t, y: -0.20 * (1 - (t - ret) / 32) });
    // ST
    for (; t < ret + 112; t += dt) pts.push({ t, y: 0 });
    // T
    const ts2 = t;
    for (; t < ts2 + 160; t += dt) pts.push({ t, y: 0.18 * Math.exp(-0.5 * ((t - ts2 - 80) / 46) ** 2) });
    // TP
    for (; t < 800; t += dt) pts.push({ t, y: 0 });
    return pts;
  }, []);

  useEffect(() => {
    cycleRef.current = buildCycle();
  }, [buildCycle]);

  const render = useCallback((ts: number) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    if (w === 0 || h === 0) { animRef.current = requestAnimationFrame(render); return; }
    if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
      canvas.width = w * dpr;
      canvas.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      const buf = new Float32Array(w);
      // Pre-fill
      const cycle = cycleRef.current;
      if (cycle.length) {
        let ci = 0, ca = 0;
        const ms = 1 / 0.12;
        for (let x = 0; x < w; x++) {
          ca += ms;
          while (ci < cycle.length - 1 && cycle[ci + 1].t <= ca) ci++;
          const p0 = cycle[ci], p1 = cycle[Math.min(ci + 1, cycle.length - 1)];
          const f = p1.t !== p0.t ? Math.min(1, (ca - p0.t) / (p1.t - p0.t)) : 0;
          buf[x] = p0.y + (p1.y - p0.y) * f + (Math.random() - 0.5) * 0.008;
          if (ca >= cycle[cycle.length - 1].t) { ca -= cycle[cycle.length - 1].t; ci = 0; }
        }
        cIdxRef.current = ci;
        cAccRef.current = ca;
      }
      bufRef.current = buf;
      headRef.current = w;
    }

    if (lastRef.current === 0) lastRef.current = ts;
    const delta = Math.min(ts - lastRef.current, 50);
    lastRef.current = ts;

    const buf = bufRef.current;
    const bLen = buf.length;
    if (bLen === 0) { animRef.current = requestAnimationFrame(render); return; }

    // Animate
    const pxPerMs = 0.12;
    let rem = delta * pxPerMs;
    const cycle = cycleRef.current;
    const cDur = cycle.length ? cycle[cycle.length - 1].t : 800;
    const msPerPx = 1 / pxPerMs;
    while (rem >= 1 && cycle.length) {
      cAccRef.current += msPerPx;
      while (cIdxRef.current < cycle.length - 1 && cycle[cIdxRef.current + 1].t <= cAccRef.current) cIdxRef.current++;
      const ci2 = cIdxRef.current;
      const p0 = cycle[ci2], p1 = cycle[Math.min(ci2 + 1, cycle.length - 1)];
      const f2 = p1.t !== p0.t ? Math.min(1, (cAccRef.current - p0.t) / (p1.t - p0.t)) : 0;
      buf[headRef.current % bLen] = p0.y + (p1.y - p0.y) * f2 + (Math.random() - 0.5) * 0.008;
      headRef.current++;
      rem -= 1;
      if (cAccRef.current >= cDur) { cAccRef.current -= cDur; cIdxRef.current = 0; }
    }

    // Draw
    const sw = headRef.current % bLen;
    ctx.fillStyle = 'rgba(2, 11, 20, 0.08)';
    ctx.fillRect(0, 0, w, h);
    ctx.clearRect(0, 0, w, h);
    const bl = h * 0.5;
    const amp = h * 0.30;
    // Trace
    ctx.strokeStyle = 'rgba(6, 182, 212, 0.13)';
    ctx.lineWidth = 1.5;
    ctx.shadowColor = 'rgba(6, 182, 212, 0.25)';
    ctx.shadowBlur = 8;
    ctx.beginPath();
    let drawing = false;
    for (let x = 0; x < bLen; x++) {
      const ahead = (x - sw + bLen) % bLen;
      if (ahead < 30 && ahead >= 0) { if (drawing) { ctx.stroke(); ctx.beginPath(); drawing = false; } continue; }
      // fade
      const age = (sw - x + bLen) % bLen;
      const opacity = Math.max(0.03, 1 - age / bLen);
      if (!drawing && opacity > 0.03) {
        // adjust opacity per segment
      }
      const y = bl - buf[x] * amp;
      if (!drawing) { ctx.moveTo(x, y); drawing = true; } else { ctx.lineTo(x, y); }
    }
    if (drawing) ctx.stroke();
    ctx.shadowBlur = 0;

    // Second trace (offset, even more faint)
    ctx.strokeStyle = 'rgba(6, 182, 212, 0.05)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    drawing = false;
    for (let x = 0; x < bLen; x++) {
      const y = h * 0.72 - buf[(x + Math.floor(bLen * 0.4)) % bLen] * amp * 0.6;
      if (!drawing) { ctx.moveTo(x, y); drawing = true; } else { ctx.lineTo(x, y); }
    }
    if (drawing) ctx.stroke();

    animRef.current = requestAnimationFrame(render);
  }, []);

  useEffect(() => {
    animRef.current = requestAnimationFrame(render);
    return () => { if (animRef.current) cancelAnimationFrame(animRef.current); };
  }, [render]);

  return (
    <canvas
      ref={canvasRef}
      className="absolute inset-0 w-full h-full"
      style={{ opacity: 1 }}
    />
  );
}

/* ═══════════════════════════════════════════════════════════════════
   Floating Orb — animated background orb element
   ═══════════════════════════════════════════════════════════════════ */
function FloatingOrb({ delay, size, x, y, color }: {
  delay: number; size: number; x: string; y: string; color: string;
}) {
  return (
    <div
      className="absolute rounded-full pointer-events-none"
      style={{
        width: size,
        height: size,
        left: x,
        top: y,
        background: `radial-gradient(circle, ${color} 0%, transparent 70%)`,
        animation: `float-orb ${8 + delay}s ease-in-out ${delay}s infinite alternate`,
        filter: 'blur(40px)',
        opacity: 0.5,
      }}
    />
  );
}

/* ═══════════════════════════════════════════════════════════════════
   LoginPage — Premium glassmorphic login with animated medical backdrop
   ═══════════════════════════════════════════════════════════════════ */
export function LoginPage() {
  const navigate = useNavigate();
  const { login, isLoading, error, clearError } = useAuthStore();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    const t = setTimeout(() => setMounted(true), 50);
    return () => clearTimeout(t);
  }, []);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    clearError();
    const success = await login(email, password);
    if (success) navigate('/dashboard');
  };

  return (
    <div className="min-h-screen flex relative overflow-hidden" style={{
      background: 'linear-gradient(135deg, #020b14 0%, #041a2e 25%, #030e1c 50%, #051e35 75%, #020b14 100%)',
    }}>
      {/* ── Animated background layers ── */}
      <EcgBackdrop />
      <div className="absolute inset-0 pointer-events-none">
        <FloatingOrb delay={0}   size={400} x="10%" y="20%" color="rgba(6,182,212,0.12)" />
        <FloatingOrb delay={2}   size={300} x="70%" y="60%" color="rgba(2,132,199,0.10)" />
        <FloatingOrb delay={4}   size={250} x="80%" y="10%" color="rgba(6,182,212,0.08)" />
        <FloatingOrb delay={1.5} size={200} x="25%" y="75%" color="rgba(2,132,199,0.08)" />
      </div>
      {/* Dot grid */}
      <div className="absolute inset-0 pointer-events-none opacity-20" style={{
        backgroundImage: 'radial-gradient(rgba(6,182,212,0.15) 1px, transparent 1px)',
        backgroundSize: '40px 40px',
      }} />

      {/* ── Left branding panel (hidden on small screens) ── */}
      <div
        className={`hidden lg:flex flex-col justify-between flex-1 relative z-10 px-16 py-12 transition-all duration-1000 ${
          mounted ? 'opacity-100 translate-x-0' : 'opacity-0 -translate-x-8'
        }`}
      >
        {/* Logo + tagline */}
        <div>
          <div className="flex items-center gap-3 mb-2">
            <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-cyan-500/20 to-cyan-600/10 border border-cyan-400/20 flex items-center justify-center shadow-lg shadow-cyan-500/20">
              <img src="/Logo.png" alt="SmartTriage" className="w-9 h-9 object-contain" />
            </div>
            <span className="text-xl font-extrabold text-white tracking-tight">SmartTriage</span>
          </div>
          <p className="text-cyan-300/40 text-[11px] font-medium tracking-wide ml-14">
            Intelligent Emergency Triage Platform
          </p>
        </div>

        {/* Hero text */}
        <div className="max-w-lg">
          <h2 className="text-4xl font-extrabold text-white leading-tight mb-4 tracking-tight">
            Real-time Emergency
            <br />
            <span className="bg-gradient-to-r from-cyan-400 to-cyan-300 bg-clip-text text-transparent">
              Triage Intelligence
            </span>
          </h2>
          <p className="text-slate-400 text-sm leading-relaxed mb-8">
            AI-powered patient prioritization with continuous vital monitoring,
            automated TEWS scoring, and IoT device integration — delivering
            faster, smarter clinical decisions.
          </p>

          {/* Feature pills */}
          <div className="flex flex-wrap gap-3">
            {[
              { icon: Activity, label: 'Live Vitals' },
              { icon: Heart, label: 'TEWS Scoring' },
              { icon: Zap, label: 'AI Alerts' },
              { icon: Monitor, label: 'IoT Devices' },
            ].map(({ icon: Icon, label }) => (
              <div
                key={label}
                className="flex items-center gap-2 px-3.5 py-2 rounded-xl text-[11px] font-semibold"
                style={{
                  background: 'rgba(6,182,212,0.08)',
                  border: '1px solid rgba(6,182,212,0.15)',
                  color: 'rgba(6,182,212,0.7)',
                }}
              >
                <Icon className="w-3.5 h-3.5" />
                {label}
              </div>
            ))}
          </div>
        </div>

        {/* Footer */}
        <div className="flex items-center gap-2">
          <div className="w-2 h-2 rounded-full bg-green-400 animate-pulse shadow-lg shadow-green-500/40" />
          <span className="text-[10px] text-slate-500 font-medium uppercase tracking-wider">
            System Operational
          </span>
        </div>
      </div>

      {/* ── Right login panel ── */}
      <div className="flex-1 flex items-center justify-center relative z-10 p-6 lg:max-w-[520px]">
        <div
          className={`w-full max-w-[420px] transition-all duration-700 ${
            mounted ? 'opacity-100 translate-y-0 scale-100' : 'opacity-0 translate-y-6 scale-[0.97]'
          }`}
          style={{ transitionTimingFunction: 'cubic-bezier(0.16, 1, 0.3, 1)' }}
        >
          {/* Card */}
          <div
            className="rounded-3xl p-8 relative overflow-hidden"
            style={{
              background: 'rgba(255,255,255,0.04)',
              backdropFilter: 'blur(40px)',
              WebkitBackdropFilter: 'blur(40px)',
              border: '1px solid rgba(255,255,255,0.08)',
              boxShadow: '0 32px 64px rgba(0,0,0,0.4), 0 8px 24px rgba(0,0,0,0.2), inset 0 1px 0 rgba(255,255,255,0.06)',
            }}
          >
            {/* Top shimmer line */}
            <div className="absolute top-0 left-0 right-0 h-[1px] bg-gradient-to-r from-transparent via-cyan-400/30 to-transparent" />

            {/* Header — mobile shows logo, desktop just the form title */}
            <div className="text-center mb-8">
              <div className="lg:hidden flex items-center justify-center gap-2.5 mb-5">
                <div className="w-11 h-11 rounded-xl bg-gradient-to-br from-cyan-500/20 to-cyan-600/10 border border-cyan-400/20 flex items-center justify-center shadow-lg shadow-cyan-500/20">
                  <img src="/Logo.png" alt="SmartTriage" className="w-8 h-8 object-contain" />
                </div>
                <span className="text-lg font-extrabold text-white tracking-tight">SmartTriage</span>
              </div>
              <h1 className="text-xl font-bold text-white tracking-tight">Welcome back</h1>
              <p className="text-slate-400 text-xs mt-1.5">Sign in to access the triage dashboard</p>
            </div>

            {/* Error */}
            {error && (
              <div
                className="mb-6 p-3.5 rounded-xl flex items-center gap-3 animate-fade-in"
                style={{
                  background: 'rgba(239,68,68,0.10)',
                  border: '1px solid rgba(239,68,68,0.20)',
                }}
              >
                <AlertCircle className="w-4 h-4 text-red-400 shrink-0" />
                <span className="text-red-300 text-[13px] font-medium">{error}</span>
              </div>
            )}

            {/* Form */}
            <form onSubmit={handleSubmit} className="space-y-5">
              <div className="space-y-1.5">
                <label className="block text-[10px] font-semibold text-slate-400 uppercase tracking-widest">
                  Email Address
                </label>
                <div className="relative group">
                  <input
                    type="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    placeholder="admin@smarttriage.com"
                    required
                    autoComplete="username"
                    className="w-full px-4 py-3 rounded-xl text-[13px] text-white placeholder-slate-600 outline-none transition-all duration-300 focus:ring-2 focus:ring-cyan-500/30"
                    style={{
                      background: 'rgba(255,255,255,0.04)',
                      border: '1px solid rgba(255,255,255,0.08)',
                    }}
                    onFocus={(e) => {
                      e.currentTarget.style.background = 'rgba(255,255,255,0.06)';
                      e.currentTarget.style.borderColor = 'rgba(6,182,212,0.3)';
                    }}
                    onBlur={(e) => {
                      e.currentTarget.style.background = 'rgba(255,255,255,0.04)';
                      e.currentTarget.style.borderColor = 'rgba(255,255,255,0.08)';
                    }}
                  />
                </div>
              </div>

              <div className="space-y-1.5">
                <label className="block text-[10px] font-semibold text-slate-400 uppercase tracking-widest">
                  Password
                </label>
                <div className="relative group">
                  <input
                    type={showPassword ? 'text' : 'password'}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    placeholder="Enter your password"
                    required
                    autoComplete="current-password"
                    className="w-full px-4 py-3 pr-12 rounded-xl text-[13px] text-white placeholder-slate-600 outline-none transition-all duration-300 focus:ring-2 focus:ring-cyan-500/30"
                    style={{
                      background: 'rgba(255,255,255,0.04)',
                      border: '1px solid rgba(255,255,255,0.08)',
                    }}
                    onFocus={(e) => {
                      e.currentTarget.style.background = 'rgba(255,255,255,0.06)';
                      e.currentTarget.style.borderColor = 'rgba(6,182,212,0.3)';
                    }}
                    onBlur={(e) => {
                      e.currentTarget.style.background = 'rgba(255,255,255,0.04)';
                      e.currentTarget.style.borderColor = 'rgba(255,255,255,0.08)';
                    }}
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-500 hover:text-cyan-400 transition-colors duration-200"
                  >
                    {showPassword ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
                  </button>
                </div>
              </div>

              {/* Sign in button */}
              <button
                type="submit"
                disabled={isLoading}
                className="w-full py-3.5 rounded-xl text-[13px] font-bold text-white shadow-xl disabled:opacity-50 transition-all duration-300 hover:-translate-y-0.5 flex items-center justify-center gap-2 relative overflow-hidden group"
                style={{
                  background: 'linear-gradient(135deg, #0891b2 0%, #0e7490 50%, #0284c7 100%)',
                  boxShadow: '0 8px 24px rgba(6,182,212,0.30), 0 2px 8px rgba(0,0,0,0.15), inset 0 1px 0 rgba(255,255,255,0.15)',
                }}
              >
                {/* Hover shimmer */}
                <div className="absolute inset-0 bg-gradient-to-r from-transparent via-white/10 to-transparent -translate-x-full group-hover:translate-x-full transition-transform duration-700" />
                {isLoading ? (
                  <>
                    <Loader2 className="w-4 h-4 animate-spin" />
                    Authenticating...
                  </>
                ) : (
                  'Sign In'
                )}
              </button>
            </form>

          </div>

          {/* Bottom text */}
          <p className="text-center text-[10px] text-slate-600 mt-5 font-medium">
            Protected health information &middot; HIPAA compliant
          </p>
        </div>
      </div>

      {/* ── Keyframe styles ── */}
      <style>{`
        @keyframes float-orb {
          0%   { transform: translate(0, 0) scale(1); }
          50%  { transform: translate(30px, -20px) scale(1.1); }
          100% { transform: translate(-20px, 15px) scale(0.95); }
        }
      `}</style>
    </div>
  );
}
