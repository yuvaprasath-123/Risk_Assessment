import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState
} from "react";

import {
  apiRequest
} from "../api/apiClient";

const AuthContext =
  createContext(null);

const SECURITY_BASE_URL =
  "/security-api/api/v1/auth";

const TOKEN_KEY =
  "accessToken";

export function AuthProvider({
  children
}) {

  const [
    token,
    setToken
  ] = useState(
    () =>
      sessionStorage.getItem(
        TOKEN_KEY
      )
  );

  const [
    user,
    setUser
  ] = useState(null);

  const [
    loading,
    setLoading
  ] = useState(true);

  async function loadCurrentUser(
    currentToken
  ) {

    if (!currentToken) {

      setUser(null);
      setLoading(false);

      return;
    }

    try {

      const currentUser =
        await apiRequest(
          `${SECURITY_BASE_URL}/me`,
          {
            token:
              currentToken
          }
        );

      setUser(
        currentUser
      );

    } catch (error) {

      sessionStorage.removeItem(
        TOKEN_KEY
      );

      setToken(null);
      setUser(null);

    } finally {

      setLoading(false);
    }
  }

  useEffect(
    () => {

      loadCurrentUser(
        token
      );

    },
    [token]
  );

  async function login(
    username,
    password
  ) {

    const response =
      await apiRequest(
        `${SECURITY_BASE_URL}/login`,
        {
          method:
            "POST",

          body: {
            username,
            password
          }
        }
      );

    const accessToken =
      response.accessToken;

    if (!accessToken) {

      throw new Error(
        "Authentication token was not returned by the server."
      );
    }

    sessionStorage.setItem(
      TOKEN_KEY,
      accessToken
    );

    setToken(
      accessToken
    );

    try {

      const currentUser =
        await apiRequest(
          `${SECURITY_BASE_URL}/me`,
          {
            token:
              accessToken
          }
        );

      setUser(
        currentUser
      );

      return currentUser;

    } catch (error) {

      sessionStorage.removeItem(
        TOKEN_KEY
      );

      setToken(null);
      setUser(null);

      throw error;
    }
  }

  function logout() {

    sessionStorage.removeItem(
      TOKEN_KEY
    );

    setToken(null);
    setUser(null);
  }

  function hasRole(
    role
  ) {

    if (
      !user ||
      !Array.isArray(
        user.roles
      )
    ) {

      return false;
    }

    return user.roles.includes(
      role
    );
  }

  const value =
    useMemo(
      () => ({
        token,
        user,
        loading,
        login,
        logout,
        hasRole,
        isAuthenticated:
          Boolean(
            token &&
            user
          )
      }),
      [
        token,
        user,
        loading
      ]
    );

  return (
    <AuthContext.Provider
      value={
        value
      }
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {

  const context =
    useContext(
      AuthContext
    );

  if (!context) {

    throw new Error(
      "useAuth must be used inside AuthProvider"
    );
  }

  return context;
}