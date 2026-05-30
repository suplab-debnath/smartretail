package com.smartretail.sis.adapter.outbound.persistence;

import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.outbound.EventStorePort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Repository
public class SalesEventRepository implements EventStorePort {

    private static final String INSERT_SALES_EVENT_SQL = """
            INSERT INTO sales.sales_events
              (transaction_id, event_date, store_id, sku_id, dc_id,
               quantity, unit_price, channel, event_timestamp)
            VALUES
              (:transactionId, :eventDate, :storeId, :skuId, :dcId,
               :quantity, :unitPrice, :channel, :eventTimestamp)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SalesEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(SalesTransaction transaction) {
        LocalDate eventDate = transaction.eventTimestamp()
                .atZone(ZoneOffset.UTC).toLocalDate();

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("transactionId", transaction.transactionId())
                .addValue("eventDate", eventDate)
                .addValue("storeId", transaction.storeId())
                .addValue("skuId", transaction.skuId())
                .addValue("dcId", transaction.dcId())
                .addValue("quantity", transaction.quantity())
                .addValue("unitPrice", transaction.unitPrice())
                .addValue("channel", transaction.channel().name())
                .addValue("eventTimestamp", Timestamp.from(transaction.eventTimestamp()));

        jdbc.update(INSERT_SALES_EVENT_SQL, params);
    }
}
