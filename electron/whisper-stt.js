'use strict';

const fs = require('fs');
const path = require('path');
const os = require('os');
const { execFileSync } = require('child_process');

const ADDON_PACKAGE = '@kutalia/whisper-node-addon';

let addonModule = null;
let preloadPromise = null;
let activeModelPath = null;

function patchMacOsAddonOnce() {
    if (process.platform !== 'darwin') return;
    let distDir;
    try {
        distDir = path.dirname(require.resolve(`${ADDON_PACKAGE}/package.json`)) + '/dist';
    } catch (e) { return; }

    const arch = os.arch();
    const expected = path.join(distDir, `darwin-${arch}`);
    const actual = path.join(distDir, `mac-${arch}`);
    if (!fs.existsSync(expected) && fs.existsSync(actual)) {
        try { fs.symlinkSync(`mac-${arch}`, expected, 'dir'); } catch (_) { }
    }

    const nodeFile = path.join(actual, 'whisper.node');
    if (!fs.existsSync(nodeFile)) return;
    try {
        const out = execFileSync('otool', ['-l', nodeFile], { encoding: 'utf8' });
        if (out.includes('@loader_path')) return;
        execFileSync('install_name_tool', ['-add_rpath', '@loader_path', nodeFile]);
        execFileSync('codesign', ['--force', '--sign', '-', nodeFile]);
        console.log('[whisper-stt] patched whisper.node rpath (@loader_path) + re-signed');
    } catch (e) {
        console.warn('[whisper-stt] rpath patch failed (transcription will fail):', e.message);
    }
}

function loadAddon() {
    if (addonModule) return addonModule;
    patchMacOsAddonOnce();
    addonModule = require(ADDON_PACKAGE);
    return addonModule;
}

function isReady(modelPath) {
    if (!modelPath || !fs.existsSync(modelPath)) return false;
    try { require.resolve(ADDON_PACKAGE); return true; } catch { return false; }
}

function preload(modelPath) {
    if (!modelPath || !fs.existsSync(modelPath)) return Promise.resolve();
    if (activeModelPath === modelPath && preloadPromise) return preloadPromise;
    activeModelPath = modelPath;
    const addon = loadAddon();
    const silentSamples = new Float32Array(16000 * 0.2);
    const t0 = Date.now();
    preloadPromise = addon.transcribe({
        model: modelPath,
        pcmf32: silentSamples,
        language: 'auto',
        use_gpu: true,
        no_prints: true,
        translate: false,
        no_timestamps: true,
    }).then(() => {
        console.log(`[whisper-stt] preload done in ${Date.now() - t0} ms (${path.basename(modelPath)})`);
    }).catch((err) => {
        console.warn('[whisper-stt] preload failed (will retry on first use):', err && err.message);
        preloadPromise = null;
        activeModelPath = null;
    });
    return preloadPromise;
}

async function transcribe({ samples, modelPath, language }) {
    if (!modelPath) throw new Error('Model path is required');
    if (!fs.existsSync(modelPath)) throw new Error(`Model file not found: ${modelPath}`);
    if (!samples || samples.length === 0) throw new Error('Empty audio payload');
    const addon = loadAddon();
    if (activeModelPath !== modelPath) {
        activeModelPath = modelPath;
        preloadPromise = null;
    }
    const result = await addon.transcribe({
        model: modelPath,
        pcmf32: samples instanceof Float32Array ? samples : new Float32Array(samples),
        language: language || 'auto',
        use_gpu: true,
        no_prints: true,
        translate: false,
        no_timestamps: true,
    });
    const segments = result && result.transcription ? result.transcription : [];
    return segments
        .map((seg) => Array.isArray(seg) ? seg[seg.length - 1] : seg)
        .join('')
        .trim();
}

function shutdown() {
    preloadPromise = null;
    activeModelPath = null;
}

module.exports = { isReady, preload, transcribe, shutdown };
