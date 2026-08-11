'use strict';

const path = require('node:path');
const { pathToFileURL } = require('node:url');

const {
  app,
  BrowserWindow,
  ipcMain,
  Menu,
  session,
  shell,
} = require('electron');

const { createSettingsStore } = require('./lib/settings');
const {
  isAllowedNavigation,
  isAllowedWebPermission,
  normalizeExternalUrl,
  normalizeRelayUrl,
} = require('./lib/relay-url');

const APP_ID = 'app.remotex.desktop';
const MAIN_PARTITION = 'persist:remotex';
const SETUP_PARTITION = 'remotex-setup';
const SETUP_PATH = path.join(__dirname, 'setup.html');
const SETUP_URL = pathToFileURL(SETUP_PATH).href;
const APP_ICON = path.join(__dirname, 'assets', 'logo.png');

let mainWindow = null;
let setupWindow = null;
let relayUrl = null;
let settingsStore = null;

function safeOpenExternal(candidate) {
  const externalUrl = normalizeExternalUrl(candidate);
  if (!externalUrl) {
    return;
  }

  shell.openExternal(externalUrl).catch(() => {
    // The operating system may have no registered browser. Keep Remotex open.
  });
}

function isCurrentRelayUrl(candidate) {
  return Boolean(relayUrl) && isAllowedNavigation(candidate, relayUrl);
}

function secureRemoteContents(contents) {
  const guardNavigation = (event, targetUrl) => {
    if (isCurrentRelayUrl(targetUrl)) {
      return;
    }

    event.preventDefault();
    safeOpenExternal(targetUrl);
  };

  contents.on('will-navigate', guardNavigation);
  contents.on('will-redirect', guardNavigation);
  contents.on('will-attach-webview', (event) => event.preventDefault());

  contents.setWindowOpenHandler(({ url }) => {
    if (isCurrentRelayUrl(url)) {
      setImmediate(() => {
        if (mainWindow && !mainWindow.isDestroyed()) {
          mainWindow.loadURL(url).catch(() => {});
        }
      });
    } else {
      safeOpenExternal(url);
    }

    return { action: 'deny' };
  });
}

function secureSetupContents(contents) {
  contents.on('will-navigate', (event, targetUrl) => {
    if (targetUrl === SETUP_URL) {
      return;
    }

    event.preventDefault();
    safeOpenExternal(targetUrl);
  });
  contents.on('will-attach-webview', (event) => event.preventDefault());
  contents.setWindowOpenHandler(({ url }) => {
    safeOpenExternal(url);
    return { action: 'deny' };
  });
}

function permissionComesFromRelay(webContents, permission, requestingUrl) {
  return Boolean(
    mainWindow
      && !mainWindow.isDestroyed()
      // Electron passes null WebContents for some permission checks,
      // including notifications. The exact requesting origin remains
      // authoritative; cross-origin frames still fail the check.
      && (!webContents || webContents === mainWindow.webContents)
      && isAllowedWebPermission(permission, requestingUrl, relayUrl),
  );
}

function configureSessionPermissions() {
  const remoteSession = session.fromPartition(MAIN_PARTITION);
  const setupSession = session.fromPartition(SETUP_PARTITION);

  remoteSession.setPermissionRequestHandler((webContents, permission, callback, details) => {
    const requestingUrl = (details && details.requestingUrl) || webContents.getURL();
    const allowed = permissionComesFromRelay(webContents, permission, requestingUrl);
    callback(allowed);
  });
  remoteSession.setPermissionCheckHandler((webContents, permission, requestingOrigin, details) => (
    permissionComesFromRelay(
      webContents,
      permission,
      (details && details.requestingUrl) || requestingOrigin,
    )
  ));
  remoteSession.setDevicePermissionHandler(() => false);

  setupSession.setPermissionRequestHandler((_webContents, _permission, callback) => callback(false));
  setupSession.setPermissionCheckHandler(() => false);
  setupSession.setDevicePermissionHandler(() => false);
}

function createMainWindow() {
  if (mainWindow && !mainWindow.isDestroyed()) {
    return mainWindow;
  }

  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 860,
    minHeight: 600,
    show: false,
    autoHideMenuBar: false,
    backgroundColor: '#050910',
    icon: APP_ICON,
    title: 'Remotex',
    webPreferences: {
      partition: MAIN_PARTITION,
      nodeIntegration: false,
      nodeIntegrationInWorker: false,
      nodeIntegrationInSubFrames: false,
      contextIsolation: true,
      sandbox: true,
      webSecurity: true,
      allowRunningInsecureContent: false,
      webviewTag: false,
      navigateOnDragDrop: false,
      devTools: !app.isPackaged,
    },
  });

  secureRemoteContents(mainWindow.webContents);
  mainWindow.once('ready-to-show', () => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.show();
    }
  });
  mainWindow.on('closed', () => {
    mainWindow = null;
  });

  return mainWindow;
}

function openRelayWindow() {
  if (!relayUrl) {
    openSetupWindow();
    return;
  }

  const window = createMainWindow();
  window.loadURL(relayUrl).catch(() => {
    // Chromium displays its network error page and the app menu remains usable.
  });
  if (window.isMinimized()) {
    window.restore();
  }
  window.show();
  window.focus();
}

function openSetupWindow() {
  if (setupWindow && !setupWindow.isDestroyed()) {
    setupWindow.show();
    setupWindow.focus();
    return;
  }

  const hasParent = Boolean(mainWindow && !mainWindow.isDestroyed());
  setupWindow = new BrowserWindow({
    width: 680,
    height: 650,
    minWidth: 420,
    minHeight: 560,
    show: false,
    parent: hasParent ? mainWindow : undefined,
    modal: hasParent,
    autoHideMenuBar: true,
    backgroundColor: '#050910',
    icon: APP_ICON,
    title: 'Connect Remotex',
    webPreferences: {
      partition: SETUP_PARTITION,
      preload: path.join(__dirname, 'setup-preload.js'),
      nodeIntegration: false,
      nodeIntegrationInWorker: false,
      nodeIntegrationInSubFrames: false,
      contextIsolation: true,
      sandbox: true,
      webSecurity: true,
      allowRunningInsecureContent: false,
      webviewTag: false,
      navigateOnDragDrop: false,
      devTools: !app.isPackaged,
    },
  });

  secureSetupContents(setupWindow.webContents);
  setupWindow.once('ready-to-show', () => {
    if (setupWindow && !setupWindow.isDestroyed()) {
      setupWindow.show();
    }
  });
  setupWindow.on('closed', () => {
    setupWindow = null;
    if (!relayUrl && (!mainWindow || mainWindow.isDestroyed())) {
      app.quit();
    }
  });
  setupWindow.loadFile(SETUP_PATH).catch(() => app.quit());
}

function trustedSetupSender(event) {
  return Boolean(
    setupWindow
      && !setupWindow.isDestroyed()
      && event.sender === setupWindow.webContents
      && event.senderFrame
      && event.senderFrame.url === SETUP_URL,
  );
}

function registerSetupIpc() {
  ipcMain.handle('remotex-setup:get-state', (event) => {
    if (!trustedSetupSender(event)) {
      throw new Error('Blocked untrusted settings request.');
    }

    return {
      relayUrl,
      canCancel: Boolean(relayUrl && mainWindow && !mainWindow.isDestroyed()),
    };
  });

  ipcMain.handle('remotex-setup:save-relay-url', (event, input) => {
    if (!trustedSetupSender(event)) {
      throw new Error('Blocked untrusted settings request.');
    }

    if (typeof input !== 'string' || input.length > 2048) {
      return { ok: false, error: 'Enter a valid relay URL.' };
    }

    let normalized;
    try {
      normalized = normalizeRelayUrl(input);
    } catch (error) {
      return { ok: false, error: error.message };
    }

    try {
      relayUrl = settingsStore.save(normalized);
    } catch {
      return { ok: false, error: 'Desktop settings could not be saved.' };
    }

    setTimeout(() => {
      const windowToClose = setupWindow;
      openRelayWindow();
      if (windowToClose && !windowToClose.isDestroyed()) {
        windowToClose.close();
      }
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.show();
        mainWindow.focus();
      }
    }, 0);

    return { ok: true, relayUrl };
  });

  ipcMain.handle('remotex-setup:cancel', (event) => {
    if (!trustedSetupSender(event)) {
      throw new Error('Blocked untrusted settings request.');
    }

    if (relayUrl && mainWindow && !mainWindow.isDestroyed()) {
      setTimeout(() => {
        if (setupWindow && !setupWindow.isDestroyed()) {
          setupWindow.close();
        }
      }, 0);
    }

    return true;
  });
}

function installApplicationMenu() {
  const viewItems = [
    { role: 'reload' },
    { role: 'forceReload' },
    { type: 'separator' },
    { role: 'resetZoom' },
    { role: 'zoomIn' },
    { role: 'zoomOut' },
    { type: 'separator' },
    { role: 'togglefullscreen' },
  ];

  if (!app.isPackaged) {
    viewItems.push({ type: 'separator' }, { role: 'toggleDevTools' });
  }

  const template = [
    {
      label: 'Relay',
      submenu: [
        {
          label: 'Change Relay…',
          accelerator: 'CmdOrCtrl+,',
          click: () => openSetupWindow(),
        },
        { type: 'separator' },
        { role: 'quit' },
      ],
    },
    {
      label: 'Edit',
      submenu: [
        { role: 'undo' },
        { role: 'redo' },
        { type: 'separator' },
        { role: 'cut' },
        { role: 'copy' },
        { role: 'paste' },
        { role: 'selectAll' },
      ],
    },
    { label: 'View', submenu: viewItems },
    {
      label: 'Window',
      submenu: [
        { role: 'minimize' },
        { role: 'close' },
      ],
    },
  ];

  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

const hasSingleInstanceLock = app.requestSingleInstanceLock();

if (!hasSingleInstanceLock) {
  app.quit();
} else {
  app.setAppUserModelId(APP_ID);

  app.on('second-instance', () => {
    const window = setupWindow && !setupWindow.isDestroyed() ? setupWindow : mainWindow;
    if (!window || window.isDestroyed()) {
      return;
    }
    if (window.isMinimized()) {
      window.restore();
    }
    window.show();
    window.focus();
  });

  app.whenReady().then(() => {
    settingsStore = createSettingsStore(app.getPath('userData'));
    relayUrl = settingsStore.load().relayUrl;

    configureSessionPermissions();
    registerSetupIpc();
    installApplicationMenu();

    if (relayUrl) {
      openRelayWindow();
    } else {
      openSetupWindow();
    }

    app.on('activate', () => {
      if (BrowserWindow.getAllWindows().length === 0) {
        if (relayUrl) {
          openRelayWindow();
        } else {
          openSetupWindow();
        }
      }
    });
  });
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});
