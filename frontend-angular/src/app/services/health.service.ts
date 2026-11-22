import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { HealthResponse } from '../models/health.model';

@Injectable({
  providedIn: 'root',
})
export class HealthService {
  private http = inject(HttpClient);
  private readonly apiUrl = '/api/health';

  getHealth(): Observable<HealthResponse> {
    return this.http.get<HealthResponse>(this.apiUrl);
  }
}
