package com.smartretail.ims.adapter.outbound.persistence;

import com.smartretail.ims.domain.model.InventoryPosition;
import com.smartretail.ims.domain.model.StockAlert;
import com.smartretail.ims.port.outbound.InventoryRepositoryPort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InventoryRepository implements InventoryRepositoryPort {

    private static final String FIND_BY_SKU_DC_SQL = """
            SELECT position_id, sku_id, dc_id, on_hand, in_transit, reserved,
                   reorder_point, safety_stock, version, last_updated_at
            FROM inventory.inventory_positions
            WHERE sku_id = :skuId AND dc_id = :dcId
            """;

    private static final String FIND_BY_ID_SQL = """
            SELECT position_id, sku_id, dc_id, on_hand, in_transit, reserved,
                   reorder_point, safety_stock, version, last_updated_at
            FROM inventory.inventory_positions
            WHERE position_id = :positionId
            """;

    private static final String DECREMENT_SQL = """
            UPDATE inventory.inventory_positions
            SET on_hand = on_hand - :quantity,
                last_updated_at = NOW(),
                version = version + 1
            WHERE position_id = :positionId
              AND version = :version
              AND on_hand >= :quantity
            """;

    private static final String INSERT_ALERT_SQL = """
            INSERT INTO inventory.stock_alerts
              (alert_id, position_id, alert_type, severity,
               threshold_value, actual_value, status, raised_at)
            VALUES
              (:alertId, :positionId, :alertType, :severity,
               :thresholdValue, :actualValue, :status, :raisedAt)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public InventoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<InventoryPosition> findBySkuAndDc(String skuId, String dcId) {
        var params = new MapSqlParameterSource()
                .addValue("skuId", skuId)
                .addValue("dcId", dcId);
        return jdbc.query(FIND_BY_SKU_DC_SQL, params, POSITION_ROW_MAPPER).stream().findFirst();
    }

    @Override
    public Optional<InventoryPosition> findById(UUID positionId) {
        var params = new MapSqlParameterSource("positionId", positionId);
        return jdbc.query(FIND_BY_ID_SQL, params, POSITION_ROW_MAPPER).stream().findFirst();
    }

    @Override
    public int decrementOnHand(UUID positionId, int quantity, int currentVersion) {
        var params = new MapSqlParameterSource()
                .addValue("positionId", positionId)
                .addValue("quantity", quantity)
                .addValue("version", currentVersion);
        return jdbc.update(DECREMENT_SQL, params);
    }

    @Override
    public void saveAlert(StockAlert alert) {
        var params = new MapSqlParameterSource()
                .addValue("alertId", alert.getAlertId())
                .addValue("positionId", alert.getPositionId())
                .addValue("alertType", alert.getAlertType().name())
                .addValue("severity", alert.getSeverity().name())
                .addValue("thresholdValue", alert.getThresholdValue())
                .addValue("actualValue", alert.getActualValue())
                .addValue("status", alert.getStatus())
                .addValue("raisedAt", Timestamp.from(alert.getRaisedAt()));
        jdbc.update(INSERT_ALERT_SQL, params);
    }

    @Override
    public List<InventoryPosition> findPositions(String dcId, String skuId, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT position_id, sku_id, dc_id, on_hand, in_transit, reserved,
                       reorder_point, safety_stock, version, last_updated_at
                FROM inventory.inventory_positions
                WHERE 1=1
                """);
        var params = new MapSqlParameterSource();
        appendFilters(sql, params, dcId, skuId);
        sql.append(" ORDER BY dc_id, sku_id LIMIT :size OFFSET :offset");
        params.addValue("size", size).addValue("offset", (long) page * size);
        return jdbc.query(sql.toString(), params, POSITION_ROW_MAPPER);
    }

    @Override
    public long countPositions(String dcId, String skuId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM inventory.inventory_positions WHERE 1=1");
        var params = new MapSqlParameterSource();
        appendFilters(sql, params, dcId, skuId);
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public List<com.smartretail.ims.domain.model.StockAlert> findAlerts(
            String dcId, String severity, String status, int page, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT sa.alert_id, sa.position_id, ip.sku_id, ip.dc_id,
                       sa.alert_type, sa.severity, sa.threshold_value, sa.actual_value,
                       sa.status, sa.raised_at
                FROM inventory.stock_alerts sa
                JOIN inventory.inventory_positions ip ON sa.position_id = ip.position_id
                WHERE 1=1
                """);
        var params = new MapSqlParameterSource();
        appendAlertFilters(sql, params, dcId, severity, status);
        sql.append(" ORDER BY sa.raised_at DESC LIMIT :size OFFSET :offset");
        params.addValue("size", size).addValue("offset", (long) page * size);
        return jdbc.query(sql.toString(), params, ALERT_ROW_MAPPER);
    }

    @Override
    public long countAlerts(String dcId, String severity, String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM inventory.stock_alerts sa
                JOIN inventory.inventory_positions ip ON sa.position_id = ip.position_id
                WHERE 1=1
                """);
        var params = new MapSqlParameterSource();
        appendAlertFilters(sql, params, dcId, severity, status);
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count != null ? count : 0L;
    }

    private void appendFilters(StringBuilder sql, MapSqlParameterSource params,
                               String dcId, String skuId) {
        if (dcId != null && !dcId.isBlank()) {
            sql.append(" AND dc_id = :dcId");
            params.addValue("dcId", dcId);
        }
        if (skuId != null && !skuId.isBlank()) {
            sql.append(" AND sku_id = :skuId");
            params.addValue("skuId", skuId);
        }
    }

    private void appendAlertFilters(StringBuilder sql, MapSqlParameterSource params,
                                    String dcId, String severity, String status) {
        if (dcId != null && !dcId.isBlank()) {
            sql.append(" AND ip.dc_id = :dcId");
            params.addValue("dcId", dcId);
        }
        if (severity != null && !severity.isBlank()) {
            sql.append(" AND sa.severity = :severity");
            params.addValue("severity", severity);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND sa.status = :status");
            params.addValue("status", status);
        }
    }

    private static final RowMapper<InventoryPosition> POSITION_ROW_MAPPER = (rs, rowNum) -> {
        var pos = new InventoryPosition();
        pos.setPositionId(UUID.fromString(rs.getString("position_id")));
        pos.setSkuId(rs.getString("sku_id"));
        pos.setDcId(rs.getString("dc_id"));
        pos.setOnHand(rs.getInt("on_hand"));
        pos.setInTransit(rs.getInt("in_transit"));
        pos.setReserved(rs.getInt("reserved"));
        pos.setReorderPoint(rs.getInt("reorder_point"));
        pos.setSafetyStock(rs.getInt("safety_stock"));
        pos.setVersion(rs.getInt("version"));
        var ts = rs.getTimestamp("last_updated_at");
        if (ts != null) pos.setLastUpdatedAt(ts.toInstant());
        return pos;
    };

    private static final RowMapper<StockAlert> ALERT_ROW_MAPPER = (rs, rowNum) ->
            StockAlert.fromDb(
                    UUID.fromString(rs.getString("alert_id")),
                    UUID.fromString(rs.getString("position_id")),
                    rs.getString("sku_id"),
                    rs.getString("dc_id"),
                    com.smartretail.ims.domain.model.AlertType.valueOf(rs.getString("alert_type")),
                    com.smartretail.ims.domain.model.AlertSeverity.valueOf(rs.getString("severity")),
                    rs.getInt("threshold_value"),
                    rs.getInt("actual_value"),
                    rs.getString("status"),
                    rs.getTimestamp("raised_at").toInstant()
            );
}
