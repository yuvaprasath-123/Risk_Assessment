import {
  BrowserRouter,
  Navigate,
  Route,
  Routes
} from "react-router";

import {
  AuthProvider
} from "./auth/AuthContext";

import {
  ThemeProvider
} from "./theme/ThemeContext";

import ProtectedRoute
  from "./auth/ProtectedRoute";

import Navbar
  from "./components/Navbar";

import LoginPage
  from "./pages/LoginPage";

import DashboardPage
  from "./pages/DashboardPage";

import SuppliersPage
  from "./pages/SuppliersPage";

import InvoicesPage
  from "./pages/InvoicesPage";

import PaymentsPage
  from "./pages/PaymentsPage";

import PaymentDetailsPage
  from "./pages/PaymentDetailsPage";

function AppRoutes() {

  return (
    <>
      <Navbar />

      <main className="app-main">

        <Routes>

          <Route
            path="/login"
            element={
              <LoginPage />
            }
          />

          <Route
            path="/"
            element={
              <ProtectedRoute
                allowedRoles={[
                  "PAYMENT_REQUESTER",
                  "FINANCE_APPROVER",
                  "FINANCE_MANAGER"
                ]}
              >
                <DashboardPage />
              </ProtectedRoute>
            }
          />

          <Route
            path="/suppliers"
            element={
              <ProtectedRoute
                allowedRoles={[
                  "PAYMENT_REQUESTER"
                ]}
              >
                <SuppliersPage />
              </ProtectedRoute>
            }
          />

          <Route
            path="/invoices"
            element={
              <ProtectedRoute
                allowedRoles={[
                  "PAYMENT_REQUESTER"
                ]}
              >
                <InvoicesPage />
              </ProtectedRoute>
            }
          />

          <Route
            path="/payments"
            element={
              <ProtectedRoute
                allowedRoles={[
                  "PAYMENT_REQUESTER"
                ]}
              >
                <PaymentsPage />
              </ProtectedRoute>
            }
          />

          <Route
            path="/payments/:paymentId"
            element={
              <ProtectedRoute
                allowedRoles={[
                  "PAYMENT_REQUESTER"
                ]}
              >
                <PaymentDetailsPage />
              </ProtectedRoute>
            }
          />

          <Route
            path="*"
            element={
              <Navigate
                to="/"
                replace
              />
            }
          />

        </Routes>

      </main>
    </>
  );
}

export default function App() {

  return (
    <BrowserRouter>

      <ThemeProvider>

        <AuthProvider>

          <AppRoutes />

        </AuthProvider>

      </ThemeProvider>

    </BrowserRouter>
  );
}