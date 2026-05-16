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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class S3RawArchiveAdapter implements RawArchivePort {

    private static final Logger log = LoggerFactory.getLogger(S3RawArchiveAdapter.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);

    private final S3Client s3;
    private final String bucket;
    private final ObjectMapper objectMapper;

    public S3RawArchiveAdapter(
            S3Client s3,
            @Value("${smartretail.s3.events-bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public String archive(SalesTransaction transaction) {
        try {
            String date = DATE_FMT.format(transaction.eventTimestamp());
            String key = "events/" + date + "/" + transaction.transactionId() + ".json";
            byte[] payload = objectMapper.writeValueAsBytes(transaction);

            s3.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType("application/json")
                    .build(), RequestBody.fromBytes(payload));

            String s3Uri = "s3://" + bucket + "/" + key;
            log.debug("Archived raw event: {} → {}", transaction.transactionId(), s3Uri);
            return s3Uri;
        } catch (Exception e) {
            throw new RuntimeException("Failed to archive event " + transaction.transactionId() + " to S3", e);
        }
    }
}
