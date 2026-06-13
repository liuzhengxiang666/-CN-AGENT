package cncode.permission;

import cncode.tool.Tool;
import cncode.tool.ToolCall;
import cncode.tool.ToolCategory;
import cncode.tool.ToolJson;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class PermissionChecker {
    private final PermissionRuleStore ruleStore;
    private final PathSandbox sandbox;
    private final List<PermissionRule> sessionRules = new ArrayList<>();

    public PermissionChecker(Path projectRoot) {
        this.ruleStore = new PermissionRuleStore(projectRoot);
        this.sandbox = new PathSandbox(projectRoot);
    }

    public PermissionCheckResult check(Tool tool, ToolCall call, PermissionMode mode, boolean planOnly) {
        Map<String, Object> args;
        try {
            args = ToolJson.parseObject(call.argumentsJson());
        } catch (Exception error) {
            return PermissionCheckResult.deny("工具参数解析失败，已拒绝：" + error.getMessage());
        }

        String content = call.argumentsJson();
        String path = extractPath(args);
        if (tool.category() != ToolCategory.COMMAND) {
            PermissionCheckResult pathResult = sandbox.check(path);
            if (pathResult.decision() == PermissionDecision.DENY) {
                return pathResult;
            }
        }

        if (tool.category() == ToolCategory.COMMAND) {
            String reason = DangerousCommandDetector.reason(ToolJson.string(args, "command"));
            if (!reason.isBlank()) {
                return PermissionCheckResult.deny(reason);
            }
        }

        if (planOnly && tool.category() != ToolCategory.READ) {
            return PermissionCheckResult.deny("Plan 模式下禁止执行 " + tool.category() + " 工具。");
        }

        PermissionCheckResult rule = matchRules(sessionRules, call.name(), content, "会话规则");
        if (rule != null) {
            return rule;
        }
        rule = matchRules(ruleStore.projectRules(), call.name(), content, "项目规则");
        if (rule != null) {
            return rule;
        }
        rule = matchRules(ruleStore.userRules(), call.name(), content, "用户规则");
        if (rule != null) {
            return rule;
        }

        return switch (mode == null ? PermissionMode.DEFAULT : mode) {
            case STRICT, DEFAULT -> defaultAskOrAllow(tool, call, path);
            case ALLOW -> PermissionCheckResult.allow("放行模式允许非危险工具调用。");
        };
    }

    public void rememberSession(PermissionRequest request) {
        sessionRules.add(new PermissionRule(PermissionDecision.ALLOW, request.toolName(), patternFor(request), "session"));
    }

    public void rememberAlways(PermissionRequest request) {
        PermissionRule rule = new PermissionRule(PermissionDecision.ALLOW, request.toolName(), patternFor(request), "user");
        ruleStore.appendUserRule(rule);
    }

    private PermissionCheckResult defaultAskOrAllow(Tool tool, ToolCall call, String path) {
        if (tool.category() == ToolCategory.READ) {
            return PermissionCheckResult.allow("读工具默认允许。");
        }
        return PermissionCheckResult.ask(new PermissionRequest(
                UUID.randomUUID().toString(),
                call.name(),
                tool.category().name(),
                summarize(call.argumentsJson()),
                path,
                tool.category() == ToolCategory.WRITE ? "写文件操作需要用户确认。" : "命令执行需要用户确认。",
                new CompletableFuture<>()
        ));
    }

    private PermissionCheckResult matchRules(List<PermissionRule> rules, String toolName, String content, String label) {
        for (PermissionRule rule : rules) {
            if (!rule.matches(toolName, content)) {
                continue;
            }
            if (rule.decision() == PermissionDecision.ALLOW) {
                return PermissionCheckResult.allow(label + "允许：" + rule.pattern());
            }
            if (rule.decision() == PermissionDecision.DENY) {
                return PermissionCheckResult.deny(label + "拒绝：" + rule.pattern());
            }
            return null;
        }
        return null;
    }

    private String extractPath(Map<String, Object> args) {
        for (String key : List.of("path", "file", "target", "pattern")) {
            Object value = args.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private String patternFor(PermissionRequest request) {
        if (request.path() != null && !request.path().isBlank()) {
            return request.path();
        }
        return request.argumentSummary().length() > 80 ? request.argumentSummary().substring(0, 80) : request.argumentSummary();
    }

    private String summarize(String json) {
        if (json == null) {
            return "";
        }
        return json.length() > 600 ? json.substring(0, 600) + "..." : json;
    }
}
