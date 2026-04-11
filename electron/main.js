'use strict';
const { app, BrowserWindow, ipcMain, dialog, clipboard, screen, shell, safeStorage, systemPreferences, session, } =
require
('electron');
const path = require('path');
const { spawn } = require('child_process');
const http = require('http');
const treeKill = require('tree-kill');
const fs = require('fs');
const { pathToFileURL } = require('url');
const yaml = require('js-yaml');
const {
  SERVER_READY_TIMEOUT_MS,
  UI_READY_GRACE_TIMEOUT_MS,
  PRELOAD_PATH,
  SPLASH_PATH,
  CONFIG_EDITOR_PATH,
  SERVER_SPLASH_PATH,
  CONFIG_TEMPLATES,
  DEFAULT_STARTER_TEMPLATE_IDS,
  startTempServer,
} = require('./launcher-config');

let tempServer = null;
let mainWindow, splashWindow, serverSplashWindow, configWindow, serverProcess;
let dynamicServerPort = null;
let dynamicServerUrl = null;
const isDev = !app.isPackaged;
let isQuitting = false;
let fullLogBuffer = "";
let activeConfigPath = null;
let currentConfigId = null;
let restartToConfigAfterStop = false;
let currentLaunchToken = 0;
let lastLaunchCommand = '';
let secretsStoreCache = null;
let secretsEncryptionAvailable = null;

let serverReadyStartTime = 0;

const providerTypeCache = new Map();

function canHandleMediaPermission(permission) {
  return permission === 'media' || permission === 'audioCapture';
}

function openMicrophonePrivacySettings() {
  if (process.platform === 'darwin') {
    shell.openExternal('x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone');
    return;
  }

  if (process.platform === 'win32') {
    shell.openExternal('ms-settings:privacy-microphone');
  }
}

async function ensureMacMicrophoneAccess() {
  if (process.platform !== 'darwin') {
    return { status: 'not-applicable', granted: true };
  }

  const status = systemPreferences.getMediaAccessStatus('microphone');
  if (status === 'granted') {
    return { status, granted: true };
  }

  const granted = await systemPreferences.askForMediaAccess('microphone');
  const nextStatus = systemPreferences.getMediaAccessStatus('microphone');
  return { status: nextStatus, granted };
}

function getTemplateById(templateId) {
  return Object.values(CONFIG_TEMPLATES).find(item => item.id === templateId) || null;
}

function getTemplateByName(name) {
  return Object.values(CONFIG_TEMPLATES).find(item => item.name === name) || null;
}

function getStarterTemplateForProviderType(providerType = 'ollama') {
  return Object.values(CONFIG_TEMPLATES).find(item =>
    DEFAULT_STARTER_TEMPLATE_IDS.has(item.id) && item.providerType === providerType
  ) || CONFIG_TEMPLATES.ollama;
}

function shouldCompactToTemplateYaml(yamlText = '') {
  const text = String(yamlText || '');
  return text.includes('system-prompt:') || text.includes('\n---\n') || text.includes('spring.config.activate');
}

function getPreferredTemplateNameForYaml(yamlText = '') {
  const providerType = detectProviderType(yamlText);
  if (providerType === 'openai') return 'OpenAI';
  if (providerType === 'openai-compatible') return 'OpenAI Compatible';
  return 'Ollama';
}


function getFriendlyConfigName(rawName = '', yamlText = '') {
  const preferredName = getPreferredTemplateNameForYaml(yamlText);
  const normalized = sanitizeConfigName(rawName) || preferredName;
  const duplicateMatch = normalized.match(/^(Ollama|OpenAI|OpenAI Compatible)\s+(\d+)$/i);
  if (duplicateMatch) {
    const modelName = extractPrimaryModelName(yamlText);
    if (modelName) return `${preferredName} - ${modelName}`;
    return `${preferredName} Custom ${duplicateMatch[2]}`;
  }
  if (['default', 'test'].includes(normalized.toLowerCase())) return preferredName;
  return normalized;
}

function getConfigsForProviderType(index, providerType = 'ollama') {
  return index.configs.filter(config => getCachedProviderType(config.id) === providerType);
}

function ensureProviderConfig(index, providerType = 'ollama') {
  const existing = getConfigsForProviderType(index, providerType);
  if (existing.length > 0) return existing[0];
  const template = getStarterTemplateForProviderType(providerType);
  const existingIds = new Set(index.configs.map(config => config.id));
  const existingNames = new Set(index.configs.map(config => config.name.toLowerCase()));
  const configId = createUniqueConfigId(template.name, existingIds);
  const configName = createUniqueConfigName(template.name, existingNames);
  fs.writeFileSync(getConfigFilePath(configId), template.yaml, 'utf8');
  const created = { id: configId, name: configName };
  index.configs.push(created);
  return created;
}

function extractPlaceholdersFromText(text = '') {
  return [...String(text).matchAll(/\$\{([^}]+)\}/g)].map(match => match[1]);
}

function collectSecretSuggestions(yamlText = '') {
  const suggestions = new Map();
  const providerType = detectProviderType(yamlText);

  for (const placeholder of extractPlaceholdersFromText(yamlText)) {
    let description = 'Referenced by the selected YAML override.';
    if (placeholder === 'OPENAI_API_KEY' && providerType === 'openai') {
      description = 'Needed when this setting uses the OpenAI backend for chat and embedding.';
    } else if (placeholder === 'OPENAI_API_KEY' && providerType === 'openai-compatible') {
      description = 'Needed only if your OpenAI-compatible server expects an API key.';
    }
    suggestions.set(placeholder, { key: placeholder, source: 'profile', profileRequired: true, toolNames: [], description });
  }

  for (const placeholder of extractPlaceholdersFromText(fs.readFileSync(getDefaultConfigPath(), 'utf8'))) {
    if (suggestions.has(placeholder)) continue;
    suggestions.set(placeholder, {
      key: placeholder,
      source: 'application',
      profileRequired: false,
      toolNames: [],
      description: `Available to the bundled Spring configuration as environment variable ${placeholder}.`,
    });
  }

  const toolSpecsPath = getDefaultToolSpecsPath();
  if (fs.existsSync(toolSpecsPath)) {
    try {
      const toolSpecs = JSON.parse(fs.readFileSync(toolSpecsPath, 'utf8'));
      for (const tool of toolSpecs) {
        for (const variable of tool.staticVariables || []) {
          for (const value of Object.values(variable)) {
            if (typeof value !== 'string') continue;
            for (const placeholder of extractPlaceholdersFromText(value)) {
              const current = suggestions.get(placeholder) || { key: placeholder, source: 'tool', profileRequired: false, toolNames: [], description: '' };
              current.source = current.profileRequired ? 'profile+tool' : 'tool';
              current.toolNames = [...new Set([...(current.toolNames || []), tool.name])].sort();
              suggestions.set(placeholder, current);
            }
          }
        }
      }
    } catch (error) {
    }
  }

  return [...suggestions.values()]
    .map(item => {
      const toolText = item.toolNames?.length
        ? item.toolNames.length === 1
          ? `Used by tool "${item.toolNames[0]}".`
          : `Used by tools: ${item.toolNames.join(', ')}.`
        : '';
      const whenText = item.profileRequired
        ? 'Used by the currently selected setting when that backend is enabled.'
        : toolText
          ? 'Optional unless you use the related built-in tool.'
          : 'Optional unless you enable the related feature.';
      return { ...item, whenText, toolText };
    })
    .filter(item => item.profileRequired || item.toolNames?.length)
    .sort((a, b) => {
      if (a.profileRequired !== b.profileRequired) return a.profileRequired ? -1 : 1;
      return a.key.localeCompare(b.key);
    });
}

function parseJavaPropertiesToMap(lines = []) {
  const map = {};
  for (const line of lines) {
    const trimmed = String(line).trim().replace(/^-D/, '');
    const separatorIndex = trimmed.indexOf('=');
    if (separatorIndex <= 0) continue;
    map[trimmed.slice(0, separatorIndex)] = trimmed.slice(separatorIndex + 1);
  }
  return map;
}

function getWindowIconPath() {
  return path.join(__dirname, 'static', 'icons', 'icon.png');
}

function getDefaultConfigPath() {
  const packagedPath = path.join(process.resourcesPath, 'default-application.yaml');
  if (!isDev && fs.existsSync(packagedPath)) return packagedPath;
  const preparedResourcePath = path.join(__dirname, 'resources', 'default-application.yaml');
  if (fs.existsSync(preparedResourcePath)) return preparedResourcePath;
  return path.join(__dirname, '..', 'src', 'main', 'resources', 'application.yaml');
}

function getDefaultToolSpecsPath() {
  const packagedPath = path.join(process.resourcesPath, 'default-tool-specs.json');
  if (!isDev && fs.existsSync(packagedPath)) return packagedPath;
  const preparedResourcePath = path.join(__dirname, 'resources', 'default-tool-specs.json');
  if (fs.existsSync(preparedResourcePath)) return preparedResourcePath;
  return path.join(__dirname, '..', 'src', 'main', 'resources', 'default-tool-specs.json');
}

function getUserConfigPath() {
  return path.join(app.getPath('userData'), 'application.yaml');
}

function getConfigDirectory() {
  return path.join(app.getPath('userData'), 'configs');
}

function getConfigIndexPath() {
  return path.join(getConfigDirectory(), 'index.json');
}

function getSecretsStorePath() {
  return path.join(getConfigDirectory(), 'secrets.store');
}

function cloneSecretsStore(store) {
  return JSON.parse(JSON.stringify(store || {}));
}

function clearSecretsStoreCache() {
  secretsStoreCache = null;
}

function isSecretsEncryptionAvailable() {
  if (secretsEncryptionAvailable == null) {
    secretsEncryptionAvailable = safeStorage.isEncryptionAvailable();
  }
  return secretsEncryptionAvailable;
}

function readSecretsStore() {
  if (secretsStoreCache) {
    return cloneSecretsStore(secretsStoreCache);
  }
  const legacyPath = path.join(getConfigDirectory(), 'secrets.json.enc');
  const storePath = getSecretsStorePath();
  if (fs.existsSync(legacyPath) && !fs.existsSync(storePath)) {
    try { fs.renameSync(legacyPath, storePath); } catch { }
  }
  if (!fs.existsSync(storePath)) {
    secretsStoreCache = {};
    return {};
  }
  try {
    const raw = fs.readFileSync(storePath);
    let parsed;
    if (isSecretsEncryptionAvailable()) {
      parsed = JSON.parse(safeStorage.decryptString(raw));
    } else {
      parsed = JSON.parse(raw.toString('utf8'));
    }
    secretsStoreCache = parsed && typeof parsed === 'object' ? parsed : {};
    return cloneSecretsStore(secretsStoreCache);
  } catch (error) {
    secretsStoreCache = {};
    return {};
  }
}

function writeSecretsStore(store) {
  const storePath = getSecretsStorePath();
  const payload = JSON.stringify(store, null, 2);
  if (isSecretsEncryptionAvailable()) {
    fs.writeFileSync(storePath, safeStorage.encryptString(payload));
  } else {
    fs.writeFileSync(storePath, payload, 'utf8');
  }
  secretsStoreCache = cloneSecretsStore(store);
}

function getSecretsStorageStatus() {
  const encrypted = isSecretsEncryptionAvailable();
  return {
    encrypted,
    label: encrypted
      ? 'Encrypted by your OS secure storage'
      : 'OS-backed encryption unavailable — stored as plain text in this session',
  };
}

function getSecretsForConfig(configId) {
  const store = readSecretsStore();
  return store?.[configId] && typeof store[configId] === 'object' ? store[configId] : {};
}

function saveSecretsForConfig(configId, secretValues = {}) {
  const store = readSecretsStore();
  const nextSecrets = {};
  for (const [key, value] of Object.entries(secretValues || {})) {
    if (value != null && String(value).trim() !== '') {
      nextSecrets[key] = String(value).trim();
    }
  }
  if (JSON.stringify(store[configId] || {}) === JSON.stringify(nextSecrets)) {
    return;
  }
  store[configId] = nextSecrets;
  writeSecretsStore(store);
}

function deleteSecretsForConfig(configId) {
  const store = readSecretsStore();
  delete store[configId];
  writeSecretsStore(store);
}

function buildConfigBundle(configId, payload = null) {
  const index = readConfigIndex();
  const selectedConfig = getConfigRecord(configId, index);
  if (!selectedConfig) throw new Error('Selected config was not found.');
  const savedRuntime = index.runtime || getDefaultRuntimeSettings();
  const savedPreferences = index.preferences || getDefaultPreferences();
  const effectivePayload = payload || {};
  return {
    format: 'spring-ai-playground-config',
    version: 1,
    exportedAt: new Date().toISOString(),
    config: {
      id: selectedConfig.id,
      name: selectedConfig.name,
      yamlText: effectivePayload?.yamlText ?? readConfigYaml(selectedConfig.id),
      runtime: {
        jvmOptionsText: effectivePayload?.jvmOptionsText ?? (Array.isArray(savedRuntime.jvmOptions) ? savedRuntime.jvmOptions.join('\n') : ''),
        appArgsText: effectivePayload?.appArgsText ?? (Array.isArray(savedRuntime.appArgs) ? savedRuntime.appArgs.join('\n') : ''),
      },
      // Export config shape without serializing local secrets to disk.
      environmentVariables: {},
      preferences: {
        skipOllamaCheck: effectivePayload?.preferences?.skipOllamaCheck ?? savedPreferences.skipOllamaCheck,
      },
    },
  };
}

function applyImportedConfigBundle(bundle) {
  if (!bundle || typeof bundle !== 'object' || bundle.format !== 'spring-ai-playground-config') {
    throw new Error('This file is not a Spring AI Playground config export.');
  }
  const imported = bundle.config || {};
  const importedName = getFriendlyConfigName(imported.name || 'Imported Config', imported.yamlText || '');
  const yamlText = String(imported.yamlText || '').trim();
  if (!yamlText) throw new Error('The imported config file does not contain YAML overrides.');
  const index = readConfigIndex();
  let target = index.configs.find(config => config.name.toLowerCase() === importedName.toLowerCase()) || null;
  if (!target) {
    const existingIds = new Set(index.configs.map(config => config.id));
    const configId = createUniqueConfigId(importedName, existingIds);
    target = { id: configId, name: importedName };
    index.configs.push(target);
  } else {
    target.name = importedName;
  }
  saveYamlToConfig(target.id, yamlText);
  saveSecretsForConfig(target.id, imported.environmentVariables || {});
  ensureRuntimeSettings(index);
  index.runtime.jvmOptions = parseArgsText(imported.runtime?.jvmOptionsText);
  index.runtime.appArgs = parseArgsText(imported.runtime?.appArgsText);
  ensurePreferences(index);
  index.preferences.skipOllamaCheck = imported.preferences?.skipOllamaCheck ?? index.preferences.skipOllamaCheck;
  index.activeConfigId = target.id;
  saveConfigIndex(index);
  return buildConfigLoadPayload(target.id);
}

function getDefaultRuntimeSettings() {
  return { jvmOptions: ['-Xmx2g'], appArgs: ['--logging.level.root=INFO'] };
}

function getDefaultPreferences() {
  return { autoCopyLogs: true, skipOllamaCheck: false };
}

function isFirstLaunch(index) {
  const currentVersion = app.getVersion();
  return !index?.meta?.hasCompletedInitialSetup || index?.meta?.initialSetupCompletedVersion !== currentVersion;
}

function getTemplateList() {
  return Object.values(CONFIG_TEMPLATES).map(template => ({
    id: template.id,
    providerType: template.providerType,
    name: template.name,
    description: template.description,
  }));
}


function getYamlObj(yamlText) {
  try { return yaml.load(yamlText) || {}; } catch (e) { return {}; }
}

function isOfficialOpenAiBaseUrl(baseUrl) {
  if (!baseUrl) return false;
  try {
    return new URL(String(baseUrl)).hostname === 'api.openai.com';
  } catch (error) {
    return false;
  }
}

function detectProviderType(yamlText = '') {
  const doc = getYamlObj(yamlText);
  const chatModel = doc?.spring?.ai?.model?.chat;
  const openaiBaseUrl = doc?.spring?.ai?.['openai-sdk']?.['base-url'];
  const embeddingModel = doc?.spring?.ai?.model?.embedding;
  const hasOllamaEmbedding = embeddingModel === 'ollama' || !!doc?.spring?.ai?.ollama?.embedding;
  const usesCompatibleBaseUrl = Boolean(openaiBaseUrl) && !isOfficialOpenAiBaseUrl(openaiBaseUrl);
  if (chatModel === 'openai-sdk' && (usesCompatibleBaseUrl || hasOllamaEmbedding)) return 'openai-compatible';
  if (chatModel === 'openai-sdk' || doc?.spring?.ai?.['openai-sdk']) return 'openai';
  if (chatModel === 'ollama' || doc?.spring?.ai?.ollama) return 'ollama';
  return 'custom';
}
function hasEmbeddingModelConfig(yamlText = '') {
  const doc = getYamlObj(yamlText);
  return !!doc?.spring?.ai?.model?.embedding || !!doc?.spring?.ai?.ollama?.embedding?.options?.model || !!doc?.spring?.ai?.['openai-sdk']?.embedding?.options?.model;
}
function isOllamaRequired(yamlText = '') {
  const providerType = detectProviderType(yamlText);
  if (providerType === 'ollama') return true;
  const doc = getYamlObj(yamlText);
  return doc?.spring?.ai?.model?.embedding === 'ollama';
}
function parseOllamaBaseUrl(yamlText = '') {
  const doc = getYamlObj(yamlText);
  const explicitOllamaBaseUrl = doc?.spring?.ai?.ollama?.['base-url'];
  if (explicitOllamaBaseUrl) return String(explicitOllamaBaseUrl);
  const compatibleUrl = doc?.spring?.ai?.['openai-sdk']?.['base-url'];
  if (compatibleUrl && String(compatibleUrl).includes('11434')) return String(compatibleUrl).replace(/\/v1\/?$/i, '');
  return 'http://127.0.0.1:11434';
}
function extractPrimaryModelName(yamlText = '') {
  const doc = getYamlObj(yamlText);
  const chatModel = doc?.spring?.ai?.model?.chat;
  if (chatModel === 'ollama') return doc?.spring?.ai?.ollama?.chat?.options?.model || null;
  if (chatModel === 'openai-sdk') return doc?.spring?.ai?.['openai-sdk']?.chat?.options?.model || null;
  return null;
}

function getCachedProviderType(configId) {
  if (!providerTypeCache.has(configId)) {
    try {
      providerTypeCache.set(configId, detectProviderType(readConfigYaml(configId)));
    } catch {
      providerTypeCache.set(configId, 'custom');
    }
  }
  return providerTypeCache.get(configId);
}



function checkOllamaInstalled() {
  const command = process.platform === 'win32' ? 'where' : 'which';
  return new Promise((resolve) => {
    const child = spawn(command, ['ollama'], { stdio: 'ignore' });
    child.on('error', () => resolve(false));
    child.on('close', (code) => resolve(code === 0));
  });
}


function getOllamaAppCandidates() {
  if (process.platform === 'darwin') {
    return ['/Applications/Ollama.app', path.join(app.getPath('home'), 'Applications', 'Ollama.app')];
  }
  if (process.platform === 'win32') {
    const localAppData = process.env.LOCALAPPDATA || '';
    return [
      path.join(localAppData, 'Programs', 'Ollama', 'ollama app.exe'),
      path.join(localAppData, 'Ollama', 'ollama app.exe'),
    ];
  }
  return [];
}

function getOllamaBinaryCandidates() {
  if (process.platform === 'win32') {
    const localAppData = process.env.LOCALAPPDATA || '';
    return [
      path.join(localAppData, 'Programs', 'Ollama', 'ollama.exe'),
      path.join(localAppData, 'Ollama', 'ollama.exe'),
    ];
  }
  return [];
}

async function findOllamaLaunchTarget() {
  if (await commandExists('ollama')) return { kind: 'command', command: 'ollama' };
  for (const appPath of getOllamaAppCandidates()) {
    if (appPath && fs.existsSync(appPath)) return { kind: 'app', path: appPath };
  }
  for (const binaryPath of getOllamaBinaryCandidates()) {
    if (binaryPath && fs.existsSync(binaryPath)) return { kind: 'binary', path: binaryPath };
  }
  return null;
}

function parseArgsText(text = '') {
  return String(text).split(/\r?\n/).map(line => line.trim()).filter(Boolean);
}

function ensureRuntimeSettings(index) {
  const defaults = getDefaultRuntimeSettings();
  if (!index.runtime || typeof index.runtime !== 'object') index.runtime = defaults;
  index.runtime.jvmOptions = Array.isArray(index.runtime.jvmOptions) && index.runtime.jvmOptions.length
    ? index.runtime.jvmOptions
    : [...defaults.jvmOptions];
  index.runtime.appArgs = Array.isArray(index.runtime.appArgs) && index.runtime.appArgs.length
    ? index.runtime.appArgs
    : [...defaults.appArgs];
  if (index.runtime.appArgs.length === 1 && (
    index.runtime.appArgs[0] === '--logging.level.org.springframework.ai=INFO' ||
    index.runtime.appArgs[0] === '--logging.level.jm.kr.spring.ai.playground=INFO'
  )) {
    index.runtime.appArgs = [...defaults.appArgs];
  }
}

function ensurePreferences(index) {
  if (!index.preferences || typeof index.preferences !== 'object') {
    index.preferences = getDefaultPreferences();
  }
  if (typeof index.preferences.autoCopyLogs !== 'boolean') index.preferences.autoCopyLogs = true;
  if (typeof index.preferences.skipOllamaCheck !== 'boolean') index.preferences.skipOllamaCheck = false;
}

function getRuntimeSettingsPayload(index, secretSuggestions = collectSecretSuggestions()) {
  ensureRuntimeSettings(index);
  const secretKeys = new Set(secretSuggestions.map(item => item.key));
  const envVariablesMap = { ...getSecretsForConfig(index.activeConfigId) };
  for (const suggestion of secretSuggestions) {
    if ((envVariablesMap[suggestion.key] == null || envVariablesMap[suggestion.key] === '') && process.env[suggestion.key]) {
      envVariablesMap[suggestion.key] = process.env[suggestion.key];
    }
  }
  const visibleEnvVariables = Object.fromEntries(Object.entries(envVariablesMap).filter(([key]) => !secretKeys.has(key)));
  return {
    jvmOptionsText: index.runtime.jvmOptions.join('\n'),
    appArgsText: index.runtime.appArgs.join('\n'),
    envVariablesMap,
    customEnvVariables: visibleEnvVariables,
  };
}

function buildSpawnArguments(jrePath, jarPath, configPath, runtimeSettings, configId) {
  const jvmOptions = Array.isArray(runtimeSettings?.jvmOptions) ? runtimeSettings.jvmOptions : [];
  const appArgs = Array.isArray(runtimeSettings?.appArgs) ? runtimeSettings.appArgs : [];
  const userHome = app.getPath('userData');
  const configArg = `--spring.config.additional-location=${pathToFileURL(configPath).href}`;
  const envVariables = getSecretsForConfig(configId);

  return {
    args: [
      `-Dspring.ai.playground.user-home=${userHome}`,
      ...jvmOptions,
      '-jar',
      jarPath,
      configArg,
      ...appArgs,
    ],
    env: { ...process.env, ...envVariables },
  };
}

function httpGetJson(targetUrl) {
  return new Promise((resolve, reject) => {
    const request = http.get(targetUrl, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', chunk => { body += chunk; });
      res.on('end', () => {
        if (res.statusCode && res.statusCode >= 200 && res.statusCode < 300) {
          resolve({ statusCode: res.statusCode, body });
        } else {
          reject(new Error(`HTTP ${res.statusCode || 'error'}`));
        }
      });
    });
    request.on('error', reject);
    request.setTimeout(2000, () => request.destroy(new Error('Timeout')));
  });
}

function httpGetText(targetUrl) {
  return new Promise((resolve, reject) => {
    const request = http.get(targetUrl, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', chunk => { body += chunk; });
      res.on('end', () => resolve({ statusCode: res.statusCode, body }));
    });
    request.on('error', reject);
    request.setTimeout(2000, () => request.destroy(new Error('Timeout')));
  });
}

async function getOllamaEnvironmentInfo(yamlText = '') {
  const ollamaTarget = await findOllamaLaunchTarget();
  const ollamaInstalled = Boolean(ollamaTarget);
  const baseUrl = parseOllamaBaseUrl(yamlText);
  const providerType = detectProviderType(yamlText);
  const embeddingConfigured = hasEmbeddingModelConfig(yamlText);
  const ollamaRequired = isOllamaRequired(yamlText);
  let running = false, version = null, error = null;
  try {
    const tagsResponse = await httpGetJson(new URL('/api/tags', baseUrl));
    running = true;
    try { version = JSON.parse(tagsResponse.body || '{}')?.version || null; } catch { version = null; }
  } catch (requestError) {
    error = requestError.message || String(requestError);
  }
  return {
    ollamaInstalled, ollamaRequired, providerType, embeddingConfigured,
    baseUrl, running, version, error,
    installTarget: ollamaTarget?.kind || null,
    platform: process.platform,
    canAutoInstall: process.platform === 'darwin' || process.platform === 'linux',
    canAutoStart: Boolean(ollamaTarget),
  };
}

function runDetachedCommand(command, args = []) {
  const child = spawn(command, args, { detached: true, stdio: 'ignore' });
  child.unref();
}

function commandExists(command) {
  const lookup = process.platform === 'win32' ? 'where' : 'which';
  return new Promise((resolve) => {
    const child = spawn(lookup, [command], { stdio: 'ignore' });
    child.on('error', () => resolve(false));
    child.on('close', code => resolve(code === 0));
  });
}

async function installOllama() {
  if (process.platform === 'darwin') {
    if (await commandExists('brew')) {
      await new Promise((resolve, reject) => {
        const child = spawn('brew', ['install', '--cask', 'ollama'], { stdio: 'inherit' });
        child.on('error', reject);
        child.on('close', code => code === 0 ? resolve() : reject(new Error(`brew exited with code ${code}`)));
      });
      return { mode: 'installed' };
    }
    await shell.openExternal('https://ollama.com/download/mac');
    return { mode: 'external' };
  }
  if (process.platform === 'linux') {
    if (await commandExists('curl')) {
      await new Promise((resolve, reject) => {
        const child = spawn('/bin/sh', ['-c', 'curl -fsSL https://ollama.com/install.sh | sh'], { stdio: 'inherit' });
        child.on('error', reject);
        child.on('close', code => code === 0 ? resolve() : reject(new Error(`install script exited with code ${code}`)));
      });
      return { mode: 'installed' };
    }
    await shell.openExternal('https://ollama.com/download/linux');
    return { mode: 'external' };
  }
  await shell.openExternal('https://ollama.com/download');
  return { mode: 'external' };
}

async function startOllamaService() {
  const target = await findOllamaLaunchTarget();
  if (!target) { await shell.openExternal('https://ollama.com/download'); return { mode: 'external' }; }
  if (process.platform === 'darwin') {
    if (target.kind === 'app' && await commandExists('open')) { runDetachedCommand('open', ['-a', 'Ollama']); return { mode: 'started' }; }
  }
  if (target.kind === 'command') { runDetachedCommand('ollama', ['serve']); return { mode: 'started' }; }
  if (target.kind === 'binary') { runDetachedCommand(target.path, ['serve']); return { mode: 'started' }; }
  if (target.kind === 'app') { await shell.openPath(target.path); return { mode: 'started' }; }
  await shell.openExternal('https://ollama.com/download');
  return { mode: 'external' };
}

async function prepareOllamaForLaunch(configPath) {
  const yamlText = fs.readFileSync(configPath, 'utf8');
  const providerType = detectProviderType(yamlText);
  if (providerType === 'openai') {
    appendLog('Selected profile uses OpenAI only, so Ollama startup is skipped.');
    return true;
  }
  const ollamaInfo = await getOllamaEnvironmentInfo(yamlText);
  const index = readConfigIndex();
  const skipOllamaCheck = index.preferences?.skipOllamaCheck ?? false;
  if (skipOllamaCheck) {
    appendLog('Skipping Ollama startup check because it is disabled in launcher settings.');
    return true;
  }
  if (!ollamaInfo.ollamaRequired) {
    appendLog('Selected profile does not require Ollama for startup.');
    return true;
  }
  appendLog(`Checking Ollama endpoint: ${ollamaInfo.baseUrl}`);
  if (ollamaInfo.running) {
    appendLog(`Ollama is already running at ${ollamaInfo.baseUrl}.`);
    return true;
  }
  if (!ollamaInfo.ollamaInstalled) {
    appendLog('Ollama is not installed. Open https://ollama.com/download to install it if this profile needs local models.', true);
  } else {
    appendLog(`Ollama is not responding at ${ollamaInfo.baseUrl}. Check that Ollama is running before using this setting.`, true);
  }
  const choice = await dialog.showMessageBox({
    type: 'warning',
    title: 'Ollama Connection Check',
    buttons: ['Continue Launch', 'Back to Setup'],
    defaultId: 1,
    cancelId: 1,
    noLink: true,
    message: 'Ollama is not responding.',
    detail: [
      'This setting uses Ollama for chat or embedding.',
      `Configured Ollama endpoint: ${ollamaInfo.baseUrl}`,
      '',
      'Check that Ollama is installed and running, then verify the connection at that address.',
      'Choose Continue Launch to start the server anyway, or Back to Setup to review the config.',
    ].join('\n'),
  });
  if (choice.response === 0) {
    appendLog(`Continuing launch after Ollama warning for ${ollamaInfo.baseUrl}.`, true);
    return true;
  }
  appendLog('Launch canceled after Ollama warning. Returning to config setup.', true);
  return false;
}

function sanitizeConfigName(name) {
  return String(name || '').trim()
    .replace(/[<>:"/\\|?*\u0000-\u001F]/g, '-')
    .replace(/\s+/g, ' ');
}

function slugifyConfigName(name) {
  return sanitizeConfigName(name)
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '') || 'config';
}

function createUniqueConfigId(name, existingIds = new Set()) {
  const baseSlug = slugifyConfigName(name);
  const base = `cfg-${baseSlug || 'config'}`;
  let candidate = base;
  let counter = 2;
  while (existingIds.has(candidate)) { candidate = `${base}-${counter}`; counter += 1; }
  return candidate;
}

function createUniqueConfigName(name, existingNames = new Set()) {
  let candidate = name;
  let counter = 2;
  while (existingNames.has(candidate.toLowerCase())) { candidate = `${name} ${counter}`; counter += 1; }
  existingNames.add(candidate.toLowerCase());
  return candidate;
}

function getConfigFilePath(configId) {
  return path.join(getConfigDirectory(), `${configId}.yaml`);
}

function readJsonFile(filePath, fallbackValue) {
  if (!fs.existsSync(filePath)) return fallbackValue;
  try { return JSON.parse(fs.readFileSync(filePath, 'utf8')); } catch { return fallbackValue; }
}

function writeJsonFile(filePath, value) {
  fs.writeFileSync(filePath, JSON.stringify(value, null, 2), 'utf8');
}

function createDefaultConfigRecord(existingIds = new Set()) {
  const defaultYaml = CONFIG_TEMPLATES.ollama.yaml;
  const configId = createUniqueConfigId('Default', existingIds);
  const config = { id: configId, name: 'Ollama' };
  fs.writeFileSync(getConfigFilePath(configId), defaultYaml, 'utf8');
  return config;
}

function ensureStarterConfigs(index) {
  const existingNames = new Set(index.configs.map(config => config.name.toLowerCase()));
  const existingIds = new Set(index.configs.map(config => config.id));
  for (const template of Object.values(CONFIG_TEMPLATES)) {
    if (!DEFAULT_STARTER_TEMPLATE_IDS.has(template.id)) continue;
    if (existingNames.has(template.name.toLowerCase())) continue;
    const configId = createUniqueConfigId(template.name, existingIds);
    existingIds.add(configId);
    fs.writeFileSync(getConfigFilePath(configId), template.yaml, 'utf8');
    index.configs.push({ id: configId, name: template.name });
  }
}

function normalizeConfigYamlToTemplateIfNeeded(config) {
  const template = getTemplateByName(config.name);
  if (!template) return;
  const configPath = getConfigFilePath(config.id);
  if (!fs.existsSync(configPath)) { fs.writeFileSync(configPath, template.yaml, 'utf8'); return; }
  const existingYaml = fs.readFileSync(configPath, 'utf8');
  if (shouldCompactToTemplateYaml(existingYaml)) fs.writeFileSync(configPath, template.yaml, 'utf8');
}

function ensureConfigStore() {
  const configDir = getConfigDirectory();
  fs.mkdirSync(configDir, { recursive: true });
  const indexPath = getConfigIndexPath();
  const legacyConfigPath = getUserConfigPath();
  let index = readJsonFile(indexPath, null);

  if (!index || !Array.isArray(index.configs) || index.configs.length === 0) {
    let initialYaml = null;
    if (fs.existsSync(legacyConfigPath)) initialYaml = fs.readFileSync(legacyConfigPath, 'utf8');
    const config = { id: 'default', name: 'Ollama' };
    fs.writeFileSync(getConfigFilePath(config.id), initialYaml ?? CONFIG_TEMPLATES.ollama.yaml, 'utf8');
    index = { activeConfigId: config.id, configs: [config], meta: { hasCompletedInitialSetup: false } };
    writeJsonFile(indexPath, index);
  }

  if (!index.meta || typeof index.meta !== 'object') index.meta = { hasCompletedInitialSetup: false };
  ensureRuntimeSettings(index);
  ensurePreferences(index);

  const existingIds = new Set();
  index.configs = index.configs
    .filter(config => config && config.id && config.name)
    .map(config => {
      const originalConfigId = String(config.id);
      let configId = originalConfigId;
      if (!configId.startsWith('cfg-') || existingIds.has(configId)) {
        configId = createUniqueConfigId(config.name, existingIds);
      }
      if (configId !== originalConfigId) {
        const oldPath = getConfigFilePath(originalConfigId);
        const newPath = getConfigFilePath(configId);
        if (fs.existsSync(oldPath) && !fs.existsSync(newPath)) fs.renameSync(oldPath, newPath);
      }
      existingIds.add(configId);
      const configPath = fs.existsSync(getConfigFilePath(configId))
        ? getConfigFilePath(configId)
        : (fs.existsSync(getConfigFilePath(originalConfigId)) ? getConfigFilePath(originalConfigId) : null);
      const existingYaml = configPath && fs.existsSync(configPath) ? fs.readFileSync(configPath, 'utf8') : '';
      return { id: configId, name: getFriendlyConfigName(config.name, existingYaml) };
    });

  const existingNames = new Set();
  index.configs = index.configs.map(config => ({
    ...config,
    name: createUniqueConfigName(config.name, existingNames),
  }));

  if (index.configs.length === 0) index.configs.push(createDefaultConfigRecord(existingIds));
  ensureStarterConfigs(index);
  for (const config of index.configs) normalizeConfigYamlToTemplateIfNeeded(config);
  for (const config of index.configs) {
    const configPath = getConfigFilePath(config.id);
    if (!fs.existsSync(configPath)) {
      const template = getTemplateByName(config.name);
      fs.writeFileSync(configPath, template?.yaml ?? CONFIG_TEMPLATES.ollama.yaml, 'utf8');
    }
  }
  if (!index.configs.some(config => config.id === index.activeConfigId)) {
    index.activeConfigId = index.configs[0].id;
  }
  writeJsonFile(indexPath, index);
  currentConfigId = index.activeConfigId;
  return index;
}

function readConfigIndex() {
  return ensureConfigStore();
}

function saveConfigIndex(index) {
  writeJsonFile(getConfigIndexPath(), index);
  currentConfigId = index.activeConfigId;
}

function getConfigRecord(configId, index = readConfigIndex()) {
  return index.configs.find(config => config.id === configId) || null;
}

function readConfigYaml(configId) {
  return fs.readFileSync(getConfigFilePath(configId), 'utf8');
}

function saveYamlToConfig(configId, yamlText) {
  fs.writeFileSync(getConfigFilePath(configId), yamlText, 'utf8');
  providerTypeCache.delete(configId);
}

function buildConfigLoadPayload(selectedConfigId = null) {
  const index = readConfigIndex();
  const resolvedConfigId = selectedConfigId && getConfigRecord(selectedConfigId, index)
    ? selectedConfigId
    : index.activeConfigId;
  const selectedConfig = getConfigRecord(resolvedConfigId, index);
  index.activeConfigId = resolvedConfigId;
  saveConfigIndex(index);
  const selectedConfigYaml = readConfigYaml(selectedConfig.id);
  const secretSuggestions = collectSecretSuggestions(selectedConfigYaml);
  return {
    activeConfigId: resolvedConfigId,
    selectedConfig: {
      id: selectedConfig.id,
      name: selectedConfig.name,
      path: getConfigFilePath(selectedConfig.id),
      yaml: selectedConfigYaml,
      providerType: detectProviderType(selectedConfigYaml),
      embeddingConfigured: hasEmbeddingModelConfig(selectedConfigYaml),
    },
    configs: index.configs.map(config => ({
      id: config.id,
      name: config.name,
      path: getConfigFilePath(config.id),
      providerType: getCachedProviderType(config.id),
    })),
    configDirectory: getConfigDirectory(),
    defaultConfigPath: getDefaultConfigPath(),
    templates: getTemplateList(),
    isFirstLaunch: isFirstLaunch(index),
    runtimeSettings: getRuntimeSettingsPayload(index, secretSuggestions),
    secretSuggestions,
    secretsStorage: getSecretsStorageStatus(),
    preferences: {
      autoCopyLogs: index.preferences.autoCopyLogs,
      skipOllamaCheck: index.preferences.skipOllamaCheck,
    },
    launchCommand: lastLaunchCommand,
  };
}

function getJarPath() {
  if (isDev) {
    const targetDir = path.resolve(__dirname, '..', 'target');
    if (!fs.existsSync(targetDir)) return null;
    const files = fs.readdirSync(targetDir);
    const jarName = files.find(f => f.endsWith('.jar') && !f.startsWith('original-') && !f.endsWith('-sources.jar'));
    return jarName ? path.join(targetDir, jarName) : null;
  }
  return path.join(process.resourcesPath, 'app.jar');
}

function createConfigWindow() {
  configWindow = new BrowserWindow({
    width: 1080,
    height: 760,
    show: false,
    autoHideMenuBar: true,
    icon: getWindowIconPath(),
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: PRELOAD_PATH,
    },
  });
  configWindow.on('closed', () => {
    configWindow = null;
    if (!mainWindow && !serverSplashWindow && !isQuitting) app.quit();
  });
  configWindow.webContents.on('did-fail-load', (event, errorCode, errorDescription, validatedURL) => {
    appendLog(`Config window failed to load (${errorCode}): ${errorDescription} - ${validatedURL || CONFIG_EDITOR_PATH}`, true);
  });

  configWindow.loadURL(tempServer.getUrl(CONFIG_EDITOR_PATH));
  configWindow.once('ready-to-show', () => configWindow.show());
}

function createServerSplashWindow() {
  serverSplashWindow = new BrowserWindow({
    width: 1040,
    height: 820,
    frame: false,
    alwaysOnTop: true,
    transparent: false,
    icon: getWindowIconPath(),
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: PRELOAD_PATH,
    },
  });
  serverSplashWindow.webContents.on('did-fail-load', (event, errorCode, errorDescription, validatedURL) => {
    appendLog(`Splash window failed to load (${errorCode}): ${errorDescription} - ${validatedURL || SERVER_SPLASH_PATH}`, true);
  });

  serverSplashWindow.loadURL(tempServer.getUrl(SERVER_SPLASH_PATH));
}

function createMainWindow() {

  if (tempServer) {
    tempServer.stop();
    tempServer = null;
  }

  const { workAreaSize } = screen.getPrimaryDisplay();
  const width = Math.max(1280, Math.floor(workAreaSize.width * 0.67));
  const height = Math.max(820, Math.floor(workAreaSize.height * 0.82));
  mainWindow = new BrowserWindow({
    width, height,
    minWidth: 1280, minHeight: 820,
    show: false,
    fullscreen: false,
    autoHideMenuBar: true,
    backgroundColor: '#141920',
    icon: getWindowIconPath(),
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: false,
      allowRunningInsecureContent: true,
      webSecurity: false,
    },
  });
  mainWindow.webContents.on('console-message', (event, level, message, line, sourceId) => {
    appendLog(`UI console [${level}] ${message} (${sourceId}:${line})`, level >= 2);
  });
  mainWindow.webContents.on('render-process-gone', (event, details) => {
    appendLog(`UI render process gone: ${details.reason}`, true);
  });
  mainWindow.webContents.on('did-finish-load', async () => {
    appendLog('UI finished loading.');
    try {
      const pageState = await mainWindow.webContents.executeJavaScript(`
        (() => {
          const html = document.documentElement?.outerHTML || '';
          const bodyText = document.body?.innerText?.trim() || '';
          return {
            title: document.title || '',
            bodyTextLength: bodyText.length,
            hasAppName: html.includes('Spring AI Playground'),
            hasVaadin: /vaadin|hilla/i.test(html),
            url: location.href
          };
        })()
      `);
      appendLog(`UI state: ${JSON.stringify(pageState)}`);
      const looksReady = pageState.hasAppName || pageState.hasVaadin || pageState.bodyTextLength > 0;
      if (!looksReady) {
        appendLog('UI loaded an unexpected blank page. Keeping splash visible.', true);
        return;
      }
    } catch (error) {
      appendLog(`Failed to inspect UI state: ${error.message || error}`, true);
      return;
    }
    if (serverSplashWindow && !serverSplashWindow.isDestroyed()) serverSplashWindow.close();
    if (!mainWindow.isDestroyed()) { mainWindow.show(); mainWindow.focus(); }
  });
  mainWindow.webContents.on('did-fail-load', (event, errorCode, errorDescription, validatedURL) => {
    appendLog(`UI failed to load (${errorCode}): ${errorDescription} - ${validatedURL}`, true);
  });
  mainWindow.on('closed', () => { mainWindow = null; });
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
      shell.openExternal(url);
      return { action: 'deny' };
    });
}

function createNamedConfig(name, yamlText) {
  const index = readConfigIndex();
  const normalizedName = sanitizeConfigName(name);
  if (!normalizedName) throw new Error('A config name is required.');
  if (index.configs.some(config => config.name.toLowerCase() === normalizedName.toLowerCase())) {
    throw new Error(`A config named "${normalizedName}" already exists.`);
  }
  const existingIds = new Set(index.configs.map(config => config.id));
  const configId = createUniqueConfigId(normalizedName, existingIds);
  const config = { id: configId, name: normalizedName };
  saveYamlToConfig(configId, yamlText);
  index.configs.push(config);
  index.activeConfigId = configId;
  saveConfigIndex(index);
  return buildConfigLoadPayload(configId);
}

function appendLog(message, isError = false) {
  fullLogBuffer += message + '\n';
  try {
    const index = readConfigIndex();
    if (index.preferences?.autoCopyLogs) clipboard.writeText(fullLogBuffer);
  } catch {
  }
  if (serverSplashWindow && !serverSplashWindow.isDestroyed()) {
    serverSplashWindow.webContents.send(isError ? 'server-error' : 'server-log', message);
  }
  if (isError) console.error(message);
  else console.log(message);
}

function detectDynamicServerUrl(output) {
  if (dynamicServerPort) return;
  const text = String(output || '');
  const portMatch = text.match(/(?:Tomcat|Netty) started on port(?:\(s\))?:?\s+(\d+)/i);
  if (!portMatch) return;
  dynamicServerPort = parseInt(portMatch[1], 10);
  dynamicServerUrl = `http://localhost:${dynamicServerPort}`;
  appendLog(`Detected Spring Boot running on dynamic port: ${dynamicServerPort}`);
  sendServerSplashState();
}

function sendServerSplashState() {
  if (!serverSplashWindow || serverSplashWindow.isDestroyed()) return;
  const index = readConfigIndex();
  const selectedConfigId = currentConfigId || index.activeConfigId;
  const selectedConfig = getConfigRecord(selectedConfigId, index);
  const configPath = selectedConfig ? getConfigFilePath(selectedConfig.id) : null;
  serverSplashWindow.webContents.send('launch-state', {
    selectedConfig: selectedConfig ? { id: selectedConfig.id, name: selectedConfig.name, path: configPath } : null,
    serverRunning: Boolean(serverProcess),
    serverUrl: dynamicServerUrl,
    preflight: null,
    preferences: {
      autoCopyLogs: index.preferences?.autoCopyLogs ?? true,
      skipOllamaCheck: index.preferences?.skipOllamaCheck ?? false,
    },
    launchCommand: lastLaunchCommand,
  });
}

async function stopSpringServer() {
  if (!serverProcess) return;

  appendLog('Stopping Spring server gracefully... (waiting up to 90 seconds)');

  if (dynamicServerUrl) {
    try {
      appendLog('Sending shutdown request to /actuator/shutdown...');
      await new Promise((resolve, reject) => {
        const req = http.request(`${dynamicServerUrl}/actuator/shutdown`, { method: 'POST' }, (res) => {
          res.on('data', () => {});
          resolve();
        });
        req.on('error', reject);
        req.setTimeout(4000, () => reject(new Error('Actuator timeout')));
        req.end();
      });
      await new Promise(r => setTimeout(r, 1200));
    } catch (err) {
      appendLog(`Actuator shutdown failed: ${err.message}. Proceeding with SIGTERM.`, true);
    }
  }

  try {
    treeKill(serverProcess.pid, 'SIGTERM');
    appendLog('SIGTERM sent to Spring process.');
  } catch (e) {}

  const MAX_WAIT_MS = 90000;
  const start = Date.now();

  return new Promise((resolve) => {
    const checkClosed = () => {
      if (!serverProcess) {
        appendLog('Spring server stopped completely (graceful).');
        resolve();
        return;
      }

      const elapsed = Date.now() - start;
      if (elapsed > MAX_WAIT_MS) {
        appendLog(`Graceful shutdown timeout after ${Math.round(elapsed/1000)}s → forcing SIGKILL`, true);
        treeKill(serverProcess.pid, 'SIGKILL');
        serverProcess = null;
        dynamicServerPort = null;
        dynamicServerUrl = null;
        resolve();
        return;
      }

      setTimeout(checkClosed, 300);
    };

    serverProcess.once('close', () => {
      serverProcess = null;
      dynamicServerPort = null;
      dynamicServerUrl = null;
      appendLog(`Spring server closed cleanly after ${Math.round((Date.now() - start)/1000)}s.`);
      resolve();
    });

    checkClosed();
  });
}

function handleFatalError(errorMessage) {
  try {
    clipboard.writeText(fullLogBuffer);
  } catch {
  }

  isQuitting = true;

  if (serverProcess) {
    stopSpringServer().finally(() => {
      dialog.showMessageBoxSync({
        type: 'error',
        title: 'Server Error',
        message: 'Failed to start Spring AI Server.',
        detail: errorMessage + '\n\nLogs have been copied to your clipboard.',
        buttons: ['OK']
      });
      app.exit(1);
    });
    return;
  }

  dialog.showMessageBoxSync({
    type: 'error',
    title: 'Server Error',
    message: 'Failed to start Spring AI Server.',
    detail: errorMessage + '\n\nLogs have been copied to your clipboard.',
    buttons: ['OK']
  });
  app.exit(1);
}

function startSpringServer() {
  serverReadyStartTime = Date.now();
  const launchToken = ++currentLaunchToken;

  const jarPath = getJarPath();
  if (!jarPath) {
    handleFatalError("Executable JAR not found. Run 'mvn package' first.");
    return;
  }

  const jrePath = isDev
    ? 'java'
    : path.join(process.resourcesPath, 'jre-bundle', 'bin', process.platform === 'win32' ? 'java.exe' : 'java');

  const index = readConfigIndex();
  const selectedConfigId = currentConfigId || index.activeConfigId;
  const configPath = activeConfigPath || getConfigFilePath(selectedConfigId);

  const spawnConfig = buildSpawnArguments(jrePath, jarPath, configPath, index.runtime, selectedConfigId);

  spawnConfig.args.push('--management.endpoint.shutdown.enabled=true');
  spawnConfig.args.push('--management.endpoints.web.exposure.include=health,shutdown');

  lastLaunchCommand = `${jrePath} ${spawnConfig.args.join(' ')}`;

  appendLog(`Starting JAR: ${jarPath}`);
  appendLog(`Using JRE: ${jrePath}`);
  appendLog(`Using config: ${configPath}`);
  appendLog(`Launch command: ${lastLaunchCommand}`);

  const envKeys = Object.keys(spawnConfig.env).filter(key => !(key in process.env) || process.env[key] !== spawnConfig.env[key]).sort();
  if (envKeys.length) {
    appendLog(`Launch environment keys: ${envKeys.join(', ')}`);
  }

  sendServerSplashState();

  dynamicServerPort = null;
  dynamicServerUrl = null;

  serverProcess = spawn(jrePath, spawnConfig.args, { detached: false, env: spawnConfig.env });

  serverProcess.on('error', (error) => {
    serverProcess = null;
    if (isQuitting || restartToConfigAfterStop) return;
    handleFatalError(`Failed to start server process:\n${error.message || String(error)}`);
  });

  serverProcess.stdout.on('data', (data) => {
    const output = data.toString().trim();
    appendLog(output);
    detectDynamicServerUrl(output);
  });

  serverProcess.stderr.on('data', (data) => {
    const output = data.toString().trim();
    appendLog(output, true);
    detectDynamicServerUrl(output);
  });

  serverProcess.on('close', (code) => {
    const shouldReturnToConfig = restartToConfigAfterStop;
    serverProcess = null;
    dynamicServerPort = null;
    dynamicServerUrl = null;
    sendServerSplashState();

    if (code !== 0 && code !== null && !isQuitting && !shouldReturnToConfig) {
      handleFatalError(`Server process exited unexpectedly with code ${code}`);
    }

    if (shouldReturnToConfig && !isQuitting) {
      restartToConfigAfterStop = false;
      if (mainWindow && !mainWindow.isDestroyed()) mainWindow.close();
      if (serverSplashWindow && !serverSplashWindow.isDestroyed()) serverSplashWindow.close();
      createConfigWindow();
    }
  });

  checkServerReady(launchToken);
}

function checkServerReady(launchToken) {
  if (launchToken !== currentLaunchToken || restartToConfigAfterStop) return;

  const elapsed = Date.now() - serverReadyStartTime;
  const timeoutMs = dynamicServerUrl ? UI_READY_GRACE_TIMEOUT_MS : SERVER_READY_TIMEOUT_MS;
  if (elapsed > timeoutMs) {
    appendLog(`Server startup timed out after ${timeoutMs / 1000}s.`, true);
    handleFatalError(`Server did not become ready within ${timeoutMs / 1000} seconds. Check logs for details.`);
    return;
  }

  if (!dynamicServerUrl) {
    setTimeout(() => checkServerReady(launchToken), 500);
    return;
  }

  httpGetJson(new URL('/actuator/health', dynamicServerUrl))
    .then(({ statusCode, body }) => {
      if (launchToken !== currentLaunchToken || restartToConfigAfterStop) return;
      let healthStatus = null;
      try {
        healthStatus = JSON.parse(body || '{}')?.status || null;
      } catch (error) {
        appendLog(`Actuator health response could not be parsed yet: ${error.message}`, true);
      }

      if (statusCode === 200 && healthStatus === 'UP') {
        appendLog('Actuator reports server is UP. Launching UI...');
        setTimeout(() => {
          if (launchToken !== currentLaunchToken || restartToConfigAfterStop) return;
          createMainWindow();
          mainWindow.loadURL(dynamicServerUrl);
        }, 500);
        return;
      }

      appendLog(`Waiting for actuator health... status=${statusCode}, health=${healthStatus || 'unknown'}`);
      setTimeout(() => checkServerReady(launchToken), 1000);
    })
    .catch((healthError) => {
      if (launchToken !== currentLaunchToken || restartToConfigAfterStop) return;
      appendLog(`Actuator health check not ready yet: ${healthError.message || healthError}`);
      httpGetText(dynamicServerUrl)
        .then(({ statusCode, body }) => {
          if (launchToken !== currentLaunchToken || restartToConfigAfterStop) return;
          const isHtmlResponse = /<!doctype html>/i.test(body);
          const looksLikeApp = /Spring AI Playground|vaadin|hilla/i.test(body);
          const hasUsefulBody = Boolean((body || '').trim());
          const canOpenUi = (statusCode >= 200 && statusCode < 400) && (isHtmlResponse || looksLikeApp || hasUsefulBody);
          if (canOpenUi) {
            appendLog(`Server responded with status=${statusCode}. Launching UI...`);
            setTimeout(() => {
              if (launchToken !== currentLaunchToken || restartToConfigAfterStop) return;
              createMainWindow();
              mainWindow.loadURL(dynamicServerUrl);
            }, 500);
            return;
          }
          appendLog(`Waiting for app UI... status=${statusCode}, html=${isHtmlResponse}, app=${looksLikeApp}`);
          setTimeout(() => checkServerReady(launchToken), 1000);
        })
        .catch((rootError) => {
          if (launchToken !== currentLaunchToken || restartToConfigAfterStop) return;
          appendLog(`Root URL not reachable yet: ${rootError.message || rootError}`);
          setTimeout(() => checkServerReady(launchToken), 1000);
        });
    });
}

function launchApplicationWithConfig(configPath) {
  activeConfigPath = configPath;
  restartToConfigAfterStop = false;
  createServerSplashWindow();
  sendServerSplashState();
  if (configWindow && !configWindow.isDestroyed()) configWindow.close();
  prepareOllamaForLaunch(configPath).then((shouldProceed) => {
    if (shouldProceed === false) {
      if (serverSplashWindow && !serverSplashWindow.isDestroyed()) serverSplashWindow.close();
      createConfigWindow();
      return;
    }
    startSpringServer();
  }).catch(error => {
    handleFatalError(error.message || String(error));
  });
}

ipcMain.handle('config:load', async () => buildConfigLoadPayload());

ipcMain.handle('config:save', async (event, payload) => {
  const index = readConfigIndex();
  const selectedConfigId = currentConfigId || index.activeConfigId;
  saveYamlToConfig(selectedConfigId, payload?.yamlText ?? '');
  ensureRuntimeSettings(index);
  index.runtime.jvmOptions = parseArgsText(payload?.jvmOptionsText);
  index.runtime.appArgs = parseArgsText(payload?.appArgsText);
  saveSecretsForConfig(selectedConfigId, payload?.secretValues || {});
  ensurePreferences(index);
  index.preferences.skipOllamaCheck = payload?.preferences?.skipOllamaCheck ?? index.preferences.skipOllamaCheck;
  saveConfigIndex(index);
  return buildConfigLoadPayload(selectedConfigId);
});

ipcMain.handle('config:select', async (event, configId) => buildConfigLoadPayload(configId));

ipcMain.handle('config:select-provider', async (event, providerType) => {
  const index = readConfigIndex();
  const config = ensureProviderConfig(index, providerType);
  index.activeConfigId = config.id;
  saveConfigIndex(index);
  return buildConfigLoadPayload(config.id);
});

ipcMain.handle('config:save-as', async (event, name, payload) => {
  const created = createNamedConfig(name, payload?.yamlText ?? '');
  const index = readConfigIndex();
  ensureRuntimeSettings(index);
  index.runtime.jvmOptions = parseArgsText(payload?.jvmOptionsText);
  index.runtime.appArgs = parseArgsText(payload?.appArgsText);
  saveSecretsForConfig(created.activeConfigId, payload?.secretValues || {});
  ensurePreferences(index);
  index.preferences.skipOllamaCheck = payload?.preferences?.skipOllamaCheck ?? index.preferences.skipOllamaCheck;
  saveConfigIndex(index);
  return buildConfigLoadPayload(created.activeConfigId);
});

ipcMain.handle('config:export', async (event, payload) => {
  const index = readConfigIndex();
  const selectedConfigId = currentConfigId || index.activeConfigId;
  const selectedConfig = getConfigRecord(selectedConfigId, index);
  const fileName = `${sanitizeConfigName(selectedConfig?.name || 'spring-ai-playground-config')}.json`;
  const result = await dialog.showSaveDialog({
    title: 'Export Spring AI Playground Config',
    defaultPath: path.join(app.getPath('documents'), fileName),
    filters: [{ name: 'JSON', extensions: ['json'] }],
  });
  if (result.canceled || !result.filePath) return { canceled: true };
  const bundle = buildConfigBundle(selectedConfigId, payload);
  fs.writeFileSync(result.filePath, `${JSON.stringify(bundle, null, 2)}\n`, 'utf8');
  return { canceled: false, path: result.filePath, name: bundle.config.name };
});

ipcMain.handle('config:import', async () => {
  const result = await dialog.showOpenDialog({
    title: 'Import Spring AI Playground Config',
    properties: ['openFile'],
    filters: [{ name: 'JSON', extensions: ['json'] }],
  });
  if (result.canceled || !result.filePaths?.length) return { canceled: true };
  const bundle = JSON.parse(fs.readFileSync(result.filePaths[0], 'utf8'));
  return applyImportedConfigBundle(bundle);
});

ipcMain.handle('config:reset', async () => {
  const configDir = getConfigDirectory();

  if (serverProcess) {
    try {
      treeKill(serverProcess.pid, 'SIGKILL');
      serverProcess = null;
    } catch (e) {}
  }

  try {
    if (fs.existsSync(configDir)) {
      fs.rmSync(configDir, { recursive: true, force: true });
    }
    clearSecretsStoreCache();
  } catch (error) {
    console.error("Failed to delete config directory:", error);
  }

  isQuitting = true;
  app.relaunch({ args: process.argv.slice(1).concat(['--relaunch']) });
  app.exit(0);
});

ipcMain.handle('config:delete', async () => {
  const index = readConfigIndex();
  if (index.configs.length <= 1) throw new Error('At least one setting must remain.');
  const selectedConfigId = currentConfigId || index.activeConfigId;
  const selectedConfig = getConfigRecord(selectedConfigId, index);
  if (!selectedConfig) throw new Error('Selected config was not found.');
  const selectedProvider = getCachedProviderType(selectedConfig.id);
  const configPath = getConfigFilePath(selectedConfig.id);
  index.configs = index.configs.filter(config => config.id !== selectedConfig.id);
  providerTypeCache.delete(selectedConfig.id);
  if (fs.existsSync(configPath)) fs.unlinkSync(configPath);
  deleteSecretsForConfig(selectedConfig.id);
  const remainingSameProvider = index.configs.find(config => getCachedProviderType(config.id) === selectedProvider);
  index.activeConfigId = remainingSameProvider?.id || index.configs[0]?.id;
  saveConfigIndex(index);
  return buildConfigLoadPayload(index.activeConfigId);
});

ipcMain.handle('config:apply-template', async (event, templateId) => {
  const template = getTemplateById(templateId);
  if (!template) throw new Error('Unknown starter template.');
  const index = readConfigIndex();
  const selectedConfigId = currentConfigId || index.activeConfigId;
  saveYamlToConfig(selectedConfigId, template.yaml);
  const config = getConfigRecord(selectedConfigId, index);
  if (config) { config.name = template.name; saveConfigIndex(index); }
  return buildConfigLoadPayload(selectedConfigId);
});

ipcMain.handle('config:launch', async (event, payload) => {
  const index = readConfigIndex();
  const selectedConfigId = currentConfigId || index.activeConfigId;
  const configPath = getConfigFilePath(selectedConfigId);
  saveYamlToConfig(selectedConfigId, payload?.yamlText ?? '');
  ensureRuntimeSettings(index);
  index.runtime.jvmOptions = parseArgsText(payload?.jvmOptionsText);
  index.runtime.appArgs = parseArgsText(payload?.appArgsText);
  saveSecretsForConfig(selectedConfigId, payload?.secretValues || {});
  ensurePreferences(index);
  index.preferences.skipOllamaCheck = payload?.preferences?.skipOllamaCheck ?? index.preferences.skipOllamaCheck;
  index.meta.hasCompletedInitialSetup = true;
  index.meta.initialSetupCompletedVersion = app.getVersion();
  saveConfigIndex(index);
  launchApplicationWithConfig(configPath);
  return buildConfigLoadPayload(selectedConfigId);
});

ipcMain.handle('config:environment-info', async (event, yamlText) => getOllamaEnvironmentInfo(yamlText));

ipcMain.handle('config:open-ollama-download', async () => {
  await shell.openExternal('https://ollama.com/download');
  return { ok: true };
});

ipcMain.handle('config:install-ollama', async () => installOllama());

ipcMain.handle('app:launch-state', async () => {
  const index = readConfigIndex();
  const selectedConfigId = currentConfigId || index.activeConfigId;
  const selectedConfig = getConfigRecord(selectedConfigId, index);
  return {
    selectedConfig: selectedConfig ? { id: selectedConfig.id, name: selectedConfig.name, path: getConfigFilePath(selectedConfig.id) } : null,
    serverRunning: Boolean(serverProcess),
    serverUrl: dynamicServerUrl,
    preferences: { autoCopyLogs: index.preferences?.autoCopyLogs ?? true, skipOllamaCheck: index.preferences?.skipOllamaCheck ?? false },
    launchCommand: lastLaunchCommand,
  };
});

ipcMain.handle('app:set-auto-copy-logs', async (event, enabled) => {
  const index = readConfigIndex();
  ensurePreferences(index);
  index.preferences.autoCopyLogs = Boolean(enabled);
  saveConfigIndex(index);
  return { autoCopyLogs: index.preferences.autoCopyLogs };
});

ipcMain.handle('app:mic-permission-status', async () => {
  if (process.platform === 'darwin') {
    return {
      platform: process.platform,
      status: systemPreferences.getMediaAccessStatus('microphone'),
    };
  }

  return {
    platform: process.platform,
    status: 'unknown',
  };
});

ipcMain.handle('app:request-mic-permission', async () => {
  if (process.platform === 'darwin') {
    return ensureMacMicrophoneAccess();
  }

  if (process.platform === 'win32') {
    openMicrophonePrivacySettings();
    return { status: 'open-settings', granted: false };
  }

  return { status: 'unsupported', granted: false };
});

ipcMain.handle('app:open-mic-settings', async () => {
  openMicrophonePrivacySettings();
  return { ok: true };
});

ipcMain.handle('app:quit-launcher', async () => {
  isQuitting = true;
  if (serverProcess) {
    await stopSpringServer();
  }
  app.quit();
  return { ok: true };
});

ipcMain.handle('app:restart-to-config', async () => {
  restartToConfigAfterStop = true;
  if (mainWindow && !mainWindow.isDestroyed()) { mainWindow.close(); mainWindow = null; }
  if (serverProcess) {
    appendLog('Stopping current server to return to config selection...');
    treeKill(serverProcess.pid, 'SIGTERM');
  } else {
    restartToConfigAfterStop = false;
    if (serverSplashWindow && !serverSplashWindow.isDestroyed()) serverSplashWindow.close();
    createConfigWindow();
  }
  return { ok: true };
});

app.whenReady().then(async () => {

  splashWindow = new BrowserWindow({
    width: 480,
    height: 280,
    frame: false,          // 타이틀바 없음
    transparent: true,     // 배경 투명
    alwaysOnTop: true,     // 항상 위
    center: true,          // 화면 중앙
    resizable: false,
    skipTaskbar: true,     // 작업표시줄 미표시
    webPreferences: {
      preload: PRELOAD_PATH,
      contextIsolation: true,
    },
  });
  splashWindow.loadFile(SPLASH_PATH);
  splashWindow.show();

  tempServer = await startTempServer();

  session.defaultSession.setPermissionCheckHandler((webContents, permission) => {
    if (permission === 'media' || permission === 'audioCapture' || permission === 'speechRecognition') return true;
    return false;
  });

  session.defaultSession.setPermissionRequestHandler((webContents, permission, callback) => {
    if (permission === 'media' || permission === 'audioCapture' || permission === 'speechRecognition') {
      if (process.platform === 'darwin') {
        ensureMacMicrophoneAccess()
          .then(({ granted }) => callback(granted))
          .catch(() => callback(false));
      } else {
        callback(true);
      }
      return;
    }
    callback(false);
  });

  ensureConfigStore();
  const index = readConfigIndex();

  splashWindow.close();

  if (isFirstLaunch(index)) {
    createConfigWindow();
  } else {
    const configPath = getConfigFilePath(index.activeConfigId);
    launchApplicationWithConfig(configPath);
  }
});

app.on('before-quit', async (event) => {
  if (tempServer) {
    tempServer.stop();
    tempServer = null;
  }

  if (isQuitting) return;
  event.preventDefault();
  isQuitting = true;

  if (serverProcess) {
    appendLog('App is quitting. Waiting up to 90 seconds for Spring server to shut down...');
    await stopSpringServer();
  }

  appendLog('All processes stopped. Exiting app now.');
  app.exit(0);
});

app.on('window-all-closed', () => app.quit());
