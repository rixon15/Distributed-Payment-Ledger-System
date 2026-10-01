package org.example.authorizationservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthorizationServiceApplication {

    static void main(String[] args) {
        SpringApplication.run(AuthorizationServiceApplication.class, args);
    }

}
