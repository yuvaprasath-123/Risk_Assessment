import {
  createContext,
  useContext,
  useEffect,
  useState
} from "react";

import {
  login as loginApi,
  getCurrentUser
} from "../api/authApi";

const AuthContext =
  createContext(null);

export function AuthProvider({
  children
}) {

  const [token, setToken] =
    useState(
      sessionStorage.getItem(
        "accessToken"
      )
    );

  const [user, setUser] =
    useState(null);

  const [loading, setLoading] =
    useState(true);

  useEffect(
    () => {

      async function loadUser() {

        if (!token) {
          setLoading(false);
          return;
        }

        try {

          const currentUser =
            await getCurrentUser(
              token
            );

          setUser(currentUser);

        } catch {

          sessionStorage.removeItem(
            "accessToken"
          );

          setToken(null);
          setUser(null);

        } finally {
          setLoading(false);
        }
      }

      loadUser();

    },
    [token]
  );

  async function login(
    username,
    password
  ) {

    const response =
      await loginApi(
        username,
        password
      );

    sessionStorage.setItem(
      "accessToken",
      response.accessToken
    );

    setToken(
      response.accessToken
    );

    setUser({
      userId:
        response.userId,

      username:
        response.username,

      fullName:
        response.fullName,

      roles:
        response.roles,
    });

    return response;
  }

  function logout() {

    sessionStorage.removeItem(
      "accessToken"
    );

    setToken(null);
    setUser(null);
  }

  function hasRole(role) {

    return (
      user?.roles?.includes(
        role
      ) ?? false
    );
  }

  return (
    <AuthContext.Provider
      value={{
        token,
        user,
        loading,
        login,
        logout,
        hasRole,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(
    AuthContext
  );
}