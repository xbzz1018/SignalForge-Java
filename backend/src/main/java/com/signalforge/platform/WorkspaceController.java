package com.signalforge.platform;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class WorkspaceController {
    private final boolean fixtures;
    public WorkspaceController(@Value("${signalforge.allow-fixtures}") boolean fixtures) { this.fixtures=fixtures; }
    @GetMapping("/") String home() { return "forward:/index.html"; }
    @GetMapping("/api/workspace/config") @ResponseBody Map<String,Object> config() {
        return Map.of("allowFixtures",fixtures,"capabilities",new String[]{"ETF_RISK_COMPARISON"});
    }
}
