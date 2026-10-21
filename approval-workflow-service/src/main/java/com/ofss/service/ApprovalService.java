package com.ofss.service;
import com.ofss.dto.ApprovalRequests.*;
import com.ofss.entity.*;
import com.ofss.repository.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
@Service @Transactional
public class ApprovalService {
    private final ApprovalRouteRepository routes;
    private final ApprovalTaskRepository tasks;
    private final ApprovalDecisionRepository decisions;
    private final ApprovalEscalationRepository escalations;
    private final BackupApproverRepository backups;
    private final ApproverAuthorization authorization;
    private final int defaultSla;
    public ApprovalService(ApprovalRouteRepository routes,ApprovalTaskRepository tasks,ApprovalDecisionRepository decisions,
            ApprovalEscalationRepository escalations,BackupApproverRepository backups,ApproverAuthorization authorization,
            @Value("${approval.default-sla-minutes:60}") int defaultSla) {
        this.routes=routes; this.tasks=tasks; this.decisions=decisions; this.escalations=escalations;
        this.backups=backups; this.authorization=authorization; this.defaultSla=defaultSla;
    }
    public ApprovalRoute route(Route r) {
        if(routes.findByPaymentId(r.paymentId()).isPresent()) throw conflict("Payment already has a route");
        ApprovalRoute v=new ApprovalRoute(); v.paymentId=r.paymentId(); v.requesterId=r.requesterId(); v.paymentAmount=r.paymentAmount();
        v.riskBand=r.riskBand(); v.amountApprovalLevel=r.amountApprovalLevel(); v.riskApprovalLevel=ApprovalLevel.values()[r.riskBand().ordinal()];
        v.requiredLevel=stricter(v.amountApprovalLevel,v.riskApprovalLevel); v.configurationVersion=r.configurationVersion(); v.createdAt=OffsetDateTime.now();
        v.explanation="MAX(amount level "+v.amountApprovalLevel+", risk level "+v.riskApprovalLevel+") = "+v.requiredLevel;
        return routes.save(v);
    }
    public static ApprovalLevel stricter(ApprovalLevel amount,ApprovalLevel risk) { return amount.ordinal()>=risk.ordinal()?amount:risk; }
    @Transactional(readOnly=true) public ApprovalRoute routeForPayment(Long id) { return routes.findByPaymentId(id).orElseThrow(()->missing("Approval route not found")); }
    public ApprovalTask create(Task r,String token) {
        if(tasks.findByPaymentId(r.paymentId()).isPresent()) throw conflict("Payment already has an approval task");
        ApprovalRoute route=routeForPayment(r.paymentId());
        if(route.requesterId.equals(r.assignedApproverId())) throw conflict("Requester cannot be assigned to their own payment");
        authorization.assignee(token,r.assignedApproverId(),route.requiredLevel);
        ApprovalTask t=new ApprovalTask(); t.paymentId=route.paymentId; t.routeId=route.id; t.requesterId=route.requesterId;
        t.requiredLevel=route.requiredLevel; t.assignedApproverId=r.assignedApproverId(); t.status=ApprovalStatus.PENDING_APPROVAL; t.createdAt=OffsetDateTime.now();
        t.slaMinutes=r.slaMinutes()==null?defaultSla:r.slaMinutes();
        if(t.slaMinutes<1 || t.slaMinutes>525600) throw conflict("SLA must be between 1 and 525600 minutes");
        t.dueAt=t.createdAt.plusMinutes(t.slaMinutes); return tasks.save(t);
    }
    @Transactional(readOnly=true) public ApprovalTask get(Long id) { return tasks.findById(id).orElseThrow(()->missing("Approval task not found")); }
    @Transactional(readOnly=true) public List<ApprovalTask> list(Long paymentId,Long assignedApproverId,ApprovalStatus status) {
        return tasks.findAll((root,query,cb)->{
            var predicates=new ArrayList<jakarta.persistence.criteria.Predicate>();
            if(paymentId!=null) predicates.add(cb.equal(root.get("paymentId"),paymentId));
            if(assignedApproverId!=null) predicates.add(cb.equal(root.get("assignedApproverId"),assignedApproverId));
            if(status!=null) predicates.add(cb.equal(root.get("status"),status));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        },Sort.by("createdAt").descending());
    }
    public ApprovalTask decide(Long id,Decision r,String token,boolean reject,boolean override) {
        ApprovalTask t=locked(id); pending(t,override);
        if(t.requesterId.equals(r.approverId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Self approval is forbidden");
        if(!override && !t.assignedApproverId.equals(r.approverId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Task is assigned to another approver");
        if((reject||override)&&(r.remark()==null||r.remark().isBlank())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Rejection and manager override require a justification");
        authorization.decision(token,r.approverId(),t.requiredLevel,override);
        if(!override && !OffsetDateTime.now().isBefore(t.dueAt)) throw conflict("SLA expired; reassign or escalate before deciding");
        t.status=reject?ApprovalStatus.REJECTED:ApprovalStatus.APPROVED; t.decidedBy=r.approverId(); t.remark=r.remark();
        ApprovalDecision d=new ApprovalDecision(); d.taskId=t.id; d.paymentId=t.paymentId; d.approverId=r.approverId();
        d.decision=override?"OVERRIDE_APPROVED":t.status.name(); d.remark=r.remark(); d.decidedAt=OffsetDateTime.now(); decisions.save(d); return t;
    }
    public ApprovalTask reassign(Long id,Reassign r,String token) {
        ApprovalTask t=locked(id); pending(t,false); expired(t); authorization.manager(token);
        Long backup=r.backupApproverId();
        if(backup==null) backup=backups.findByApproverIdAndApprovalLevel(t.assignedApproverId,t.requiredLevel).orElseThrow(()->missing("No backup mapping; provide backupApproverId")).backupApproverId;
        checkAssignee(t,backup,t.requiredLevel,token); ApprovalEscalation e=escalation(t,"REASSIGNED_TO_BACKUP",r.remark());
        t.assignedApproverId=backup; t.dueAt=OffsetDateTime.now().plusMinutes(t.slaMinutes); e.assignedApproverId=backup; escalations.save(e); return t;
    }
    public ApprovalTask escalate(Long id,Escalate r,String token) {
        ApprovalTask t=locked(id); pending(t,false); expired(t); authorization.manager(token); ApprovalEscalation e=escalation(t,"ESCALATED",r.remark());
        if(t.requiredLevel==ApprovalLevel.L3) { t.status=ApprovalStatus.MANUAL_REVIEW; e.action="MANUAL_REVIEW"; }
        else { ApprovalLevel next=ApprovalLevel.values()[t.requiredLevel.ordinal()+1]; checkAssignee(t,r.assignedApproverId(),next,token);
            t.requiredLevel=next; t.assignedApproverId=r.assignedApproverId(); t.status=ApprovalStatus.ESCALATED; t.dueAt=OffsetDateTime.now().plusMinutes(t.slaMinutes); }
        e.requiredLevel=t.requiredLevel; e.assignedApproverId=t.assignedApproverId; escalations.save(e); return t;
    }
    @Transactional(readOnly=true) public History history(Long paymentId) {
        ApprovalTask t=tasks.findByPaymentId(paymentId).orElseThrow(()->missing("Approval task not found"));
        return new History(t,decisions.findByPaymentIdOrderByDecidedAtAsc(paymentId),escalations.findByPaymentIdOrderByOccurredAtAsc(paymentId));
    }
    private void checkAssignee(ApprovalTask t,Long id,ApprovalLevel level,String token) {
        if(id==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Next approver is required");
        if(t.requesterId.equals(id)||t.assignedApproverId.equals(id)) throw conflict("Choose a different approver who is not the requester");
        authorization.assignee(token,id,level);
    }
    private ApprovalEscalation escalation(ApprovalTask t,String action,String remark) {
        ApprovalEscalation e=new ApprovalEscalation(); e.taskId=t.id; e.paymentId=t.paymentId; e.previousApproverId=t.assignedApproverId;
        e.previousLevel=t.requiredLevel; e.requiredLevel=t.requiredLevel; e.action=action; e.remark=remark; e.occurredAt=OffsetDateTime.now(); return e;
    }
    private ApprovalTask locked(Long id) { return tasks.findForUpdate(id).orElseThrow(()->missing("Approval task not found")); }
    private void pending(ApprovalTask t,boolean override) { if(t.status!=ApprovalStatus.PENDING_APPROVAL&&t.status!=ApprovalStatus.ESCALATED&&!(override&&t.status==ApprovalStatus.MANUAL_REVIEW)) throw conflict("Task is closed or awaiting manual review"); }
    private void expired(ApprovalTask t) { if(OffsetDateTime.now().isBefore(t.dueAt)) throw conflict("SLA has not expired"); }
    private ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT,reason); }
    private ResponseStatusException missing(String reason) { return new ResponseStatusException(HttpStatus.NOT_FOUND,reason); }
    public record History(ApprovalTask task,List<ApprovalDecision> decisions,List<ApprovalEscalation> escalations) {}
}
