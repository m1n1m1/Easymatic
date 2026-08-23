/**
 * Scroll motion for the home page.
 *
 * Four rules keep this from becoming fragile:
 *
 *  - Everything sits inside `gsap.matchMedia()`. Reduced-motion users never run
 *    any of it, and GSAP reverts its own inline styles when the query stops
 *    matching, so a mid-session preference change leaves a clean static page.
 *  - Reveals go through `ScrollTrigger.batch()`: one observer for all cards
 *    rather than one per card, and `once: true` so nothing re-animates.
 *  - Nothing is pinned. Pinning is where a landing page starts fighting mobile
 *    scroll, and the brief asked for less play, not more.
 *  - Only `transform` and `opacity` are animated, so every tween is composited.
 *
 * The hidden start state lives in CSS (`.js [data-reveal]`), not in a
 * `gsap.set()` here — see the motion guard in global.css for why.
 */
import gsap from 'gsap';
import { ScrollTrigger } from 'gsap/ScrollTrigger';

// Claims the reveal styles before anything else here can throw: BaseLayout's
// inline script reveals everything if this class has not appeared in 2.5s.
document.documentElement.classList.add('motion-ready');

gsap.registerPlugin(ScrollTrigger);

const EASE = 'power2.out';

const media = gsap.matchMedia();

media.add('(prefers-reduced-motion: no-preference)', () => {
  // ---- Hero ------------------------------------------------------------
  // Runs on load rather than on scroll: it is already in view.
  const heroParts = gsap.utils.toArray<HTMLElement>('[data-hero-part]');
  if (heroParts.length > 0) {
    gsap.to(heroParts, {
      opacity: 1,
      y: 0,
      duration: 0.9,
      ease: EASE,
      stagger: 0.09,
      clearProps: 'willChange',
    });
  }

  // ---- Everything else -------------------------------------------------
  const reveals = gsap.utils
    .toArray<HTMLElement>('[data-reveal]')
    .filter((element) => !element.hasAttribute('data-hero-part'));

  const show = (batch: Element[]) =>
    gsap.to(batch, {
      opacity: 1,
      y: 0,
      duration: 0.7,
      ease: EASE,
      stagger: 0.08,
      overwrite: true,
      clearProps: 'willChange',
    });

  ScrollTrigger.batch(reveals, {
    start: 'top 88%',
    once: true,
    onEnter: show,
    // Landing on `/#features` puts everything above the anchor behind the
    // scroll position, where `onEnter` never fires. Without this they stay at
    // opacity 0 for anyone who then scrolls up.
    onEnterBack: show,
  });

  return () => {
    // matchMedia cleanup: drop the tweens, and let CSS own the page again.
    gsap.set([...heroParts, ...reveals], { clearProps: 'all' });
  };
});

// ---- Header shadow -------------------------------------------------------
// Outside matchMedia on purpose: it is a state change, not an animation, and a
// reduced-motion reader still benefits from the header separating from content.
const header = document.querySelector<HTMLElement>('[data-site-header]');
if (header) {
  ScrollTrigger.create({
    start: 'top -40',
    end: 99999,
    onToggle: (self) => header.classList.toggle('is-scrolled', self.isActive),
  });
}
