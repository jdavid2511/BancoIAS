package com.bancoias.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;

public class Transfer {

    @Id
    private Long id;
    private String requestReference;
    private String sourceAccountId;
    private String destinationAccountId;
    private long amount;
    private TransferStatus status;
    private RejectionReason reason;
    private Instant processedAt;

    protected Transfer() {
    }

    private Transfer(String requestReference, String sourceAccountId, String destinationAccountId,
                     long amount, TransferStatus status, RejectionReason reason, Instant processedAt) {
        this.requestReference = requestReference;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.status = status;
        this.reason = reason;
        this.processedAt = processedAt;
    }

    public static Transfer authorized(String requestReference, String sourceAccountId,
                                      String destinationAccountId, long amount, Instant processedAt) {
        return new Transfer(requestReference, sourceAccountId, destinationAccountId,
                amount, TransferStatus.AUTHORIZED, null, processedAt);
    }

    public static Transfer rejected(String requestReference, String sourceAccountId,
                                    String destinationAccountId, long amount,
                                    RejectionReason reason, Instant processedAt) {
        return new Transfer(requestReference, sourceAccountId, destinationAccountId,
                amount, TransferStatus.REJECTED, reason, processedAt);
    }

    public Long getId() {
        return id;
    }

    public String getRequestReference() {
        return requestReference;
    }

    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public String getDestinationAccountId() {
        return destinationAccountId;
    }

    public long getAmount() {
        return amount;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public RejectionReason getReason() {
        return reason;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }
}