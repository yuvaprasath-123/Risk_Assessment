package com.ofss.entity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
@Entity @Table(name="PAYMENT_IDEMPOTENCY")
public class PaymentIdempotency {
    @Id @Column(name="PAYMENT_ID",length=100) public String paymentId;
    @Column(nullable=false) public OffsetDateTime succeededAt;
}
