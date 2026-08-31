import { randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { access, readFile, realpath } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { runCommand } from './git.mjs';
import { ValidationError } from './manifest.mjs';

const moduleRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const DEFAULT_PROFILES_PATH = path.join(moduleRoot, 'sandbox', 'profiles.json');
export const DEFAULT_ENTRYPOINT_PATH = path.join(moduleRoot, 'sandbox', 'entrypoint.sh');
export const DEFAULT_MAVEN_SETTINGS_PATH = path.join(moduleRoot, 'sandbox', 'maven-settings.xml');
const HOST_GIT_COMMANDS = new Set(['cat-file', 'diff', 'grep', 'log', 'ls-files', 'rev-parse', 'show', 'status']);

function boundedInteger(value, fallback, minimum, maximum, label) {
    const parsed = value === undefined ? fallback : Number(value);
    if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
        throw new ValidationError(`${label} must be an integer from ${minimum} to ${maximum}`);
    }
    return parsed;
}

export async function loadSandboxProfiles(file = DEFAULT_PROFILES_PATH) {
    let data;
    try {
        data = JSON.parse(await readFile(file, 'utf8'));
    } catch (error) {
        throw new ValidationError(`cannot read sandbox profiles: ${error.message}`);
    }
    if (data.schemaVersion !== 'coding-agent-sandbox-profiles/v1' || !data.profiles || typeof data.profiles !== 'object') {
        throw new ValidationError('sandbox profiles have an incompatible schema');
    }
    for (const [name, profile] of Object.entries(data.profiles)) {
        if (!profile || typeof profile.image !== 'string' || !Array.isArray(profile.executables)) {
            throw new ValidationError(`sandbox profile '${name}' is invalid`);
        }
    }
    return data;
}

function mount(source, target, readOnly = true) {
    return `${source}:${target}${readOnly ? ':ro' : ''}`;
}

export function buildDockerArgs(options) {
    const memoryMb = boundedInteger(options.memoryMb, 2048, 128, 32768, 'sandbox memory');
    const pidsLimit = boundedInteger(options.pidsLimit, 256, 16, 4096, 'sandbox pids limit');
    const cpus = Number(options.cpus ?? 2);
    if (!Number.isFinite(cpus) || cpus < 0.1 || cpus > 32) throw new ValidationError('sandbox cpus must be from 0.1 to 32');
    const args = [
        'run', '--rm', '--pull=never', '--name', options.name,
        '--network', 'none', '--read-only', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges', '--pids-limit', String(pidsLimit),
        '--memory', `${memoryMb}m`, '--cpus', String(cpus), '--user', '65532:65532',
        '--tmpfs', `/work:rw,nosuid,nodev,size=${memoryMb}m,uid=65532,gid=65532,mode=0700`,
        '--tmpfs', '/tmp:rw,nosuid,nodev,noexec,size=256m,uid=65532,gid=65532,mode=0700',
        '--volume', mount(options.workspace, '/source'),
        '--env', 'HOME=/work/home', '--env', 'CI=true', '--env', `SANDBOX_CWD=${options.relativeCwd ?? '.'}`
    ];
    if (options.entrypointMount !== false) {
        args.push('--volume', mount(options.entrypointPath ?? DEFAULT_ENTRYPOINT_PATH, '/usr/local/bin/coding-agent-entrypoint'));
        args.push('--entrypoint', '/usr/local/bin/coding-agent-entrypoint');
    }
    for (const item of options.extraMounts ?? []) args.push('--volume', mount(item.source, item.target, item.readOnly !== false));
    for (const [key, value] of Object.entries(options.environment ?? {})) args.push('--env', `${key}=${value}`);
    args.push(options.image, ...options.command);
    return args;
}

async function imageExists(image) {
    const result = await runCommand(['docker', 'image', 'inspect', image], { maxOutput: 20_000 });
    return result.exitCode === 0;
}

function runDockerWithTimeout(args, name, timeoutMs) {
    return new Promise((resolve, reject) => {
        const startedAt = Date.now();
        const child = spawn('docker', args, { shell: false, stdio: ['ignore', 'pipe', 'pipe'] });
        let stdout = '';
        let stderr = '';
        const limit = 200_000;
        let timedOut = false;
        let settled = false;
        child.stdout.on('data', (chunk) => { if (stdout.length < limit) stdout += chunk; });
        child.stderr.on('data', (chunk) => { if (stderr.length < limit) stderr += chunk; });
        child.on('error', (error) => {
            if (!settled) {
                settled = true;
                clearTimeout(timer);
                reject(error);
            }
        });
        const timer = setTimeout(async () => {
            timedOut = true;
            await runCommand(['docker', 'kill', name], { maxOutput: 20_000 }).catch(() => undefined);
            child.kill('SIGKILL');
        }, timeoutMs);
        child.on('close', (exitCode, signal) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            resolve({
                command: ['docker', ...args], exitCode: timedOut ? 124 : exitCode, signal,
                stdout: stdout.slice(0, limit), stderr: stderr.slice(0, limit),
                durationMs: Date.now() - startedAt, timedOut, isolation: 'docker'
            });
        });
    });
}

async function discoverMavenMounts() {
    const lookup = await runCommand(['which', 'mvn']);
    if (lookup.exitCode !== 0) throw new ValidationError('Maven executable is unavailable for the java-maven sandbox profile');
    const executable = await realpath(lookup.stdout.trim());
    const home = path.dirname(path.dirname(executable));
    const evaluated = await runCommand([
        'mvn', '-o', 'help:evaluate', '-Dexpression=settings.localRepository', '-q', '-DforceStdout'
    ], { cwd: os.tmpdir(), maxOutput: 20_000 });
    const configuredRepository = evaluated.exitCode === 0 ? evaluated.stdout.trim() : path.join(os.homedir(), '.m2', 'repository');
    if (!path.isAbsolute(configuredRepository) || configuredRepository.includes('\n')) {
        throw new ValidationError('Maven local repository resolved to an unsafe path');
    }
    const repository = await realpath(configuredRepository);
    await access(path.join(home, 'bin', 'mvn'));
    await access(repository);
    return [
        { source: home, target: '/opt/maven', readOnly: true },
        { source: repository, target: '/m2', readOnly: true }
    ];
}

function resolveProfile(profiles, executable, requested) {
    if (requested) {
        const profile = profiles.profiles[requested];
        if (!profile) throw new ValidationError(`unknown sandbox profile '${requested}'`);
        if (!profile.executables.includes(executable)) throw new ValidationError(`sandbox profile '${requested}' does not allow '${executable}'`);
        return { name: requested, ...profile };
    }
    const entry = Object.entries(profiles.profiles).find(([, profile]) => profile.executables.includes(executable));
    if (!entry) throw new ValidationError(`no sandbox profile supports '${executable}'`);
    return { name: entry[0], ...entry[1] };
}

export async function runSandboxCommand(command, options) {
    if (!Array.isArray(command) || command.length === 0) throw new ValidationError('sandbox command must be a non-empty array');
    const workspace = await realpath(options.workspace);
    const cwd = await realpath(options.cwd ?? workspace);
    const relativeCwd = path.relative(workspace, cwd) || '.';
    if (relativeCwd.startsWith('..') || path.isAbsolute(relativeCwd)) throw new ValidationError('sandbox cwd escapes workspace');
    const profiles = options.profiles ?? await loadSandboxProfiles(options.profilesPath);
    const profile = resolveProfile(profiles, command[0], options.profile);
    const imageChecker = options.imageChecker ?? imageExists;
    if (!await imageChecker(profile.image)) {
        throw new ValidationError(`required sandbox image '${profile.image}' is absent; pulls and host fallback are disabled`);
    }
    let containerCommand = [...command];
    const extraMounts = [];
    const environment = {};
    if (command[0] === 'mvn') {
        extraMounts.push(...await discoverMavenMounts());
        extraMounts.push({ source: options.mavenSettingsPath ?? DEFAULT_MAVEN_SETTINGS_PATH, target: '/opt/coding-agent/settings.xml', readOnly: true });
        containerCommand = ['/opt/maven/bin/mvn', '-o', '-s', '/opt/coding-agent/settings.xml', '-Dmaven.repo.local=/m2', ...command.slice(1)];
        environment.MAVEN_OPTS = '-Djava.io.tmpdir=/work/home -Djansi.tmpdir=/work/home';
    }
    if (command[0] === 'npm') environment.npm_config_offline = 'true';
    const name = `coding-agent-${process.pid}-${randomBytes(5).toString('hex')}`;
    const args = buildDockerArgs({
        name, workspace, relativeCwd, image: profile.image, command: containerCommand,
        extraMounts, environment, entrypointPath: options.entrypointPath,
        entrypointMount: profile.name !== 'smoke', memoryMb: options.memoryMb,
        pidsLimit: options.pidsLimit, cpus: options.cpus
    });
    return runDockerWithTimeout(args, name, options.timeoutMs ?? 480_000);
}

export function createVerificationRunner(options = {}) {
    return async (command, context) => {
        if (command[0] === 'git') {
            if (!HOST_GIT_COMMANDS.has(command[1])) throw new ValidationError('only read-only git verification may use the host runner');
            return { ...await runCommand(command, { cwd: context.cwd, env: context.env }), isolation: 'host-readonly' };
        }
        return runSandboxCommand(command, {
            workspace: context.root, cwd: context.cwd, timeoutMs: options.timeoutMs,
            profilesPath: options.profilesPath, entrypointPath: options.entrypointPath,
            memoryMb: options.memoryMb, pidsLimit: options.pidsLimit, cpus: options.cpus
        });
    };
}
