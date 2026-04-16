'use strict';
const { contextBridge, ipcRenderer } = require('electron');

const ALLOWED_INVOKE = new Set([
  'config:load', 'config:save', 'config:save-as', 'config:select',
  'config:select-provider', 'config:export', 'config:import', 'config:reset',
  'config:delete', 'config:apply-template', 'config:launch',
  'config:environment-info', 'config:open-ollama-download', 'config:install-ollama',
  'app:launch-state', 'app:restart-to-config', 'app:quit-launcher', 'app:set-auto-copy-logs',
  'app:retry-launch-readiness',
  'app:mic-permission-status', 'app:request-mic-permission', 'app:open-mic-settings',
]);

const ALLOWED_ON = new Set([
  'server-log',
  'server-error',
  'launch-state',
]);

contextBridge.exposeInMainWorld('electronAPI', {
  invoke: (channel, ...args) => {
    if (!ALLOWED_INVOKE.has(channel)) {
      throw new Error(`Blocked IPC channel: ${channel}`);
    }

    return ipcRenderer.invoke(channel, ...args);
  },
  on: (channel, listener) => {
    if (!ALLOWED_ON.has(channel)) {
      throw new Error(`Blocked IPC listen channel: ${channel}`);
    }

    const wrapped = (_event, ...args) => listener(...args);
    ipcRenderer.on(channel, wrapped);
    return () => ipcRenderer.removeListener(channel, wrapped);
  },
});
