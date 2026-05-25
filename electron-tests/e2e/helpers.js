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
  const app    = await electron.launch({ executablePath: ELECTRON_BIN, args });
  const window = await app.firstWindow();
  const jsErrors = [];
  window.on('pageerror', err => jsErrors.push(err.message));
  if (fileA) await window.waitForFunction(
    () => document.querySelector('#render-a h1') !== null,
    undefined,
    { timeout: 90_000 }
  );
  if (fileB) await window.waitForFunction(
    () => document.querySelector('#render-b h1') !== null,
    undefined,
    { timeout: 90_000 }
  );
  return { app, window, jsErrors };
}

module.exports = { launchApp, ELECTRON_BIN, APP_PATH };
