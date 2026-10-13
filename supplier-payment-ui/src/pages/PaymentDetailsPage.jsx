import {
  useEffect,
  useState
} from "react";

import {
  useParams
} from "react-router";

import {
  getPayment,
  getPaymentTimeline,
  submitPayment
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
    error,
    setError
  ] = useState("");

  async function loadData() {

    setError("");

    try {

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
          ),
        ]);

      setPayment(
        paymentData
      );

      setTimeline(
        timelineData
      );

    } catch (error) {

      setError(
        error.message
      );
    }
  }

  useEffect(
    () => {
      loadData();
    },
    [paymentId]
  );

  async function handleSubmitPayment() {

    setError("");

    try {

      await submitPayment(
        token,
        paymentId
      );

      await loadData();

    } catch (error) {

      setError(
        error.message
      );
    }
  }

  if (!payment) {

    return (
      <div className="page">
        <ErrorMessage
          message={error}
        />

        {!error &&
          "Loading..."}
      </div>
    );
  }

  return (
    <div className="page">

      <h1>
        Payment #{payment.paymentId}
      </h1>

      <ErrorMessage
        message={error}
      />

      <div className="card details">

        <p>
          <strong>
            Invoice:
          </strong>
          {" "}
          {payment.invoiceId}
        </p>

        <p>
          <strong>
            Supplier:
          </strong>
          {" "}
          {payment.supplierId}
        </p>

        <p>
          <strong>
            Amount:
          </strong>
          {" "}
          {payment.paymentAmount}
        </p>

        <p>
          <strong>
            Status:
          </strong>
          {" "}
          {payment.status}
        </p>

        {
          payment.status
          === "DRAFT"
          && (
            <button
              onClick={
                handleSubmitPayment
              }
            >
              Submit for Validation
            </button>
          )
        }

      </div>

      <h2>
        Status Timeline
      </h2>

      <div className="card">

        {timeline.length === 0 && (
          <p>
            No timeline entries.
          </p>
        )}

        {timeline.map(
          item => (
            <div
              className="timeline-item"
              key={
                item.historyId ||
                `${item.newStatus}-${item.changedAt}`
              }
            >

              <strong>
                {item.newStatus}
              </strong>

              <div>
                {item.reason}
              </div>

              <small>
                {item.changedAt}
              </small>

            </div>
          )
        )}

      </div>

    </div>
  );
}