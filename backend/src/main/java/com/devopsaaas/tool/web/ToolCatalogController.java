package com.devopsaaas.tool.web;

import com.devopsaaas.shared.security.CurrentUser;
import com.devopsaaas.tool.api.RiskLevel;
import com.devopsaaas.tool.api.ToolCategory;
import com.devopsaaas.tool.policy.ToolCatalog;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tools")
class ToolCatalogController {

    private final ToolCatalog catalog;

    ToolCatalogController(ToolCatalog catalog) {
        this.catalog = catalog;
    }

    record ToolView(String name, int version, String description, ToolCategory category, RiskLevel riskLevel,
            boolean requiresApproval, long timeoutSeconds, Map<String, Object> inputSchema) {
    }

    /** The tools the caller may use in this environment: filtered by permission and autonomy level. */
    @GetMapping
    @PreAuthorize("hasAuthority('EXECUTION_READ')")
    List<ToolView> list(@AuthenticationPrincipal CurrentUser user, @RequestParam UUID environmentId) {
        return catalog.visibleTo(user, environmentId).stream()
                .map(entry -> new ToolView(
                        entry.tool().name(),
                        entry.tool().definition().version(),
                        entry.tool().definition().description(),
                        entry.tool().definition().category(),
                        entry.tool().definition().riskLevel(),
                        entry.requiresApproval(),
                        entry.tool().definition().timeout().toSeconds(),
                        entry.tool().inputSchema()))
                .toList();
    }
}
