/** Read-only browser acceptance: views an existing task; never starts a paid run. */
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const [taskId] = process.argv.slice(2);
const usage = 'Usage: node verification/node/smoke-knowledge-agent.mjs <completed-task-id>';
if (taskId === '--help') {
  console.log(usage);
  process.exit(0);
}
if (process.argv.length !== 3 || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(taskId ?? '')) {
  console.error(usage);
  process.exit(2);
}

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const require = createRequire(path.join(root, 'frontend/package.json'));
const { chromium } = require('playwright');
const { parse } = require('dotenv');
const env = { ...parse(await fs.readFile(path.join(root, '.env'))), ...parse(await fs.readFile(path.join(root, 'frontend/.env'))) };
const base = process.env.SIGNALFORGE_WEB_URL || 'http://127.0.0.1:58872';
const output = path.join(root, '.runtime/agent-browser');
await fs.mkdir(output, { recursive: true });
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } });
try {
  const login = await context.request.post(`${base}/api/auth/sign-in/email`, {
    headers: { origin: base, 'x-signalforge-request': 'same-origin' },
    data: { email: env.SIGNALFORGE_AUTH_ADMIN_EMAIL, password: env.SIGNALFORGE_AUTH_ADMIN_PASSWORD },
  });
  if (!login.ok()) throw new Error(`Workspace login status ${login.status()}`);
  const csrf = await (await context.request.get(`${base}/api/java/auth/csrf`)).json();
  const javaLogin = await context.request.post(`${base}/api/java/auth/login`, {
    headers: { origin: base, [csrf.headerName]: csrf.token }, form: { password: env.SIGNALFORGE_DEV_PASSWORD },
  });
  if (javaLogin.status() !== 204) throw new Error(`Research session status ${javaLogin.status()}`);
  const page = await context.newPage();
  const failures = [];
  page.on('pageerror', error => failures.push(error.message));
  await page.goto(`${base}/knowledge-graph`, { waitUntil: 'domcontentloaded', timeout: 120000 });
  await page.getByText('向投研 Agent 提问', { exact: true }).waitFor({ timeout: 60000 });
  await page.getByRole('combobox', { name: '最近问答' }).selectOption(taskId);
  await page.getByRole('link', { name: '查看对应 PDF' }).waitFor({ timeout: 15000 });
  const quote = await page.locator('article blockquote').first().textContent();
  if (!quote?.trim()) throw new Error('Expected a saved public announcement citation');
  await page.screenshot({ path: path.join(output, 'desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true });
  if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)) throw new Error('Mobile horizontal overflow');
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.getByRole('combobox', { name: '最近问答' }).selectOption(taskId);
  await page.getByRole('link', { name: '查看对应 PDF' }).waitFor({ timeout: 15000 });
  if (failures.length) throw new Error(`Page errors: ${failures.join('; ')}`);
  const report = { checkedAt: new Date().toISOString(), browser: 'installed-google-chrome', taskId,
    checks: { workspaceLogin: 'passed', javaSession: 'passed', historyRestore: 'passed', citationDisplay: 'passed',
      reloadRestore: 'passed', mobileOverflow: 'passed', pageErrors: 'passed' }, paidRequestsStarted: 0, status: 'passed' };
  await fs.writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
} finally { await context.close(); await browser.close(); }
