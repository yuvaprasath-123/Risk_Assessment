import {
  test,
  expect
} from "@playwright/test";

test(
  "requester complete payment flow",
  async ({ page }) => {

    const unique =
      Date.now();

    const supplierName =
      `UI-E2E-SUP-${unique}`;

    const invoiceNumber =
      `UI-E2E-INV-${unique}`;

    const amount =
      "54321.67";


    // LOGIN

    await page.goto(
      "/login"
    );

    await page
      .getByLabel(
        "Username"
      )
      .fill(
        "requester1"
      );

    await page
      .getByLabel(
        "Password"
      )
      .fill(
        "requester@123"
      );

    const loginResponsePromise =
      page.waitForResponse(
        response =>
          response
            .url()
            .includes(
              "/api/v1/auth/login"
            )
          &&
          response
            .request()
            .method()
            === "POST"
      );

    await page
      .getByRole(
        "button",
        {
          name: "Login"
        }
      )
      .click();

    const loginResponse =
      await loginResponsePromise;

    console.log(
      "Login status:",
      loginResponse.status()
    );

    expect(
      loginResponse.status()
    ).toBe(200);

    const loginBody =
      await loginResponse.json();

    console.log(
      "Login response:",
      loginBody
    );

    expect(
      loginBody.accessToken
    ).toBeTruthy();

    expect(
      loginBody.roles
    ).toContain(
      "PAYMENT_REQUESTER"
    );

    await expect(
      page
    ).toHaveURL(
      "http://localhost:5173/",
      {
        timeout: 10000
      }
    );

    await expect(
      page.getByRole(
        "heading",
        {
          name:
            "Requester Dashboard"
        }
      )
    ).toBeVisible();


    // CREATE SUPPLIER

    await page
      .getByRole(
        "link",
        {
          name: "Suppliers"
        }
      )
      .click();

    await page
      .getByPlaceholder(
        "Supplier name"
      )
      .fill(
        supplierName
      );

    await page
      .getByRole(
        "button",
        {
          name:
            "Create Supplier"
        }
      )
      .click();

    await expect(
      page.getByText(
        supplierName
      )
    ).toBeVisible();


    // CREATE INVOICE

    await page
      .getByRole(
        "link",
        {
          name: "Invoices"
        }
      )
      .click();

    await page
      .getByPlaceholder(
        "Invoice number"
      )
      .fill(
        invoiceNumber
      );

    await page
      .locator(
        "select"
      )
      .selectOption({
        label:
          supplierName
      });

    await page
      .getByPlaceholder(
        "Invoice amount"
      )
      .fill(
        amount
      );

    const dateInputs =
      page.locator(
        'input[type="date"]'
      );

    await dateInputs
      .nth(0)
      .fill(
        "2026-10-12"
      );

    await dateInputs
      .nth(1)
      .fill(
        "2026-11-12"
      );

    await page
      .getByRole(
        "button",
        {
          name:
            "Create Invoice"
        }
      )
      .click();

    await expect(
      page.getByText(
        invoiceNumber
      )
    ).toBeVisible();


    // CREATE PAYMENT

    await page
      .getByRole(
        "link",
        {
          name: "Payments"
        }
      )
      .click();

    await page
      .locator(
        "select"
      )
      .selectOption({
        label:
          `${invoiceNumber} - ${supplierName}`
      });

    await page
      .getByRole(
        "button",
        {
          name:
            "Create Payment"
        }
      )
      .click();

    const paymentRow =
  page
    .locator(
      "tbody tr"
    )
    .filter({
      hasText:
        amount
    })
    .filter({
      hasText:
        "DRAFT"
    });

await expect(
  paymentRow
).toHaveCount(1);

await expect(
  paymentRow
).toContainText(
  "DRAFT"
);

await paymentRow
  .getByRole(
    "link",
    {
      name:
        "View"
    }
  )
  .click();

    await expect(
      page
        .getByText(
          "DRAFT"
        )
        .first()
    ).toBeVisible();


    // SUBMIT PAYMENT

    await page
      .getByRole(
        "button",
        {
          name:
            "Submit for Validation"
        }
      )
      .click();

    await expect(
      page
        .getByText(
          "PENDING_VALIDATION"
        )
        .first()
    ).toBeVisible();


    // LOGOUT

    await page
      .getByRole(
        "button",
        {
          name: "Logout"
        }
      )
      .click();

    await expect(
      page
    ).toHaveURL(
      /\/login/
    );
  }
);