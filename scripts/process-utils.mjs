import { spawnSync } from 'node:child_process';

export const isWindows = process.platform === 'win32';
export const executable = (name) => isWindows ? `${name}.cmd` : name;

export function run(command, args, options = {}) {
  const result = spawnSync(command, args, {
    cwd: options.cwd,
    encoding: options.encoding ?? 'utf8',
    env: options.env ?? process.env,
    shell: options.shell ?? (isWindows && command.toLowerCase().endsWith('.cmd')),
    stdio: options.stdio ?? 'pipe',
  });
  if (result.error) throw result.error;
  if (result.status !== 0 && options.allowFailure !== true) {
    const detail = [result.stdout, result.stderr].filter(Boolean).join('\n').trim();
    throw new Error(`${command} ${args.join(' ')} failed with exit code ${result.status}${detail ? `:\n${detail}` : ''}`);
  }
  return result;
}
