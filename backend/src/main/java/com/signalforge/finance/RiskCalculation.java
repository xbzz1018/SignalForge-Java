package com.signalforge.finance;

import com.signalforge.finance.EtfPriceSource.Bar;
import com.signalforge.infrastructure.JsonCodec;
import com.signalforge.research.ResearchRequest.Metric;
import com.signalforge.research.ResearchRequest.Security;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class RiskCalculation {
    private RiskCalculation() {}

    static String version(Security security, String adjustment, List<Bar> bars) {
        var canonical = new StringBuilder(security.market()).append('|').append(security.securityId())
                .append('|').append(adjustment).append('\n');
        for (var bar : bars) canonical.append(bar.date()).append('|').append(close(bar)).append('\n');
        return JsonCodec.sha256(canonical.toString());
    }

    static String close(Bar bar) { return bar.close().stripTrailingZeros().toPlainString(); }

    static Map<Metric, Double> metrics(List<Bar> bars) {
        if (bars.size() < 3 || bars.size() > 800) throw new IllegalArgumentException("INSUFFICIENT_PRICE_COVERAGE");
        double first = bars.getFirst().close().doubleValue();
        LocalDate previousDate = null;
        double previous = first, peak = first, drawdown = 0, mean = 0, sumSquared = 0;
        int returns = 0;
        for (var bar : bars) {
            double price = bar.close().doubleValue();
            if (!Double.isFinite(price) || price <= 0 || previousDate != null && !bar.date().isAfter(previousDate)) {
                throw new IllegalArgumentException("INVALID_PRICE_OBSERVATIONS");
            }
            if (previousDate != null) {
                double change = price / previous - 1;
                double delta = change - mean;
                mean += delta / ++returns;
                sumSquared += delta * (change - mean);
            }
            peak = Math.max(peak, price);
            drawdown = Math.max(drawdown, (peak - price) / peak);
            previous = price;
            previousDate = bar.date();
        }
        var result = new EnumMap<Metric, Double>(Metric.class);
        result.put(Metric.TOTAL_RETURN, (previous / first - 1) * 100);
        result.put(Metric.ANNUALIZED_VOLATILITY, Math.sqrt(Math.max(0, sumSquared) / (returns - 1) * 252) * 100);
        result.put(Metric.MAX_DRAWDOWN, drawdown * 100);
        if (result.values().stream().anyMatch(value -> !Double.isFinite(value))) throw new IllegalArgumentException("INVALID_RISK_METRIC");
        return result;
    }
}
