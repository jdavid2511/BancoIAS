package com.bancoias.repositories;

import com.bancoias.domain.Client;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface ClientRepository extends ReactiveCrudRepository<Client, String> {
}