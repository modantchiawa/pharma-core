import { Injectable, signal } from '@angular/core';

export type ToastKind = 'success' | 'error' | 'info';

export interface Toast {
  id: number;
  kind: ToastKind;
  message: string;
}

/** How long a toast stays on screen; errors stay longer so they can be read. */
const DURATION_MS: Record<ToastKind, number> = { success: 3500, info: 4500, error: 7000 };
const MAX_VISIBLE = 4;

/**
 * Short, non-blocking feedback after an action ("Logo mis à jour"), shown in a corner of the
 * screen by the toaster in the root component, wherever the action was done.
 */
@Injectable({ providedIn: 'root' })
export class ToastService {
  private readonly items = signal<Toast[]>([]);
  private nextId = 1;

  readonly toasts = this.items.asReadonly();

  success(message: string): void {
    this.show('success', message);
  }

  error(message: string): void {
    this.show('error', message);
  }

  info(message: string): void {
    this.show('info', message);
  }

  dismiss(id: number): void {
    this.items.update((items) => items.filter((t) => t.id !== id));
  }

  private show(kind: ToastKind, message: string): void {
    const toast: Toast = { id: this.nextId++, kind, message };
    this.items.update((items) => [...items, toast].slice(-MAX_VISIBLE));
    setTimeout(() => this.dismiss(toast.id), DURATION_MS[kind]);
  }
}
