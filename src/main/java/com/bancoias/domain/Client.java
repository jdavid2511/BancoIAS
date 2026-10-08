package com.bancoias.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

public class Client implements Persistable<String> {

    @Id
    private String id;
    private String name;
    private ClientType type;

    @Transient
    private boolean isNewRecord;

    protected Client() {
    }

    public Client(String id, String name, ClientType type) {
        this.id = id;
        this.name = name;
        this.type = type;
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

    public String getName() {
        return name;
    }

    public ClientType getType() {
        return type;
    }
}