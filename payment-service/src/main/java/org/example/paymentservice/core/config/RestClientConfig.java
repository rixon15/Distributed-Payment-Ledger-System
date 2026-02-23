package org.example.paymentservice.core.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class RestClientConfig {

    //Note: WireMock should support HTTP/2 and normally fall back to HTTP/1.1 but for some reason it's bugged

    @Bean
    public RestClient restClient() {
        var client = (HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
        var requestFactory = new JdkClientHttpRequestFactory(client);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

}
