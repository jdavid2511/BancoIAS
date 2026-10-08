package com.bancoias.api;

import com.bancoias.api.dto.TransferRequest;
import com.bancoias.api.dto.TransferResponse;
import com.bancoias.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private static final int DEFAULT_RECENT_LIMIT = 20;
    private static final int MAX_RECENT_LIMIT = 100;

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public Mono<ResponseEntity<ApiEnvelope<TransferResponse>>> create(@Valid @RequestBody Mono<TransferRequest> request) {
        return request.flatMap(transferService::process)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(ApiEnvelope.ok(response)));
    }

    @GetMapping("/{requestReference}")
    public Mono<ResponseEntity<ApiEnvelope<TransferResponse>>> findByReference(@PathVariable String requestReference) {
        return transferService.findByReference(requestReference)
                .map(response -> ResponseEntity.ok(ApiEnvelope.ok(response)))
                .switchIfEmpty(Mono.just(ResponseEntity.notFound()
                        .build()));
    }

    @GetMapping
    public Flux<TransferResponse> findRecent(@RequestParam(defaultValue = "20") int limit) {
        int effectiveLimit = Math.min(Math.max(1, limit), MAX_RECENT_LIMIT);
        return transferService.findRecent(effectiveLimit);
    }
}