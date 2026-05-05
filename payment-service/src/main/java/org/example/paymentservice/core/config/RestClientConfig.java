package org.example.paymentservice.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    //Note: WireMock should support HTTP/2 and normally fall back to HTTP/1.1 but for some reason it's bugged

    @Value("${app.rest-client.connect-timeout-ms:3000}")
    private long connectTimeoutMs;

    @Value("${app.rest-client.read-timeout-ms:3000}")
    private long readTimeoutMs;

    @Bean
    public RestClient restClient() {
        var client = (HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build());

        var requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

}
