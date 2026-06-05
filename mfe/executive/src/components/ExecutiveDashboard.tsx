import { useState, useEffect } from "react";
import { useAuth, ErrorBanner, Tooltip } from "@smartretail/auth";
import { useExecutiveDashboard } from "../hooks/useExecutiveDashboard";
import { KpiCard } from "./KpiCard";
import { MapeTrendChart } from "./MapeTrendChart";
import { ForecastHistoryTable } from "./ForecastHistoryTable";
import { StockoutChart } from "./StockoutChart";
import { StockoutHistoryTable } from "./StockoutHistoryTable";
import { CycleTimeChart } from "./CycleTimeChart";
import { CycleTimeHistoryTable } from "./CycleTimeHistoryTable";
import { SupplierRankingTable } from "./SupplierRankingTable";
import { DeliveryHistogram } from "./DeliveryHistogram";
import type { KpiCardProps } from "./KpiCard";

type CardId = "forecast" | "stockout" | "cycletime" | "otd";

function mapeColor(mape: number): KpiCardProps["color"] {
  if (mape < 0.1) return "green";
  if (mape <= 0.2) return "amber";
  return "red";
}

export function ExecutiveDashboard() {
  const { isAuthenticated, isLoading: authLoading, signIn, signOut, user, hasRole } = useAuth();
  const { data, loading, error, lastUpdated, refresh } = useExecutiveDashboard();
  const [expanded, setExpanded] = useState<CardId | null>(null);

  useEffect(() => {
    if (!authLoading && !isAuthenticated) {
      signIn();
    }
  }, [authLoading, isAuthenticated, signIn]);

  function toggle(id: CardId) {
    setExpanded((prev) => (prev === id ? null : id));
  }

  if (loading || authLoading || !isAuthenticated) {
    return (
      <div className="flex items-center justify-center h-screen bg-gray-100">
        <div className="text-gray-500">
          {authLoading
            ? "Checking authentication..."
            : !isAuthenticated
              ? "Redirecting to login..."
              : "Loading dashboard..."}
        </div>
      </div>
    );
  }

  if (!hasRole("EXECUTIVE") && !hasRole("SC_PLANNER") && !hasRole("ADMIN")) {
    return (
      <div className="flex items-center justify-center h-screen bg-gray-100">
        <div className="text-red-500">Access Denied: You do not have the required role to view this dashboard.</div>
      </div>
    );
  }

  const kpis = data?.kpis;
  const accuracyPct = kpis ? ((1 - kpis.forecastAccuracy.latestMape) * 100).toFixed(1) : "—";

  return (
    <div className="min-h-screen bg-gray-100">
      <header className="bg-white shadow-sm">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-4 flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Executive Insights Dashboard</h1>
            <p className="text-sm text-gray-500">SmartRetail · Demand Forecasting & Supply Chain</p>
          </div>
          <div className="flex items-center gap-4">
            {lastUpdated && <p className="text-xs text-gray-400">Last updated: {lastUpdated.toLocaleTimeString()}</p>}
            <div className="flex flex-col items-end">
              <span className="text-xs text-gray-600 font-medium">{user?.email}</span>
              <button onClick={signOut} className="text-xs text-blue-600 hover:text-blue-800 underline">
                Sign out
              </button>
            </div>
          </div>
        </div>
      </header>

      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-4">
        <ErrorBanner error={error} onRetry={refresh} />

        {/* KPI Cards — 2×2 grid */}
        <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4">
          <KpiCard
            label={<Tooltip term="MAPE">Forecast Accuracy</Tooltip>}
            value={`${accuracyPct}%`}
            trend={kpis?.forecastAccuracy.trend ?? "STABLE"}
            color={kpis ? mapeColor(kpis.forecastAccuracy.latestMape) : "neutral"}
            subtitle={kpis ? `MAPE: ${(kpis.forecastAccuracy.latestMape * 100).toFixed(2)}%` : undefined}
            onClick={() => toggle("forecast")}
            isExpanded={expanded === "forecast"}
          />
          <KpiCard
            label="Stockout Frequency (30d)"
            value={kpis ? String(kpis.stockoutFrequency.last30Days) : "—"}
            trend={kpis?.stockoutFrequency.trend ?? "STABLE"}
            color={
              kpis
                ? kpis.stockoutFrequency.last30Days === 0
                  ? "green"
                  : kpis.stockoutFrequency.last30Days > 10
                    ? "red"
                    : "amber"
                : "neutral"
            }
            subtitle="CRITICAL alerts raised"
            onClick={() => toggle("stockout")}
            isExpanded={expanded === "stockout"}
          />
          <KpiCard
            label={<Tooltip term="REPLENISHMENT_CYCLE_TIME">Replenishment Cycle Time</Tooltip>}
            value={kpis ? `${kpis.replenishmentCycleTime.averageDays}d` : "—"}
            trend={kpis?.replenishmentCycleTime.trend ?? "STABLE"}
            color={
              kpis
                ? kpis.replenishmentCycleTime.averageDays <= 3
                  ? "green"
                  : kpis.replenishmentCycleTime.averageDays <= 5
                    ? "amber"
                    : "red"
                : "neutral"
            }
            subtitle="Avg days DRAFT → DISPATCHED"
            onClick={() => toggle("cycletime")}
            isExpanded={expanded === "cycletime"}
          />
          <KpiCard
            label={<Tooltip term="OTD">On-Time Delivery</Tooltip>}
            value={kpis ? `${(kpis.onTimeDelivery.rate * 100).toFixed(1)}%` : "—"}
            trend={kpis?.onTimeDelivery.trend ?? "STABLE"}
            color={
              kpis
                ? kpis.onTimeDelivery.rate >= 0.9
                  ? "green"
                  : kpis.onTimeDelivery.rate >= 0.75
                    ? "amber"
                    : "red"
                : "neutral"
            }
            subtitle="Aggregate supplier OTD"
            onClick={() => toggle("otd")}
            isExpanded={expanded === "otd"}
          />
        </div>

        {/* Expandable detail panels */}
        {kpis && expanded === "forecast" && (
          <div className="space-y-4 animate-fadeIn">
            <MapeTrendChart history={kpis.forecastAccuracy.history} />
            <ForecastHistoryTable history={kpis.forecastAccuracy.history} />
          </div>
        )}

        {kpis && expanded === "stockout" && (
          <div className="space-y-4 animate-fadeIn">
            <StockoutChart history={kpis.stockoutFrequency.history} />
            <StockoutHistoryTable history={kpis.stockoutFrequency.history} />
          </div>
        )}

        {kpis && expanded === "cycletime" && (
          <div className="space-y-4 animate-fadeIn">
            <CycleTimeChart
              history={kpis.replenishmentCycleTime.history}
              overallAverage={kpis.replenishmentCycleTime.averageDays}
            />
            <CycleTimeHistoryTable history={kpis.replenishmentCycleTime.history} />
          </div>
        )}

        {kpis && expanded === "otd" && (
          <div className="space-y-4 animate-fadeIn">
            <SupplierRankingTable suppliers={kpis.supplierPerformance} />
            <DeliveryHistogram suppliers={kpis.supplierPerformance} />
          </div>
        )}
      </main>
    </div>
  );
}
