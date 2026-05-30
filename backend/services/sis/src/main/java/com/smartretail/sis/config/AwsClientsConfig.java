package com.smartretail.sis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.firehose.FirehoseClient;

@Configuration
@Profile("!local")
public class AwsClientsConfig {

    @Bean
    public FirehoseClient firehoseClient() {
        return FirehoseClient.builder()
                .region(Region.US_EAST_1)
                .build();
    }

    @Bean
    public EventBridgeClient eventBridgeClient() {
        return EventBridgeClient.builder()
                .region(Region.US_EAST_1)
                .build();
    }
}
