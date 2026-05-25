// playwright.config.js
const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
  testDir:         './electron-tests/e2e',
  globalSetup:     './electron-tests/e2e/global-setup.js',
  globalTeardown:  './electron-tests/e2e/global-teardown.js',
  timeout:         180_000,
  retries:         0,
  workers:         1,   // Sequential — specs share one Quarkus JVM via global-setup
  use: { headless: false },
});
