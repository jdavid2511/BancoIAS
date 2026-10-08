package com.bancoias.domain;

public enum RejectionReason {
    AMOUNT_NOT_POSITIVE("El valor de la transferencia debe ser mayor que cero"),
    SAME_ACCOUNT("La cuenta origen y la cuenta destino deben ser diferentes"),
    ACCOUNT_NOT_FOUND("Una de las cuentas no existe"),
    SOURCE_ACCOUNT_BLOCKED("La cuenta origen no esta habilitada"),
    INSUFFICIENT_BALANCE("Saldo disponible insuficiente");

    private final String message;

    RejectionReason(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}