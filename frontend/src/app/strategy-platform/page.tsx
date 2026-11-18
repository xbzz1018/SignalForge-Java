import type { Metadata } from 'next';
import Link from 'next/link';

export const metadata: Metadata = {
  title: '策略平台 · SignalForge',
};

export default function StrategyPlatformPage() {
  return (
    <main className="mx-auto max-w-4xl px-6 py-10">
      <h1 className="text-xl font-semibold">策略平台</h1>
      <p className="mt-3 text-sm text-muted-foreground">策略回测尚未迁入 Java 版。</p>
      <Link href="/java-research" className="mt-4 inline-block text-sm font-medium underline">
        打开 ETF 风险研究
      </Link>
    </main>
  );
}
