import {
  render,
  screen
} from "@testing-library/react";

import userEvent
  from "@testing-library/user-event";

import {
  beforeEach,
  describe,
  expect,
  test,
  vi
} from "vitest";

import {
  AuthProvider,
  useAuth
} from "./AuthContext";

const {
  mockLoginApi,
  mockGetCurrentUser
} = vi.hoisted(
  () => ({
    mockLoginApi:
      vi.fn(),

    mockGetCurrentUser:
      vi.fn(),
  })
);

vi.mock(
  "../api/authApi",
  () => ({
    login:
      mockLoginApi,

    getCurrentUser:
      mockGetCurrentUser,
  })
);

function TestComponent() {

  const {
    token,
    user,
    login,
    logout
  } = useAuth();

  return (
    <div>

      <div>
        Token:
        {token || "none"}
      </div>

      <div>
        User:
        {user?.username || "none"}
      </div>

      <button
        onClick={() =>
          login(
            "requester1",
            "requester@123"
          )
        }
      >
        Test Login
      </button>

      <button
        onClick={logout}
      >
        Test Logout
      </button>

    </div>
  );
}

describe(
  "AuthContext",
  () => {

    beforeEach(() => {

      mockLoginApi.mockReset();

      mockGetCurrentUser.mockReset();

      sessionStorage.clear();
    });

    test(
      "stores JWT after login and removes it on logout",
      async () => {

        mockLoginApi.mockResolvedValue({
          accessToken:
            "abc123",

          userId: 1,

          username:
            "requester1",

          fullName:
            "Requester One",

          roles: [
            "PAYMENT_REQUESTER"
          ],
        });

        mockGetCurrentUser.mockResolvedValue({
          userId: 1,

          username:
            "requester1",

          fullName:
            "Requester One",

          email:
            "requester1@test.com",

          status:
            "ACTIVE",

          roles: [
            "PAYMENT_REQUESTER"
          ],
        });

        const user =
          userEvent.setup();

        render(
          <AuthProvider>
            <TestComponent />
          </AuthProvider>
        );

        await user.click(
          screen.getByRole(
            "button",
            {
              name:
                "Test Login"
            }
          )
        );

        expect(
          sessionStorage.getItem(
            "accessToken"
          )
        ).toBe(
          "abc123"
        );

        expect(
          await screen.findByText(
            /User:\s*requester1/
          )
        ).toBeInTheDocument();

        expect(
          mockGetCurrentUser
        ).toHaveBeenCalledWith(
          "abc123"
        );

        await user.click(
          screen.getByRole(
            "button",
            {
              name:
                "Test Logout"
            }
          )
        );

        expect(
          sessionStorage.getItem(
            "accessToken"
          )
        ).toBeNull();

        expect(
          screen.getByText(
            /User:\s*none/
          )
        ).toBeInTheDocument();
      }
    );
  }
);