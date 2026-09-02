/**
 * Launcher for the npm scripts.
 *
 * Its only job is to install `scripts/exfat-readlink.cjs` via `--require`
 * before Node loads anything else — webpack's resolver binds `fs.readlink` in
 * its constructor, so patching it any later is too late. On a filesystem whose
 * `readlink` behaves (anything but exFAT) it adds nothing and just runs the
 * command.
 *
 *   node scripts/run.mjs next build
 */

import { spawn } from 'node:child_process';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const shim = path.join(path.dirname(fileURLToPath(import.meta.url)), 'exfat-readlink.cjs');

function readlinkIsBroken() {
  try {
    fs.readlinkSync(shim);
    return false;
  } catch (err) {
    return err.code === 'EISDIR';
  }
}

/** Resolve a package's bin entry so we can run it on `node` without a shell. */
function resolveBin(command) {
  const manifestPath = require.resolve(`${command}/package.json`);
  const { bin } = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  const entry = typeof bin === 'string' ? bin : bin?.[command];
  if (!entry) throw new Error(`${command} declares no bin entry`);
  return path.join(path.dirname(manifestPath), entry);
}

const [command, ...args] = process.argv.slice(2);
if (!command) {
  console.error('usage: node scripts/run.mjs <command> [args...]');
  process.exit(64);
}

const env = { ...process.env };
if (readlinkIsBroken()) {
  // Forward slashes: NODE_OPTIONS does not unescape Windows backslashes.
  const flag = `--require "${shim.replaceAll('\\', '/')}"`;
  env.NODE_OPTIONS = env.NODE_OPTIONS ? `${env.NODE_OPTIONS} ${flag}` : flag;
}

const child = spawn(process.execPath, [resolveBin(command), ...args], {
  stdio: 'inherit',
  env,
});
child.on('exit', (code, signal) => process.exit(code ?? (signal ? 1 : 0)));
