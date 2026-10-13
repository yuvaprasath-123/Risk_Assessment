import {
  render,
  screen
} from "@testing-library/react";

import userEvent
  from "@testing-library/user-event";

import {
  vi
} from "vitest";

import SuppliersPage
  from "./SuppliersPage";

const {
  mockGetSuppliers,
  mockCreateSupplier
} = vi.hoisted(
  () => ({
    mockGetSuppliers:
      vi.fn(),

    mockCreateSupplier:
      vi.fn(),
  })
);

vi.mock(
  "../api/supplierApi",
  () => ({
    getSuppliers:
      mockGetSuppliers,

    createSupplier:
      mockCreateSupplier,
  })
);

describe(
  "SuppliersPage",
  () => {

    beforeEach(() => {

      mockGetSuppliers
        .mockReset();

      mockCreateSupplier
        .mockReset();
    });


    test(
      "loads suppliers",
      async () => {

        mockGetSuppliers
          .mockResolvedValue([
            {
              supplierId: 1,
              supplierName:
                "Test Supplier",
              status:
                "ACTIVE",
            },
          ]);

        render(
          <SuppliersPage />
        );

        expect(
          await screen.findByText(
            "Test Supplier"
          )
        ).toBeInTheDocument();

        expect(
          screen.getByText(
            "ACTIVE"
          )
        ).toBeInTheDocument();
      }
    );


    test(
      "creates supplier",
      async () => {

        mockGetSuppliers
          .mockResolvedValueOnce([])
          .mockResolvedValueOnce([
            {
              supplierId: 2,
              supplierName:
                "New Supplier",
              status:
                "ACTIVE",
            },
          ]);

        mockCreateSupplier
          .mockResolvedValue({
            supplierId: 2,
            supplierName:
              "New Supplier",
            status:
              "ACTIVE",
          });

        const user =
          userEvent.setup();

        render(
          <SuppliersPage />
        );

        const input =
          await screen.findByPlaceholderText(
            /supplier name/i
          );

        await user.type(
          input,
          "New Supplier"
        );

        await user.click(
          screen.getByRole(
            "button",
            {
              name:
                /create supplier/i
            }
          )
        );

        expect(
          mockCreateSupplier
        ).toHaveBeenCalledWith(
          "New Supplier",
          "ACTIVE"
        );

        expect(
          await screen.findByText(
            "New Supplier"
          )
        ).toBeInTheDocument();
      }
    );
  }
);