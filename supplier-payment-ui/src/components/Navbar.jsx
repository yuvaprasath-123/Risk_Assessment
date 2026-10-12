import {
  Link,
  useNavigate
} from "react-router";

import {
  useAuth
} from "../auth/AuthContext";

export default function Navbar() {

  const {
    user,
    logout,
    hasRole
  } = useAuth();

  const navigate =
    useNavigate();

  function handleLogout() {

    logout();

    navigate(
      "/login"
    );
  }

  return (
    <nav className="navbar">

      <div className="navbar-brand">
        Supplier Payment Control
      </div>

      <div className="navbar-links">

        <Link to="/">
          Dashboard
        </Link>

        {hasRole(
          "PAYMENT_REQUESTER"
        ) && (
          <>
            <Link to="/suppliers">
              Suppliers
            </Link>

            <Link to="/invoices">
              Invoices
            </Link>

            <Link to="/payments">
              Payments
            </Link>
          </>
        )}

      </div>

      <div className="navbar-user">

        <span>
          {user?.fullName ||
            user?.username}
        </span>

        <button
          onClick={
            handleLogout
          }
        >
          Logout
        </button>

      </div>

    </nav>
  );
}