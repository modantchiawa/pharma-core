import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ProductImageView } from '../../core/api/models';
import { APP_ENVIRONMENT } from '../../core/config';
import { productImagePath } from '../product-thumb';

/** Object URLs kept in memory; the oldest are released beyond this count. */
const MAX_URLS = 400;
/** Thumbnails start loading a little before they scroll into view. */
const VISIBLE_MARGIN = '200px';

/**
 * Product photos for the thumbnails. The image endpoint is authenticated, so a photo is fetched
 * as a blob through the HTTP client (bearer token) and shown from an object URL. Its URL carries
 * the photo's version. The browser asks the server before reusing a photo (304 without the bytes),
 * so a locked session gets nothing; the service worker serves the photos already seen only when
 * the server cannot be reached (offline counter). One shared observer starts the downloads when
 * the thumbnails come into view.
 */
@Injectable({ providedIn: 'root' })
export class ProductImageService {
  private readonly http = inject(HttpClient);
  private readonly apiUrl = inject(APP_ENVIRONMENT).apiUrl;
  private readonly urls = new Map<string, string>();
  private readonly pending = new Map<string, Promise<string | null>>();
  private readonly watched = new Map<Element, () => void>();
  private observer: IntersectionObserver | null = null;
  /** Versions changed on this terminal since the lists were loaded (upload, removal). */
  private readonly changed = signal<ReadonlyMap<string, string | null>>(new Map());

  imageUrl(productId: string, version: string): string {
    return this.apiUrl + productImagePath(productId, version);
  }

  /** The version to show: the one just uploaded here wins over a list loaded before. */
  versionOf(productId: string, listed: string | null | undefined): string | null {
    const local = this.changed().get(productId);
    return local !== undefined ? local : (listed ?? null);
  }

  cached(productId: string, version: string): string | null {
    return this.urls.get(key(productId, version)) ?? null;
  }

  /** Downloads a photo once, whatever the number of thumbnails asking for it; null if unavailable. */
  load(productId: string, version: string): Promise<string | null> {
    const id = key(productId, version);
    const ready = this.urls.get(id);
    if (ready) {
      return Promise.resolve(ready);
    }
    let running = this.pending.get(id);
    if (!running) {
      running = firstValueFrom(
        this.http.get(this.imageUrl(productId, version), { responseType: 'blob' }),
      )
        .then((blob) => this.remember(id, blob))
        .catch(() => null)
        .finally(() => this.pending.delete(id));
      this.pending.set(id, running);
    }
    return running;
  }

  /** Calls `visible` once the element nears the viewport; returns the cancellation. */
  whenVisible(element: Element, visible: () => void): () => void {
    if (typeof IntersectionObserver === 'undefined') {
      visible();
      return () => undefined;
    }
    this.observer ??= new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            const callback = this.watched.get(entry.target);
            this.unwatch(entry.target);
            callback?.();
          }
        }
      },
      { rootMargin: VISIBLE_MARGIN },
    );
    this.watched.set(element, visible);
    this.observer.observe(element);
    return () => this.unwatch(element);
  }

  /** Sends the prepared photo (see logo-file.ts); the bytes sent are shown at once. */
  async upload(productId: string, photo: Blob): Promise<ProductImageView> {
    const form = new FormData();
    form.append('file', photo, photo instanceof File ? photo.name : 'photo');
    const view = await firstValueFrom(
      this.http.put<ProductImageView>(
        `${this.apiUrl}/v1/inventory/products/${productId}/image`,
        form,
      ),
    );
    if (view.imageVersion) {
      this.remember(key(productId, view.imageVersion), photo);
    }
    this.markChanged(productId, view.imageVersion);
    return view;
  }

  async remove(productId: string): Promise<void> {
    await firstValueFrom(
      this.http.delete<ProductImageView>(`${this.apiUrl}/v1/inventory/products/${productId}/image`),
    );
    this.markChanged(productId, null);
  }

  private markChanged(productId: string, version: string | null): void {
    this.changed.update((current) => new Map(current).set(productId, version));
  }

  private remember(id: string, blob: Blob): string {
    const url = URL.createObjectURL(blob);
    this.urls.set(id, url);
    if (this.urls.size > MAX_URLS) {
      const [oldest, oldestUrl] = this.urls.entries().next().value as [string, string];
      this.urls.delete(oldest);
      URL.revokeObjectURL(oldestUrl);
    }
    return url;
  }

  private unwatch(element: Element): void {
    this.watched.delete(element);
    this.observer?.unobserve(element);
  }
}

function key(productId: string, version: string): string {
  return `${productId}:${version}`;
}
