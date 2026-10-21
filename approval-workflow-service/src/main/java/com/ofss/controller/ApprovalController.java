package com.ofss.controller;
import com.ofss.dto.ApprovalRequests.*;
import com.ofss.entity.*;
import com.ofss.service.ApprovalService;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1")
public class ApprovalController {
    private final ApprovalService service;
    public ApprovalController(ApprovalService service) { this.service=service; }
    @PostMapping("/approval-routes") @ResponseStatus(HttpStatus.CREATED)
    public ApprovalRoute route(@Valid @RequestBody Route r) { return service.route(r); }
    @GetMapping("/approval-routes/payment/{paymentId}")
    public ApprovalRoute route(@PathVariable Long paymentId) { return service.routeForPayment(paymentId); }
    @GetMapping("/approval-routes/payment/{paymentId}/decision-explanation")
    public Explanation explanation(@PathVariable Long paymentId) {
        ApprovalRoute r=service.routeForPayment(paymentId);
        return new Explanation(r.paymentId,r.amountApprovalLevel,r.riskApprovalLevel,r.requiredLevel,r.configurationVersion,r.explanation);
    }
    @PostMapping("/approval-tasks") @ResponseStatus(HttpStatus.CREATED)
    public ApprovalTask create(@Valid @RequestBody Task r,@RequestHeader(value="Authorization",required=false) String token) { return service.create(r,token); }
    @GetMapping("/approval-tasks")
    public List<ApprovalTask> list(@RequestParam(required=false) Long paymentId,@RequestParam(required=false) Long assignedApproverId,@RequestParam(required=false) ApprovalStatus status) { return service.list(paymentId,assignedApproverId,status); }
    @GetMapping("/approval-tasks/{approvalTaskId}")
    public ApprovalTask get(@PathVariable Long approvalTaskId) { return service.get(approvalTaskId); }
    @PostMapping("/approval-tasks/{approvalTaskId}/approve")
    public ApprovalTask approve(@PathVariable Long approvalTaskId,@Valid @RequestBody Decision r,@RequestHeader(value="Authorization",required=false) String token) { return service.decide(approvalTaskId,r,token,false,false); }
    @PostMapping("/approval-tasks/{approvalTaskId}/reject")
    public ApprovalTask reject(@PathVariable Long approvalTaskId,@Valid @RequestBody Decision r,@RequestHeader(value="Authorization",required=false) String token) { return service.decide(approvalTaskId,r,token,true,false); }
    @PostMapping("/approval-tasks/{approvalTaskId}/override-and-approve")
    public ApprovalTask override(@PathVariable Long approvalTaskId,@Valid @RequestBody Decision r,@RequestHeader(value="Authorization",required=false) String token) { return service.decide(approvalTaskId,r,token,false,true); }
    @GetMapping("/approval-tasks/payment/{paymentId}/history")
    public ApprovalService.History history(@PathVariable Long paymentId) { return service.history(paymentId); }
    @GetMapping("/approval-tasks/{approvalTaskId}/sla")
    public Sla sla(@PathVariable Long approvalTaskId) { ApprovalTask t=service.get(approvalTaskId); return new Sla(t.id,t.slaMinutes,t.dueAt,!OffsetDateTime.now().isBefore(t.dueAt),t.status); }
    @PostMapping("/approval-tasks/{approvalTaskId}/reassign-to-backup")
    public ApprovalTask reassign(@PathVariable Long approvalTaskId,@Valid @RequestBody Reassign r,@RequestHeader(value="Authorization",required=false) String token) { return service.reassign(approvalTaskId,r,token); }
    @PostMapping("/approval-tasks/{approvalTaskId}/escalate-to-next-level")
    public ApprovalTask escalate(@PathVariable Long approvalTaskId,@Valid @RequestBody Escalate r,@RequestHeader(value="Authorization",required=false) String token) { return service.escalate(approvalTaskId,r,token); }
    public record Explanation(Long paymentId,ApprovalLevel amountLevel,ApprovalLevel riskLevel,ApprovalLevel requiredLevel,String configurationVersion,String explanation) {}
    public record Sla(Long approvalTaskId,int slaMinutes,OffsetDateTime dueAt,boolean expired,ApprovalStatus status) {}
}
