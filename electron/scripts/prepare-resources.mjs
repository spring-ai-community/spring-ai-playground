import fs from 'node:fs';
import path from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';

const repoRoot = path.resolve(import.meta.dirname, '..', '..');
const targetDir = path.join(repoRoot, 'target');
const electronResourcesDir = path.join(repoRoot, 'electron', 'resources');
const defaultApplicationYaml = path.join(repoRoot, 'src', 'main', 'resources', 'application.yaml');
const defaultToolSpecsJson = path.join(repoRoot, 'src', 'main', 'resources', 'default-tool-specs.json');
const outputDefaultYaml = path.join(electronResourcesDir, 'default-application.yaml');
const outputDefaultToolSpecs = path.join(electronResourcesDir, 'default-tool-specs.json');
const outputJar = path.join(electronResourcesDir, 'app.jar');
const jreOutputDir = path.join(electronResourcesDir, 'jre-bundle');
const editorOutputDir = path.join(electronResourcesDir, 'editor');
const javaHome = process.env.JAVA_HOME;
const baselineModules = [
  'java.base',
  'java.compiler',
  'java.desktop',
  'java.instrument',
  'java.management',
  'java.naming',
  'java.net.http',
  'java.prefs',
  'java.rmi',
  'java.scripting',
  'java.security.jgss',
  'java.sql',
  'java.transaction.xa',
  'java.xml',
  'jdk.crypto.ec',
  'jdk.jfr',
  'jdk.management',
  'jdk.zipfs',
  'jdk.unsupported',
];

if (!fs.existsSync(targetDir)) {
  throw new Error(`Maven target directory not found: ${targetDir}`);
}

if (!javaHome) {
  throw new Error(
    'JAVA_HOME must point to a GraalVM JDK. This desktop build always bundles a GraalVM runtime.',
  );
}

const javaHomeDir = path.resolve(javaHome);
const javaReleaseFile = path.join(javaHomeDir, 'release');
const javaBinPath = path.join(
  javaHomeDir,
  'bin',
  process.platform === 'win32' ? 'java.exe' : 'java',
);
const jlinkPath = path.join(
  javaHomeDir,
  'bin',
  process.platform === 'win32' ? 'jlink.exe' : 'jlink',
);
const jdepsPath = path.join(
  javaHomeDir,
  'bin',
  process.platform === 'win32' ? 'jdeps.exe' : 'jdeps',
);

if (!fs.existsSync(javaReleaseFile)) {
  throw new Error(`JAVA_HOME does not look like a JDK home: ${javaReleaseFile} not found`);
}

for (const [label, binPath] of [['jlink', jlinkPath], ['jdeps', jdepsPath]]) {
  if (!fs.existsSync(binPath)) {
    throw new Error(`Required JDK tool not found: ${label} at ${binPath}. Make sure JAVA_HOME points to a full JDK, not a JRE.`);
  }
}

const releaseInfo = fs.readFileSync(javaReleaseFile, 'utf8');
const javaVersion = spawnSync(javaBinPath, ['-version'], { encoding: 'utf8' });
const javaVersionOutput = `${javaVersion.stdout ?? ''}${javaVersion.stderr ?? ''}`;

if (javaVersion.status !== 0) {
  throw new Error(`Failed to inspect JAVA_HOME runtime: ${javaVersionOutput.trim()}`);
}

if (!isGraalVmRuntime(releaseInfo, javaVersionOutput)) {
  throw new Error(
    [
      `JAVA_HOME must reference a GraalVM JDK, but got: ${javaHomeDir}`,
      "Set JAVA_HOME to your GraalVM JDK and rerun `npm run prepare:resources`.",
      '',
      'Detected runtime:',
      javaVersionOutput.trim(),
    ].join('\n'),
  );
}

const majorJavaVersion = parseMajorJavaVersion(releaseInfo);
console.log(`Detected Java major version: ${majorJavaVersion}`);

const jarFile = fs
  .readdirSync(targetDir)
  .find(
    (file) =>
      file.endsWith('.jar') &&
      !file.startsWith('original-') &&
      !file.endsWith('-sources.jar'),
  );

if (!jarFile) {
  throw new Error('Executable JAR not found in target. Run Maven package first.');
}

fs.mkdirSync(electronResourcesDir, { recursive: true });
fs.mkdirSync(editorOutputDir, { recursive: true });
fs.copyFileSync(defaultApplicationYaml, outputDefaultYaml);
console.log(`Copied default config: ${defaultApplicationYaml} -> ${outputDefaultYaml}`);
fs.copyFileSync(defaultToolSpecsJson, outputDefaultToolSpecs);
console.log(`Copied default tool specs: ${defaultToolSpecsJson} -> ${outputDefaultToolSpecs}`);
fs.copyFileSync(path.join(targetDir, jarFile), outputJar);
console.log(`Copied executable JAR: ${jarFile} -> ${outputJar}`);

for (const assetName of ['ace.js', 'mode-yaml.js', 'theme-textmate.js', 'worker-yaml.js']) {
  const source = path.join(repoRoot, 'node_modules', 'ace-builds', 'src-noconflict', assetName);
  const destination = path.join(editorOutputDir, assetName);
  fs.copyFileSync(source, destination);
  console.log(`Copied editor asset: ${source} -> ${destination}`);
}

fs.rmSync(path.join(electronResourcesDir, 'generated'), { recursive: true, force: true });
fs.rmSync(jreOutputDir, { recursive: true, force: true });

const moduleDeps = execFileSync(
  jdepsPath,
  [
    '--ignore-missing-deps',
    '--multi-release',
    String(majorJavaVersion),
    '--print-module-deps',
    outputJar,
  ],
  { encoding: 'utf8' },
).trim();

const jlinkModules = Array.from(
  new Set([
    ...baselineModules,
    ...moduleDeps.split(',').map((moduleName) => moduleName.trim()).filter(Boolean),
  ]),
).join(',');

const compressFlag = '--compress=zip-6';

execFileSync(
  jlinkPath,
  [
    '--add-modules',
    jlinkModules,
    '--strip-debug',
    '--no-man-pages',
    '--no-header-files',
    compressFlag,
    '--output',
    jreOutputDir,
  ],
  { stdio: 'inherit' },
);

console.log(`Created runtime image: ${jreOutputDir}`);

function parseMajorJavaVersion(releaseText) {
  const match = releaseText.match(/^JAVA_VERSION="?(\d+)/m);
  if (match) return parseInt(match[1], 10);
  return 21;
}

function isGraalVmRuntime(releaseText, javaVersionOutput) {
  const combined = `${releaseText}\n${javaVersionOutput}`.toLowerCase();
  return combined.includes('graalvm');
}
