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

import LoginPage
  from "./LoginPage";

const {
  mockLogin
} = vi.hoisted(
  () => ({
    mockLogin: vi.fn(),
  })
);

vi.mock(
  "../auth/AuthContext",
  () => ({
    useAuth: () => ({
      login: mockLogin,
    }),
  })
);

describe(
  "LoginPage",
  () => {

    beforeEach(() => {
      mockLogin.mockReset();
    });

    test(
      "logs requester in and redirects to dashboard",
      async () => {

        mockLogin.mockResolvedValue({
          userId: 1,
          username:
            "requester1",
          fullName:
            "Requester One",
          roles: [
            "PAYMENT_REQUESTER"
          ],
          accessToken:
            "test-token",
        });

        const user =
          userEvent.setup();

        render(
          <MemoryRouter
            initialEntries={[
              "/login"
            ]}
          >

            <Routes>

              <Route
                path="/login"
                element={
                  <LoginPage />
                }
              />

              <Route
                path="/"
                element={
                  <div>
                    Dashboard reached
                  </div>
                }
              />

            </Routes>

          </MemoryRouter>
        );

        await user.type(
          screen.getByLabelText(
            /username/i
          ),
          "requester1"
        );

        await user.type(
          screen.getByLabelText(
            /password/i
          ),
          "requester@123"
        );

        await user.click(
          screen.getByRole(
            "button",
            {
              name: /login/i
            }
          )
        );

        expect(
          mockLogin
        ).toHaveBeenCalledWith(
          "requester1",
          "requester@123"
        );

        expect(
          await screen.findByText(
            "Dashboard reached"
          )
        ).toBeInTheDocument();
      }
    );


    test(
      "shows login error",
      async () => {

        mockLogin.mockRejectedValue(
          new Error(
            "Invalid username or password"
          )
        );

        const user =
          userEvent.setup();

        render(
          <MemoryRouter>

            <LoginPage />

          </MemoryRouter>
        );

        await user.type(
          screen.getByLabelText(
            /username/i
          ),
          "wrong"
        );

        await user.type(
          screen.getByLabelText(
            /password/i
          ),
          "wrong"
        );

        await user.click(
          screen.getByRole(
            "button",
            {
              name: /login/i
            }
          )
        );

        expect(
          await screen.findByText(
            "Invalid username or password"
          )
        ).toBeInTheDocument();
      }
    );
  }
);