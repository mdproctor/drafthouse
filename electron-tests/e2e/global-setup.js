// electron-tests/e2e/global-setup.js
'use strict';
const fs   = require('fs');
const path = require('path');
const os   = require('os');
const { _electron: electron } = require('playwright');
const { ELECTRON_BIN, APP_PATH } = require('./helpers');

module.exports = async function globalSetup() {
  const fileA = path.join(os.tmpdir(), 'mdcompare-test-a.md');
  const fileB = path.join(os.tmpdir(), 'mdcompare-test-b.md');

  fs.writeFileSync(fileA, [
    '# Rule Engines',
    '',
    'A rule engine evaluates business rules against a set of facts.',
    '',
    '## How It Works',
    '',
    'Rules have a condition and an action.',
    '',
    '```java',
    'rule "Large order"',
    'when Order(total > 1000)',
    'then flag(order);',
    'end',
    '```',
    '',
    '## Limitations',
    '',
    'Rule engines add operational complexity and require careful tuning.',
  ].join('\n'));

  fs.writeFileSync(fileB, [
    '# Rule Engines',
    '',
    'A rule engine runs your business rules so developers do not have to hard-code them.',
    '',
    '## How It Works',
    '',
    'Each rule has a condition and an action. When the condition matches, the action fires.',
    '',
    '```java',
    'rule "Large order"',
    'when Order(total > 1000)',
    'then flag(order);',
    'end',
    '```',
    '',
    '## When Not to Use One',
    '',
    'Do not reach for a rule engine to replace five if/else statements.',
  ].join('\n'));

  process.env.TEST_FILE_A = fileA;
  process.env.TEST_FILE_B = fileB;

  // Warm up the Quarkus JVM once before tests begin. Each spec file launches
  // its own JVM; cold starts on a loaded machine can take >180s. A single warmup
  // here loads the JAR bytecode into OS page cache so subsequent launches are fast.
  // We pass both fixture files so the full startup path (Quarkus start + file fetch
  // + render) runs — this is the most thorough warmup.
  // Global setup has no per-test timeout — this can run as long as needed.
  console.log('[global-setup] warming Quarkus JVM (may take 2-3 min on cold machine)...');
  const app = await electron.launch({ executablePath: ELECTRON_BIN, args: [APP_PATH, fileA, fileB] });
  const win  = await app.firstWindow();
  win.on('console', msg => { if (msg.type() === 'error') process.stdout.write(`[startup:error] ${msg.text()}\n`); });
  // Wait for panels to render OR an error window to appear (fast fail instead of 90s hang)
  await win.waitForFunction(
    () => document.querySelector('#render-a h1') !== null || document.querySelector('h2[style]') !== null,
    undefined,
    { timeout: 90_000 }
  );
  if (await win.evaluate(() => document.querySelector('#render-a h1') === null)) {
    const errText = await win.evaluate(() => document.querySelector('p')?.textContent || 'unknown startup error');
    throw new Error(`Startup failed: ${errText}`);
  }
  await win.waitForFunction(() => document.querySelector('#render-b h1') !== null, undefined, { timeout: 90_000 });
  await app.close();
  console.log('[global-setup] JVM warm, starting tests');
};
