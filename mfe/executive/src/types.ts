export type Trend = 'IMPROVING' | 'STABLE' | 'DEGRADING'
export type DirectionTrend = 'INCREASING' | 'STABLE' | 'DECREASING'

export interface MapeDataPoint {
  runDate: string
  mape: number
}

export interface ForecastAccuracyKpi {
  latestMape: number
  trend: Trend
  history: MapeDataPoint[]
}

export interface StockoutAlertDataPoint {
  alertDate: string
  criticalCount: number
}

export interface StockoutFrequencyKpi {
  last30Days: number
  trend: DirectionTrend
  history: StockoutAlertDataPoint[]
}

export interface CycleTimeDataPoint {
  weekStart: string
  averageDays: number
  poCount: number
}

export interface ReplenishmentCycleTimeKpi {
  averageDays: number
  trend: Trend
  history: CycleTimeDataPoint[]
}

export interface OnTimeDeliveryKpi {
  rate: number
  trend: Trend
}

export interface SupplierPerformanceEntry {
  supplierId: string
  supplierName: string
  otdRate: number
  fillRate: number
  earlyCount: number
  onTimeCount: number
  lateCount: number
  openExceptions: number
}

export interface ExecutiveKpis {
  forecastAccuracy: ForecastAccuracyKpi
  stockoutFrequency: StockoutFrequencyKpi
  replenishmentCycleTime: ReplenishmentCycleTimeKpi
  onTimeDelivery: OnTimeDeliveryKpi
  supplierPerformance: SupplierPerformanceEntry[]
}

export interface ExecutiveDashboardResponse {
  kpis: ExecutiveKpis
  dataFreshness: string
}
