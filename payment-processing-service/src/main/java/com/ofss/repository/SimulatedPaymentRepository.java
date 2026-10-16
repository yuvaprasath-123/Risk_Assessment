package com.ofss.repository;
import com.ofss.entity.SimulatedPayment; import java.util.Optional; import org.springframework.data.jpa.repository.JpaRepository;
public interface SimulatedPaymentRepository extends JpaRepository<SimulatedPayment,Long>{Optional<SimulatedPayment> findByPaymentId(String paymentId);}
