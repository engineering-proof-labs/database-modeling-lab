package com.engineeringprooflabs.datamodeling;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class OrdersApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrdersApiApplication.class, args);
    }

    @Bean
    Clock clock(@Value("${lab.clock.instant:}") String fixedInstant) {
        return fixedInstant.isBlank() ? Clock.systemUTC() : Clock.fixed(Instant.parse(fixedInstant), ZoneOffset.UTC);
    }
}
