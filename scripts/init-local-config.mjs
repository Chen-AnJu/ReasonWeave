#!/usr/bin/env node
import { randomBytes } from 'node:crypto';
import { chmod, mkdir, open } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repositoryRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const secretDirectory = resolve(repositoryRoot, '.local', 'secrets');
const passwordPath = resolve(secretDirectory, 'postgres_password');

await mkdir(secretDirectory, { recursive: true, mode: 0o700 });
let created = false;
try {
  const handle = await open(passwordPath, 'wx', 0o644);
  try {
    await handle.writeFile(`${randomBytes(32).toString('base64url')}\n`, 'utf8');
    created = true;
  } finally {
    await handle.close();
  }
} catch (error) {
  if (error?.code !== 'EEXIST') throw error;
}

try {
  // Docker Compose bind-mounts file-backed secrets without remapping ownership.
  // Keep the parent directory private while allowing the non-root container user
  // to read the single file through its read-only /run/secrets mount.
  await chmod(secretDirectory, 0o700);
  await chmod(passwordPath, 0o644);
} catch {
  // Windows does not expose POSIX file modes; the file remains inside an ignored local directory.
}

console.log(created
  ? 'Created .local/secrets/postgres_password'
  : 'Local configuration already exists; no secret was overwritten.');
