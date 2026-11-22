import { Component } from '@angular/core';
import { StatusDashboardComponent } from './components/status-dashboard/status-dashboard.component';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [StatusDashboardComponent],
  template: `<app-status-dashboard />`,
})
export class AppComponent {}
