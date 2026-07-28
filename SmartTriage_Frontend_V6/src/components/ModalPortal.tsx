import { type ReactNode } from 'react';
import { createPortal } from 'react-dom';

/**
 * ModalPortal — renders its children into <body> via a portal.
 *
 * Why this exists: SmartTriage's surfaces are built from glass cards
 * whose `backdrop-filter` (and any `transform`/`filter`) makes them a
 * CSS containing block. A `position: fixed` overlay rendered *inside*
 * such a card resolves against the CARD, not the viewport — so the
 * modal gets clipped into a strip and the user has to scroll to find
 * it, instead of it popping up centred over the whole screen.
 *
 * Wrapping a modal's root in <ModalPortal> escapes that containing
 * block: the overlay always resolves against the real viewport.
 * Portaling a modal that already rendered correctly is harmless, so
 * this is safe to apply uniformly to every app modal.
 *
 * Usage:
 *   return (
 *     <ModalPortal>
 *       <div className="fixed inset-0 z-[9999] ...">…</div>
 *     </ModalPortal>
 *   );
 */
export function ModalPortal({ children }: { children: ReactNode }) {
  if (typeof document === 'undefined') return null;
  return createPortal(children, document.body);
}
