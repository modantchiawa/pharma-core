import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { Toaster } from './shared/ui/toaster';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, Toaster],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '<router-outlet /><app-toaster />',
})
export class App {}
