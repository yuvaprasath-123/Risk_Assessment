
package com.ofss.service;

import com.ofss.dto.ApprovalRequests;
import com.ofss.dto.ApprovalWorkflowResponse;
import com.ofss.entity.ApprovalLevel;
import com.ofss.entity.ApprovalTask;
import com.ofss.repository.ApprovalRouteRepository;
import com.ofss.repository.ApprovalTaskRepository;
import com.ofss.repository.TrustedApprovalDataRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InternalApprovalAssignmentService {

    private final ApprovalService approvalService;
    private final ApprovalRouteRepository routeRepository;
    private final ApprovalTaskRepository taskRepository;
    private final TrustedApprovalDataRepository trustedData;
    private final EligibleApproverSelector approverSelector;

    public InternalApprovalAssignmentService(
            ApprovalService approvalService,
            ApprovalRouteRepository routeRepository,
            ApprovalTaskRepository taskRepository,
            TrustedApprovalDataRepository trustedData,
            EligibleApproverSelector approverSelector
    ) {
        this.approvalService = approvalService;
        this.routeRepository = routeRepository;
        this.taskRepository = taskRepository;
        this.trustedData = trustedData;
        this.approverSelector = approverSelector;
    }

    @Transactional
    public ApprovalWorkflowResponse assign(
            Long paymentId,
            String authorization
    ) {

        if (paymentId == null || paymentId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Valid payment ID required"
            );
        }

        if (authorization == null
                || !authorization.startsWith("Bearer ")
                || authorization.substring(7).isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Machine JWT is required"
            );
        }

        /*
         * IDEMPOTENCY:
         * Return the existing task when an
         * assignment already exists.
         *
         * Never intentionally create a second
         * approval task for the same payment.
         */
        var existingTask =
                taskRepository.findByPaymentId(paymentId);

        if (existingTask.isPresent()) {
            return response(existingTask.get());
        }

        /*
         * Read trusted payment and risk information
         * directly from Oracle.
         */
        var payment =
                trustedData.getPaymentData(paymentId);

        if (!"PENDING_APPROVAL".equals(
                payment.paymentStatus())) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Payment is not pending approval"
            );
        }

        if (!"COMPLETED".equals(
                payment.assessmentStatus())) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Risk assessment is not completed"
            );
        }

        /*
         * Convert the trusted Oracle approval level
         * into the application enum.
         */
        ApprovalLevel requiredLevel =
                switch (payment.requiredApprovalLevel()) {

                    case 1 -> ApprovalLevel.L1;

                    case 2 -> ApprovalLevel.L2;

                    case 3 -> ApprovalLevel.L3;

                    default ->
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "Invalid required approval level"
                            );
                };

        /*
         * Shared eligible-approver selection.
         *
         * Preserve initial assignment behavior:
         * prefer the required level and allow
         * higher-level fallback.
         *
         * The selector verifies active authority,
         * active user status, finance role,
         * and requester exclusion.
         */
        Long approverId =
                approverSelector.selectAtOrAboveLevel(
                        requiredLevel,
                        payment.requesterId(),
                        authorization
                );

        /*
         * Create the approval route if missing.
         *
         * ApprovalService.route() independently
         * validates the payment and risk rules.
         */
        if (routeRepository
                .findByPaymentId(paymentId)
                .isEmpty()) {

            approvalService.route(
                    new ApprovalRequests.Route(
                            paymentId,
                            payment.requesterId(),
                            payment.paymentAmount(),
                            null,
                            null,
                            null
                    )
            );
        }

        /*
         * Create the approval task.
         *
         * ApprovalService.create() independently
         * validates the assignment and reads
         * the configured SLA from Oracle.
         *
         * Route and task creation remain
         * in the same transaction.
         */
        ApprovalTask task =
                approvalService.create(
                        new ApprovalRequests.Task(
                                paymentId,
                                approverId,
                                payment.slaDurationSeconds() / 60
                        ),
                        authorization
                );

        return response(task);
    }

    private ApprovalWorkflowResponse response(
            ApprovalTask task
    ) {
        return new ApprovalWorkflowResponse(
                task.id,
                task.paymentId,
                task.status,
                task.requiredLevel,
                task.decidedBy,
                task.remark,
                task.dueAt
        );
    }
}
