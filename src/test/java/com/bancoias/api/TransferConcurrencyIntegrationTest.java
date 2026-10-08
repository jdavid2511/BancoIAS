package com.bancoias.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.bancoias.api.dto.TransferRequest;
import com.bancoias.domain.Account;
import com.bancoias.domain.AccountStatus;
import com.bancoias.domain.RejectionReason;
import com.bancoias.domain.TransferStatus;
import com.bancoias.repositories.AccountRepository;
import com.bancoias.repositories.TransferRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
class TransferConcurrencyIntegrationTest {

    private static final String SOURCE = "CTA-1001";
    private static final String DESTINATION = "CTA-2001";
    private static final long INITIAL_BALANCE = 1_000_000L;
    private static final long AMOUNT = 400_000L;
    private static final int TOTAL = 20;

    @Autowired
    private WebTestClient client;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransferRepository transferRepository;
    @Autowired
    private DatabaseClient databaseClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void resetState() {
        databaseClient.sql("DELETE FROM transfer").fetch().rowsUpdated().block();
        databaseClient.sql("DELETE FROM account").fetch().rowsUpdated().block();
        insertAccount(SOURCE, "USR-10", "ACTIVE", INITIAL_BALANCE);
        insertAccount("CTA-1002", "USR-10", "BLOCKED", 800_000L);
        insertAccount(DESTINATION, "USR-20", "ACTIVE", 2_000_000L);
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

    @Test
    void concurrentTransfersNeverLeaveAccountNegative() throws Exception {
        List<Captured> results = runConcurrent(TOTAL);

        long authorized = results.stream()
                .filter(r -> r.status().equals(TransferStatus.AUTHORIZED.name()))
                .count();
        long rejectedInsufficient = results.stream()
                .filter(r -> r.status().equals(TransferStatus.REJECTED.name())
                        && r.reason().equals(RejectionReason.INSUFFICIENT_BALANCE.name()))
                .count();

        int expectedAuthorized = (int) Math.floorDiv(INITIAL_BALANCE, AMOUNT);
        assertThat(authorized).isEqualTo(expectedAuthorized);
        assertThat(rejectedInsufficient).isEqualTo(TOTAL - expectedAuthorized);

        Account source = accountRepository.findById(SOURCE).block();
        assertThat(source.getAvailableBalance()).isEqualTo(INITIAL_BALANCE - expectedAuthorized * AMOUNT);
        assertThat(source.getAvailableBalance()).isGreaterThanOrEqualTo(0);

        assertThat(transferRepository.count().block()).isEqualTo(TOTAL);
    }

    private List<Captured> runConcurrent(int total) throws Exception {
        List<Captured> results = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 1; i <= total; i++) {
                String reference = "REF-C-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        startGate.await();
                        String body = client.post().uri("/api/transfers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(new TransferRequest(reference, SOURCE, DESTINATION, AMOUNT))
                                .exchange()
                                .expectStatus().isEqualTo(HttpStatus.CREATED)
                                .expectBody(String.class)
                                .returnResult()
                                .getResponseBody();
                        results.add(parse(body));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        return results;
    }

    private Captured parse(String body) throws Exception {
        JsonNode json = objectMapper.readTree(body);
        String status = json.at("/data/status").asText();
        JsonNode reasonNode = json.at("/data/reason");
        String reason = reasonNode.isMissingNode() || reasonNode.isNull() ? null : reasonNode.asText();
        return new Captured(status, reason);
    }

    private record Captured(String status, String reason) {
    }
}
