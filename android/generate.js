const { TwaManifest, TwaGenerator, BufferedLog, ConsoleLog } = require('@bubblewrap/core');
const path = require('path');
const fs = require('fs');
const crypto = require('crypto');

async function main() {
  const targetDirectory = __dirname;
  // Point this at YOUR deployed site's manifest before regenerating — the TWA
  // wraps whatever host serves this manifest (see HANDOVER.md, Android section).
  const twaManifest = await TwaManifest.fromWebManifest(
    'https://YOUR-DOMAIN.example/manifest.webmanifest',
  );

  twaManifest.packageId = 'com.duabiskuttelur.app';
  twaManifest.launcherName = 'DuaBiskut';
  twaManifest.display = 'standalone';
  twaManifest.orientation = 'portrait';
  twaManifest.signingKey.path = path.join(targetDirectory, 'duabiskuttelur-upload.keystore');
  twaManifest.signingKey.alias = 'upload';
  // Play Console rejected versionCode 1 as a duplicate of an existing bundle
  // (from an earlier upload attempt under this package). Bump on every
  // rebuild that's meant for a new Play Console upload.
  twaManifest.appVersionCode = 2;
  twaManifest.appVersionName = '2';

  const manifestPath = path.join(targetDirectory, 'twa-manifest.json');
  await twaManifest.saveToFile(manifestPath);

  const twaGenerator = new TwaGenerator();
  await twaGenerator.removeTwaProject(targetDirectory);
  const log = new BufferedLog(new ConsoleLog('Generating TWA'));
  await twaGenerator.createTwaProject(targetDirectory, twaManifest, log, (current, total) => {
    console.log(`Progress: ${Math.round((current / total) * 100)}%`);
  });
  log.flush();

  // createTwaProject regenerates gradle.properties from its template, wiping
  // the fix for a local Windows JVM bug (unable to open a loopback Selector
  // without this). Re-apply it every time we regenerate the project.
  const gradlePropsPath = path.join(targetDirectory, 'gradle.properties');
  fs.appendFileSync(
    gradlePropsPath,
    '\norg.gradle.jvmargs=-Xmx1536m -Djdk.net.unixdomain.tmpdir=C:/Users/Public\n',
  );

  const manifestContents = fs.readFileSync(manifestPath);
  const sum = crypto.createHash('sha1').update(manifestContents).digest('hex');
  fs.writeFileSync(path.join(targetDirectory, 'manifest-checksum.txt'), sum);

  console.log('DONE. packageId=', twaManifest.packageId, 'appVersionCode=', twaManifest.appVersionCode,
    'host=', twaManifest.host);
}

main().catch((e) => {
  console.error('FAILED:', e);
  process.exit(1);
});
