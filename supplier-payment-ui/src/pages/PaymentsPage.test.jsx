import {
  render,
  screen
} from "@testing-library/react";

import userEvent
  from "@testing-library/user-event";

import {
  MemoryRouter
} from "react-router";

import {
  vi
} from "vitest";

import PaymentsPage
  from "./PaymentsPage";

const {
  mockGetPayments,
  mockCreatePayment,
  mockGetInvoices
} = vi.hoisted(
  () => ({
    mockGetPayments:
      vi.fn(),

    mockCreatePayment:
      vi.fn(),

    mockGetInvoices:
      vi.fn(),
  })
);

vi.mock(
  "../auth/AuthContext",
  () => ({
    useAuth: () => ({
      token:
        "test-token",
    }),
  })
);

vi.mock(
  "../api/paymentApi",
  () => ({
    getPayments:
      mockGetPayments,

    createPayment:
      mockCreatePayment,
  })
);

vi.mock(
  "../api/invoiceApi",
  () => ({
    getInvoices:
      mockGetInvoices,
  })
);

describe(
  "PaymentsPage",
  () => {

    beforeEach(() => {

      mockGetPayments
        .mockReset();

      mockCreatePayment
        .mockReset();

      mockGetInvoices
        .mockReset();
    });


    test(
      "creates payment using unused invoice",
      async () => {

        mockGetPayments
          .mockResolvedValue([
            {
              paymentId: 1,
              invoiceId: 1,
              supplierId: 5,
              paymentAmount:
                10000,
              status:
                "DRAFT",
            },
          ]);

        mockGetInvoices
          .mockResolvedValue([
            {
              invoiceId: 1,
              invoiceNumber:
                "OLD-INV",
              supplierName:
                "Supplier A",
              invoiceAmount:
                10000,
            },

            {
              invoiceId: 2,
              invoiceNumber:
                "NEW-INV",
              supplierName:
                "Supplier B",
              invoiceAmount:
                60000,
            },
          ]);

        mockCreatePayment
          .mockResolvedValue({
            paymentId: 2,
            status:
              "DRAFT",
          });

        const user =
          userEvent.setup();

        render(
          <MemoryRouter>
            <PaymentsPage />
          </MemoryRouter>
        );

        const select =
          await screen.findByRole(
            "combobox"
          );

        await user.selectOptions(
          select,
          "2"
        );

        await user.click(
          screen.getByRole(
            "button",
            {
              name:
                /create payment/i
            }
          )
        );

        expect(
          mockCreatePayment
        ).toHaveBeenCalledWith(
          "test-token",
          "2",
          60000
        );
      }
    );
  }
);