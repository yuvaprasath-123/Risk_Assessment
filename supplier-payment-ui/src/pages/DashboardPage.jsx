import {
  useAuth
} from "../auth/AuthContext";

export default function DashboardPage() {

  const {
    user
  } = useAuth();

  return (
    <div className="page">

      <h1>
        Requester Dashboard
      </h1>

      <p>
        Welcome,
        {" "}
        {
          user?.fullName ||
          user?.username
        }
      </p>

      <div className="grid">

        <div className="card">
          <h3>
            Suppliers
          </h3>

          <p>
            Create and review supplier
            records.
          </p>
        </div>

        <div className="card">
          <h3>
            Invoices
          </h3>

          <p>
            Create invoices against
            active suppliers.
          </p>
        </div>

        <div className="card">
          <h3>
            Payments
          </h3>

          <p>
            Create, submit and track
            your payment requests.
          </p>
        </div>

      </div>

    </div>
  );
}