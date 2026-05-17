export type AlertSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM'
export type AlertType = 'LOW_STOCK' | 'OVERSTOCK'

export interface AlertKpi {
  criticalCount: number
  highCount: number
  mediumCount: number
  totalActive: number
}

export interface StockAlertSummary {
  alertId: string
  skuId: string
  dcId: string
  alertType: AlertType
  severity: AlertSeverity
  onHand: number
  reorderPoint: number
  raisedAt: string
}

export interface StoreManagerDashboardResponse {
  dcId: string
  alertKpi: AlertKpi
  totalOnHandUnits: number
  pendingReplenishmentCount: number
  forecastCoveragePct: number
  alerts: StockAlertSummary[]
  alertsPage: number
  alertsTotalPages: number
  dataFreshness: string
}
