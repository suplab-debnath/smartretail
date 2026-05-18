package com.smartretail.re.adapter.outbound.persistence;

import com.smartretail.re.domain.model.PoLineItem;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "po_line_items", schema = "replenishment")
public class PoLineItemJpaEntity {

    @Id
    @Column(name = "line_id", nullable = false)
    private UUID lineId;

    @Column(name = "po_id", nullable = false)
    private UUID poId;

    @Column(name = "sku_id", nullable = false, length = 50)
    private String skuId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_cost", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitCost;

    @Column(name = "line_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal lineTotal;

    protected PoLineItemJpaEntity() {
    }

    public static PoLineItemJpaEntity from(PoLineItem item) {
        PoLineItemJpaEntity e = new PoLineItemJpaEntity();
        e.lineId = item.getLineId();
        e.poId = item.getPoId();
        e.skuId = item.getSkuId();
        e.quantity = item.getQuantity();
        e.unitCost = item.getUnitCost();
        e.lineTotal = item.getLineTotal();
        return e;
    }

    public PoLineItem toDomain() {
        return new PoLineItem(lineId, poId, skuId, quantity, unitCost, lineTotal);
    }
}
