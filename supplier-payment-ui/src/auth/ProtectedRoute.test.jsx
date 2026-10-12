import {
  render,
  screen
} from "@testing-library/react";

import {
  MemoryRouter,
  Route,
  Routes
} from "react-router";

import {
  vi
} from "vitest";

import ProtectedRoute
  from "./ProtectedRoute";

const {
  mockUseAuth
} = vi.hoisted(
  () => ({
    mockUseAuth:
      vi.fn(),
  })
);

vi.mock(
  "./AuthContext",
  () => ({
    useAuth:
      mockUseAuth,
  })
);

describe(
  "ProtectedRoute",
  () => {

    test(
      "redirects unauthenticated user to login",
      () => {

        mockUseAuth.mockReturnValue({
          token: null,
          loading: false,
          hasRole:
            () => false,
        });

        render(
          <MemoryRouter
            initialEntries={[
              "/protected"
            ]}
          >

            <Routes>

              <Route
                path="/login"
                element={
                  <div>
                    Login Page
                  </div>
                }
              />

              <Route
                path="/protected"
                element={
                  <ProtectedRoute>
                    <div>
                      Protected Page
                    </div>
                  </ProtectedRoute>
                }
              />

            </Routes>

          </MemoryRouter>
        );

        expect(
          screen.getByText(
            "Login Page"
          )
        ).toBeInTheDocument();
      }
    );


    test(
      "allows authenticated requester",
      () => {

        mockUseAuth.mockReturnValue({
          token:
            "valid-token",

          loading: false,

          hasRole:
            (role) =>
              role ===
              "PAYMENT_REQUESTER",
        });

        render(
          <MemoryRouter>

            <ProtectedRoute
              requiredRole=
                "PAYMENT_REQUESTER"
            >
              <div>
                Protected Page
              </div>
            </ProtectedRoute>

          </MemoryRouter>
        );

        expect(
          screen.getByText(
            "Protected Page"
          )
        ).toBeInTheDocument();
      }
    );


    test(
      "blocks user without required role",
      () => {

        mockUseAuth.mockReturnValue({
          token:
            "valid-token",

          loading: false,

          hasRole:
            () => false,
        });

        render(
          <MemoryRouter>

            <ProtectedRoute
              requiredRole=
                "PAYMENT_REQUESTER"
            >
              <div>
                Protected Page
              </div>
            </ProtectedRoute>

          </MemoryRouter>
        );

        expect(
          screen.getByText(
            /access denied/i
          )
        ).toBeInTheDocument();
      }
    );
  }
);