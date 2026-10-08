package com.bancoias.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.bancoias.api.dto.TransferRequest;
import com.bancoias.domain.Account;
import com.bancoias.domain.AccountStatus;
import com.bancoias.domain.RejectionReason;
import com.bancoias.domain.TransferStatus;
import com.bancoias.repositories.AccountRepository;
import com.bancoias.repositories.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
class TransferApiIntegrationTest {

    @Autowired
    private WebTestClient client;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransferRepository transferRepository;
    @Autowired
    private DatabaseClient databaseClient;

    @BeforeEach
    void resetState() {
        databaseClient.sql("DELETE FROM transfer").fetch().rowsUpdated().block();
        databaseClient.sql("DELETE FROM account").fetch().rowsUpdated().block();
        insertAccount("CTA-1001", "USR-10", "ACTIVE", 1_000_000L);
        insertAccount("CTA-1002", "USR-10", "BLOCKED", 800_000L);
        insertAccount("CTA-2001", "USR-20", "ACTIVE", 2_000_000L);
    }

    @Test
    void processesAndRetrievesAuthorizedTransfer() {
        client.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TransferRequest("REF-001", "CTA-1001", "CTA-2001", 600_000L))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.requestReference").isEqualTo("REF-001")
                .jsonPath("$.data.status").isEqualTo("AUTHORIZED")
                .jsonPath("$.data.amount").isEqualTo(600_000L)
                .jsonPath("$.data.processedAt").isNotEmpty();

        client.get().uri("/api/transfers/REF-001")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("AUTHORIZED")
                .jsonPath("$.data.reason").doesNotExist();

        client.get().uri("/api/transfers")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].requestReference").isEqualTo("REF-001");

        Account source = accountRepository.findById("CTA-1001").block();
        assertThat(source.getAvailableBalance()).isEqualTo(400_000L);
    }

    @Test
    void rejectsNonPositiveAmount() {
        client.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TransferRequest("REF-02", "CTA-1001", "CTA-2001", 0L))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("REJECTED")
                .jsonPath("$.data.reason").isEqualTo("AMOUNT_NOT_POSITIVE");
    }

    @Test
    void rejectsSameSourceAndDestination() {
        client.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TransferRequest("REF-03", "CTA-1001", "CTA-1001", 100L))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("REJECTED")
                .jsonPath("$.data.reason").isEqualTo("SAME_ACCOUNT");
    }

    @Test
    void rejectsWhenAccountDoesNotExist() {
        client.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TransferRequest("REF-04", "CTA-9999", "CTA-2001", 100L))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("REJECTED")
                .jsonPath("$.data.reason").isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void rejectsWhenSourceAccountIsBlocked() {
        client.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TransferRequest("REF-05", "CTA-1002", "CTA-2001", 100L))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("REJECTED")
                .jsonPath("$.data.reason").isEqualTo("SOURCE_ACCOUNT_BLOCKED");
    }

    @Test
    void rejectsWhenBalanceInsufficientAndDoesNotModifyBalance() {
        client.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TransferRequest("REF-06", "CTA-1001", "CTA-2001", 2_000_000L))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("REJECTED")
                .jsonPath("$.data.reason").isEqualTo("INSUFFICIENT_BALANCE");

        Account source = accountRepository.findById("CTA-1001").block();
        assertThat(source.getAvailableBalance()).isEqualTo(1_000_000L);
    }

    @Test
    void isIdempotentForRepeatedReference() {
        TransferRequest first = new TransferRequest("REF-07", "CTA-1001", "CTA-2001", 600_000L);
        TransferRequest duplicate = new TransferRequest("REF-07", "CTA-1001", "CTA-2001", 1L);

        client.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(first).exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody().jsonPath("$.data.status").isEqualTo("AUTHORIZED");

        client.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(duplicate).exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED)
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("AUTHORIZED")
                .jsonPath("$.data.amount").isEqualTo(600_000L);

        assertThat(transferRepository.findByRequestReference("REF-07").block().getAmount()).isEqualTo(600_000L);
        assertThat(transferRepository.count().block()).isEqualTo(1L);
        assertThat(accountRepository.findById("CTA-1001").block().getAvailableBalance()).isEqualTo(400_000L);
    }

    @Test
    void returnsNotFoundForUnknownReference() {
        client.get().uri("/api/transfers/REF-NO-EXISTE")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void returnsBadRequestForMissingFields() {
        client.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"requestReference":"REF-08","destinationAccountId":"CTA-2001","amount":100}
                        """)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void listsTransfersMostRecentFirst() {
        client.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TransferRequest("REF-A", "CTA-1001", "CTA-2001", 100_000L)).exchange()
                .expectStatus().isCreated();

        client.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TransferRequest("REF-B", "CTA-1001", "CTA-2001", 150_000L)).exchange()
                .expectStatus().isCreated();

        client.get().uri("/api/transfers?limit=10")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].requestReference").isEqualTo("REF-B")
                .jsonPath("$[1].requestReference").isEqualTo("REF-A")
                .jsonPath("$.length()").isEqualTo(2);
    }

    @Test
    void persistsAllTransferOutcomes() {
        assertThat(transferRepository.count().block()).isZero();

        client.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TransferRequest("REF-P-1", "CTA-1001", "CTA-2001", 10_000L)).exchange()
                .expectStatus().isCreated();

        client.post().uri("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new TransferRequest("REF-P-2", "CTA-1002", "CTA-2001", 10_000L)).exchange()
                .expectStatus().isCreated();

        assertThat(transferRepository.count().block()).isEqualTo(2L);
        assertThat(transferRepository.findByRequestReference("REF-P-1").block().getStatus())
                .isEqualTo(TransferStatus.AUTHORIZED);
        assertThat(transferRepository.findByRequestReference("REF-P-2").block().getStatus())
                .isEqualTo(TransferStatus.REJECTED);
        assertThat(transferRepository.findByRequestReference("REF-P-2").block().getReason())
                .isEqualTo(RejectionReason.SOURCE_ACCOUNT_BLOCKED);
    }

    private void insertAccount(String id, String clientId, String status, long balance) {
        databaseClient.sql("INSERT INTO account (id, client_id, status, available_balance, version) "
                + "VALUES (:id, :clientId, :status, :balance, 0)")
                .bind("id", id)
                .bind("clientId", clientId)
                .bind("status", status)
                .bind("balance", balance)
                .fetch()
                .rowsUpdated()
                .block();
    }
}
