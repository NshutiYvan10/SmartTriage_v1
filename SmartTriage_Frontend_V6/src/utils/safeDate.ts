/**
 * safeDate — clinical-safety wrappers around date-fns.
 *
 * date-fns v3 throws RangeError("Invalid time value") when handed
 * undefined, null, or an unparseable string. Any throw during render
 * unmounts the React subtree — which in production looked like the
 * dashboard rendering as a blank gradient with no sidebar, the exact
 * "had to reload" failure the user reported. These helpers make the
 * failure mode a missing label ("—") instead of a blank screen.
 *
 * Use everywhere a backend timestamp lands in JSX. Cheap to inline,
 * but the shared helper keeps the fallback string consistent and
 * makes future format changes one-line.
 */
import { formatDistanceToNow as dfFormatDistanceToNow, format as dfFormat } from 'date-fns';

type DateLike = Date | string | number | null | undefined;

/** Coerce backend timestamps (often ISO strings) into a Date. */
export function toDate(value: DateLike): Date | null {
  if (value == null) return null;
  if (value instanceof Date) return isNaN(value.getTime()) ? null : value;
  const d = new Date(value);
  return isNaN(d.getTime()) ? null : d;
}

/** Render-safe formatDistanceToNow. Returns fallback on invalid input. */
export function safeFormatDistanceToNow(
  value: DateLike,
  options?: Parameters<typeof dfFormatDistanceToNow>[1],
  fallback = '—',
): string {
  const d = toDate(value);
  if (!d) return fallback;
  try {
    return dfFormatDistanceToNow(d, options);
  } catch {
    return fallback;
  }
}

/** Render-safe format. Returns fallback on invalid input. */
export function safeFormat(
  value: DateLike,
  pattern: string,
  fallback = '—',
): string {
  const d = toDate(value);
  if (!d) return fallback;
  try {
    return dfFormat(d, pattern);
  } catch {
    return fallback;
  }
}
