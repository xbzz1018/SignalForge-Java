import { PrismaClient } from '../frontend/node_modules/@prisma/client';
import { spawnSync } from 'node:child_process';

async function main() {
const prisma = new PrismaClient();
try {
  const target = new URL(process.env.DATABASE_URL ?? '');
  if (!['/signalforge_java_web', '/signalforge_web'].includes(target.pathname) || !['127.0.0.1', 'localhost'].includes(target.hostname)) {
    throw new Error('Frontend bootstrap requires a dedicated local SignalForge Java web database.');
  }
  const existing = await prisma.authUser.findUnique({ where: { email: process.env.SIGNALFORGE_AUTH_ADMIN_EMAIL } });
  if (existing) {
    console.log('Frontend account already exists; password and sessions preserved.');
  } else {
    for (const script of ['scripts/auth/ensure-access-control.ts', 'scripts/auth/bootstrap-user.ts']) {
      const result = spawnSync(process.execPath, ['--env-file=.env', '--import', 'tsx', script], {
        cwd: process.cwd(), env: process.env, stdio: 'inherit', windowsHide: true,
      });
      if (result.status !== 0) throw new Error('Frontend account initialization failed.');
    }
  }
} finally {
  await prisma.$disconnect();
}
}
main().catch(() => { console.error('Frontend bootstrap failed; inspect local database/configuration.'); process.exitCode = 1; });
