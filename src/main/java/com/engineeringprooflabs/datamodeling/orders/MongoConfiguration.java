package com.engineeringprooflabs.datamodeling.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

@Configuration
@Profile("mongodb")
class MongoConfiguration {

    @Bean(destroyMethod = "close")
    MongoClient mongoClient(@Value("${mongodb.uri}") String uri) {
        return MongoClients.create(uri);
    }
}
