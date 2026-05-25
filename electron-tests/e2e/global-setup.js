// electron-tests/e2e/global-setup.js
'use strict';
const fs   = require('fs');
const path = require('path');
const os   = require('os');
const { JavaServer, findFreePort } = require('../../java-server');

let server = null;

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

  console.log('[global-setup] starting shared Quarkus JVM...');
  server = new JavaServer();
  const port = await findFreePort();
  await server.spawnServer(port);
  process.env.TEST_QUARKUS_PORT = String(port);
  console.log(`[global-setup] Quarkus ready on port ${port} (pid ${server.getPid()})`);
};

module.exports.getServer = () => server;
