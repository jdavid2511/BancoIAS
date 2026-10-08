package com.bancoias.repositories;

import com.bancoias.domain.Transfer;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TransferRepository extends ReactiveCrudRepository<Transfer, Long> {

    Mono<Transfer> findByRequestReference(String requestReference);

    @Query("SELECT * FROM transfer ORDER BY processed_at DESC LIMIT :limit")
    Flux<Transfer> findRecent(@Param("limit") int limit);
}