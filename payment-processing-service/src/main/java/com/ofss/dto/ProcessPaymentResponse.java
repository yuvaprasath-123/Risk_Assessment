package com.ofss.dto;
import com.ofss.entity.ProcessingStatus; import java.time.OffsetDateTime;
public record ProcessPaymentResponse(String paymentId,ProcessingStatus status,String reason,boolean idempotentReplay,OffsetDateTime processedAt){}
