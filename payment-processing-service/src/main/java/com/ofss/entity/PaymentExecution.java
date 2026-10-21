package com.ofss.entity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
@Entity @Table(name="PAYMENT_PROCESSING")
public class PaymentExecution {
    @Id @Column(name="PAYMENT_ID",length=100) public String paymentId;
    @Column(nullable=false) public Long supplierId;
    @Column(nullable=false,precision=19,scale=2) public BigDecimal amount;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public ProcessingStatus status;
    @Column(nullable=false) public int attemptCount;
    @Column(length=500) public String reason;
    @Column(nullable=false) public OffsetDateTime processedAt;
    @Version public Long version;
}
