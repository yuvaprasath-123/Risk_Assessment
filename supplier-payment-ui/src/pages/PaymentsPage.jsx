import {
  Link
} from "react-router";

import {
  useEffect,
  useState
} from "react";

import {
  createPayment,
  getPayments
} from "../api/paymentApi";

import {
  getInvoices
} from "../api/invoiceApi";

import {
  useAuth
} from "../auth/AuthContext";

import ErrorMessage
  from "../components/ErrorMessage";

export default function PaymentsPage() {

  const {
    token
  } = useAuth();

  const [
    payments,
    setPayments
  ] = useState([]);

  const [
    invoices,
    setInvoices
  ] = useState([]);

  const [
    invoiceId,
    setInvoiceId
  ] = useState("");

  const [
    paymentAmount,
    setPaymentAmount
  ] = useState("");

  const [
    error,
    setError
  ] = useState("");

  const [
    submitting,
    setSubmitting
  ] = useState(false);

  async function loadData() {

    try {

      setError("");

      const [
        paymentData,
        invoiceData
      ] =
        await Promise.all([
          getPayments(
            token
          ),

          getInvoices(
            token
          )
        ]);

      setPayments(
        paymentData
      );

      const usedInvoiceIds =
        new Set(
          paymentData.map(
            payment =>
              payment.invoiceId
          )
        );

      const availableInvoices =
        invoiceData.filter(
          invoice =>
            !usedInvoiceIds.has(
              invoice.invoiceId
            )
        );

      setInvoices(
        availableInvoices
      );

    } catch (error) {

      setError(
        error.message
      );
    }
  }

  useEffect(
    () => {

      if (token) {

        loadData();
      }

    },
    [token]
  );

  function handleInvoiceChange(
    event
  ) {

    const selectedId =
      event.target.value;

    setInvoiceId(
      selectedId
    );

    const selectedInvoice =
      invoices.find(
        invoice =>
          String(
            invoice.invoiceId
          ) === selectedId
      );

    if (selectedInvoice) {

      setPaymentAmount(
        selectedInvoice.invoiceAmount
      );

    } else {

      setPaymentAmount("");
    }
  }

  async function handleSubmit(
    event
  ) {

    event.preventDefault();

    if (submitting) {
      return;
    }

    setSubmitting(true);
    setError("");

    try {

      await createPayment(
        token,
        invoiceId,
        paymentAmount
      );

      setInvoiceId("");
      setPaymentAmount("");

      await loadData();

    } catch (error) {

      if (
        error.status === 409
      ) {

        setError(
          "Another user has already created a payment request for this invoice."
        );

        await loadData();

        return;
      }

      setError(
        error.message
      );

    } finally {

      setSubmitting(false);
    }
  }

  return (
    <div className="page">

      <h1>
        Payment Requests
      </h1>

      <ErrorMessage
        message={
          error
        }
      />

      <form
        className="card form-row"
        onSubmit={
          handleSubmit
        }
      >

        <select
          value={
            invoiceId
          }
          onChange={
            handleInvoiceChange
          }
          required
          disabled={
            submitting
          }
        >

          <option value="">
            Select invoice
          </option>

          {
            invoices.map(
              invoice => (

                <option
                  key={
                    invoice.invoiceId
                  }
                  value={
                    invoice.invoiceId
                  }
                >
                  {
                    invoice.invoiceNumber
                  }

                  {" - "}

                  {
                    invoice.supplierName
                  }
                </option>
              )
            )
          }

        </select>

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
            submitting
          }
        />

        <button
          type="submit"
          disabled={
            submitting
          }
        >
          {
            submitting
              ? "Creating..."
              : "Create Payment"
          }
        </button>

      </form>

      <div className="card">

        <table>

          <thead>

            <tr>
              <th>ID</th>
              <th>Invoice</th>
              <th>Supplier</th>
              <th>Amount</th>
              <th>Status</th>
              <th>Action</th>
            </tr>

          </thead>

          <tbody>

            {
              payments.map(
                payment => (

                  <tr
                    key={
                      payment.paymentId
                    }
                  >

                    <td>
                      {
                        payment.paymentId
                      }
                    </td>

                    <td>
                      {
                        payment.invoiceId
                      }
                    </td>

                    <td>
                      {
                        payment.supplierId
                      }
                    </td>

                    <td>
                      {
                        payment.paymentAmount
                      }
                    </td>

                    <td>

                      <span
                        className="status"
                      >
                        {
                          payment.status
                        }
                      </span>

                    </td>

                    <td>

                      <Link
                        to={
                          `/payments/${payment.paymentId}`
                        }
                      >
                        View
                      </Link>

                    </td>

                  </tr>
                )
              )
            }

          </tbody>

        </table>

      </div>

    </div>
  );
}