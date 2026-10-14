import {
  useEffect,
  useState
} from "react";

import {
  useNavigate,
  useParams
} from "react-router";

import {
  getPayment,
  getPaymentTimeline,
  submitPayment,
  updatePayment
} from "../api/paymentApi";

import {
  useAuth
} from "../auth/AuthContext";

import ErrorMessage
  from "../components/ErrorMessage";

export default function PaymentDetailsPage() {

  const {
    paymentId
  } = useParams();

  const navigate =
    useNavigate();

  const {
    token
  } = useAuth();

  const [
    payment,
    setPayment
  ] = useState(null);

  const [
    timeline,
    setTimeline
  ] = useState([]);

  const [
    paymentAmount,
    setPaymentAmount
  ] = useState("");

  const [
    error,
    setError
  ] = useState("");

  const [
    loading,
    setLoading
  ] = useState(true);

  const [
    updating,
    setUpdating
  ] = useState(false);

  const [
    submitting,
    setSubmitting
  ] = useState(false);

  async function loadPayment() {

    try {

      setError("");

      const [
        paymentData,
        timelineData
      ] =
        await Promise.all([
          getPayment(
            token,
            paymentId
          ),

          getPaymentTimeline(
            token,
            paymentId
          )
        ]);

      setPayment(
        paymentData
      );

      setTimeline(
        timelineData
      );

      setPaymentAmount(
        paymentData.paymentAmount
      );

    } catch (error) {

      setError(
        error.message
      );

    } finally {

      setLoading(
        false
      );
    }
  }

  useEffect(
    () => {

      if (
        token &&
        paymentId
      ) {

        loadPayment();
      }

    },
    [
      token,
      paymentId
    ]
  );

  async function handleUpdate(
    event
  ) {

    event.preventDefault();

    if (updating) {
      return;
    }

    setUpdating(
      true
    );

    setError("");

    try {

      await updatePayment(
        token,
        paymentId,
        paymentAmount
      );

      await loadPayment();

    } catch (error) {

      if (
        error.status === 409
      ) {

        setError(
          "This payment request was modified by another user. Refresh and try again."
        );

        return;
      }

      setError(
        error.message
      );

    } finally {

      setUpdating(
        false
      );
    }
  }

  async function handleSubmitPayment() {

    if (submitting) {
      return;
    }

    setSubmitting(
      true
    );

    setError("");

    try {

      await submitPayment(
        token,
        paymentId
      );

      navigate(
        `/payments/${paymentId}/submission-success`
      );

    } catch (error) {

      let message =
        error.message
        ||
        "Payment submission failed.";

      if (
        error.status === 409
      ) {

        message =
          "This payment request was already modified or submitted by another user.";
      }

      navigate(
        `/payments/${paymentId}/submission-failure`,
        {
          state: {
            message
          }
        }
      );

    } finally {

      setSubmitting(
        false
      );
    }
  }

  if (loading) {

    return (
      <div className="page">

        <p>
          Loading payment...
        </p>

      </div>
    );
  }

  if (!payment) {

    return (
      <div className="page">

        <ErrorMessage
          message={
            error ||
            "Payment request not found"
          }
        />

      </div>
    );
  }

  return (
    <div className="page">

      <h1>
        Payment Details
      </h1>

      <ErrorMessage
        message={
          error
        }
      />

      <div className="card">

        <p>
          <strong>
            Payment ID:
          </strong>

          {" "}

          {
            payment.paymentId
          }
        </p>

        <p>
          <strong>
            Invoice ID:
          </strong>

          {" "}

          {
            payment.invoiceId
          }
        </p>

        <p>
          <strong>
            Supplier ID:
          </strong>

          {" "}

          {
            payment.supplierId
          }
        </p>

        <p>
          <strong>
            Requester ID:
          </strong>

          {" "}

          {
            payment.requesterId
          }
        </p>

        <p>
          <strong>
            Status:
          </strong>

          {" "}

          <span className="status">

            {
              payment.status
            }

          </span>
        </p>

        {
          payment.riskScore !== null &&
          payment.riskScore !== undefined &&
          (
            <p>

              <strong>
                Risk Score:
              </strong>

              {" "}

              {
                payment.riskScore
              }

            </p>
          )
        }

        {
          payment.riskBand &&
          (
            <p>

              <strong>
                Risk Band:
              </strong>

              {" "}

              {
                payment.riskBand
              }

            </p>
          )
        }

        {
          payment.requiredApprovalLevel &&
          (
            <p>

              <strong>
                Required Approval Level:
              </strong>

              {" "}

              {
                payment.requiredApprovalLevel
              }

            </p>
          )
        }

      </div>

      {
        payment.status ===
        "DRAFT" &&
        (
          <form
            className="card form-row"
            onSubmit={
              handleUpdate
            }
          >

            <input
              type="number"
              min="0.01"
              step="0.01"
              value={
                paymentAmount
              }
              onChange={
                event =>
                  setPaymentAmount(
                    event.target.value
                  )
              }
              required
              disabled={
                updating ||
                submitting
              }
            />

            <button
              type="submit"
              disabled={
                updating ||
                submitting
              }
            >
              {
                updating
                  ? "Updating..."
                  : "Update Payment"
              }
            </button>

            <button
              type="button"
              onClick={
                handleSubmitPayment
              }
              disabled={
                updating ||
                submitting
              }
            >
              {
                submitting
                  ? "Submitting..."
                  : "Submit Payment"
              }
            </button>

          </form>
        )
      }

      <div className="card">

        <h2>
          Timeline
        </h2>

        {
          timeline.length === 0
            ? (
              <p>
                No status history available.
              </p>
            )
            : (
              <table>

                <thead>

                  <tr>

                    <th>
                      Previous Status
                    </th>

                    <th>
                      New Status
                    </th>

                    <th>
                      Changed By
                    </th>

                    <th>
                      Changed At
                    </th>

                    <th>
                      Reason
                    </th>

                  </tr>

                </thead>

                <tbody>

                  {
                    timeline.map(
                      history => (

                        <tr
                          key={
                            history.id
                          }
                        >

                          <td>
                            {
                              history.previousStatus ||
                              "-"
                            }
                          </td>

                          <td>
                            {
                              history.newStatus
                            }
                          </td>

                          <td>
                            {
                              history.changedBy
                            }
                          </td>

                          <td>
                            {
                              history.changedAt
                            }
                          </td>

                          <td>
                            {
                              history.reason ||
                              "-"
                            }
                          </td>

                        </tr>
                      )
                    )
                  }

                </tbody>

              </table>
            )
        }

      </div>

    </div>
  );
}