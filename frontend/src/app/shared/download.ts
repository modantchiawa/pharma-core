import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

/**
 * Downloads a file served by the API (Excel workbook, CSV): the request carries the session's
 * token, which a plain link would not, then the browser saves the result under {@code fileName}.
 */
export async function downloadFile(http: HttpClient, url: string, fileName: string): Promise<void> {
  const blob = await firstValueFrom(http.get(url, { responseType: 'blob' }));
  const link = document.createElement('a');
  link.href = URL.createObjectURL(blob);
  link.download = fileName;
  link.click();
  setTimeout(() => URL.revokeObjectURL(link.href), 1_000);
}
