package com.ofss.repository;
import com.ofss.entity.PaymentAttempt;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt,Long> {
    List<PaymentAttempt> findByPaymentIdOrderByAttemptNumberAsc(String paymentId);
}
