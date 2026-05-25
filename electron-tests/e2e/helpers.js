// electron-tests/e2e/helpers.js
'use strict';
const { _electron: electron } = require('playwright');
const path = require('path');

const ELECTRON_BIN = process.env.ELECTRON_BIN ||
  '/Users/mdproctor/claude/sparge/node_modules/electron/dist/Electron.app/Contents/MacOS/Electron';

const APP_PATH = path.join(__dirname, '..', '..');

async function launchApp(fileA, fileB) {
  if (fileA === undefined) throw new TypeError(
    'launchApp: fileA is undefined — TEST_FILE_A env var not set; run tests via Playwright with global-setup');
  if (fileB === undefined) throw new TypeError(
    'launchApp: fileB is undefined — TEST_FILE_B env var not set; run tests via Playwright with global-setup');

  const args = [APP_PATH];
  if (fileA) args.push(fileA);
  if (fileB) args.push(fileB);
  const env = { ...process.env };
  if (process.env.TEST_QUARKUS_PORT) {
    env.QUARKUS_PORT = process.env.TEST_QUARKUS_PORT;
  }
  const app    = await electron.launch({ executablePath: ELECTRON_BIN, args, env });
  const window = await app.firstWindow();
  const jsErrors = [];
  window.on('pageerror', err => jsErrors.push(err.message));
  // polling:100 uses timer-based CDP evaluation rather than requestAnimationFrame
  // polling (the default). RAF does not fire in hidden Electron windows, which caused
  // waitForFunction to hang until the window became visible. See global-setup.js.
  if (fileA) await window.waitForFunction(
    () => document.querySelector('#render-a h1') !== null,
    undefined,
    { timeout: 0, polling: 100 }
  );
  if (fileB) await window.waitForFunction(
    () => document.querySelector('#render-b h1') !== null,
    undefined,
    { timeout: 0, polling: 100 }
  );
  return { app, window, jsErrors };
}

module.exports = { launchApp, ELECTRON_BIN, APP_PATH };
