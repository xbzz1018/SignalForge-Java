import { open } from 'node:fs/promises';

export async function syncDirectory(directory: string): Promise<void> {
  const handle = await open(directory, 'r');
  try {
    await handle.sync();
  } catch (error) {
    // Windows refuses fsync on a read-only directory handle. File data is
    // still flushed before rename; directory-entry durability is OS-managed.
    if (process.platform !== 'win32' || (error as NodeJS.ErrnoException).code !== 'EPERM') throw error;
  } finally {
    await handle.close();
  }
}
