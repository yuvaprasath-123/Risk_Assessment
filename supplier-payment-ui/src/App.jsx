import {
  Navigate,
  Route,
  Routes
} from "react-router";

import ProtectedRoute
  from "./auth/ProtectedRoute";

import Layout
  from "./components/Layout";

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

export default function App() {

  return (
    <Routes>

      <Route
        path="/login"
        element={
          <LoginPage />
        }
      />

      <Route
        element={
          <ProtectedRoute
            requiredRole=
              "PAYMENT_REQUESTER"
          >
            <Layout />
          </ProtectedRoute>
        }
      >

        <Route
          index
          element={
            <DashboardPage />
          }
        />

        <Route
          path="suppliers"
          element={
            <SuppliersPage />
          }
        />

        <Route
          path="invoices"
          element={
            <InvoicesPage />
          }
        />

        <Route
          path="payments"
          element={
            <PaymentsPage />
          }
        />

        <Route
          path="payments/:paymentId"
          element={
            <PaymentDetailsPage />
          }
        />

      </Route>

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
  );
}