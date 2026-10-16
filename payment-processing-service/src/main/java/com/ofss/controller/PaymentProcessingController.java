package com.ofss.controller;
import com.ofss.dto.*;
import com.ofss.entity.PaymentAttempt;
import com.ofss.service.PaymentProcessingService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/payment-processing")
public class PaymentProcessingController {
    private final PaymentProcessingService service;
    public PaymentProcessingController(PaymentProcessingService service) { this.service=service; }
    @PostMapping({"","/execute"})
    public ResponseEntity<ProcessPaymentResponse> process(@Valid @RequestBody ProcessPaymentRequest r) {
        ProcessPaymentResponse response=service.process(r);
        return ResponseEntity.status(response.idempotentReplay()?HttpStatus.OK:HttpStatus.CREATED).body(response);
    }
    @GetMapping("/{paymentId}") public ProcessPaymentResponse get(@PathVariable String paymentId) { return service.get(paymentId); }
    @PostMapping("/{paymentId}/retry") public ProcessPaymentResponse retry(@PathVariable String paymentId,@RequestBody(required=false) RetryPaymentRequest r) { return service.retry(paymentId,r==null?new RetryPaymentRequest(null):r); }
    @GetMapping("/{paymentId}/attempts") public List<PaymentAttempt> attempts(@PathVariable String paymentId) { return service.attempts(paymentId); }
    @GetMapping("/{paymentId}/idempotency-status") public PaymentProcessingService.IdempotencyStatus idempotency(@PathVariable String paymentId) { return service.idempotency(paymentId); }
    @PutMapping("/simulation-mode") public SimulationModeRequest simulation(@Valid @RequestBody SimulationModeRequest r) { return service.simulation(r); }
}
