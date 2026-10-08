package com.bancoias.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TransferRequest(
        @NotBlank(message = "requestReference es obligatorio")
        @Size(max = 50, message = "requestReference no puede superar 50 caracteres")
        String requestReference,

        @NotBlank(message = "sourceAccountId es obligatorio")
        @Size(max = 20, message = "sourceAccountId no puede superar 20 caracteres")
        String sourceAccountId,

        @NotBlank(message = "destinationAccountId es obligatorio")
        @Size(max = 20, message = "destinationAccountId no puede superar 20 caracteres")
        String destinationAccountId,

        long amount) {
}