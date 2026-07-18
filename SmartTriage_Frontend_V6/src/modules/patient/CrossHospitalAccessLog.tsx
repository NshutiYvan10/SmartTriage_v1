/* ── CrossHospitalAccessLog ──
 *
 * Patient-scoped "who accessed this record" log — every break-the-glass override on THIS person's
 * cross-hospital record, across all hospitals. The data-subject "accounting of disclosures" view
 * (complements the actor-scoped governance Override Register). Read-only; the underlying events are
 * the same immutable forensic records governance reviews, re-read here by patient instead of hospital.
 */
import { useEffect, useState } from 'react';
import { format } from 'date-fns';
import { History, ShieldAlert, Lock, CheckCircle2, Clock, Loader2 } from 'lucide-react';
import { governanceApi, type BreakTheGlassEvent } from '@/api/crossHospital';
import { ApiError } from '@/api/client';
import { useTheme } from '@/hooks/useTheme';

const titleCase = (s: string | null) =>
  (s || '').toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());

export function CrossHospitalAccessLog({ patientId }: { patientId: string }) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const [events, setEvents] = useState<BreakTheGlassEvent[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    governanceApi.getBreakTheGlassEventsForPatient(patientId)
      .then((rows) => { if (!cancelled) setEvents(Array.isArray(rows) ? rows : []); })
      .catch((e) => { if (!cancelled) setError(e instanceof ApiError ? e.message : 'Failed to load the access log.'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [patientId]);

  return (
    <div className="rounded-2xl p-4 sm:p-5" style={glassCard}>
      <div className="flex items-start gap-2.5">
        <div className={`w-8 h-8 rounded-xl flex items-center justify-center flex-shrink-0 ${isDark ? 'bg-white/10' : 'bg-slate-100'}`}>
          <History className={`w-4 h-4 ${text.muted}`} />
        </div>
        <div className="flex-1 min-w-0">
          <h3 className={`text-sm font-bold ${text.heading}`}>Cross-hospital access log</h3>
          <p className={`text-xs ${text.muted}`}>Break-the-glass access to this patient's record from other hospitals</p>
        </div>
        {events && events.length > 0 && (
          <span className={`text-[10px] font-bold px-2 py-1 rounded-lg flex-shrink-0 ${isDark ? 'bg-red-500/20 text-red-300' : 'bg-red-50 text-red-700'}`}>
            {events.length} override{events.length === 1 ? '' : 's'}
          </span>
        )}
      </div>

      <div className="mt-3">
        {loading ? (
          <div className={`flex items-center gap-2 py-4 text-xs ${text.muted}`}>
            <Loader2 className="w-4 h-4 animate-spin" /> Loading access log…
          </div>
        ) : error ? (
          <div className={`rounded-xl p-3 text-xs font-semibold ${isDark ? 'text-red-300 bg-red-500/15' : 'text-red-700 bg-red-50'}`}>{error}</div>
        ) : !events || events.length === 0 ? (
          <div className={`rounded-xl p-5 text-center ${isDark ? 'bg-white/[0.02]' : 'bg-slate-50'}`}>
            <Lock className={`w-6 h-6 mx-auto mb-1.5 ${text.muted}`} />
            <p className={`text-xs ${text.muted}`}>No cross-hospital access on record — no one has broken the glass on this patient.</p>
          </div>
        ) : (
          <div className="space-y-2">
            {events.map((e) => <AccessRow key={e.id} event={e} isDark={isDark} text={text} glassInner={glassInner} />)}
          </div>
        )}
      </div>

      <p className={`mt-3 flex items-center gap-1.5 text-[11px] ${text.muted}`}>
        <Lock className="w-3 h-3 flex-shrink-0" />
        Read-only. Every access is recorded automatically and reviewed by governance.
      </p>
    </div>
  );
}

function AccessRow({ event, isDark, text, glassInner }: {
  event: BreakTheGlassEvent; isDark: boolean; text: any; glassInner: React.CSSProperties;
}) {
  const when = event.accessedAt ? format(new Date(event.accessedAt), 'dd MMM yyyy, HH:mm') : '';
  return (
    <div className="rounded-xl p-3 flex gap-3" style={glassInner}>
      <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 ${isDark ? 'bg-red-500/20 text-red-300' : 'bg-red-50 text-red-600'}`}>
        <ShieldAlert className="w-4 h-4" />
      </div>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 flex-wrap">
          <span className={`text-sm font-semibold ${text.heading}`}>{event.actorName || 'Unknown clinician'}</span>
          <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${isDark ? 'bg-red-500/20 text-red-300' : 'bg-red-50 text-red-700'}`}>Broke the glass</span>
        </div>
        <p className={`text-[11px] mt-0.5 ${text.muted}`}>
          {event.actorHospitalName || 'Unknown hospital'}{event.actorRole ? ` · ${titleCase(event.actorRole)}` : ''}
        </p>
        {event.reason && (
          <p className={`text-xs mt-1.5 italic ${text.body}`}>&ldquo;{event.reason}&rdquo;</p>
        )}
        <div className="mt-1.5">
          {event.acknowledged ? (
            <p className={`inline-flex items-center gap-1 text-[11px] ${isDark ? 'text-emerald-300' : 'text-emerald-600'}`}>
              <CheckCircle2 className="w-3 h-3" />
              Reviewed{event.acknowledgedByName ? ` by ${event.acknowledgedByName}` : ''}
              {event.acknowledgmentNote ? ` · ${event.acknowledgmentNote}` : ''}
            </p>
          ) : (
            <p className={`inline-flex items-center gap-1 text-[11px] ${isDark ? 'text-amber-300' : 'text-amber-600'}`}>
              <Clock className="w-3 h-3" /> Awaiting governance review
            </p>
          )}
        </div>
      </div>
      <span className={`text-[11px] whitespace-nowrap flex-shrink-0 ${text.muted}`}>{when}</span>
    </div>
  );
}
