import { spawn } from 'node:child_process';
import { writeFile } from 'node:fs/promises';
import { sanitizeTelemetry } from './telemetry.mjs';
import { ValidationError } from './manifest.mjs';

const MAX_RAW_OUTPUT = 1_000_000;

function classifyFailure(stderr) {
    return /(?:401|403|429|auth|network|connection|rate.?limit|temporar|unavailable|timed? out)/i.test(stderr)
        ? 'infra_error'
        : 'infra_error';
}

function extractUsage(stdout) {
    let inputTokens = null;
    let outputTokens = null;
    for (const line of stdout.split('\n')) {
        try {
            const event = JSON.parse(line);
            const usage = event.usage ?? event.payload?.usage ?? event.data?.usage;
            if (usage) {
                inputTokens = usage.input_tokens ?? usage.inputTokens ?? inputTokens;
                outputTokens = usage.output_tokens ?? usage.outputTokens ?? outputTokens;
            }
        } catch {
            // Non-JSON diagnostic lines are retained only in the temporary raw file.
        }
    }
    return { inputTokens, outputTokens };
}

export function buildCodexCommand(workspace, model) {
    const command = [
        'exec', '--ephemeral', '--ignore-user-config', '--json', '--color', 'never', '--sandbox', 'workspace-write',
        '-C', workspace, '-c', 'model_reasoning_effort="medium"', '-c', 'shell_environment_policy.inherit="none"'
    ];
    if (model) command.push('--model', model);
    command.push('-');
    return command;
}

export async function runCodexCandidate({ workspace, prompt, timeoutMs, rawEventPath, model }) {
    if (!Number.isInteger(timeoutMs) || timeoutMs < 1_000) throw new ValidationError('candidate timeout must be at least one second');
    const command = buildCodexCommand(workspace, model);
    const startedAt = Date.now();
    return new Promise((resolve) => {
        const child = spawn('codex', command, {
            cwd: workspace,
            env: process.env,
            detached: true,
            shell: false,
            stdio: ['pipe', 'pipe', 'pipe']
        });
        let stdout = '';
        let stderr = '';
        let timedOut = false;
        let hardKillTimer;
        const collect = (current, chunk) => current.length >= MAX_RAW_OUTPUT ? current : `${current}${chunk}`.slice(0, MAX_RAW_OUTPUT);
        child.stdout.on('data', (chunk) => { stdout = collect(stdout, chunk); });
        child.stderr.on('data', (chunk) => { stderr = collect(stderr, chunk); });
        child.on('error', (error) => { stderr = collect(stderr, error.message); });
        child.stdin.on('error', (error) => { stderr = collect(stderr, error.message); });
        child.stdin.end(`${prompt}\n`);
        const timer = setTimeout(() => {
            timedOut = true;
            try { process.kill(-child.pid, 'SIGTERM'); } catch { child.kill('SIGTERM'); }
            hardKillTimer = setTimeout(() => { try { process.kill(-child.pid, 'SIGKILL'); } catch { child.kill('SIGKILL'); } }, 2_000);
            hardKillTimer.unref();
        }, timeoutMs);
        child.on('close', async (exitCode, signal) => {
            clearTimeout(timer);
            clearTimeout(hardKillTimer);
            const durationMs = Date.now() - startedAt;
            let rawWriteError = null;
            if (rawEventPath) {
                try { await writeFile(rawEventPath, `${stdout}\n${stderr}`, { flag: 'wx' }); }
                catch (error) { rawWriteError = error; }
            }
            const usage = extractUsage(stdout);
            resolve({
                status: timedOut ? 'timeout' : rawWriteError ? 'infra_error' : exitCode === 0 ? 'completed' : classifyFailure(stderr),
                exitCode,
                signal,
                durationMs,
                tokens: usage,
                cost: null,
                outputDigestSource: sanitizeTelemetry(stdout.slice(-8_000)),
                errorSummary: sanitizeTelemetry(timedOut
                    ? `candidate exceeded ${timeoutMs} ms timeout`
                    : rawWriteError
                        ? `cannot persist raw candidate events: ${rawWriteError.message}`
                        : stderr.slice(-2_000))
            });
        });
    });
}
