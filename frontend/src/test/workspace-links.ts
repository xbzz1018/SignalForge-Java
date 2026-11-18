import fs from 'node:fs/promises';
import path from 'node:path';

export function linkDirectory(target: string, alias: string) {
  return fs.symlink(target, alias, process.platform === 'win32' ? 'junction' : 'dir');
}

// A junction to the parent exposes the same real file without requiring the
// Windows privilege for file symlinks. Return its path as seen by the caller.
export async function linkFile(target: string, alias: string): Promise<string> {
  if (process.platform === 'win32') {
    await linkDirectory(path.dirname(target), alias);
    return path.join(alias, path.basename(target));
  }
  await fs.symlink(target, alias, 'file');
  return alias;
}
