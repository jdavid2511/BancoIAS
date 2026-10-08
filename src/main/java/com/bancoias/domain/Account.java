package com.bancoias.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

public class Account implements Persistable<String> {

    @Id
    private String id;
    private String clientId;
    private AccountStatus status;
    private long availableBalance;
    private long version;

    @Transient
    private boolean isNewRecord;

    protected Account() {
    }

    public Account(String id, String clientId, AccountStatus status, long availableBalance) {
        this.id = id;
        this.clientId = clientId;
        this.status = status;
        this.availableBalance = availableBalance;
        this.version = 0;
        this.isNewRecord = true;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNewRecord;
    }

    public String getClientId() {
        return clientId;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public long getAvailableBalance() {
        return availableBalance;
    }

    public long getVersion() {
        return version;
    }
}