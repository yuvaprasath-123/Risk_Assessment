import {
  apiRequest
} from "./apiClient";

const BASE =
  "/supplier-api/api/v1/invoices";

export function getInvoices() {
  return apiRequest(BASE);
}

export function getInvoice(
  invoiceId
) {
  return apiRequest(
    `${BASE}/${invoiceId}`
  );
}

export function createInvoice(
  invoice
) {
  return apiRequest(
    BASE,
    {
      method: "POST",

      body: JSON.stringify(
        invoice
      ),
    }
  );
}