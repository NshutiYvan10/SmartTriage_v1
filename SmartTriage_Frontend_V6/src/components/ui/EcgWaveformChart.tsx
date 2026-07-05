import { useRef, useEffect, useCallback, memo } from 'react';

/* ════════════════════════════════════════════════════════════════════════
   EcgWaveformChart — real-time bedside-monitor style ECG trace
   ────────────────────────────────────────────────────────────────────────
   Generates a clinically-realistic Lead-II ECG waveform using only the
   patient's current heart rate and ST-segment deviation.  No raw waveform
   data needs to travel over WebSocket — the morphology is synthesised
   client-side, identical to how commercial bedside displays work.

   Visual style:
     • Dark background (#0a0a0a) with faint grid lines
     • Phosphor-green trace (#00ff41) with glow
     • Sweep-bar eraser (left → right, like GE / Philips monitors)
     • Rhythm label + HR readout overlaid

   Props:
     heartRate     – bpm, controls R-R interval
     stDeviation   – mV, shifts ST segment (+elevation / −depression)
     rhythm        – e.g. "NSR", "AF", "SVT" (display label)
     qrsDuration   – ms (currently informational, displayed on HUD)
     isLive        – true → animating, false → paused "no signal" state
     height        – canvas height in px (default 180)
     className     – optional wrapper classes
   ════════════════════════════════════════════════════════════════════════ */

interface EcgWaveformChartProps {
  heartRate: number;
  stDeviation: number;
  rhythm?: string;
  qrsDuration?: number;
  isLive?: boolean;
  height?: number;
  className?: string;
}

// ── Millisecond durations for each ECG segment (at 75 bpm baseline) ──
const BASE_HR = 75;
const BASE_CYCLE_MS = (60 / BASE_HR) * 1000; // 800 ms
const P_DUR = 80;
const PR_DUR = 40;
const QRS_DUR = 80;
const ST_DUR = 80;
const T_DUR = 160;

// Sweep speed: 25 mm/s equivalent → pixels per millisecond
const PIXELS_PER_MS = 0.20;

// ── Physiological variation — makes the trace look alive ──
const WANDER_FREQ = 0.22;    // Hz (~13 breaths/min respiratory baseline wander)
const WANDER_AMP  = 0.035;   // wander amplitude in display units
const NOISE_AMP   = 0.010;   // fine noise per pixel
const AMP_JITTER  = 0.07;    // ±7% amplitude variation between beats
const HR_JITTER   = 0.03;    // ±3% heart rate variation between beats
const DECAY_MAX   = 0.40;    // phosphor-decay max darkening for oldest trace

// ── Generate one cardiac cycle as an array of {t, y} points ──
// y is in "monitor units" where 0=baseline, 1.0=max R peak height
function generateCycle(hrBpm: number, stMv: number): { t: number; y: number }[] {
  const cycleDuration = (60 / Math.max(hrBpm, 30)) * 1000;
  const points: { t: number; y: number }[] = [];
  const dt = 2; // 2ms resolution

  // Scale segment durations proportionally to cycle length
  const scale = cycleDuration / BASE_CYCLE_MS;
  const pDur = P_DUR * scale;
  const prDur = PR_DUR * scale;
  const qrsDur = QRS_DUR * Math.min(scale, 1.2); // QRS doesn't stretch as much
  const stDur = ST_DUR * scale;
  const tDur = T_DUR * scale;
  const tpDur = cycleDuration - pDur - prDur - qrsDur - stDur - tDur;

  let t = 0;

  // P wave — small gaussian bump (0.15 amplitude)
  const pCenter = pDur / 2;
  const pSigma = pDur / 4;
  for (; t < pDur; t += dt) {
    const g = Math.exp(-0.5 * ((t - pCenter) / pSigma) ** 2);
    points.push({ t, y: 0.15 * g });
  }

  // PR segment — flat baseline
  const prStart = t;
  for (; t < prStart + prDur; t += dt) {
    points.push({ t, y: 0 });
  }

  // QRS complex
  const qrsStart = t;
  const qHalf = qrsDur * 0.15;
  const rHalf = qrsDur * 0.25;
  const sHalf = qrsDur * 0.20;
  const returnDur = qrsDur - qHalf - rHalf - sHalf;
  // Q wave — small negative deflection
  for (; t < qrsStart + qHalf; t += dt) {
    const frac = (t - qrsStart) / qHalf;
    points.push({ t, y: -0.12 * Math.sin(frac * Math.PI) });
  }
  // R wave — tall positive spike
  const rStart = t;
  for (; t < rStart + rHalf; t += dt) {
    const frac = (t - rStart) / rHalf;
    points.push({ t, y: -0.12 + 1.12 * Math.sin(frac * Math.PI) });
  }
  // S wave — negative dip
  const sStart = t;
  for (; t < sStart + sHalf; t += dt) {
    const frac = (t - sStart) / sHalf;
    points.push({ t, y: -0.25 * Math.sin(frac * Math.PI) });
  }
  // Return to baseline (or ST level)
  const retStart = t;
  const stLevel = stMv * 0.3; // scale mV → display units
  for (; t < retStart + returnDur; t += dt) {
    const frac = (t - retStart) / returnDur;
    points.push({ t, y: -0.25 * (1 - frac) + stLevel * frac });
  }

  // ST segment — held at stLevel
  const stSegStart = t;
  for (; t < stSegStart + stDur; t += dt) {
    points.push({ t, y: stLevel });
  }

  // T wave — broad positive bump (affected by ST deviation)
  const tStart = t;
  const tCenter = tDur / 2;
  const tSigma = tDur / 3.5;
  const tAmplitude = 0.22 + stMv * 0.08; // taller T with ST elevation
  for (; t < tStart + tDur; t += dt) {
    const local = t - tStart;
    const g = Math.exp(-0.5 * ((local - tCenter) / tSigma) ** 2);
    points.push({ t, y: stLevel * (1 - local / tDur) + tAmplitude * g });
  }

  // TP segment — return to baseline
  const tpStart = t;
  for (; t < tpStart + Math.max(tpDur, 20); t += dt) {
    const frac = Math.min(1, (t - tpStart) / 40);
    const decay = stLevel * (1 - frac);
    points.push({ t, y: decay * (1 - frac) });
  }

  return points;
}

function EcgWaveformChartInner({
  heartRate,
  stDeviation,
  rhythm = 'NSR',
  qrsDuration,
  isLive = true,
  height = 180,
  className = '',
}: EcgWaveformChartProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const animFrameRef = useRef<number>(0);
  // Ring buffer: each slot = one pixel column of y-value
  const bufferRef = useRef<Float32Array>(new Float32Array(0));
  // writeHead always increments; screen-x = writeHead % bufLen
  const writeHeadRef = useRef(0);
  const lastTimeRef = useRef(0);
  const cyclePointsRef = useRef<{ t: number; y: number }[]>([]);
  const cycleIdxRef = useRef(0);
  const cycleTimeAccRef = useRef(0);
  const totalMsRef = useRef(0);        // accumulated time → baseline wander phase
  const beatAmpRef = useRef(1.0);      // current beat's amplitude scale

  // Regenerate the single-cycle template when vitals change
  useEffect(() => {
    cyclePointsRef.current = generateCycle(
      heartRate || 75,
      stDeviation || 0,
    );
    cycleIdxRef.current = 0;
    cycleTimeAccRef.current = 0;
  }, [heartRate, stDeviation]);

  // Pre-fill buffer with varied waveform — full trace visible immediately.
  const prefillBuffer = useCallback((buffer: Float32Array, hr: number, st: number) => {
    let cycle = generateCycle(hr, st);
    if (cycle.length === 0) return;

    const bufLen = buffer.length;
    const msPerPx = 1 / PIXELS_PER_MS;
    let cIdx = 0;
    let cTimeAcc = 0;
    let localBeatAmp = 1.0;
    let localTotalMs = 0;

    for (let x = 0; x < bufLen; x++) {
      cTimeAcc += msPerPx;
      localTotalMs += msPerPx;

      // Advance cycle index
      while (cIdx < cycle.length - 1 && cycle[cIdx + 1].t <= cTimeAcc) {
        cIdx++;
      }

      // Interpolate base y
      let y: number;
      if (cIdx >= cycle.length - 1) {
        y = cycle[cycle.length - 1].y;
      } else {
        const p0 = cycle[cIdx];
        const p1 = cycle[cIdx + 1];
        const frac = Math.min(1, Math.max(0, (cTimeAcc - p0.t) / (p1.t - p0.t)));
        y = p0.y + (p1.y - p0.y) * frac;
      }

      // Apply variation: beat amplitude, respiratory wander, fine noise
      const wander = WANDER_AMP * Math.sin(2 * Math.PI * WANDER_FREQ * localTotalMs / 1000);
      const noise = (Math.random() - 0.5) * NOISE_AMP * 2;
      buffer[x] = y * localBeatAmp + wander + noise;

      // Wrap cardiac cycle → new beat with jitter
      const cycleDur = cycle[cycle.length - 1].t;
      if (cTimeAcc >= cycleDur) {
        cTimeAcc -= cycleDur;
        cIdx = 0;
        localBeatAmp = 1.0 + (Math.random() - 0.5) * AMP_JITTER * 2;
        const jitteredHR = hr * (1 + (Math.random() - 0.5) * HR_JITTER * 2);
        cycle = generateCycle(jitteredHR, st);
      }
    }

    // Sync animation state so live sweep continues seamlessly
    cycleIdxRef.current = cIdx;
    cycleTimeAccRef.current = cTimeAcc;
    cyclePointsRef.current = cycle;
    beatAmpRef.current = localBeatAmp;
    totalMsRef.current = localTotalMs;
    writeHeadRef.current = bufLen;
  }, []);

  const render = useCallback((timestamp: number) => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    // Handle high-DPI
    const dpr = window.devicePixelRatio || 1;
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    if (w === 0 || h === 0) {
      animFrameRef.current = requestAnimationFrame(render);
      return;
    }
    if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
      canvas.width = w * dpr;
      canvas.height = h * dpr;
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      // Re-init buffer to match canvas width and pre-fill with waveform
      const newBuf = new Float32Array(w);
      bufferRef.current = newBuf;
      prefillBuffer(newBuf, heartRate || 75, stDeviation || 0);
    }

    const buffer = bufferRef.current;
    const bufLen = buffer.length;
    if (bufLen === 0) {
      animFrameRef.current = requestAnimationFrame(render);
      return;
    }

    // Time delta
    if (lastTimeRef.current === 0) lastTimeRef.current = timestamp;
    const dt = Math.min(timestamp - lastTimeRef.current, 50); // cap at 50ms
    lastTimeRef.current = timestamp;

    if (isLive) {
      const pxAdvance = dt * PIXELS_PER_MS;
      const msPerPx = 1 / PIXELS_PER_MS;
      let remaining = pxAdvance;
      while (remaining >= 1 && cyclePointsRef.current.length > 0) {
        const cycle = cyclePointsRef.current; // fresh ref each iteration
        const cycleDur = cycle[cycle.length - 1].t;

        cycleTimeAccRef.current += msPerPx;
        totalMsRef.current += msPerPx;

        // Advance cycle index
        while (
          cycleIdxRef.current < cycle.length - 1 &&
          cycle[cycleIdxRef.current + 1].t <= cycleTimeAccRef.current
        ) {
          cycleIdxRef.current++;
        }

        // Interpolate y
        const ci = cycleIdxRef.current;
        let y: number;
        if (ci >= cycle.length - 1) {
          y = cycle[cycle.length - 1].y;
        } else {
          const p0 = cycle[ci];
          const p1 = cycle[ci + 1];
          const frac = Math.min(1, Math.max(0,
            (cycleTimeAccRef.current - p0.t) / (p1.t - p0.t)));
          y = p0.y + (p1.y - p0.y) * frac;
        }

        // Apply variation: beat amplitude, respiratory wander, fine noise
        const wander = WANDER_AMP * Math.sin(
          2 * Math.PI * WANDER_FREQ * totalMsRef.current / 1000);
        const noise = (Math.random() - 0.5) * NOISE_AMP * 2;
        const finalY = y * beatAmpRef.current + wander + noise;

        const writeSlot = writeHeadRef.current % bufLen;
        buffer[writeSlot] = finalY;
        writeHeadRef.current++;
        remaining -= 1;

        // Wrap cardiac cycle → regenerate with jitter for next beat
        if (cycleTimeAccRef.current >= cycleDur) {
          cycleTimeAccRef.current -= cycleDur;
          cycleIdxRef.current = 0;
          beatAmpRef.current = 1.0 + (Math.random() - 0.5) * AMP_JITTER * 2;
          const jHR = (heartRate || 75) * (1 + (Math.random() - 0.5) * HR_JITTER * 2);
          cyclePointsRef.current = generateCycle(jHR, stDeviation || 0);
        }
      }
    }

    // ── Draw ──
    // The write head position on screen (the sweep bar)
    const sweepX = writeHeadRef.current % bufLen;
    const eraserWidth = 20;

    // Background
    ctx.fillStyle = '#0a0a0a';
    ctx.fillRect(0, 0, w, h);

    // Grid lines (faint)
    ctx.strokeStyle = 'rgba(0, 255, 65, 0.06)';
    ctx.lineWidth = 0.5;
    const gridSpacingX = 40;
    const gridSpacingY = h / 6;
    for (let gx = 0; gx < w; gx += gridSpacingX) {
      ctx.beginPath();
      ctx.moveTo(gx, 0);
      ctx.lineTo(gx, h);
      ctx.stroke();
    }
    for (let gy = 0; gy < h; gy += gridSpacingY) {
      ctx.beginPath();
      ctx.moveTo(0, gy);
      ctx.lineTo(w, gy);
      ctx.stroke();
    }
    // Minor grid
    ctx.strokeStyle = 'rgba(0, 255, 65, 0.025)';
    const minorSpacing = gridSpacingX / 5;
    for (let gx = 0; gx < w; gx += minorSpacing) {
      ctx.beginPath();
      ctx.moveTo(gx, 0);
      ctx.lineTo(gx, h);
      ctx.stroke();
    }
    for (let gy = 0; gy < h; gy += gridSpacingY / 5) {
      ctx.beginPath();
      ctx.moveTo(0, gy);
      ctx.lineTo(w, gy);
      ctx.stroke();
    }

    const baseline = h * 0.55;
    const amplitude = h * 0.35;

    // ── Draw the ECG trace ──
    // We draw from x=0 to x=bufLen. Each x reads buffer[x] directly
    // because writeHead writes to buffer[head % bufLen], and x IS the
    // screen position = the buffer slot.
    // The "gap" (eraser) is around sweepX.

    ctx.shadowColor = '#00ff41';
    ctx.shadowBlur = 4;
    ctx.strokeStyle = '#00ff41';
    ctx.lineWidth = 1.8;
    ctx.lineJoin = 'round';
    ctx.lineCap = 'round';
    ctx.beginPath();

    let drawing = false;
    for (let x = 0; x < bufLen; x++) {
      // How far ahead of sweep is this pixel?
      // Pixels just ahead of sweep are "future" (erased zone).
      const aheadOfSweep = (x - sweepX + bufLen) % bufLen;
      if (aheadOfSweep < eraserWidth && aheadOfSweep >= 0) {
        // Inside the eraser zone — break the path
        if (drawing) {
          ctx.stroke();
          ctx.beginPath();
          drawing = false;
        }
        continue;
      }

      const y = baseline - buffer[x] * amplitude;

      if (!drawing) {
        ctx.moveTo(x, y);
        drawing = true;
      } else {
        ctx.lineTo(x, y);
      }
    }
    if (drawing) ctx.stroke();
    ctx.shadowBlur = 0;

    // Eraser bar gradient overlay
    {
      const grad = ctx.createLinearGradient(
        sweepX - 2, 0,
        sweepX + eraserWidth + 4, 0
      );
      grad.addColorStop(0, 'rgba(10,10,10,0)');
      grad.addColorStop(0.3, 'rgba(10,10,10,0.95)');
      grad.addColorStop(0.7, 'rgba(10,10,10,0.95)');
      grad.addColorStop(1, 'rgba(10,10,10,0)');
      ctx.fillStyle = grad;
      ctx.fillRect(sweepX - 2, 0, eraserWidth + 6, h);
    }

    // ── Bright sweep cursor — visible moving line ──
    ctx.strokeStyle = 'rgba(0, 255, 65, 0.9)';
    ctx.shadowColor = '#00ff41';
    ctx.shadowBlur = 12;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(sweepX, 0);
    ctx.lineTo(sweepX, h);
    ctx.stroke();
    ctx.shadowBlur = 0;

    // ── Phosphor decay — older trace gradually dims ──
    {
      const trailLen = bufLen - eraserWidth;
      if (trailLen > 0) {
        const eraserEnd = (sweepX + eraserWidth) % bufLen;
        if (eraserEnd > sweepX || eraserEnd === 0) {
          // Normal case: eraser zone is to the right of sweep
          // Section A: sweepX → 0 (fresh → moderately old)
          if (sweepX > 0) {
            const alphaAtZero = DECAY_MAX * (sweepX / trailLen);
            const g1 = ctx.createLinearGradient(sweepX, 0, 0, 0);
            g1.addColorStop(0, 'rgba(10,10,10,0)');
            g1.addColorStop(1, `rgba(10,10,10,${alphaAtZero.toFixed(3)})`);
            ctx.fillStyle = g1;
            ctx.fillRect(0, 0, sweepX, h);
          }
          // Section B: bufLen → eraserEnd (continues to oldest)
          if (eraserEnd < bufLen) {
            const alphaAtRight = DECAY_MAX * ((sweepX + 1) / trailLen);
            const g2 = ctx.createLinearGradient(bufLen, 0, eraserEnd, 0);
            g2.addColorStop(0, `rgba(10,10,10,${alphaAtRight.toFixed(3)})`);
            g2.addColorStop(1, `rgba(10,10,10,${DECAY_MAX.toFixed(3)})`);
            ctx.fillStyle = g2;
            ctx.fillRect(eraserEnd, 0, bufLen - eraserEnd, h);
          }
        } else {
          // Wrapped case: entire visible trail is one contiguous range
          if (sweepX > eraserEnd) {
            const g = ctx.createLinearGradient(sweepX, 0, eraserEnd, 0);
            g.addColorStop(0, 'rgba(10,10,10,0)');
            g.addColorStop(1, `rgba(10,10,10,${DECAY_MAX.toFixed(3)})`);
            ctx.fillStyle = g;
            ctx.fillRect(eraserEnd, 0, sweepX - eraserEnd, h);
          }
        }
      }
    }

    // ── HUD Overlay ──
    ctx.font = 'bold 11px "SF Mono", "Fira Code", "Cascadia Code", monospace';
    ctx.fillStyle = 'rgba(0, 255, 65, 0.8)';
    ctx.fillText('II', 8, 16);

    ctx.font = '10px "SF Mono", "Fira Code", "Cascadia Code", monospace';
    ctx.fillStyle = 'rgba(0, 255, 65, 0.5)';
    ctx.fillText(`${rhythm || 'NSR'}`, 28, 16);

    if (qrsDuration) {
      ctx.fillText(`QRS: ${qrsDuration}ms`, 8, 30);
    }

    // ST deviation indicator (top-right)
    const stLabel = stDeviation > 0.05
      ? `ST \u2191${stDeviation.toFixed(1)}mV`
      : stDeviation < -0.05
        ? `ST \u2193${Math.abs(stDeviation).toFixed(1)}mV`
        : 'ST  \u2014';
    const stColor = Math.abs(stDeviation) > 1.0
      ? 'rgba(255, 80, 80, 0.9)'
      : Math.abs(stDeviation) > 0.5
        ? 'rgba(255, 200, 50, 0.9)'
        : 'rgba(0, 255, 65, 0.5)';
    ctx.fillStyle = stColor;
    ctx.font = 'bold 11px "SF Mono", "Fira Code", "Cascadia Code", monospace';
    const stTextWidth = ctx.measureText(stLabel).width;
    ctx.fillText(stLabel, w - stTextWidth - 8, 16);

    // Heart rate (bottom-right, large)
    const hr = heartRate || 0;
    if (hr > 0) {
      ctx.font = 'bold 28px "SF Mono", "Fira Code", "Cascadia Code", monospace';
      ctx.fillStyle = '#00ff41';
      const hrText = `${hr}`;
      const hrTextWidth = ctx.measureText(hrText).width;
      ctx.fillText(hrText, w - hrTextWidth - 36, h - 12);
      ctx.font = '10px "SF Mono", "Fira Code", "Cascadia Code", monospace';
      ctx.fillStyle = 'rgba(0, 255, 65, 0.5)';
      ctx.fillText('BPM', w - 32, h - 14);
    }

    // "No signal" overlay when not live
    if (!isLive) {
      ctx.fillStyle = 'rgba(10, 10, 10, 0.6)';
      ctx.fillRect(0, 0, w, h);
      ctx.font = 'bold 14px sans-serif';
      ctx.fillStyle = 'rgba(255, 80, 80, 0.8)';
      const noSigText = '\u2014 NO SIGNAL \u2014';
      const noSigWidth = ctx.measureText(noSigText).width;
      ctx.fillText(noSigText, (w - noSigWidth) / 2, h / 2);
    }

    animFrameRef.current = requestAnimationFrame(render);
  }, [isLive, heartRate, stDeviation, rhythm, qrsDuration]);

  useEffect(() => {
    lastTimeRef.current = 0;
    animFrameRef.current = requestAnimationFrame(render);
    return () => {
      if (animFrameRef.current) cancelAnimationFrame(animFrameRef.current);
    };
  }, [render]);

  return (
    <div className={`relative rounded-xl overflow-hidden ${className}`}>
      <canvas
        ref={canvasRef}
        style={{ width: '100%', height: `${height}px`, display: 'block' }}
      />
    </div>
  );
}

export const EcgWaveformChart = memo(EcgWaveformChartInner);
export default EcgWaveformChart;
