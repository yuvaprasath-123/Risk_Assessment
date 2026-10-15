import {
  Link,
  useNavigate
} from "react-router";

import {
  useTranslation
} from "react-i18next";

import {
  useAuth
} from "../auth/AuthContext";

import {
  useTheme
} from "../theme/ThemeContext";

import LanguageSelector
  from "./LanguageSelector";

export default function Navbar() {

  const {
    t
  } = useTranslation();

  const {
    user,
    logout,
    hasRole,
    isAuthenticated
  } = useAuth();

  const {
    theme,
    toggleTheme
  } = useTheme();

  const navigate =
    useNavigate();

  if (!isAuthenticated) {

    return null;
  }

  const isRequester =
    hasRole(
      "PAYMENT_REQUESTER"
    );

  const isFinanceApprover =
    hasRole(
      "FINANCE_APPROVER"
    );

  const isFinanceManager =
    hasRole(
      "FINANCE_MANAGER"
    );

  const canCreateRequester =
    isFinanceApprover ||
    isFinanceManager;

  function handleLogout() {

    logout();

    navigate(
      "/login"
    );
  }

  return (
    <header className="navbar">

      <div className="navbar-left">

        <Link
          to="/"
          className="navbar-brand"
        >
          {
            t(
              "appName"
            )
          }
        </Link>

        <nav className="navbar-links">

          <Link
            to="/"
            className="nav-link"
          >
            {
              t(
                "nav.dashboard"
              )
            }
          </Link>

          {
            isRequester &&
            (
              <>
                <Link
                  to="/suppliers"
                  className="nav-link"
                >
                  {
                    t(
                      "nav.suppliers"
                    )
                  }
                </Link>

                <Link
                  to="/invoices"
                  className="nav-link"
                >
                  {
                    t(
                      "nav.invoices"
                    )
                  }
                </Link>

                <Link
                  to="/payments"
                  className="nav-link"
                >
                  {
                    t(
                      "nav.payments"
                    )
                  }
                </Link>
              </>
            )
          }

          {
            canCreateRequester &&
            (
              <Link
                to="/users/create-requester"
                className="nav-link"
              >
                Create Requester
              </Link>
            )
          }

        </nav>

      </div>

      <div className="navbar-right">

        <div className="navbar-user">

          {
            user?.displayName ||
            user?.username ||
            user?.userId ||
            ""
          }

        </div>

        <LanguageSelector />

        <button
          type="button"
          className="theme-toggle"
          onClick={
            toggleTheme
          }
          aria-label={
            theme === "dark"
              ? "Switch to light mode"
              : "Switch to dark mode"
          }
          title={
            theme === "dark"
              ? "Switch to light mode"
              : "Switch to dark mode"
          }
        >

          {
            theme === "dark"
              ? "☀ Light"
              : "🌙 Dark"
          }

        </button>

        <button
          type="button"
          className="logout-button"
          onClick={
            handleLogout
          }
        >
          {
            t(
              "nav.logout"
            )
          }
        </button>

      </div>

    </header>
  );
}