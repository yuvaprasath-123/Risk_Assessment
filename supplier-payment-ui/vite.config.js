import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

const proxyConfig = {
  "/security-api": {
    target: "http://localhost:8081",
    changeOrigin: true,
    rewrite: (path) =>
      path.replace(/^\/security-api/, "")
  },

  "/supplier-api": {
    target: "http://localhost:8082",
    changeOrigin: true,
    rewrite: (path) =>
      path.replace(/^\/supplier-api/, "")
  },

  "/payment-api": {
    target: "http://localhost:8083",
    changeOrigin: true,
    rewrite: (path) =>
      path.replace(/^\/payment-api/, "")
  }
};

export default defineConfig({
  plugins: [
    react()
  ],

  server: {
    port: 4173,
    allowedHosts: true,
    proxy: proxyConfig
  },

  preview: {
    port: 4173,
    allowedHosts: true,
    proxy: proxyConfig
  },

  test: {
    globals: true,

    environment: "jsdom",

    setupFiles: "./src/test/setup.js",

    css: true,

    include: [
      "src/**/*.{test,spec}.{js,jsx,ts,tsx}"
    ],

    exclude: [
      "e2e/**",
      "node_modules/**",
      "dist/**"
    ]
  }
});