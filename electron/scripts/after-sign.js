const { execSync } = require('child_process');
const path = require('path');

exports.default = async function (context) {
  if (context.electronPlatformName !== 'darwin') return;

  const appPath = path.join(
    context.appOutDir,
    `${context.packager.appInfo.productFilename}.app`
  );

  console.log(`[after-sign] Ad-hoc signing: ${appPath}`);
  execSync(
    `codesign --force --deep --sign - --entitlements "${path.resolve(__dirname, '../build/entitlements.mac.plist')}" "${appPath}"`,
    { stdio: 'inherit' }
  );
};