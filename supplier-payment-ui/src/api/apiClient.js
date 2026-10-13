export async function apiRequest(
  url,
  options = {}
) {
  const response =
    await fetch(
      url,
      {
        ...options,

        headers: {
          "Content-Type": "application/json",
          ...(options.headers || {}),
        },
      }
    );

  let body = null;

  const contentType =
    response.headers.get(
      "content-type"
    );

  if (
    contentType &&
    contentType.includes(
      "application/json"
    )
  ) {
    body =
      await response.json();
  }

  if (!response.ok) {

    const message =
      body?.message ||
      `Request failed with status ${response.status}`;

    const error =
      new Error(message);

    error.status =
      response.status;

    error.body =
      body;

    throw error;
  }

  return body;
}