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


    // =========================================================
    // LOGIN
    // =========================================================

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
          name:
            "Login"
        }
      )
      .click();

    const loginResponse =
      await loginResponsePromise;

    expect(
      loginResponse.status()
    ).toBe(
      200
    );

    const loginBody =
      await loginResponse.json();

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
      /\/$/,
      {
        timeout:
          10000
      }
    );

    await expect(
      page.getByRole(
        "heading",
        {
          name:
            /dashboard/i
        }
      )
    ).toBeVisible();


    // =========================================================
    // CREATE SUPPLIER
    // =========================================================

    await page
      .getByRole(
        "link",
        {
          name:
            "Suppliers",
          exact:
            true
        }
      )
      .click();

    await expect(
      page
    ).toHaveURL(
      /\/suppliers/
    );

    await expect(
      page.getByRole(
        "heading",
        {
          name:
            "Suppliers"
        }
      )
    ).toBeVisible();

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
        supplierName,
        {
          exact:
            true
        }
      )
    ).toBeVisible({
      timeout:
        10000
    });


    // =========================================================
    // CREATE INVOICE
    // =========================================================

    await page
      .getByRole(
        "link",
        {
          name:
            "Invoices",
          exact:
            true
        }
      )
      .click();

    await expect(
      page
    ).toHaveURL(
      /\/invoices/
    );

    await expect(
      page.getByRole(
        "heading",
        {
          name:
            "Invoices"
        }
      )
    ).toBeVisible();

    /*
     * IMPORTANT:
     *
     * There is now also a LanguageSelector <select>
     * in the navbar.
     *
     * Therefore:
     *
     *   page.locator("select")
     *
     * is no longer safe.
     *
     * Restrict the selector to the page form.
     */
    const invoiceForm =
      page.locator(
        "form.form-row"
      );

    const supplierSelect =
      invoiceForm.locator(
        "select"
      );

    await expect(
      supplierSelect
    ).toBeVisible();

    await supplierSelect
      .selectOption({
        label:
          supplierName
      });

    await page
      .getByPlaceholder(
        "Invoice number"
      )
      .fill(
        invoiceNumber
      );

    await page
      .getByPlaceholder(
        "Invoice amount"
      )
      .fill(
        amount
      );

    const dateInputs =
      invoiceForm.locator(
        'input[type="date"]'
      );

    await expect(
      dateInputs
    ).toHaveCount(
      2
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
        invoiceNumber,
        {
          exact:
            true
        }
      )
    ).toBeVisible({
      timeout:
        10000
    });


    // =========================================================
    // CREATE PAYMENT
    // =========================================================

    await page
      .getByRole(
        "link",
        {
          name:
            "Payments",
          exact:
            true
        }
      )
      .click();

    await expect(
      page
    ).toHaveURL(
      /\/payments/
    );

    await expect(
      page.getByRole(
        "heading",
        {
          name:
            "Payment Requests"
        }
      )
    ).toBeVisible();

    /*
     * Again, restrict the select to the payment form.
     * This prevents Playwright from choosing the
     * navbar language dropdown.
     */
    const paymentForm =
      page.locator(
        "form.form-row"
      );

    const invoiceSelect =
      paymentForm.locator(
        "select"
      );

    await expect(
      invoiceSelect
    ).toBeVisible();

    await invoiceSelect
      .selectOption({
        label:
          `${invoiceNumber} - ${supplierName}`
      });

    /*
     * Payment amount should automatically come
     * from the selected invoice.
     */
    const paymentAmountInput =
      paymentForm.locator(
        'input[type="number"]'
      );

    await expect(
      paymentAmountInput
    ).toHaveValue(
      amount
    );

    await page
      .getByRole(
        "button",
        {
          name:
            "Create Payment"
        }
      )
      .click();

    /*
     * Find the exact payment row created by this test.
     */
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
    ).toHaveCount(
      1
    );

    await expect(
      paymentRow
    ).toContainText(
      amount
    );

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


    // =========================================================
    // PAYMENT DETAILS
    // =========================================================

    await expect(
      page
    ).toHaveURL(
      /\/payments\/\d+/
    );

    await expect(
      page.getByRole(
        "heading",
        {
          name:
            "Payment Details"
        }
      )
    ).toBeVisible();

    await expect(
      page
        .getByText(
          "DRAFT"
        )
        .first()
    ).toBeVisible();


    // =========================================================
    // SUBMIT PAYMENT
    // =========================================================

    await page
      .getByRole(
        "button",
        {
          name:
            "Submit Payment"
        }
      )
      .click();

    await expect(
      page
        .getByText(
          "PENDING_VALIDATION"
        )
        .first()
    ).toBeVisible({
      timeout:
        10000
    });


    // =========================================================
    // LOGOUT
    // =========================================================

    await page
      .getByRole(
        "button",
        {
          name:
            "Logout"
        }
      )
      .click();

    await expect(
      page
    ).toHaveURL(
      /\/login/
    );

    await expect(
      page.getByRole(
        "button",
        {
          name:
            "Login"
        }
      )
    ).toBeVisible();
  }
);