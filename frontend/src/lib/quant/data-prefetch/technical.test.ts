import { describe, expect, it } from 'vitest';
import { calculateMetrics, ensureTechnicalSummary } from './technical';

function bars(prices: Array<number | null>) {
  return prices.map((close, index) => ({ date: new Date(Date.UTC(2025, 0, index + 1)).toISOString().slice(0, 10), close }));
}

describe('20-day volatility window', () => {
  it('ignores earlier volatility when the last 20 returns are flat', () => {
    expect(calculateMetrics({ bars: bars([100, 200, 100, 200, 100, ...Array(21).fill(100)]) }).volatility20d).toBe(0);
  });
  it('sorts dated observations before calculating the window', () => {
    const source = bars(Array.from({ length: 26 }, (_, index) => 100 + index * index));
    expect(calculateMetrics({ bars: [...source].reverse() })).toEqual(calculateMetrics({ bars: source }));
  });
  it.each(['short', 'missing', 'duplicate', 'invalid-date', 'rolled-date', 'weekly'])('reports unavailable data for %s', kind => {
    const source = bars(Array(21).fill(100));
    if (kind === 'short') source.pop();
    if (kind === 'missing') source[10].close = null;
    if (kind === 'duplicate') source[10].date = source[11].date;
    if (kind === 'invalid-date') source[10].date = 'not-a-date';
    if (kind === 'rolled-date') source[10].date = '2025-02-30';
    const result = calculateMetrics({ bars: source, period: kind === 'weekly' ? 'weekly' : 'daily' });
    expect(result.volatility20d).toBeNull();
    expect(result.metricWarnings).not.toEqual([]);
  });
  it('does not label full-history backend volatility as a 20-day observation', () => {
    const summary = ensureTechnicalSummary({ symbol: '510300',
      kline: { bars: bars(Array(21).fill(100)) },
      technicalIndicators: { summary: { volatility_annualized_pct: 88 } },
    });
    expect(summary.volatility_20d_annualized_pct).toBe(0);
  });
});
