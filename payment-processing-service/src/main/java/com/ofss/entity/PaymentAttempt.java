package com.ofss.entity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
@Entity @Table(name="PAYMENT_PROCESSING_ATTEMPTS",uniqueConstraints=@UniqueConstraint(columnNames={"PAYMENT_ID","ATTEMPT_NUMBER"}))
public class PaymentAttempt {
    @Id @GeneratedValue(strategy=GenerationType.SEQUENCE,generator="attempt_seq")
    @SequenceGenerator(name="attempt_seq",sequenceName="PAYMENT_ATTEMPTS_SEQ",allocationSize=1) public Long id;
    @Column(name="PAYMENT_ID",nullable=false,length=100) public String paymentId;
    @Column(name="ATTEMPT_NUMBER",nullable=false) public int attemptNumber;
    @Enumerated(EnumType.STRING) @Column(nullable=false) public ProcessingStatus status;
    @Column(length=500) public String reason;
    @Column(nullable=false) public OffsetDateTime attemptedAt;
}
