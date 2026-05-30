package com.smartretail.sis.adapter.outbound.archive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartretail.sis.domain.model.SalesTransaction;
import com.smartretail.sis.port.outbound.RawArchivePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.firehose.FirehoseClient;
import software.amazon.awssdk.services.firehose.model.PutRecordRequest;
import software.amazon.awssdk.services.firehose.model.Record;

@Component
public class FirehoseArchiveAdapter implements RawArchivePort {

    private static final Logger log = LoggerFactory.getLogger(FirehoseArchiveAdapter.class);

    private final FirehoseClient firehose;
    private final String deliveryStreamName;
    private final ObjectMapper objectMapper;

    public FirehoseArchiveAdapter(
            FirehoseClient firehose,
            @Value("${smartretail.firehose.delivery-stream}") String deliveryStreamName) {
        this.firehose = firehose;
        this.deliveryStreamName = deliveryStreamName;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public String archive(SalesTransaction transaction) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(transaction);
            // Firehose concatenates records in S3; newline delimiter ensures valid NDJSON output
            byte[] payload = appendNewline(json);

            firehose.putRecord(PutRecordRequest.builder()
                    .deliveryStreamName(deliveryStreamName)
                    .record(Record.builder()
                            .data(SdkBytes.fromByteArray(payload))
                            .build())
                    .build());

            log.debug("Archived raw event to Firehose: transactionId={}", transaction.transactionId());
            return "firehose://" + deliveryStreamName + "/" + transaction.transactionId();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to archive event " + transaction.transactionId() + " to Firehose", e);
        }
    }

    private static byte[] appendNewline(byte[] payload) {
        byte[] result = new byte[payload.length + 1];
        System.arraycopy(payload, 0, result, 0, payload.length);
        result[payload.length] = '\n';
        return result;
    }
}
