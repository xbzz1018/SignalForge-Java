package com.signalforge.finance;

import com.signalforge.research.ResearchExecutor;
import com.signalforge.research.ResearchRequest;
import com.signalforge.research.TaskStore;
import org.springframework.stereotype.Component;

@Component
public class RiskResearchExecutor implements ResearchExecutor {
    private final RiskService finance;
    private final EvidenceVerifier verifier;
    private final MarketSnapshots snapshots;
    public RiskResearchExecutor(RiskService finance,EvidenceVerifier verifier,MarketSnapshots snapshots) {
        this.finance=finance; this.verifier=verifier; this.snapshots=snapshots;
    }
    public String researchType() { return "ETF_RISK_COMPARISON"; }
    public String toolName() { return "finance.risk"; }
    public String execute(TaskStore.Claim claim) throws Exception {
        return finance.calculate(claim.task().request(),snapshots.find(claim.task().projectId(),claim.task().request()));
    }
    public void verify(ResearchRequest request,String response) { verifier.verify(request,response); }
}
