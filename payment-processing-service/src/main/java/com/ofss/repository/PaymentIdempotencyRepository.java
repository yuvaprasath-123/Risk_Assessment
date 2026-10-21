package com.ofss.repository;
import com.ofss.entity.PaymentIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PaymentIdempotencyRepository extends JpaRepository<PaymentIdempotency,String> {}
