package com.smartretail.sup.adapter.outbound.persistence;

import com.smartretail.sup.port.outbound.SupplierOrderReadPort;
import com.smartretail.sup.port.outbound.SupplierOrderWritePort;
import com.smartretail.sup.port.outbound.SupplierReadPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Reads from supplier schema only — no cross-schema JOINs.
 * All JOINs are within supplier.supplier_pos, supplier.supplier_records,
 * and supplier.shipment_updates.
 */
@Repository
public class SupplierOrderRepository implements SupplierOrderReadPort, SupplierOrderWritePort, SupplierReadPort {

    /**
     * EXCEPTION rows first, then sorted by eta ascending.
     * Uses LATERAL to get the single most-recent shipment update per PO.
     * All tables are within the supplier schema.
     */
    private static final String SUPPLIER_ORDERS_SQL = """
            SELECT sp.supplier_po_id,
                   sp.po_id,
                   sp.supplier_id,
                   sr.supplier_name,
                   sp.sku_id,
                   sp.dc_id,
                   sp.quantity,
                   sp.po_status         AS shipment_status,
                   sp.confirmed_at,
                   sp.dispatched_at,
                   sp.eta,
                   su.created_at        AS last_update_at
            FROM supplier.supplier_pos sp
            JOIN supplier.supplier_records sr ON sr.supplier_id = sp.supplier_id
            LEFT JOIN LATERAL (
                SELECT created_at
                FROM supplier.shipment_updates
                WHERE supplier_po_id = sp.supplier_po_id
                ORDER BY created_at DESC
                LIMIT 1
            ) su ON true
            WHERE (CAST(:status AS TEXT) IS NULL OR sp.po_status = :status)
            ORDER BY
                CASE WHEN sp.po_status = 'EXCEPTION' THEN 0 ELSE 1 END,
                sp.eta ASC NULLS LAST
            """;

    private static final String DATA_FRESHNESS_SQL = """
            SELECT MAX(su.created_at) AS max_update
            FROM supplier.shipment_updates su
            """;

    private static final String INSERT_SUPPLIER_PO_SQL = """
            INSERT INTO supplier.supplier_pos
                (supplier_po_id, po_id, supplier_id, sku_id, dc_id, quantity, po_status, last_update_at)
            VALUES
                (:supplierPoId, :poId, :supplierId, :skuId, :dcId, :quantity, 'PENDING', NOW())
            """;

    private static final String ALL_SUPPLIERS_SQL = """
            SELECT supplier_id, supplier_name
            FROM supplier.supplier_records
            ORDER BY supplier_name
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SupplierOrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SupplierOrderRow> findSupplierOrders(String shipmentStatus) {
        return jdbc.query(
                SUPPLIER_ORDERS_SQL,
                new MapSqlParameterSource("status", shipmentStatus),
                (rs, rowNum) -> new SupplierOrderRow(
                        toUuid(rs, "supplier_po_id"),
                        toUuid(rs, "po_id"),
                        toUuid(rs, "supplier_id"),
                        rs.getString("supplier_name"),
                        rs.getString("sku_id"),
                        rs.getString("dc_id"),
                        rs.getInt("quantity"),
                        rs.getString("shipment_status"),
                        toInstant(rs, "confirmed_at"),
                        toInstant(rs, "dispatched_at"),
                        rs.getObject("eta", LocalDate.class),
                        toInstant(rs, "last_update_at")
                )
        );
    }

    @Override
    public Instant findDataFreshness() {
        List<Instant> results = jdbc.query(
                DATA_FRESHNESS_SQL,
                new MapSqlParameterSource(),
                (rs, rowNum) -> toInstant(rs, "max_update")
        );
        return (results.isEmpty() || results.getFirst() == null) ? Instant.now() : results.getFirst();
    }

    @Override
    public UUID insertSupplierOrder(UUID poId, UUID supplierId, String skuId, String dcId, int quantity) {
        UUID supplierPoId = UUID.randomUUID();
        try {
            jdbc.update(INSERT_SUPPLIER_PO_SQL, new MapSqlParameterSource()
                    .addValue("supplierPoId", supplierPoId.toString())
                    .addValue("poId", poId.toString())
                    .addValue("supplierId", supplierId.toString())
                    .addValue("skuId", skuId)
                    .addValue("dcId", dcId)
                    .addValue("quantity", quantity));
        } catch (DuplicateKeyException e) {
            throw new DuplicatePoException(poId);
        }
        return supplierPoId;
    }

    @Override
    public List<SupplierRow> findAllSuppliers() {
        return jdbc.query(ALL_SUPPLIERS_SQL, new MapSqlParameterSource(),
                (rs, rowNum) -> new SupplierRow(
                        toUuid(rs, "supplier_id"),
                        rs.getString("supplier_name")));
    }

    private Instant toInstant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(col);
        return ts != null ? ts.toInstant() : null;
    }

    private UUID toUuid(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        String val = rs.getString(col);
        return val != null ? UUID.fromString(val) : null;
    }
}
