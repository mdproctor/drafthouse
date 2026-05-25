// java-server.js — Quarkus process manager for md-compare
'use strict';
const { spawn }        = require('child_process');
const http             = require('http');
const net              = require('net');
const path             = require('path');
const { EventEmitter } = require('events');

const MAX_RESTARTS       = 3;
const STABILITY_RESET_MS = 60_000;
const BACKOFF_MS         = [1000, 2000, 4000];
const LOG_BUFFER_SIZE    = 200;

function findFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      server.close(() => resolve(port));
    });
    server.on('error', reject);
  });
}

function pollUntilReady(port, { intervalMs = 200, timeoutMs = 20000 } = {}) {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + timeoutMs;
    const attempt = () => {
      if (Date.now() >= deadline) {
        reject(new Error(`Server did not start within ${timeoutMs}ms`));
        return;
      }
      const req = http.get(`http://127.0.0.1:${port}/api/ping`, (res) => {
        res.resume();
        if (res.statusCode === 200) resolve();
        else setTimeout(attempt, intervalMs);
      });
      req.on('error', () => setTimeout(attempt, intervalMs));
      req.setTimeout(intervalMs, () => { req.destroy(); });
    };
    attempt();
  });
}

function getJarPath(isPackaged, resourcesPath) {
  if (isPackaged) return path.join(resourcesPath, 'mdcompare-server-runner.jar');
  return path.join(__dirname, 'server', 'target', 'mdcompare-server-runner.jar');
}

function getUiDir(isPackaged, resourcesPath) {
  if (isPackaged) return path.join(resourcesPath, 'ui');
  return __dirname;
}

class JavaServer extends EventEmitter {
  constructor({ isPackaged = false, resourcesPath = '' } = {}) {
    super();
    this._isPackaged    = isPackaged;
    this._resourcesPath = resourcesPath;
    this._port          = null;
    this._process       = null;
    this._state         = 'idle';
    this._logs          = [];
    this._crashCount    = 0;
    this._stabilityTimer = null;
    this._restartTimer  = null;
    this._pollFn        = pollUntilReady;
  }

  getPort()  { return this._port; }
  getLogs()  { return [...this._logs]; }
  getPid()   { return this._process ? this._process.pid : null; }

  async spawnServer(port) {
    if (this._state !== 'idle') throw new Error(`Cannot spawn: server is in state '${this._state}'`);
    this._port  = port;
    this._state = 'starting';
    this._doSpawn();
    await this._pollFn(port);
    this._state = 'healthy';
    this._resetStabilityTimer();
  }

  _doSpawn() {
    const jarPath = getJarPath(this._isPackaged, this._resourcesPath);
    const uiDir   = getUiDir(this._isPackaged, this._resourcesPath);
    const jvmArgs = [
      `-Dquarkus.http.port=${this._port}`,
      `-Dui.dir=${uiDir}`,
      '-jar', jarPath,
    ];
    this._process = spawn('java', jvmArgs, { env: { ...process.env } });
    this._process.stdout.on('data', d => this._appendLog(d.toString()));
    this._process.stderr.on('data', d => this._appendLog(d.toString()));
    this._process.on('exit', (code, signal) => this._onExit(code, signal));
  }

  _appendLog(text) {
    const lines = text.split('\n').filter(l => l.length > 0);
    this._logs.push(...lines);
    if (this._logs.length > LOG_BUFFER_SIZE) this._logs = this._logs.slice(-LOG_BUFFER_SIZE);
  }

  _resetStabilityTimer() {
    if (this._stabilityTimer) clearTimeout(this._stabilityTimer);
    this._stabilityTimer = setTimeout(() => { this._crashCount = 0; }, STABILITY_RESET_MS);
  }

  _onExit(code, signal) {
    if (this._state === 'idle') return;
    this._state = 'crashed';
    this.emit('crashed', { code, signal });
    this._crashCount++;
    if (this._crashCount > MAX_RESTARTS) {
      this._state = 'fatal';
      this.emit('fatal', { logs: this.getLogs() });
      return;
    }
    const delay = BACKOFF_MS[Math.min(this._crashCount - 1, BACKOFF_MS.length - 1)];
    this._restartTimer = setTimeout(() => this._restart(), delay);
  }

  async _restart() {
    this._state = 'restarting';
    this._doSpawn();
    try {
      await this._pollFn(this._port);
      this._state = 'healthy';
      this.emit('restarted');
      this._resetStabilityTimer();
    } catch (_) {
      // Poll timed out — process may have exited already and _onExit already fired.
      // If state is still 'restarting' (no _onExit yet), reset to 'crashed' so
      // the next _onExit can trigger another restart attempt.
      if (this._state === 'restarting') this._state = 'crashed';
    }
  }

  async killServer() {
    this._state = 'idle';
    if (this._stabilityTimer) clearTimeout(this._stabilityTimer);
    if (this._restartTimer) clearTimeout(this._restartTimer);
    if (!this._process) return;
    return new Promise((resolve) => {
      const timer = setTimeout(() => { this._process.kill('SIGKILL'); resolve(); }, 5000);
      this._process.once('exit', () => { clearTimeout(timer); resolve(); });
      this._process.kill('SIGTERM');
    });
  }
}

module.exports = { JavaServer, findFreePort };
