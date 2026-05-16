package com.smartretail.sis.adapter.outbound.idempotency;

import com.smartretail.sis.port.outbound.IdempotencyPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.Map;

@Component
public class DynamoDbIdempotencyAdapter implements IdempotencyPort {

    private static final long TTL_SECONDS = 48 * 60 * 60;
    private static final String KEY_ATTR = "event_id";
    private static final String TTL_ATTR = "expires_at";

    private final DynamoDbClient dynamoDb;
    private final String tableName;

    public DynamoDbIdempotencyAdapter(
            DynamoDbClient dynamoDb,
            @Value("${smartretail.dynamodb.idempotency-table}") String tableName) {
        this.dynamoDb = dynamoDb;
        this.tableName = tableName;
    }

    @Override
    public boolean isDuplicate(String eventId) {
        var response = dynamoDb.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(KEY_ATTR, AttributeValue.fromS(eventId)))
                .build());
        return response.hasItem();
    }

    @Override
    public void markProcessed(String eventId) {
        long expiresAt = Instant.now().getEpochSecond() + TTL_SECONDS;
        dynamoDb.putItem(PutItemRequest.builder()
                .tableName(tableName)
                .item(Map.of(
                        KEY_ATTR, AttributeValue.fromS(eventId),
                        TTL_ATTR, AttributeValue.fromN(String.valueOf(expiresAt))
                ))
                .build());
    }
}
