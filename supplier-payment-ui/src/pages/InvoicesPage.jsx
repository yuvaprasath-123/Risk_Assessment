import {
  useEffect,
  useState
} from "react";

import {
  createInvoice,
  getInvoices
} from "../api/invoiceApi";

import {
  getSuppliers
} from "../api/supplierApi";

import ErrorMessage
  from "../components/ErrorMessage";

export default function InvoicesPage() {

  const [
    invoices,
    setInvoices
  ] = useState([]);

  const [
    suppliers,
    setSuppliers
  ] = useState([]);

  const [
    form,
    setForm
  ] = useState({
    invoiceNumber: "",
    supplierId: "",
    invoiceAmount: "",
    invoiceDate: "",
    dueDate: "",
  });

  const [
    error,
    setError
  ] = useState("");

  async function loadData() {

    try {

      const [
        invoiceData,
        supplierData
      ] =
        await Promise.all([
          getInvoices(),
          getSuppliers(),
        ]);

      setInvoices(
        invoiceData
      );

      setSuppliers(
        supplierData.filter(
          supplier =>
            supplier.status
            === "ACTIVE"
        )
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
    []
  );

  function handleChange(
    event
  ) {

    setForm({
      ...form,
      [event.target.name]:
        event.target.value,
    });
  }

  async function handleSubmit(
    event
  ) {

    event.preventDefault();

    setError("");

    try {

      await createInvoice({
        invoiceNumber:
          form.invoiceNumber,

        supplierId:
          Number(
            form.supplierId
          ),

        invoiceAmount:
          Number(
            form.invoiceAmount
          ),

        invoiceDate:
          form.invoiceDate,

        dueDate:
          form.dueDate,
      });

      setForm({
        invoiceNumber: "",
        supplierId: "",
        invoiceAmount: "",
        invoiceDate: "",
        dueDate: "",
      });

      await loadData();

    } catch (error) {

      setError(
        error.message
      );
    }
  }

  return (
    <div className="page">

      <h1>
        Invoices
      </h1>

      <ErrorMessage
        message={error}
      />

      <form
        className="card form-grid"
        onSubmit={
          handleSubmit
        }
      >

        <input
          name="invoiceNumber"
          placeholder="Invoice number"
          value={
            form.invoiceNumber
          }
          onChange={
            handleChange
          }
          required
        />

        <select
          name="supplierId"
          value={
            form.supplierId
          }
          onChange={
            handleChange
          }
          required
        >
          <option value="">
            Select supplier
          </option>

          {suppliers.map(
            supplier => (
              <option
                key={
                  supplier.supplierId
                }
                value={
                  supplier.supplierId
                }
              >
                {
                  supplier.supplierName
                }
              </option>
            )
          )}
        </select>

        <input
          name="invoiceAmount"
          type="number"
          min="0.01"
          step="0.01"
          placeholder="Invoice amount"
          value={
            form.invoiceAmount
          }
          onChange={
            handleChange
          }
          required
        />

        <input
          name="invoiceDate"
          type="date"
          value={
            form.invoiceDate
          }
          onChange={
            handleChange
          }
          required
        />

        <input
          name="dueDate"
          type="date"
          value={
            form.dueDate
          }
          onChange={
            handleChange
          }
          required
        />

        <button type="submit">
          Create Invoice
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
              <th>Due Date</th>
            </tr>
          </thead>

          <tbody>

            {invoices.map(
              invoice => (
                <tr
                  key={
                    invoice.invoiceId
                  }
                >
                  <td>
                    {
                      invoice.invoiceId
                    }
                  </td>

                  <td>
                    {
                      invoice.invoiceNumber
                    }
                  </td>

                  <td>
                    {
                      invoice.supplierName
                    }
                  </td>

                  <td>
                    {
                      invoice.invoiceAmount
                    }
                  </td>

                  <td>
                    {
                      invoice.dueDate
                    }
                  </td>
                </tr>
              )
            )}

          </tbody>

        </table>

      </div>

    </div>
  );
}