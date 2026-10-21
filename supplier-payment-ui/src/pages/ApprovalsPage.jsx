
import { useEffect, useState } from "react";
import { useNavigate } from "react-router";

import { useAuth } from "../auth/AuthContext";

import {
  getApprovalTasks,
  approveTask,
  rejectTask,
  overrideAndApproveTask
} from "../api/approvalApi";

import "./ApprovalsPage.css";

export default function ApprovalsPage() {
  const { token, user, hasRole } = useAuth();
  const navigate = useNavigate();

  const [tasks, setTasks] = useState([]);
  const [status, setStatus] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");
  const [refreshKey, setRefreshKey] = useState(0);

  const [selectedTask, setSelectedTask] = useState(null);
  const [action, setAction] = useState("");
  const [remark, setRemark] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const currentUserId = Number(user?.userId);
  const isManager = hasRole("FINANCE_MANAGER");

  useEffect(() => {
    let cancelled = false;

    async function loadTasks() {
      setLoading(true);
      setError("");

      try {
        const result = await getApprovalTasks(
          token,
          status ? { status } : {}
        );

        if (!cancelled) {
          setTasks(
            Array.isArray(result) ? result : []
          );
        }
      } catch (err) {
        if (!cancelled) {
          setError(
            err.message || "Unable to load approvals."
          );
          setTasks([]);
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    }

    loadTasks();

    return () => {
      cancelled = true;
    };
  }, [token, status, refreshKey]);

  function isOpenTask(task) {
    return [
      "PENDING_APPROVAL",
      "ESCALATED"
    ].includes(task.status);
  }

  function canDecide(task) {
    return (
      isOpenTask(task) &&
      Number(task.assignedApproverId) === currentUserId &&
      Number(task.requesterId) !== currentUserId
    );
  }

  function canOverride(task) {
    return (
      isManager &&
      [
        "PENDING_APPROVAL",
        "ESCALATED",
        "MANUAL_REVIEW"
      ].includes(task.status) &&
      Number(task.requesterId) !== currentUserId
    );
  }

  function openAction(task, selectedAction) {
    setSelectedTask(task);
    setAction(selectedAction);
    setRemark("");
    setError("");
    setSuccess("");
  }

  function closeAction() {
    if (submitting) return;

    setSelectedTask(null);
    setAction("");
    setRemark("");
  }

  async function submitDecision(event) {
    event.preventDefault();

    if (!selectedTask || submitting) return;

    if (
      !Number.isSafeInteger(currentUserId) ||
      currentUserId <= 0
    ) {
      setError("Authenticated user ID is unavailable.");
      return;
    }

    if (
      (action === "reject" || action === "override") &&
      !remark.trim()
    ) {
      setError("A justification is required.");
      return;
    }

    const labels = {
      approve: "Approve",
      reject: "Reject",
      override: "Override and approve"
    };

    if (
      !window.confirm(
        `${labels[action]} task ${selectedTask.id}?`
      )
    ) {
      return;
    }

    const paymentId = selectedTask.paymentId;
    const taskId = selectedTask.id;
    const completedAction = action;

    setSubmitting(true);
    setError("");
    setSuccess("");

    const decision = {
      approverId: currentUserId,
      remark: remark.trim() || null
    };

    try {
      if (completedAction === "approve") {
        await approveTask(
          token,
          taskId,
          decision
        );
      } else if (completedAction === "reject") {
        await rejectTask(
          token,
          taskId,
          decision
        );
      } else if (completedAction === "override") {
        await overrideAndApproveTask(
          token,
          taskId,
          decision
        );
      } else {
        throw new Error("Unknown approval action.");
      }

      setSelectedTask(null);
      setAction("");
      setRemark("");
      setRefreshKey((value) => value + 1);

      if (
        completedAction === "approve" ||
        completedAction === "override"
      ) {
        navigate(
          `/payment-processing?paymentId=${encodeURIComponent(
            paymentId
          )}`,
          {
            state: {
              fromApproval: true,
              taskId
            }
          }
        );
        return;
      }

      setSuccess(
        `Task ${taskId} rejected successfully.`
      );

    } catch (err) {
      setError(
        err.message || "Unable to complete approval action."
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section className="approvals-page">
      <div className="approvals-header">
        <h1>Approval Workflow</h1>
        <p>
          Review payment approval tasks and their status.
        </p>
      </div>

      <div className="approval-filters">
        <label htmlFor="approval-status">
          Filter by status
        </label>

        <select
          id="approval-status"
          value={status}
          onChange={(event) =>
            setStatus(event.target.value)
          }
        >
          <option value="">All statuses</option>
          <option value="PENDING_APPROVAL">
            Pending Approval
          </option>
          <option value="ESCALATED">Escalated</option>
          <option value="MANUAL_REVIEW">
            Manual Review
          </option>
          <option value="APPROVED">Approved</option>
          <option value="REJECTED">Rejected</option>
        </select>
      </div>

      {error && (
        <p role="alert" className="approval-error">
          {error}
        </p>
      )}

      {success && (
        <p role="status" className="approval-success">
          {success}
        </p>
      )}

      {loading && (
        <p role="status">Loading approvals...</p>
      )}

      {!loading && !error && tasks.length === 0 && (
        <p>No approval tasks found.</p>
      )}

      {!loading && tasks.length > 0 && (
        <div className="approvals-table-wrapper">
          <table className="approvals-table">
            <thead>
              <tr>
                <th>Task ID</th>
                <th>Payment ID</th>
                <th>Assigned Approver</th>
                <th>Status</th>
                <th>Due At</th>
                <th>Actions</th>
              </tr>
            </thead>

            <tbody>
              {tasks.map((task) => (
                <tr key={task.id}>
                  <td>{task.id}</td>
                  <td>{task.paymentId}</td>
                  <td>{task.assignedApproverId ?? "—"}</td>
                  <td>{task.status}</td>
                  <td>
                    {task.dueAt
                      ? new Date(task.dueAt).toLocaleString()
                      : "—"}
                  </td>
                  <td>
                    <div className="approval-actions">
                      {canDecide(task) && (
                        <>
                          <button
                            type="button"
                            onClick={() =>
                              openAction(task, "approve")
                            }
                          >
                            Approve
                          </button>

                          <button
                            type="button"
                            onClick={() =>
                              openAction(task, "reject")
                            }
                          >
                            Reject
                          </button>
                        </>
                      )}

                      {canOverride(task) && (
                        <button
                          type="button"
                          onClick={() =>
                            openAction(task, "override")
                          }
                        >
                          Manager Override
                        </button>
                      )}

                      {!canDecide(task) &&
                        !canOverride(task) &&
                        "—"}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selectedTask && (
        <form
          className="approval-decision-form"
          onSubmit={submitDecision}
        >
          <h2>
            {action === "approve"
              ? "Approve Payment"
              : action === "reject"
                ? "Reject Payment"
                : "Manager Override"}
          </h2>

          <p>
            Task #{selectedTask.id} — Payment #
            {selectedTask.paymentId}
          </p>

          <label htmlFor="approval-remark">
            Remark
            {action !== "approve" ? " (required)" : ""}
          </label>

          <textarea
            id="approval-remark"
            value={remark}
            onChange={(event) =>
              setRemark(event.target.value)
            }
            maxLength={2000}
            rows={3}
            required={action !== "approve"}
            placeholder="Enter your decision notes"
          />

          <div className="approval-actions">
            <button
              type="submit"
              disabled={submitting}
            >
              {submitting
                ? "Submitting..."
                : "Confirm Decision"}
            </button>

            <button
              type="button"
              disabled={submitting}
              onClick={closeAction}
            >
              Cancel
            </button>
          </div>
        </form>
      )}
    </section>
  );
}
