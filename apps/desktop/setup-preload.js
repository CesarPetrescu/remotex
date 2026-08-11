'use strict';

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('remotexSetup', Object.freeze({
  cancel: () => ipcRenderer.invoke('remotex-setup:cancel'),
  getState: () => ipcRenderer.invoke('remotex-setup:get-state'),
  saveRelayUrl: (relayUrl) => ipcRenderer.invoke('remotex-setup:save-relay-url', relayUrl),
}));
