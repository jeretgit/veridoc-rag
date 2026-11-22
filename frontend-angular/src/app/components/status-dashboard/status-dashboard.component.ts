import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HealthService } from '../../services/health.service';
import { HealthResponse } from '../../models/health.model';

@Component({
  selector: 'app-status-dashboard',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="dashboard">
      <h1>veridoc-rag System Status</h1>

      <div class="status-grid" *ngIf="health">
        <div class="status-card" [class.up]="isUp(health.backend)" [class.down]="!isUp(health.backend)">
          <span class="label">Backend</span>
          <span class="status">{{ health.backend }}</span>
        </div>
        <div class="status-card" [class.up]="isUp(health.database)" [class.down]="!isUp(health.database)">
          <span class="label">Database</span>
          <span class="status">{{ health.database }}</span>
        </div>
        <div class="status-card" [class.up]="isUp(health.aiService)" [class.down]="!isUp(health.aiService)">
          <span class="label">AI Service</span>
          <span class="status">{{ health.aiService }}</span>
        </div>
        <div class="status-card info">
          <span class="label">LLM Provider</span>
          <span class="status">{{ health.llmProvider }}</span>
        </div>
      </div>

      <div class="loading" *ngIf="loading && !health">Loading...</div>
      <div class="error" *ngIf="error">{{ error }}</div>

      <button (click)="refresh()" [disabled]="loading">
        {{ loading ? 'Refreshing...' : 'Refresh Status' }}
      </button>
    </div>
  `,
  styles: [`
    .dashboard {
      max-width: 900px;
      margin: 40px auto;
      padding: 0 20px;
      text-align: center;
    }
    h1 {
      font-size: 1.8rem;
      margin-bottom: 30px;
      color: #1a1a2e;
    }
    .status-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 16px;
      margin-bottom: 30px;
    }
    .status-card {
      padding: 24px 16px;
      border-radius: 12px;
      background: #fff;
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .status-card.up {
      border-left: 4px solid #22c55e;
    }
    .status-card.down {
      border-left: 4px solid #ef4444;
    }
    .status-card.info {
      border-left: 4px solid #3b82f6;
    }
    .label {
      font-size: 0.85rem;
      color: #6b7280;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    .status {
      font-size: 1.2rem;
      font-weight: 600;
      color: #1a1a2e;
    }
    .loading, .error {
      padding: 20px;
      color: #6b7280;
      margin-bottom: 20px;
    }
    .error {
      color: #ef4444;
    }
    button {
      padding: 10px 28px;
      font-size: 1rem;
      border: none;
      border-radius: 8px;
      background: #2563eb;
      color: #fff;
      cursor: pointer;
      transition: background 0.2s;
    }
    button:hover:not(:disabled) {
      background: #1d4ed8;
    }
    button:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
  `],
})
export class StatusDashboardComponent implements OnInit {
  private healthService = inject(HealthService);

  health: HealthResponse | null = null;
  loading = false;
  error = '';

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.loading = true;
    this.error = '';
    this.healthService.getHealth().subscribe({
      next: (data) => {
        this.health = data;
        this.loading = false;
      },
      error: (err) => {
        this.error = 'Failed to fetch health status: ' + err.message;
        this.loading = false;
      },
    });
  }

  isUp(status: string): boolean {
    return status === 'up' || status === 'ok';
  }
}
