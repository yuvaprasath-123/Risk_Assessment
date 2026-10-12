import {
  test,
  expect
} from "@playwright/test";

test(
  "supplier input does not execute script",
  async ({ page }) => {

    let dialogOpened =
      false;

    page.on(
      "dialog",
      async dialog => {

        dialogOpened =
          true;

        await dialog.dismiss();
      }
    );

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

    await page
      .getByRole(
        "button",
        {
          name:
            "Login"
        }
      )
      .click();

    await expect(
      page.getByRole(
        "heading",
        {
          name:
            "Requester Dashboard"
        }
      )
    ).toBeVisible();

    await page
      .getByRole(
        "link",
        {
          name:
            "Suppliers"
        }
      )
      .click();

    const payload =
      `<script>alert("xss-${Date.now()}")</script>`;

    await page
      .getByPlaceholder(
        "Supplier name"
      )
      .fill(
        payload
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

    await page
  .getByPlaceholder(
    "Supplier name"
  )
  .fill(
    payload
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

const createdSupplier =
  page.getByRole(
    "cell",
    {
      name: payload,
      exact: true
    }
  );

await expect(
  createdSupplier
).toHaveCount(1);

await expect(
  createdSupplier
).toBeVisible();

expect(
  dialogOpened
).toBe(false);
  }
);