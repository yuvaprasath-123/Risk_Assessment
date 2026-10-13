import {
  render,
  screen
} from "@testing-library/react";

import userEvent
  from "@testing-library/user-event";

import {
  vi
} from "vitest";

import InvoicesPage
  from "./InvoicesPage";

const {
  mockGetInvoices,
  mockCreateInvoice,
  mockGetSuppliers
} = vi.hoisted(
  () => ({
    mockGetInvoices:
      vi.fn(),

    mockCreateInvoice:
      vi.fn(),

    mockGetSuppliers:
      vi.fn(),
  })
);

vi.mock(
  "../api/invoiceApi",
  () => ({
    getInvoices:
      mockGetInvoices,

    createInvoice:
      mockCreateInvoice,
  })
);

vi.mock(
  "../api/supplierApi",
  () => ({
    getSuppliers:
      mockGetSuppliers,
  })
);

describe(
  "InvoicesPage",
  () => {

    beforeEach(() => {

      mockGetInvoices
        .mockReset();

      mockCreateInvoice
        .mockReset();

      mockGetSuppliers
        .mockReset();
    });


    test(
      "creates invoice for active supplier",
      async () => {

        mockGetInvoices
          .mockResolvedValue([]);

        mockGetSuppliers
          .mockResolvedValue([
            {
              supplierId: 10,
              supplierName:
                "Supplier A",
              status:
                "ACTIVE",
            },
          ]);

        mockCreateInvoice
          .mockResolvedValue({
            invoiceId: 20,
          });

        const user =
          userEvent.setup();

        render(
          <InvoicesPage />
        );

        await screen.findByText(
          "Supplier A"
        );

        await user.type(
          screen.getByPlaceholderText(
            /invoice number/i
          ),
          "INV-TEST-001"
        );

        await user.selectOptions(
          screen.getByRole(
            "combobox"
          ),
          "10"
        );

        await user.type(
          screen.getByPlaceholderText(
            /invoice amount/i
          ),
          "60000"
        );

        const dateInputs =
          document.querySelectorAll(
            'input[type="date"]'
          );

        await user.type(
          dateInputs[0],
          "2026-10-12"
        );

        await user.type(
          dateInputs[1],
          "2026-11-12"
        );

        await user.click(
          screen.getByRole(
            "button",
            {
              name:
                /create invoice/i
            }
          )
        );

        expect(
          mockCreateInvoice
        ).toHaveBeenCalledWith({
          invoiceNumber:
            "INV-TEST-001",

          supplierId: 10,

          invoiceAmount:
            60000,

          invoiceDate:
            "2026-10-12",

          dueDate:
            "2026-11-12",
        });
      }
    );
  }
);