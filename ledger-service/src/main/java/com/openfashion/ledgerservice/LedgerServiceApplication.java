package com.openfashion.ledgerservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

@SuppressWarnings("java:S1118")
@SpringBootApplication
@EnableRetry
public class LedgerServiceApplication {

    static void main(String[] args) {
        SpringApplication.run(LedgerServiceApplication.class, args);
    }

}
