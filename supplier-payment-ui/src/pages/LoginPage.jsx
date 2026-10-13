import {
  useState
} from "react";

import {
  useNavigate
} from "react-router";

import {
  useTranslation
} from "react-i18next";

import {
  useAuth
} from "../auth/AuthContext";

import {
  useTheme
} from "../theme/ThemeContext";

import LanguageSelector
  from "../components/LanguageSelector";

export default function LoginPage() {

  const {
    t
  } = useTranslation();

  const {
    login
  } = useAuth();

  const {
    theme,
    toggleTheme
  } = useTheme();

  const navigate =
    useNavigate();

  const [
    username,
    setUsername
  ] = useState("");

  const [
    password,
    setPassword
  ] = useState("");

  const [
    error,
    setError
  ] = useState("");

  const [
    loading,
    setLoading
  ] = useState(false);

  async function handleSubmit(
    event
  ) {

    event.preventDefault();

    setError("");
    setLoading(true);

    try {

      await login(
        username,
        password
      );

      navigate("/");

    } catch (err) {

      setError(
        err.message ||
        t(
          "login.error"
        )
      );

    } finally {

      setLoading(false);
    }
  }

  return (
    <div className="login-page">

      <div className="login-card">

        <div className="login-toolbar">

          <LanguageSelector />

          <button
            type="button"
            className="theme-toggle"
            onClick={
              toggleTheme
            }
            aria-label={
              theme === "dark"
                ? "Switch to light mode"
                : "Switch to dark mode"
            }
            title={
              theme === "dark"
                ? "Switch to light mode"
                : "Switch to dark mode"
            }
          >
            {
              theme === "dark"
                ? "☀ Light"
                : "🌙 Dark"
            }
          </button>

        </div>

        <h1>
          {
            t(
              "appName"
            )
          }
        </h1>

        <p>
          {
            t(
              "login.subtitle"
            )
          }
        </p>

        {
          error &&
          (
            <div className="error-message">

              {error}

            </div>
          )
        }

        <form
          onSubmit={
            handleSubmit
          }
        >

          <label
            htmlFor="username"
          >
            {
              t(
                "login.username"
              )
            }
          </label>

          <input
            id="username"
            type="text"
            value={
              username
            }
            onChange={
              event =>
                setUsername(
                  event.target.value
                )
            }
            required
            disabled={
              loading
            }
          />

          <label
            htmlFor="password"
          >
            {
              t(
                "login.password"
              )
            }
          </label>

          <input
            id="password"
            type="password"
            value={
              password
            }
            onChange={
              event =>
                setPassword(
                  event.target.value
                )
            }
            required
            disabled={
              loading
            }
          />

          <button
            type="submit"
            disabled={
              loading
            }
          >
            {
              loading
                ? t(
                    "login.loggingIn"
                  )
                : t(
                    "login.button"
                  )
            }
          </button>

        </form>

      </div>

    </div>
  );
}