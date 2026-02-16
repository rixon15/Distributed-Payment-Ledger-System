package org.example.paymentservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SuppressWarnings("java:S1118")
@SpringBootApplication
@EnableRetry
@EnableScheduling
public class PaymentServiceApplication {

    static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }

}
