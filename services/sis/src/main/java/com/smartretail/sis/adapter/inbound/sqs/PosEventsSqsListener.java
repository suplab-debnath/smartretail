package com.smartretail.sis.adapter.inbound.sqs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartretail.sis.domain.model.IngestionResult;
import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.inbound.SalesEventPort;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Component
@Profile("dev")
public class PosEventsSqsListener {

    private static final Logger log = LoggerFactory.getLogger(PosEventsSqsListener.class);

    private final SalesEventPort salesEventPort;
    private final ObjectMapper objectMapper;

    public PosEventsSqsListener(SalesEventPort salesEventPort, ObjectMapper objectMapper) {
        this.salesEventPort = salesEventPort;
        this.objectMapper = objectMapper;
    }

    @SqsListener("${smartretail.sqs.pos-events-queue-url}")
    public void onPosEvent(String rawMessage) {
        PosEventMessage msg;
        try {
            msg = objectMapper.readValue(rawMessage, PosEventMessage.class);
        } catch (Exception e) {
            log.error("Failed to deserialise POS event — message will be retried or sent to DLQ", e);
            throw new RuntimeException("POS event deserialisation failed", e);
        }

        MDC.put("traceId",       UUID.randomUUID().toString());
        MDC.put("transactionId", msg.transactionId().toString());
        MDC.put("skuId",         msg.skuId());
        MDC.put("dcId",          msg.dcId());
        try {
            SalesTransaction transaction = toSalesTransaction(msg);
            IngestionResult result = salesEventPort.ingest(transaction);
            switch (result) {
                case IngestionResult.Accepted a ->
                    log.info("POS event accepted via SQS: transactionId={}", a.transactionId());
                case IngestionResult.Duplicate d ->
                    log.warn("POS event duplicate — skipped: transactionId={}", d.transactionId());
            }
        } catch (Exception e) {
            log.error("Failed to process POS event — will retry or DLQ: transactionId={}", msg.transactionId(), e);
            throw new RuntimeException("POS SQS processing failed", e);
        } finally {
            MDC.clear();
        }
    }

    private SalesTransaction toSalesTransaction(PosEventMessage msg) {
        return new SalesTransaction(
                msg.transactionId(),
                msg.storeId(),
                msg.skuId(),
                msg.dcId(),
                msg.quantity(),
                msg.unitPrice(),
                SalesTransaction.Channel.valueOf(msg.channel().toUpperCase()),
                msg.eventTimestamp()
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PosEventMessage(
            UUID transactionId,
            String storeId,
            String skuId,
            String dcId,
            int quantity,
            BigDecimal unitPrice,
            String channel,
            Instant eventTimestamp
    ) {}
}
