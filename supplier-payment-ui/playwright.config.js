import {
  defineConfig
} from "@playwright/test";

export default defineConfig({

  testDir:
    "./e2e",

  timeout:
    60000,

  expect: {
    timeout:
      10000
  },

  use: {

    baseURL:
      "http://localhost:4173",

    headless:
      true,

    screenshot:
      "only-on-failure",

    trace:
      "retain-on-failure"
  },

  webServer: {

    command:
      "npm run dev -- --host localhost --port 4173",

    url:
      "http://localhost:4173",

    reuseExistingServer:
      true,

    timeout:
      120000
  }
});