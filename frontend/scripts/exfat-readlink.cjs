/**
 * exFAT readlink compatibility shim.
 *
 * On an exFAT volume Node's `readlink` answers `EISDIR` for an ordinary file
 * instead of the `EINVAL` every other filesystem returns. Webpack's resolver,
 * its file-system cache and Next's build pipeline all treat `EINVAL` as
 * "this is not a symlink" and treat anything else as fatal, so a build from an
 * exFAT drive dies on the first file it inspects.
 *
 * exFAT cannot store symlinks at all, so `EINVAL` is the truthful answer here:
 * this rewrites that one error code and changes nothing else. It self-detects
 * and stays out of the way on a filesystem that behaves.
 *
 * Loaded from `next.config.ts`, which every Next process (build, dev, and the
 * static-generation workers) reads before it touches the filesystem.
 */

const fs = require('node:fs');

const NOT_A_SYMLINK = 'EINVAL';

function isBroken() {
  try {
    fs.readlinkSync(__filename);
    return false; // a real symlink somehow — nothing to fix
  } catch (err) {
    return err && err.code === 'EISDIR';
  }
}

function asNotASymlink(err) {
  if (!err || err.code !== 'EISDIR') return err;
  const fixed = new Error(
    `${NOT_A_SYMLINK}: invalid argument, readlink '${err.path}'`,
  );
  return Object.assign(fixed, err, { code: NOT_A_SYMLINK, errno: -4071 });
}

function install() {
  const { readlink, readlinkSync } = fs;
  const promisedReadlink = fs.promises.readlink;

  fs.readlink = function patchedReadlink(...args) {
    const callback = args[args.length - 1];
    if (typeof callback === 'function') {
      args[args.length - 1] = (err, ...rest) => callback(asNotASymlink(err), ...rest);
    }
    return readlink.apply(this, args);
  };

  fs.readlinkSync = function patchedReadlinkSync(...args) {
    try {
      return readlinkSync.apply(this, args);
    } catch (err) {
      throw asNotASymlink(err);
    }
  };

  fs.promises.readlink = function patchedReadlinkPromise(...args) {
    return promisedReadlink.apply(this, args).catch((err) => {
      throw asNotASymlink(err);
    });
  };
}

if (isBroken()) install();
