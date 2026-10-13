import {
  apiRequest
} from "./apiClient";

const BASE =
  "/security-api/api/v1/auth";

export function login(
  username,
  password
) {
  return apiRequest(
    `${BASE}/login`,
    {
      method: "POST",

      body: JSON.stringify({
        username,
        password,
      }),
    }
  );
}

export function getCurrentUser(
  token
) {
  return apiRequest(
    `${BASE}/me`,
    {
      headers: {
        Authorization:
          `Bearer ${token}`,
      },
    }
  );
}