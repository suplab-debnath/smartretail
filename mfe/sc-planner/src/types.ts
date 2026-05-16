// Shared
export type ShipmentStatus = 'PENDING' | 'CONFIRMED' | 'DISPATCHED' | 'DELIVERED' | 'EXCEPTION'
export type AlertSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM'
export type AlertType = 'LOW_STOCK' | 'OVERSTOCK'
export type AlertStatus = 'ACTIVE' | 'RESOLVED'
export type StockoutRisk = 'CRITICAL' | 'HIGH' | 'MODERATE' | 'OK'

// ARS — SC Planner Dashboard
export interface ScPlannerForecastAccuracy {
  latestMape: number
  mapeThreshold: number
  lastRunAt: string
  status: 'WITHIN_THRESHOLD' | 'ABOVE_THRESHOLD'
}
export interface ScPlannerDashboardResponse {
  pendingApprovalCount: number
  activeAlertCount: number
  forecastAccuracy: ScPlannerForecastAccuracy
  dataFreshness: string
}

// IMS — Stock Alerts
export interface StockAlert {
  alertId: string
  skuId: string
  dcId: string
  alertType: AlertType
  severity: AlertSeverity
  status: AlertStatus
  onHand: number
  reorderPoint: number
  raisedAt: string
}
export interface StockAlertListResponse {
  alerts: StockAlert[]
  dataFreshness: string
}

// IMS — Inventory Positions
export interface InventoryPosition {
  positionId: string
  skuId: string
  dcId: string
  onHand: number
  inTransit: number
  reserved: number
  atp: number
  reorderPoint: number
  safetyStock: number
  updatedAt: string
}
export interface InventoryPositionListResponse {
  positions: InventoryPosition[]
  dataFreshness: string
}

// DFS — Forecast
export interface ForecastBand {
  forecastDate: string
  p10: number
  p50: number
  p90: number
  actualUnits: number | null
}
export interface ForecastDataResponse {
  skuId: string
  dcId: string
  horizonDays: 7 | 14 | 30
  latestMape: number
  bands: ForecastBand[]
  dataFreshness: string
}

// RE — Purchase Orders
export interface PurchaseOrder {
  poId: string
  supplierId: string
  skuId: string
  dcId: string
  quantity: number
  totalValue: number
  workflowStatus: string
  version: number
  createdAt: string
}
export interface PurchaseOrderListResponse {
  orders: PurchaseOrder[]
  dataFreshness: string
}

// SUP — Supplier Orders
export interface SupplierOrder {
  supplierPoId: string
  poId: string
  supplierId: string
  supplierName: string
  skuId: string
  dcId: string
  quantity: number
  shipmentStatus: ShipmentStatus
  confirmedAt: string | null
  dispatchedAt: string | null
  eta: string | null
  lastUpdateAt: string | null
}
export interface SupplierOrderListResponse {
  orders: SupplierOrder[]
  dataFreshness: string
}

// ARS — Supplier Performance Scorecard
export interface ScPlannerSupplierEntry {
  supplierId: string
  supplierName: string
  onTimeDeliveryRate: number
  poAcknowledgementSlaCompliance: number
  openExceptions: number
  avgLeadTimeVarianceDays: number
  totalPoCount: number
  totalPoValue: number
}
export interface SupplierPerformanceDashboardResponse {
  suppliers: ScPlannerSupplierEntry[]
  dataFreshness: string
}
