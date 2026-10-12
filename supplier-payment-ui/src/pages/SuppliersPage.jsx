import {
  useEffect,
  useState
} from "react";

import {
  createSupplier,
  getSuppliers
} from "../api/supplierApi";

import ErrorMessage
  from "../components/ErrorMessage";

export default function SuppliersPage() {

  const [
    suppliers,
    setSuppliers
  ] = useState([]);

  const [
    supplierName,
    setSupplierName
  ] = useState("");

  const [
    error,
    setError
  ] = useState("");

  async function loadSuppliers() {

    try {

      const data =
        await getSuppliers();

      setSuppliers(data);

    } catch (error) {

      setError(
        error.message
      );
    }
  }

  useEffect(
    () => {
      loadSuppliers();
    },
    []
  );

  async function handleCreate(
    event
  ) {

    event.preventDefault();

    setError("");

    try {

      await createSupplier(
        supplierName,
        "ACTIVE"
      );

      setSupplierName("");

      await loadSuppliers();

    } catch (error) {

      setError(
        error.message
      );
    }
  }

  return (
    <div className="page">

      <h1>
        Suppliers
      </h1>

      <ErrorMessage
        message={error}
      />

      <form
        className="card form-row"
        onSubmit={
          handleCreate
        }
      >

        <input
          placeholder="Supplier name"
          value={supplierName}
          onChange={
            (event) =>
              setSupplierName(
                event.target.value
              )
          }
          required
        />

        <button type="submit">
          Create Supplier
        </button>

      </form>

      <div className="card">

        <table>

          <thead>
            <tr>
              <th>ID</th>
              <th>Name</th>
              <th>Status</th>
            </tr>
          </thead>

          <tbody>

            {suppliers.map(
              (supplier) => (

                <tr
                  key={
                    supplier.supplierId
                  }
                >
                  <td>
                    {
                      supplier.supplierId
                    }
                  </td>

                  <td>
                    {
                      supplier.supplierName
                    }
                  </td>

                  <td>
                    {
                      supplier.status
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