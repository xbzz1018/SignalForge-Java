import type { Metadata } from 'next';
import { redirect } from 'next/navigation';

import LoginClient from './LoginClient';
import { getDevelopmentAdminDefaults, getProjectAuthConfig } from '@/lib/config/auth';

export const metadata: Metadata = {
  title: '登录 · SignalForge',
};

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ next?: string | string[] }>;
}) {
  const config = getProjectAuthConfig();
  if (!config.enabled) redirect('/');
  const query = await searchParams;
  const nextPath = typeof query.next === 'string' ? query.next : undefined;
  const hasConfiguredCredentials = Boolean(
    process.env[config.bootstrap.emailEnv]?.trim() || process.env[config.bootstrap.passwordEnv],
  );
  const developmentDefaults = hasConfiguredCredentials ? null : getDevelopmentAdminDefaults(config);
  return (
    <LoginClient
      nextPath={nextPath}
      rememberMe={config.session.rememberMe}
      localIdentity={process.env.SIGNALFORGE_WEB_LOGIN && process.env.SIGNALFORGE_AUTH_ADMIN_EMAIL ? {
        login: process.env.SIGNALFORGE_WEB_LOGIN,
        email: process.env.SIGNALFORGE_AUTH_ADMIN_EMAIL,
      } : null}
      developmentAdmin={developmentDefaults ? {
        login: developmentDefaults.login,
        email: developmentDefaults.email,
      } : null}
    />
  );
}
