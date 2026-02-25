package com.openfashion.ledgerservice.controller;

import com.openfashion.ledgerservice.dto.ReleaseRequest;
import com.openfashion.ledgerservice.dto.ReservationRequest;
import com.openfashion.ledgerservice.service.LedgerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final LedgerService ledgerService;

    public AccountController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @PostMapping("/reserve")
    public ResponseEntity<Void> reserveFunds(@RequestBody ReservationRequest request) {
        ledgerService.reserveFunds(request);
        return new ResponseEntity<>(HttpStatus.CREATED);
    }

    @PostMapping("/release-reserve")
    public ResponseEntity<Void> releaseFunds(@RequestBody ReleaseRequest request) {
        ledgerService.releaseFunds(request);
        return new ResponseEntity<>(HttpStatus.OK);
    }

}
