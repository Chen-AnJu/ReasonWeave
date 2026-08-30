#!/usr/bin/env node
import { existsSync } from 'node:fs';
import { delimiter, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isWindows, run } from './process-utils.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const javaHome = process.env.JAVA_HOME ? resolve(process.env.JAVA_HOME) : '';
const java = javaHome ? resolve(javaHome, 'bin', isWindows ? 'java.exe' : 'java') : 'java';
if (javaHome && !existsSync(java)) throw new Error(`Java executable not found under JAVA_HOME: ${javaHome}`);
const version = run(java, ['-version'], { allowFailure: true });
const versionText = `${version.stdout ?? ''}\n${version.stderr ?? ''}`;
if (version.status !== 0 || !/version "21(?:\.|\")/.test(versionText)) {
  throw new Error(`Java 21 is required. Selected runtime reported:\n${versionText.trim() || 'not found'}`);
}

const wrapper = resolve(root, 'backend', isWindows ? 'mvnw.cmd' : 'mvnw');
const mavenArgs = process.argv.slice(2);
if (mavenArgs.length === 0) mavenArgs.push('-B', 'verify');
const env = { ...process.env };
if (javaHome) env.PATH = `${resolve(javaHome, 'bin')}${delimiter}${env.PATH ?? ''}`;
run(wrapper, mavenArgs, { cwd: resolve(root, 'backend'), env, stdio: 'inherit', shell: isWindows });
