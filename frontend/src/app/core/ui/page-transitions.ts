import { ActivatedRouteSnapshot, ViewTransitionInfo } from '@angular/router';
import { prefersReducedMotion } from '../../shared/ui/motion';

/**
 * Router view transition hook: the content column fades and slides, the top bar title follows
 * (styles.css, :root.page-transition). Skipped with reduced motion and when the screen stays the
 * same (tab or filter kept in the query string), so that a filter never makes the page blink.
 * The view-transition names live on <html> only for the duration of the transition: a lasting
 * name would make `main` a stacking context for its dialogs.
 */
export function onPageTransition({ transition, from, to }: ViewTransitionInfo): void {
  if (prefersReducedMotion() || leaf(from).routeConfig === leaf(to).routeConfig) {
    transition.skipTransition();
    return;
  }
  const root = document.documentElement;
  root.classList.add('page-transition');
  const done = () => root.classList.remove('page-transition');
  transition.finished.then(done, done);
}

function leaf(snapshot: ActivatedRouteSnapshot): ActivatedRouteSnapshot {
  let current = snapshot;
  while (current.firstChild) {
    current = current.firstChild;
  }
  return current;
}
