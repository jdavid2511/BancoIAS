package com.bancoias.api.dto;

import com.bancoias.domain.RejectionReason;
import com.bancoias.domain.Transfer;
import com.bancoias.domain.TransferStatus;
import java.time.Instant;

public record TransferResponse(
        String requestReference,
        String sourceAccountId,
        String destinationAccountId,
        long amount,
        TransferStatus status,
        RejectionReason reason,
        Instant processedAt) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getRequestReference(),
                transfer.getSourceAccountId(),
                transfer.getDestinationAccountId(),
                transfer.getAmount(),
                transfer.getStatus(),
                transfer.getReason(),
                transfer.getProcessedAt());
    }
}