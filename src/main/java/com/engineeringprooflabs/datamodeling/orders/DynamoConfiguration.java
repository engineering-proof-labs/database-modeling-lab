package com.engineeringprooflabs.datamodeling.orders;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Configuration(proxyBeanMethods = false)
@Profile("dynamodb")
class DynamoConfiguration {

    @Bean(destroyMethod = "close")
    DynamoDbClient dynamoDbClient(@Value("${dynamodb.endpoint}") URI endpoint) {
        return DynamoDbClient.builder()
                .endpointOverride(endpoint)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(10)))
                .build();
    }
}
