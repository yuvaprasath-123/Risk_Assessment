package com.ofss.controller;

import com.ofss.entity.ApprovalDecision;
import com.ofss.entity.ApprovalEscalation;
import com.ofss.entity.ApprovalTask;
import com.ofss.repository.TrustedApprovalDataRepository;
import com.ofss.security.SecurityConfiguration.JwtPrincipal;
import com.ofss.service.ApprovalService;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentSlaTrackerController {

    private final ApprovalService approvalService;
    private final TrustedApprovalDataRepository trustedData;

    public PaymentSlaTrackerController(
            ApprovalService approvalService,
            TrustedApprovalDataRepository trustedData
    ) {
        this.approvalService = approvalService;
        this.trustedData = trustedData;
    }

    @GetMapping("/{paymentId}/sla-tracker")
    @Transactional(readOnly = true)
    public TrackerResponse getTracker(
            @PathVariable Long paymentId,
            Authentication authentication
    ) {

        if (paymentId == null || paymentId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Valid payment ID required"
            );
        }

        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal()
                        instanceof JwtPrincipal principal)
                || principal.userId() == null
                || principal.userId() <= 0) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Authenticated human user required"
            );
        }

        boolean manager = hasRole(
                authentication,
                "FINANCE_MANAGER"
        );

        boolean requester = hasRole(
                authentication,
                "PAYMENT_REQUESTER"
        );

        boolean approver = hasRole(
                authentication,
                "FINANCE_APPROVER"
        );

        if (!manager && !requester && !approver) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "SLA tracker access denied"
            );
        }

        // Look up ownership from trusted Oracle data.
        // Never accept requesterId from a query parameter.
        var payment = trustedData.getPaymentData(paymentId);

        boolean ownsPayment = requester
                && principal.userId().equals(
                        payment.requesterId()
                );

        // Avoid retrieving approval history for unrelated
        // payment requesters.
        if (!manager && !approver && !ownsPayment) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "SLA tracker access denied"
            );
        }

        ApprovalService.History history =
                approvalService.history(paymentId);

        ApprovalTask task = history.task();

        boolean assignedApprover = approver
                && principal.userId().equals(
                        task.assignedApproverId
                );

        if (!manager && !ownsPayment && !assignedApprover) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "SLA tracker access denied"
            );
        }

        List<DecisionItem> decisionItems =
                history.decisions().stream()
                        .map(this::toDecisionItem)
                        .toList();

        List<EscalationItem> escalationItems =
                history.escalations().stream()
                        .map(this::toEscalationItem)
                        .toList();

        return new TrackerResponse(
                paymentId,
                payment.paymentStatus(),
                "L" + payment.requiredApprovalLevel(),
                new TaskItem(
                        task.id,
                        task.requiredLevel.name(),
                        task.status.name(),
                        task.assignedApproverId,
                        task.slaMinutes,
                        task.createdAt,
                        task.dueAt,
                        !OffsetDateTime.now().isBefore(
                                task.dueAt
                        )
                ),
                decisionItems,
                escalationItems
        );
    }

    private boolean hasRole(
            Authentication authentication,
            String role
    ) {
        String authority = "ROLE_" + role;

        for (GrantedAuthority granted :
                authentication.getAuthorities()) {

            if (authority.equals(granted.getAuthority())) {
                return true;
            }
        }

        return false;
    }

    private DecisionItem toDecisionItem(
            ApprovalDecision decision
    ) {
        return new DecisionItem(
                decision.id,
                decision.approverId,
                decision.decision,
                decision.remark,
                decision.decidedAt
        );
    }

    private EscalationItem toEscalationItem(
            ApprovalEscalation escalation
    ) {
        return new EscalationItem(
                escalation.id,
                escalation.action,
                escalation.previousApproverId,
                escalation.assignedApproverId,
                escalation.previousLevel == null
                        ? null
                        : escalation.previousLevel.name(),
                escalation.requiredLevel == null
                        ? null
                        : escalation.requiredLevel.name(),
                escalation.remark,
                escalation.occurredAt
        );
    }

    public record TrackerResponse(
            Long paymentId,
            String paymentStatus,
            String originalRequiredLevel,
            TaskItem task,
            List<DecisionItem> decisions,
            List<EscalationItem> escalations
    ) {}

    public record TaskItem(
            Long taskId,
            String currentLevel,
            String status,
            Long assignedApproverId,
            int slaMinutes,
            OffsetDateTime createdAt,
            OffsetDateTime dueAt,
            boolean slaExpired
    ) {}

    public record DecisionItem(
            Long decisionId,
            Long approverId,
            String decision,
            String remark,
            OffsetDateTime decidedAt
    ) {}

    public record EscalationItem(
            Long escalationId,
            String action,
            Long previousApproverId,
            Long assignedApproverId,
            String previousLevel,
            String newLevel,
            String remark,
            OffsetDateTime occurredAt
    ) {}
}