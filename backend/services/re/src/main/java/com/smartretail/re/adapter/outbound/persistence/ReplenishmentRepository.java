package com.smartretail.re.adapter.outbound.persistence;

import com.smartretail.re.domain.model.PoLineItem;
import com.smartretail.re.domain.model.PurchaseOrder;
import com.smartretail.re.domain.model.ReplenishmentRule;
import com.smartretail.re.domain.model.WorkflowStatus;
import com.smartretail.re.port.outbound.ReplenishmentRepositoryPort;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence adapter for the replenishment schema.
 * All SQL is confined to replenishment.* — no cross-schema joins (C1).
 */
@Repository
public class ReplenishmentRepository implements ReplenishmentRepositoryPort {

    // -------------------------------------------------------------------------
    // Rule SQL
    // -------------------------------------------------------------------------

    private static final String FIND_ACTIVE_RULE_SQL = """
            SELECT rule_id, supplier_id, sku_id, dc_id, lead_time_days, moq,
                   cost_per_unit, auto_approve_threshold
            FROM replenishment.replenishment_rules
            WHERE sku_id = :skuId AND dc_id = :dcId AND active = true
            LIMIT 1
            """;

    // -------------------------------------------------------------------------
    // Purchase Order SQL
    // -------------------------------------------------------------------------

    private static final String INSERT_PO_SQL = """
            INSERT INTO replenishment.purchase_orders
              (po_id, rule_id, supplier_id, sku_id, dc_id, quantity, total_value,
               workflow_status, version, alert_id, created_at, updated_at)
            VALUES
              (:poId, :ruleId, :supplierId, :skuId, :dcId, :quantity, :totalValue,
               :workflowStatus, 0, :alertId, :createdAt, :updatedAt)
            """;

    /**
     * Optimistic-lock UPDATE — critical: WHERE version = :currentVersion (C2).
     * Returns 0 if no row matched (version conflict), 1 on success.
     */
    private static final String UPDATE_STATUS_SQL = """
            UPDATE replenishment.purchase_orders
            SET workflow_status   = :newStatus,
                version           = version + 1,
                approved_by       = :approvedBy,
                approved_at       = :approvedAt,
                rejected_by       = :rejectedBy,
                rejected_at       = :rejectedAt,
                rejection_reason  = :rejectionReason,
                updated_at        = NOW()
            WHERE po_id = :poId
              AND version = :currentVersion
            """;

    private static final String FIND_PO_BY_ID_SQL = """
            SELECT po_id, rule_id, supplier_id, sku_id, dc_id, quantity, total_value,
                   workflow_status, version, approved_by, approved_at,
                   rejected_by, rejected_at, rejection_reason, alert_id,
                   created_at, updated_at
            FROM replenishment.purchase_orders
            WHERE po_id = :poId
            """;

    // -------------------------------------------------------------------------
    // Line Item SQL
    // -------------------------------------------------------------------------

    private static final String INSERT_LINE_ITEM_SQL = """
            INSERT INTO replenishment.po_line_items
              (line_id, po_id, sku_id, quantity, unit_cost, line_total)
            VALUES
              (:lineId, :poId, :skuId, :quantity, :unitCost, :lineTotal)
            """;

    private static final String FIND_LINE_ITEMS_SQL = """
            SELECT line_id, po_id, sku_id, quantity, unit_cost, line_total
            FROM replenishment.po_line_items
            WHERE po_id = :poId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ReplenishmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // Rule operations
    // -------------------------------------------------------------------------

    @Override
    public Optional<ReplenishmentRule> findActiveRule(String skuId, String dcId) {
        var params = new MapSqlParameterSource()
                .addValue("skuId", skuId)
                .addValue("dcId", dcId);
        return jdbc.query(FIND_ACTIVE_RULE_SQL, params, RULE_ROW_MAPPER).stream().findFirst();
    }

    // -------------------------------------------------------------------------
    // Purchase Order write operations
    // -------------------------------------------------------------------------

    @Override
    public void savePurchaseOrder(PurchaseOrder po) {
        var params = new MapSqlParameterSource()
                .addValue("poId",           po.getPoId())
                .addValue("ruleId",         po.getRuleId())
                .addValue("supplierId",     po.getSupplierId())
                .addValue("skuId",          po.getSkuId())
                .addValue("dcId",           po.getDcId())
                .addValue("quantity",       po.getQuantity())
                .addValue("totalValue",     po.getTotalValue())
                .addValue("workflowStatus", po.getWorkflowStatus().name())
                .addValue("alertId",        po.getAlertId())
                .addValue("createdAt",      Timestamp.from(po.getCreatedAt()))
                .addValue("updatedAt",      Timestamp.from(po.getUpdatedAt()));
        jdbc.update(INSERT_PO_SQL, params);
    }

    @Override
    public int updateStatus(UUID poId,
                            WorkflowStatus newStatus,
                            int currentVersion,
                            String approvedBy,
                            Instant approvedAt,
                            String rejectedBy,
                            Instant rejectedAt,
                            String rejectionReason) {
        var params = new MapSqlParameterSource()
                .addValue("poId",            poId)
                .addValue("newStatus",       newStatus.name())
                .addValue("currentVersion",  currentVersion)
                .addValue("approvedBy",      approvedBy)
                .addValue("approvedAt",      approvedAt != null ? Timestamp.from(approvedAt) : null)
                .addValue("rejectedBy",      rejectedBy)
                .addValue("rejectedAt",      rejectedAt != null ? Timestamp.from(rejectedAt) : null)
                .addValue("rejectionReason", rejectionReason);
        return jdbc.update(UPDATE_STATUS_SQL, params);
    }

    // -------------------------------------------------------------------------
    // Line Item write operations
    // -------------------------------------------------------------------------

    @Override
    public void saveLineItem(PoLineItem item) {
        var params = new MapSqlParameterSource()
                .addValue("lineId",   item.getLineId())
                .addValue("poId",     item.getPoId())
                .addValue("skuId",    item.getSkuId())
                .addValue("quantity", item.getQuantity())
                .addValue("unitCost", item.getUnitCost())
                .addValue("lineTotal",item.getLineTotal());
        jdbc.update(INSERT_LINE_ITEM_SQL, params);
    }

    // -------------------------------------------------------------------------
    // Purchase Order read operations
    // -------------------------------------------------------------------------

    @Override
    public Optional<PurchaseOrder> findById(UUID poId) {
        var params = new MapSqlParameterSource("poId", poId);
        return jdbc.query(FIND_PO_BY_ID_SQL, params, PO_ROW_MAPPER).stream().findFirst();
    }

    @Override
    public List<PurchaseOrder> findOrders(String status, String dcId, String skuId,
                                          int page, int size) {
        StringBuilder sql = new StringBuilder("""
                SELECT po_id, rule_id, supplier_id, sku_id, dc_id, quantity, total_value,
                       workflow_status, version, approved_by, approved_at,
                       rejected_by, rejected_at, rejection_reason, alert_id,
                       created_at, updated_at
                FROM replenishment.purchase_orders
                WHERE 1=1
                """);
        var params = new MapSqlParameterSource();
        appendOrderFilters(sql, params, status, dcId, skuId);
        sql.append(" ORDER BY created_at DESC LIMIT :size OFFSET :offset");
        params.addValue("size", size).addValue("offset", (long) page * size);
        return jdbc.query(sql.toString(), params, PO_ROW_MAPPER);
    }

    @Override
    public long countOrders(String status, String dcId, String skuId) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM replenishment.purchase_orders WHERE 1=1");
        var params = new MapSqlParameterSource();
        appendOrderFilters(sql, params, status, dcId, skuId);
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count != null ? count : 0L;
    }

    @Override
    public List<PoLineItem> findLineItemsByPoId(UUID poId) {
        var params = new MapSqlParameterSource("poId", poId);
        return jdbc.query(FIND_LINE_ITEMS_SQL, params, LINE_ITEM_ROW_MAPPER);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void appendOrderFilters(StringBuilder sql, MapSqlParameterSource params,
                                    String status, String dcId, String skuId) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND workflow_status = :status");
            params.addValue("status", status);
        }
        if (dcId != null && !dcId.isBlank()) {
            sql.append(" AND dc_id = :dcId");
            params.addValue("dcId", dcId);
        }
        if (skuId != null && !skuId.isBlank()) {
            sql.append(" AND sku_id = :skuId");
            params.addValue("skuId", skuId);
        }
    }

    // -------------------------------------------------------------------------
    // Row Mappers
    // -------------------------------------------------------------------------

    private static final RowMapper<ReplenishmentRule> RULE_ROW_MAPPER = (rs, rowNum) -> {
        var rule = new ReplenishmentRule();
        rule.setRuleId(UUID.fromString(rs.getString("rule_id")));
        rule.setSupplierId(rs.getString("supplier_id"));
        rule.setSkuId(rs.getString("sku_id"));
        rule.setDcId(rs.getString("dc_id"));
        rule.setLeadTimeDays(rs.getInt("lead_time_days"));
        rule.setMoq(rs.getInt("moq"));
        rule.setCostPerUnit(rs.getBigDecimal("cost_per_unit"));
        rule.setAutoApproveThreshold(rs.getBigDecimal("auto_approve_threshold"));
        rule.setActive(true);
        return rule;
    };

    private static final RowMapper<PurchaseOrder> PO_ROW_MAPPER = (rs, rowNum) -> {
        var po = new PurchaseOrder();
        po.setPoId(UUID.fromString(rs.getString("po_id")));
        po.setRuleId(UUID.fromString(rs.getString("rule_id")));
        po.setSupplierId(rs.getString("supplier_id"));
        po.setSkuId(rs.getString("sku_id"));
        po.setDcId(rs.getString("dc_id"));
        po.setQuantity(rs.getInt("quantity"));
        po.setTotalValue(rs.getBigDecimal("total_value"));
        po.setWorkflowStatus(WorkflowStatus.valueOf(rs.getString("workflow_status")));
        po.setVersion(rs.getInt("version"));
        po.setApprovedBy(rs.getString("approved_by"));
        var approvedAt = rs.getTimestamp("approved_at");
        if (approvedAt != null) po.setApprovedAt(approvedAt.toInstant());
        po.setRejectedBy(rs.getString("rejected_by"));
        var rejectedAt = rs.getTimestamp("rejected_at");
        if (rejectedAt != null) po.setRejectedAt(rejectedAt.toInstant());
        po.setRejectionReason(rs.getString("rejection_reason"));
        var alertId = rs.getString("alert_id");
        if (alertId != null) po.setAlertId(UUID.fromString(alertId));
        po.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        po.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return po;
    };

    private static final RowMapper<PoLineItem> LINE_ITEM_ROW_MAPPER = (rs, rowNum) -> {
        var item = new PoLineItem();
        item.setLineId(UUID.fromString(rs.getString("line_id")));
        item.setPoId(UUID.fromString(rs.getString("po_id")));
        item.setSkuId(rs.getString("sku_id"));
        item.setQuantity(rs.getInt("quantity"));
        item.setUnitCost(rs.getBigDecimal("unit_cost"));
        item.setLineTotal(rs.getBigDecimal("line_total"));
        return item;
    };
}
