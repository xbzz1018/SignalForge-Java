package com.signalforge.finance;

import com.signalforge.finance.IntradayQuoteSource.Quote;
import com.signalforge.platform.ProjectAccess;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{project}/market/intraday/{market}/{code}")
public class IntradayQuoteController {
    private final ProjectAccess access;
    private final IntradayQuotes quotes;

    public IntradayQuoteController(ProjectAccess access, IntradayQuotes quotes) {
        this.access = access;
        this.quotes = quotes;
    }

    @GetMapping
    Quote query(@PathVariable UUID project, @PathVariable String market, @PathVariable String code, Authentication auth) {
        access.require(project, auth, false);
        return quotes.query(market, code);
    }

    @PostMapping("/refresh")
    Quote refresh(@PathVariable UUID project, @PathVariable String market, @PathVariable String code, Authentication auth) {
        access.require(project, auth, true);
        return quotes.refresh(market, code);
    }
}
