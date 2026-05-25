// main.js
'use strict';
const { app, BrowserWindow, ipcMain, dialog } = require('electron');
const path = require('path');
const { JavaServer, findFreePort } = require('./java-server');

let mainWindow = null;
const server = new JavaServer({ isPackaged: app.isPackaged, resourcesPath: process.resourcesPath });

// Extra CLI args: electron <app> [fileA] [fileB]
// Filter out Electron/Chromium flags (--*) and the app directory itself, which
// Playwright may leave in argv when it fails to splice its debug flags cleanly.
const initFiles = process.argv.slice(2).filter(
  a => !a.startsWith('--') && a !== __dirname
);

function showErrorWindow(message) {
  const escape = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const win  = new BrowserWindow({ width: 700, height: 500, show: false });
  const logs = escape(server.getLogs().join('\n'));
  const html = `<!DOCTYPE html><html><body style="font-family:monospace;padding:20px;background:#1a1a1a;color:#eee">
    <h2 style="color:#f87171">md-compare failed to start</h2>
    <p>${escape(message)}</p>
    <pre style="overflow:auto;background:#111;padding:10px;max-height:350px">${logs}</pre>
    </body></html>`;
  win.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(html)}`);
  win.show();
}

async function createMainWindow(port) {
  mainWindow = new BrowserWindow({
    width: 1400,
    height: 900,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  mainWindow.once('ready-to-show', () => {
    mainWindow.show();
    mainWindow.webContents.send('init:config', { port });
    if (initFiles.length > 0) {
      mainWindow.webContents.send('init:files', initFiles[0] || null, initFiles[1] || null);
    }
  });
  await mainWindow.loadURL(`http://127.0.0.1:${port}/`);
}

app.whenReady().then(async () => {
  try {
    let port;
    if (process.env.QUARKUS_PORT) {
      port = parseInt(process.env.QUARKUS_PORT, 10);
    } else {
      server.on('fatal', () => showErrorWindow('The md-compare server crashed and could not restart.'));
      port = await findFreePort();
      await server.spawnServer(port);
    }
    await createMainWindow(port);
  } catch (err) {
    showErrorWindow(err.message);
  }
});

app.on('before-quit', async (event) => {
  event.preventDefault();
  if (!process.env.QUARKUS_PORT) await server.killServer();
  app.exit(0);
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

// Native file dialog — stays as IPC (can't do this over HTTP)
ipcMain.handle('dialog:selectFile', async () => {
  const { canceled, filePaths } = await dialog.showOpenDialog(mainWindow, {
    properties: ['openFile'],
    filters: [{ name: 'Markdown / Text', extensions: ['md', 'markdown', 'txt'] }],
  });
  return canceled ? null : filePaths[0];
});
