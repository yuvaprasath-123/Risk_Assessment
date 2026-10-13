import {
  Navigate
} from "react-router";

import {
  useAuth
} from "./AuthContext";

export default function ProtectedRoute({
  children,
  requiredRole
}) {

  const {
    token,
    loading,
    hasRole
  } = useAuth();

  if (loading) {
    return (
      <div className="center">
        Loading...
      </div>
    );
  }

  if (!token) {
    return (
      <Navigate
        to="/login"
        replace
      />
    );
  }

  if (
    requiredRole &&
    !hasRole(requiredRole)
  ) {
    return (
      <div className="page">
        <h2>
          Access denied
        </h2>

        <p>
          You do not have permission
          to access this page.
        </p>
      </div>
    );
  }

  return children;
}