package com.smartretail.sis.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Configuration
@Profile("local")
public class LocalStackConfig {

    @Value("${smartretail.localstack.endpoint:http://localhost:4566}")
    private String localstackEndpoint;

    private static final Region REGION = Region.US_EAST_1;
    private static final StaticCredentialsProvider FAKE_CREDS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));

    @Bean
    public DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder()
                .endpointOverride(URI.create(localstackEndpoint))
                .region(REGION)
                .credentialsProvider(FAKE_CREDS)
                .build();
    }

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(localstackEndpoint))
                .region(REGION)
                .credentialsProvider(FAKE_CREDS)
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    public EventBridgeClient eventBridgeClient() {
        return EventBridgeClient.builder()
                .endpointOverride(URI.create(localstackEndpoint))
                .region(REGION)
                .credentialsProvider(FAKE_CREDS)
                .build();
    }
}
