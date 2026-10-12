import {
  apiRequest
} from "./apiClient";

const BASE =
  "/payment-api/api/v1/payment-requests";

function authHeader(token) {
  return {
    Authorization:
      `Bearer ${token}`,
  };
}

export function getPayments(
  token
) {
  return apiRequest(
    BASE,
    {
      headers:
        authHeader(token),
    }
  );
}

export function getPayment(
  token,
  paymentId
) {
  return apiRequest(
    `${BASE}/${paymentId}`,
    {
      headers:
        authHeader(token),
    }
  );
}

export function createPayment(
  token,
  invoiceId,
  paymentAmount
) {
  return apiRequest(
    BASE,
    {
      method: "POST",

      headers:
        authHeader(token),

      body: JSON.stringify({
        invoiceId:
          Number(invoiceId),

        paymentAmount:
          Number(paymentAmount),
      }),
    }
  );
}

export function submitPayment(
  token,
  paymentId
) {
  return apiRequest(
    `${BASE}/${paymentId}/submit`,
    {
      method: "POST",

      headers:
        authHeader(token),
    }
  );
}

export function getPaymentTimeline(
  token,
  paymentId
) {
  return apiRequest(
    `${BASE}/${paymentId}/timeline`,
    {
      headers:
        authHeader(token),
    }
  );
}