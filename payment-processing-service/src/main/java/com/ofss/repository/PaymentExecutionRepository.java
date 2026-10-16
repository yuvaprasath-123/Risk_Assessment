package com.ofss.repository;
import com.ofss.entity.PaymentExecution;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface PaymentExecutionRepository extends JpaRepository<PaymentExecution,String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from PaymentExecution p where p.paymentId=:id")
    Optional<PaymentExecution> findForUpdate(@Param("id") String id);
}
