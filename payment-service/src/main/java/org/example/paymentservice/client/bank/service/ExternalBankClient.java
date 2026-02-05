package org.example.paymentservice.client.bank.service;

import org.example.paymentservice.client.bank.dto.BankPaymentRequest;
import org.example.paymentservice.client.bank.dto.BankPaymentResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ExternalBankClient {

    private final RestClient restClient;

    public ExternalBankClient(@Value("${app.bank.url}") String bankUrl) {
        this.restClient = RestClient.builder().baseUrl(bankUrl).build();
    }

    public BankPaymentResponse processPayment(BankPaymentRequest request) {
        return restClient.put()
                .uri("/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(BankPaymentResponse.class);
    }

}
