import { DestroyRef } from '@angular/core';

/**
 * Runs `task` every `periodMs` while the browser tab is visible, and right away when it becomes
 * visible again: a supervision screen left in a background tab costs no request.
 */
export function whileVisible(task: () => void, periodMs: number, destroyRef: DestroyRef): void {
  const timer = setInterval(() => {
    if (document.visibilityState === 'visible') {
      task();
    }
  }, periodMs);
  const onVisibility = () => {
    if (document.visibilityState === 'visible') {
      task();
    }
  };
  document.addEventListener('visibilitychange', onVisibility);
  destroyRef.onDestroy(() => {
    clearInterval(timer);
    document.removeEventListener('visibilitychange', onVisibility);
  });
}
