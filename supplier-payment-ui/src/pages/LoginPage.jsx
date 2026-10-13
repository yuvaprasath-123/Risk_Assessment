import {
  useState
} from "react";

import {
  useNavigate
} from "react-router";

import {
  useAuth
} from "../auth/AuthContext";

import ErrorMessage
  from "../components/ErrorMessage";

export default function LoginPage() {

  const [username, setUsername] =
    useState("");

  const [password, setPassword] =
    useState("");

  const [error, setError] =
    useState("");

  const [submitting, setSubmitting] =
    useState(false);

  const {
    login
  } = useAuth();

  const navigate =
    useNavigate();

  async function handleSubmit(event) {

    event.preventDefault();

    setError("");
    setSubmitting(true);

    try {

      const response =
        await login(
          username,
          password
        );

      if (
        response.roles.includes(
          "PAYMENT_REQUESTER"
        )
      ) {

        navigate(
          "/"
        );

      } else {

        setError(
          "Frontend currently supports the requester workflow only."
        );
      }

    } catch (error) {

      setError(
        error.message
      );

    } finally {

      setSubmitting(false);
    }
  }

  return (
    <div className="login-page">

      <form
        className="card login-card"
        onSubmit={
          handleSubmit
        }
      >

        <h1>
          Supplier Payment Control
        </h1>

        <p>
          Sign in to continue
        </p>

        <ErrorMessage
          message={error}
        />

        <label htmlFor="username">
  Username
</label>

<input
  id="username"
  value={username}
  onChange={
    (event) =>
      setUsername(
        event.target.value
      )
  }
  required
/>

<label htmlFor="password">
  Password
</label>

<input
  id="password"
  type="password"
  value={password}
  onChange={
    (event) =>
      setPassword(
        event.target.value
      )
  }
  required
/>

        <button
          type="submit"
          disabled={submitting}
        >
          {
            submitting
              ? "Signing in..."
              : "Login"
          }
        </button>

      </form>

    </div>
  );
}