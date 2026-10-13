import {
  Link
} from "react-router";

import {
  useTranslation
} from "react-i18next";

import {
  useAuth
} from "../auth/AuthContext";

export default function DashboardPage() {

  const {
    t
  } = useTranslation();

  const {
    user,
    hasRole
  } = useAuth();

  const isRequester =
    hasRole(
      "PAYMENT_REQUESTER"
    );

  const isApprover =
    hasRole(
      "FINANCE_APPROVER"
    );

  const isManager =
    hasRole(
      "FINANCE_MANAGER"
    );

  return (
    <div className="page">

      <h1>
        {
          t(
            "dashboard.title",
            "Dashboard"
          )
        }
      </h1>

      <div className="card">

        <h2>
          {
            t(
              "dashboard.welcome",
              "Welcome"
            )
          }
          {
            user?.displayName ||
            user?.username ||
            user?.userId
              ? `, ${
                  user.displayName ||
                  user.username ||
                  user.userId
                }`
              : ""
          }
        </h2>

        <p>
          {
            t(
              "dashboard.subtitle",
              "Supplier Payment Authorization & Control System"
            )
          }
        </p>

      </div>

      {
        isRequester &&
        (
          <div className="dashboard-grid">

            <div className="card">

              <h2>
                {
                  t(
                    "dashboard.suppliers.title",
                    "Suppliers"
                  )
                }
              </h2>

              <p>
                {
                  t(
                    "dashboard.suppliers.description",
                    "Create and manage suppliers."
                  )
                }
              </p>

              <Link
                to="/suppliers"
              >
                {
                  t(
                    "dashboard.suppliers.action",
                    "Manage Suppliers"
                  )
                }
              </Link>

            </div>

            <div className="card">

              <h2>
                {
                  t(
                    "dashboard.invoices.title",
                    "Invoices"
                  )
                }
              </h2>

              <p>
                {
                  t(
                    "dashboard.invoices.description",
                    "Create and view supplier invoices."
                  )
                }
              </p>

              <Link
                to="/invoices"
              >
                {
                  t(
                    "dashboard.invoices.action",
                    "Manage Invoices"
                  )
                }
              </Link>

            </div>

            <div className="card">

              <h2>
                {
                  t(
                    "dashboard.payments.title",
                    "Payment Requests"
                  )
                }
              </h2>

              <p>
                {
                  t(
                    "dashboard.payments.description",
                    "Create, review, and submit payment requests."
                  )
                }
              </p>

              <Link
                to="/payments"
              >
                {
                  t(
                    "dashboard.payments.action",
                    "Manage Payments"
                  )
                }
              </Link>

            </div>

          </div>
        )
      }

      {
        isApprover &&
        (
          <div className="card">

            <h2>
              {
                t(
                  "dashboard.approver.title",
                  "Finance Approver"
                )
              }
            </h2>

            <p>
              {
                t(
                  "dashboard.approver.description",
                  "Approval functionality will be available when the approval workflow service is integrated."
                )
              }
            </p>

          </div>
        )
      }

      {
        isManager &&
        (
          <div className="card">

            <h2>
              {
                t(
                  "dashboard.manager.title",
                  "Finance Manager"
                )
              }
            </h2>

            <p>
              {
                t(
                  "dashboard.manager.description",
                  "Manager approval and override functionality will be available when the approval workflow service is integrated."
                )
              }
            </p>

          </div>
        )
      }

    </div>
  );
}