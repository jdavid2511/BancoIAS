package com.bancoias.service;

import lombok.RequiredArgsConstructor;
import com.bancoias.api.dto.TransferRequest;
import com.bancoias.api.dto.TransferResponse;
import com.bancoias.domain.AccountStatus;
import com.bancoias.domain.RejectionReason;
import com.bancoias.domain.Transfer;
import com.bancoias.domain.TransferStatus;
import com.bancoias.repositories.AccountRepository;
import com.bancoias.repositories.TransferRepository;
import java.time.Clock;
import java.time.Instant;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;
    private final TransactionalOperator transactionalOperator;
    private final Clock clock;
    private final PublisherRMQ publisherRMQ;


    public Mono<TransferResponse> process(TransferRequest request) {
        return evaluateRules(request)
                .flatMap(decision -> {
                    if (decision.status() == TransferStatus.REJECTED) {
                        return persistRejected(request, decision.reason());
                    }
                    //send message to rabbitmq
                    publisherRMQ.sendMessage(request);
                    return processAuthorized(request);
                })
                .onErrorResume(DuplicateKeyException.class, e ->
                        transferRepository.findByRequestReference(request.requestReference())
                                .map(TransferResponse::from));
    }

    public Mono<TransferResponse> findByReference(String requestReference) {
        return transferRepository.findByRequestReference(requestReference)
                .map(TransferResponse::from);
    }

    public Flux<TransferResponse> findRecent(int limit) {
        return transferRepository.findRecent(limit)
                .map(TransferResponse::from);
    }

    private Mono<Decision> evaluateRules(TransferRequest request) {
        if (request.amount() <= 0) {
            return Mono.just(Decision.rejected(RejectionReason.AMOUNT_NOT_POSITIVE));
        }
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            return Mono.just(Decision.rejected(RejectionReason.SAME_ACCOUNT));
        }
        return accountRepository.findById(request.sourceAccountId())
                .flatMap(source -> accountRepository.findById(request.destinationAccountId())
                        .map(destination -> {
                            if (source.getStatus() != AccountStatus.ACTIVE) {
                                return Decision.rejected(RejectionReason.SOURCE_ACCOUNT_BLOCKED);
                            }
                            return Decision.authorized();
                        })
                        .switchIfEmpty(Mono.just(Decision.rejected(RejectionReason.ACCOUNT_NOT_FOUND))))
                .switchIfEmpty(Mono.just(Decision.rejected(RejectionReason.ACCOUNT_NOT_FOUND)));
    }

    private Mono<TransferResponse> persistRejected(TransferRequest request, RejectionReason reason) {
        Transfer transfer = Transfer.rejected(request.requestReference(),
                request.sourceAccountId(), request.destinationAccountId(),
                request.amount(), reason, now());
        return transferRepository.save(transfer).map(TransferResponse::from);
    }

    private Mono<TransferResponse> processAuthorized(TransferRequest request) {
        return transactionalOperator.transactional(
                accountRepository.tryDebit(request.sourceAccountId(), request.amount())
                        .flatMap(affected -> {
                            if (affected == 1) {
                                Transfer transfer = Transfer.authorized(request.requestReference(),
                                        request.sourceAccountId(), request.destinationAccountId(),
                                        request.amount(), now());
                                return transferRepository.save(transfer).map(TransferResponse::from);
                            }
                            Transfer transfer = Transfer.rejected(request.requestReference(),
                                    request.sourceAccountId(), request.destinationAccountId(),
                                    request.amount(), RejectionReason.INSUFFICIENT_BALANCE, now());
                            return transferRepository.save(transfer).map(TransferResponse::from);
                        }))
                .single();
    }

    private Instant now() {
        return clock.instant();
    }

    private record Decision(TransferStatus status, RejectionReason reason) {

        static Decision authorized() {
            return new Decision(TransferStatus.AUTHORIZED, null);
        }

        static Decision rejected(RejectionReason reason) {
            return new Decision(TransferStatus.REJECTED, reason);
        }
    }
}