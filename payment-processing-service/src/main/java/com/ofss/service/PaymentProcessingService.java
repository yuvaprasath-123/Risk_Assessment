package com.ofss.service;
import com.ofss.dto.*;
import com.ofss.entity.*;
import com.ofss.repository.*;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
@Service @Transactional
public class PaymentProcessingService {
    private final PaymentExecutionRepository executions;
    private final PaymentAttemptRepository attempts;
    private final PaymentIdempotencyRepository idempotency;
    private final SimulationConfigurationRepository configuration;
    private final boolean allowModeChange;
    public PaymentProcessingService(PaymentExecutionRepository executions,PaymentAttemptRepository attempts,
            PaymentIdempotencyRepository idempotency,SimulationConfigurationRepository configuration,
            @Value("${simulation.allow-mode-change:true}") boolean allowModeChange) {
        this.executions=executions; this.attempts=attempts; this.idempotency=idempotency;
        this.configuration=configuration; this.allowModeChange=allowModeChange;
    }
    public ProcessPaymentResponse process(ProcessPaymentRequest r) {
        var old=executions.findForUpdate(r.paymentId());
        if(old.isPresent()) {
            PaymentExecution p=old.get();
            if(!p.supplierId.equals(r.supplierId())||p.amount.compareTo(r.amount())!=0)
                throw conflict("Payment ID already belongs to a different supplier or amount");
            return response(p,true);
        }
        PaymentExecution p=new PaymentExecution(); p.paymentId=r.paymentId(); p.supplierId=r.supplierId(); p.amount=r.amount();
        executeAttempt(p,r.simulateFailure());
        executions.saveAndFlush(p);
        return response(p,false);
    }
    public ProcessPaymentResponse retry(String paymentId,RetryPaymentRequest r) {
        PaymentExecution p=executions.findForUpdate(paymentId).orElseThrow(()->missing());
        if(p.status==ProcessingStatus.SUCCESS || idempotency.existsById(paymentId)) return response(p,true);
        executeAttempt(p,r.simulateFailure()); return response(p,false);
    }
    @Transactional(readOnly=true) public ProcessPaymentResponse get(String paymentId) { return response(require(paymentId),false); }
    @Transactional(readOnly=true) public List<PaymentAttempt> attempts(String paymentId) {
        require(paymentId); return attempts.findByPaymentIdOrderByAttemptNumberAsc(paymentId);
    }
    @Transactional(readOnly=true) public IdempotencyStatus idempotency(String paymentId) {
        PaymentExecution p=require(paymentId);
        return new IdempotencyStatus(paymentId,p.status,idempotency.existsById(paymentId),p.attemptCount);
    }
    public SimulationModeRequest simulation(SimulationModeRequest r) {
        if(!allowModeChange) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Simulation mode changes are disabled");
        SimulationConfiguration c=configuration.findById(1L).orElseGet(SimulationConfiguration::new);
        c.id=1L; c.simulateFailure=r.simulateFailure(); configuration.save(c); return r;
    }
    private void executeAttempt(PaymentExecution p,Boolean override) {
        boolean failure=override!=null?override:configuration.findById(1L).map(c->c.simulateFailure).orElse(false);
        p.status=failure?ProcessingStatus.FAILED:ProcessingStatus.SUCCESS;
        p.reason=failure?"Simulated payment processing failure":"Simulated payment processed successfully";
        p.processedAt=OffsetDateTime.now(); p.attemptCount++;
        PaymentAttempt a=new PaymentAttempt(); a.paymentId=p.paymentId; a.attemptNumber=p.attemptCount;
        a.status=p.status; a.reason=p.reason; a.attemptedAt=p.processedAt; attempts.save(a);
        if(p.status==ProcessingStatus.SUCCESS) {
            PaymentIdempotency key=new PaymentIdempotency(); key.paymentId=p.paymentId; key.succeededAt=p.processedAt; idempotency.save(key);
        }
    }
    private PaymentExecution require(String id) { return executions.findById(id).orElseThrow(()->missing()); }
    private ProcessPaymentResponse response(PaymentExecution p,boolean replay) { return new ProcessPaymentResponse(p.paymentId,p.status,p.reason,replay,p.processedAt); }
    private ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"Payment processing record not found"); }
    private ResponseStatusException conflict(String text) { return new ResponseStatusException(HttpStatus.CONFLICT,text); }
    public record IdempotencyStatus(String paymentId,ProcessingStatus status,boolean successfulExecutionProtected,int attemptCount) {}
}
