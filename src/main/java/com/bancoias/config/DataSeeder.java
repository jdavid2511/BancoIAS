package com.bancoias.config;

import com.bancoias.domain.Account;
import com.bancoias.domain.AccountStatus;
import com.bancoias.domain.Client;
import com.bancoias.domain.ClientType;
import com.bancoias.repositories.AccountRepository;
import com.bancoias.repositories.ClientRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final ClientRepository clientRepository;
    private final AccountRepository accountRepository;

    public DataSeeder(ClientRepository clientRepository, AccountRepository accountRepository) {
        this.clientRepository = clientRepository;
        this.accountRepository = accountRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed()
                .doOnSuccess(v -> log.info("Datos iniciales verificados/insertados"))
                .onErrorResume(e -> {
                    log.warn("No se pudieron insertar los datos iniciales: {}", e.getMessage());
                    return Mono.empty();
                })
                .block();
    }

    private Mono<Void> seed() {
        return accountRepository.count()
                .flatMap(count -> {
                    if (count > 0) {
                        return Mono.empty();
                    }
                    Client usr10 = new Client("USR-10", "Cliente Ejemplo 10", ClientType.PERSON);
                    Client usr20 = new Client("USR-20", "Cliente Ejemplo 20", ClientType.COMPANY);
                    Account cta1001 = new Account("CTA-1001", "USR-10", AccountStatus.ACTIVE, 1_000_000L);
                    Account cta1002 = new Account("CTA-1002", "USR-10", AccountStatus.BLOCKED, 800_000L);
                    Account cta2001 = new Account("CTA-2001", "USR-20", AccountStatus.ACTIVE, 2_000_000L);
                    return clientRepository.saveAll(Flux.just(usr10, usr20))
                            .thenMany(accountRepository.saveAll(Flux.just(cta1001, cta1002, cta2001)))
                            .then();
                });
    }
}