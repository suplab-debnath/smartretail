package com.smartretail.sis.adapter.outbound.persistence;

import com.smartretail.sis.domain.model.SalesTransaction;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;

@Entity
@Table(name = "sales_events", schema = "sales")
public class SalesTransactionJpaEntity {

    @EmbeddedId
    private SalesEventId id;

    @Column(name = "store_id", nullable = false, length = 50)
    private String storeId;

    @Column(name = "sku_id", nullable = false, length = 50)
    private String skuId;

    @Column(name = "dc_id", nullable = false, length = 50)
    private String dcId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Column(name = "raw_s3_reference", length = 500)
    private String rawS3Reference;

    protected SalesTransactionJpaEntity() {
    }

    public static SalesTransactionJpaEntity from(SalesTransaction tx, String rawS3Reference) {
        SalesTransactionJpaEntity e = new SalesTransactionJpaEntity();
        e.id = new SalesEventId(
                tx.transactionId(),
                tx.eventTimestamp().atZone(ZoneOffset.UTC).toLocalDate());
        e.storeId = tx.storeId();
        e.skuId = tx.skuId();
        e.dcId = tx.dcId();
        e.quantity = tx.quantity();
        e.unitPrice = tx.unitPrice();
        e.channel = tx.channel().name();
        e.eventTimestamp = tx.eventTimestamp();
        e.rawS3Reference = rawS3Reference;
        return e;
    }

    public SalesEventId getId() {
        return id;
    }

    public String getStoreId() {
        return storeId;
    }

    public String getSkuId() {
        return skuId;
    }

    public String getDcId() {
        return dcId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public String getChannel() {
        return channel;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public String getRawS3Reference() {
        return rawS3Reference;
    }
}
