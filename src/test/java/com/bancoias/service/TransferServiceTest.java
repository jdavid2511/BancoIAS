package com.bancoias.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bancoias.api.dto.TransferRequest;
import com.bancoias.api.dto.TransferResponse;
import com.bancoias.domain.Account;
import com.bancoias.domain.AccountStatus;
import com.bancoias.domain.RejectionReason;
import com.bancoias.domain.Transfer;
import com.bancoias.domain.TransferStatus;
import com.bancoias.repositories.AccountRepository;
import com.bancoias.repositories.TransferRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private TransferRepository transferRepository;
    @Mock
    private TransactionalOperator transactionalOperator;

    private TransferService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new TransferService(accountRepository, transferRepository, transactionalOperator, clock);
    }

    @Test
    void authorizesTransferWhenRulesPass() {
        stubActiveAccounts();
        when(accountRepository.tryDebit("CTA-1001", 600_000L)).thenReturn(Mono.just(1));
        when(transferRepository.save(any(Transfer.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.process(new TransferRequest("REF-1", "CTA-1001", "CTA-2001", 600_000L)))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(TransferStatus.AUTHORIZED);
                    assertThat(response.reason()).isNull();
                    assertThat(response.processedAt()).isEqualTo(FIXED_NOW);
                    assertThat(response.amount()).isEqualTo(600_000L);
                })
                .verifyComplete();

        ArgumentCaptor<Transfer> captor = ArgumentCaptor.forClass(Transfer.class);
        verify(transferRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(TransferStatus.AUTHORIZED);
        verify(accountRepository).tryDebit("CTA-1001", 600_000L);
    }

    @Test
    void rejectsNonPositiveAmountWithoutDebiting() {
        rejectAndPersist(RejectionReason.AMOUNT_NOT_POSITIVE, new TransferRequest("REF-0", "CTA-1001", "CTA-2001", 0L));
        rejectAndPersist(RejectionReason.AMOUNT_NOT_POSITIVE, new TransferRequest("REF--5", "CTA-1001", "CTA-2001", -5L));
        verify(accountRepository, never()).tryDebit(anyString(), any(long.class));
    }

    @Test
    void rejectsSameSourceAndDestination() {
        rejectAndPersist(RejectionReason.SAME_ACCOUNT, new TransferRequest("REF-S", "CTA-1001", "CTA-1001", 100L));
        verify(accountRepository, never()).tryDebit(anyString(), any(long.class));
    }

    @Test
    void rejectsWhenSourceAccountDoesNotExist() {
        when(accountRepository.findById("CTA-9999")).thenReturn(Mono.empty());

        rejectAndPersist(RejectionReason.ACCOUNT_NOT_FOUND, new TransferRequest("REF-M", "CTA-9999", "CTA-2001", 100L));
        verify(accountRepository, never()).tryDebit(anyString(), any(long.class));
    }

    @Test
    void rejectsWhenDestinationAccountDoesNotExist() {
        when(accountRepository.findById("CTA-1001")).thenReturn(Mono.just(activeAccount("CTA-1001", 1_000_000L)));
        when(accountRepository.findById("CTA-9999")).thenReturn(Mono.empty());

        rejectAndPersist(RejectionReason.ACCOUNT_NOT_FOUND, new TransferRequest("REF-M2", "CTA-1001", "CTA-9999", 100L));
        verify(accountRepository, never()).tryDebit(anyString(), any(long.class));
    }

    @Test
    void rejectsWhenSourceAccountIsBlocked() {
        when(accountRepository.findById("CTA-1002")).thenReturn(Mono.just(blockedAccount("CTA-1002")));
        when(accountRepository.findById("CTA-2001")).thenReturn(Mono.just(activeAccount("CTA-2001", 2_000_000L)));

        rejectAndPersist(RejectionReason.SOURCE_ACCOUNT_BLOCKED, new TransferRequest("REF-B", "CTA-1002", "CTA-2001", 100L));
        verify(accountRepository, never()).tryDebit(anyString(), any(long.class));
    }

    @Test
    void persistsRejectedTransferWhenBalanceInsufficient() {
        stubActiveAccounts();
        when(accountRepository.tryDebit("CTA-1001", 2_000_000L)).thenReturn(Mono.just(0));
        Transfer saved = Transfer.rejected("REF-I", "CTA-1001", "CTA-2001", 2_000_000L,
                RejectionReason.INSUFFICIENT_BALANCE, FIXED_NOW);
        when(transferRepository.save(any(Transfer.class))).thenReturn(Mono.just(saved));

        StepVerifier.create(service.process(new TransferRequest("REF-I", "CTA-1001", "CTA-2001", 2_000_000L)))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(TransferStatus.REJECTED);
                    assertThat(response.reason()).isEqualTo(RejectionReason.INSUFFICIENT_BALANCE);
                })
                .verifyComplete();

        verify(accountRepository).tryDebit("CTA-1001", 2_000_000L);
    }

    @Test
    void returnsOriginalResultWhenReferenceIsDuplicated() {
        stubActiveAccounts();
        when(accountRepository.tryDebit("CTA-1001", 600_000L)).thenReturn(Mono.just(1));
        when(transferRepository.save(any(Transfer.class)))
                .thenReturn(Mono.error(new DuplicateKeyException("UK_TRANSFER_REQUEST_REFERENCE")));

        Transfer original = Transfer.authorized("REF-D", "CTA-1001", "CTA-2001", 600_000L, FIXED_NOW);
        when(transferRepository.findByRequestReference("REF-D")).thenReturn(Mono.just(original));

        StepVerifier.create(service.process(new TransferRequest("REF-D", "CTA-1001", "CTA-2001", 600_000L)))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(TransferStatus.AUTHORIZED);
                    assertThat(response.amount()).isEqualTo(600_000L);
                    assertThat(response.processedAt()).isEqualTo(FIXED_NOW);
                })
                .verifyComplete();

        verify(transferRepository).findByRequestReference("REF-D");
    }

    @Test
    void findsTransferByReference() {
        Transfer transfer = Transfer.authorized("REF-X", "CTA-1001", "CTA-2001", 500_000L, FIXED_NOW);
        when(transferRepository.findByRequestReference("REF-X")).thenReturn(Mono.just(transfer));

        StepVerifier.create(service.findByReference("REF-X"))
                .assertNext(response -> assertThat(response.status()).isEqualTo(TransferStatus.AUTHORIZED))
                .verifyComplete();
    }

    @Test
    void emptyWhenReferenceDoesNotExist() {
        when(transferRepository.findByRequestReference("NOPE")).thenReturn(Mono.empty());

        StepVerifier.create(service.findByReference("NOPE")).verifyComplete();
    }

    private void rejectAndPersist(RejectionReason expectedReason, TransferRequest request) {
        when(transferRepository.save(any(Transfer.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.process(request))
                .assertNext(response -> {
                    assertThat(response.status()).isEqualTo(TransferStatus.REJECTED);
                    assertThat(response.reason()).isEqualTo(expectedReason);
                    assertThat(response.amount()).isEqualTo(request.amount());
                    assertThat(response.processedAt()).isEqualTo(FIXED_NOW);
                })
                .verifyComplete();

        ArgumentCaptor<Transfer> captor = ArgumentCaptor.forClass(Transfer.class);
        verify(transferRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getReason())
                .isEqualTo(expectedReason);
    }

    private void stubActiveAccounts() {
        when(accountRepository.findById("CTA-1001")).thenReturn(Mono.just(activeAccount("CTA-1001", 1_000_000L)));
        when(accountRepository.findById("CTA-2001")).thenReturn(Mono.just(activeAccount("CTA-2001", 2_000_000L)));
    }

    private Account activeAccount(String id, long balance) {
        return new Account(id, "USR-X", AccountStatus.ACTIVE, balance);
    }

    private Account blockedAccount(String id) {
        return new Account(id, "USR-X", AccountStatus.BLOCKED, 800_000L);
    }
}