package com.bancoias.repositories;

import com.bancoias.domain.Account;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface AccountRepository extends ReactiveCrudRepository<Account, String> {

    @Modifying
    @Query("""
            UPDATE account
               SET available_balance = available_balance - :amount,
                   version = version + 1
             WHERE id = :accountId
               AND status = 'ACTIVE'
               AND available_balance >= :amount
            """)
    Mono<Integer> tryDebit(@Param("accountId") String accountId, @Param("amount") long amount);
}