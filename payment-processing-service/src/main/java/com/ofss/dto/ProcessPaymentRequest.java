package com.ofss.dto;
import jakarta.validation.constraints.*; import java.math.BigDecimal;
public record ProcessPaymentRequest(@NotBlank @Size(max=100) String paymentId,@NotNull @Positive Long supplierId,
    @NotNull @DecimalMin("0.01") @Digits(integer=17,fraction=2) BigDecimal amount,Boolean simulateFailure){}
