import {
  render,
  screen
} from "@testing-library/react";

import userEvent
  from "@testing-library/user-event";

import {
  MemoryRouter,
  Route,
  Routes
} from "react-router";

import {
  vi
} from "vitest";

import PaymentDetailsPage
  from "./PaymentDetailsPage";

const {
  mockGetPayment,
  mockGetTimeline,
  mockSubmitPayment
} = vi.hoisted(
  () => ({
    mockGetPayment:
      vi.fn(),

    mockGetTimeline:
      vi.fn(),

    mockSubmitPayment:
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
    getPayment:
      mockGetPayment,

    getPaymentTimeline:
      mockGetTimeline,

    submitPayment:
      mockSubmitPayment,
  })
);

describe(
  "PaymentDetailsPage",
  () => {

    beforeEach(() => {

      mockGetPayment
        .mockReset();

      mockGetTimeline
        .mockReset();

      mockSubmitPayment
        .mockReset();
    });


    test(
      "displays DRAFT payment and submits it",
      async () => {

        mockGetPayment
          .mockResolvedValueOnce({
            paymentId: 50,
            invoiceId: 25,
            supplierId: 12,
            paymentAmount:
              60000,
            status:
              "DRAFT",
          })
          .mockResolvedValueOnce({
            paymentId: 50,
            invoiceId: 25,
            supplierId: 12,
            paymentAmount:
              60000,
            status:
              "PENDING_VALIDATION",
          });

        mockGetTimeline
          .mockResolvedValue([
            {
              historyId: 1,
              newStatus:
                "DRAFT",
              reason:
                "Payment request created",
              changedAt:
                "2026-10-12T10:00:00",
            },
          ]);

        mockSubmitPayment
          .mockResolvedValue({
            status:
              "PENDING_VALIDATION",
          });

        const user =
          userEvent.setup();

        render(
          <MemoryRouter
            initialEntries={[
              "/payments/50"
            ]}
          >

            <Routes>

              <Route
                path=
                  "/payments/:paymentId"
                element={
                  <PaymentDetailsPage />
                }
              />

            </Routes>

          </MemoryRouter>
        );

        expect(
          await screen.findByText(
            "Payment #50"
          )
        ).toBeInTheDocument();

        expect(
  screen.getAllByText(
    "DRAFT"
  )
).toHaveLength(2);

        await user.click(
          screen.getByRole(
            "button",
            {
              name:
                /submit for validation/i
            }
          )
        );

        expect(
          mockSubmitPayment
        ).toHaveBeenCalledWith(
          "test-token",
          "50"
        );

        expect(
  (
    await screen.findAllByText(
      "PENDING_VALIDATION"
    )
  ).length
).toBeGreaterThan(0);
      }
    );
  }
);