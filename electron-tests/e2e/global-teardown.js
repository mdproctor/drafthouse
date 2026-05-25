// electron-tests/e2e/global-teardown.js
'use strict';
const { getServer } = require('./global-setup');

module.exports = async function globalTeardown() {
  const server = getServer();
  if (server) await server.killServer();
};
