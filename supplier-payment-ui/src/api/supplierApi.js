import {
  apiRequest
} from "./apiClient";

const BASE =
  "/supplier-api/api/v1/suppliers";

export function getSuppliers() {
  return apiRequest(BASE);
}

export function getSupplier(
  supplierId
) {
  return apiRequest(
    `${BASE}/${supplierId}`
  );
}

export function createSupplier(
  supplierName,
  status
) {
  return apiRequest(
    BASE,
    {
      method: "POST",

      body: JSON.stringify({
        supplierName,
        status,
      }),
    }
  );
}

export function updateSupplierStatus(
  supplierId,
  status,
  changedBy
) {
  return apiRequest(
    `${BASE}/${supplierId}/status`,
    {
      method: "PATCH",

      body: JSON.stringify({
        status,
        changedBy,
      }),
    }
  );
}