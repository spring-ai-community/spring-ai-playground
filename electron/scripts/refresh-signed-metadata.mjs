import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const yaml = require('js-yaml');
const { buildBlockMap } = require('app-builder-lib/out/targets/blockmap/blockmap.js');

const distDir = path.resolve(import.meta.dirname, '..', 'dist');
const exeNames = fs.readdirSync(distDir).filter((name) => name.endsWith('.exe'));
if (exeNames.length === 0) {
  throw new Error(`No installer executable found in ${distDir}`);
}

const refreshed = new Map();
for (const exeName of exeNames) {
  const exePath = path.join(distDir, exeName);
  refreshed.set(exeName, await buildBlockMap(exePath, 'gzip', `${exePath}.blockmap`));
}

const metadataNames = fs
  .readdirSync(distDir)
  .filter((name) => name.startsWith('latest') && name.endsWith('.yml'));
for (const metadataName of metadataNames) {
  const metadataPath = path.join(distDir, metadataName);
  const metadata = yaml.load(fs.readFileSync(metadataPath, 'utf8'));
  for (const file of metadata.files ?? []) {
    const info = refreshed.get(file.url);
    if (info) {
      file.sha512 = info.sha512;
      file.size = info.size;
    }
  }
  const pathInfo = refreshed.get(metadata.path);
  if (pathInfo) {
    metadata.sha512 = pathInfo.sha512;
  }
  fs.writeFileSync(metadataPath, yaml.dump(metadata, { lineWidth: -1 }));
  console.log(`Refreshed ${metadataName}`);
}

for (const [exeName, info] of refreshed) {
  console.log(`${exeName}: size=${info.size} sha512=${info.sha512}`);
}
