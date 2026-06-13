package cncode.web;

public class WebAssetsTest {
    public static void main(String[] args) {
        String css = WebAssets.css();
        if (!css.contains(".markdown-body") || !css.contains(".message.user") || !css.contains(".composer")) {
            throw new AssertionError("markdown/chat styles missing");
        }
        if (!css.contains("color-scheme: light") || !css.contains("border-radius: 18px")) {
            throw new AssertionError("chatgpt-like light style missing");
        }
        if (!css.contains("height: 100vh") || !css.contains("overflow: hidden")) {
            throw new AssertionError("viewport-locked layout missing");
        }
        if (!css.contains("grid-template-rows: minmax(0, 1fr) auto") || !css.contains("min-height: 0")) {
            throw new AssertionError("chat grid scroll containment missing");
        }
        if (!css.contains(".messages") || !css.contains("overflow-y: auto")) {
            throw new AssertionError("messages scroll container missing");
        }
        if (css.contains("position: fixed") || css.contains("left: calc(260px")) {
            throw new AssertionError("composer should not be a fixed overlay");
        }
        if (!css.contains(".sidebar") || !css.contains("height: 100vh") || !css.contains("overflow-y: auto")) {
            throw new AssertionError("sidebar stable viewport layout missing");
        }

        String js = WebAssets.js();
        if (!js.contains("function escapeHtml") || !js.contains("function renderMarkdown") || !js.contains("function renderInlineMarkdown")) {
            throw new AssertionError("markdown renderer missing");
        }
        if (!js.contains("assistantRaw +=") || !js.contains("parsePlanOptions(assistantRaw)") || !js.contains("setMarkdown(assistant, parsed.text)")) {
            throw new AssertionError("streaming markdown update missing");
        }
        if (!css.contains(".message.tool") || !css.contains(".tool-activity") || !css.contains(".file-button")) {
            throw new AssertionError("tool activity styles missing");
        }
        if (!js.contains("function createToolActivity") || !js.contains("function finishToolActivity")) {
            throw new AssertionError("tool activity functions missing");
        }
        if (!js.contains("function extractToolPaths") || !js.contains("function openFile") || !js.contains("/api/open-file") || !js.contains("confirmedExternal")) {
            throw new AssertionError("file open logic missing");
        }
        if (!js.contains("function showPermissionRequest") || !js.contains("function submitPermission") || !js.contains("/api/permission") || !js.contains("permission_request")) {
            throw new AssertionError("permission request UI missing");
        }
        if (!css.contains(".permission-card") || !css.contains(".permission-actions")) {
            throw new AssertionError("permission card styles missing");
        }
        if (js.contains("Tool start:")) {
            throw new AssertionError("tool start should not render as system log");
        }
        if (!WebAssets.html().contains("id=\"planMode\"") || !WebAssets.html().contains("id=\"doMode\"") || !WebAssets.html().contains("id=\"planOptions\"")) {
            throw new AssertionError("plan/do controls missing");
        }
        if (!js.contains("function parsePlanOptions") || !js.contains("cncode-options")) {
            throw new AssertionError("plan options parser missing");
        }
        if (!js.contains("function submitPlanSelection") || !js.contains("我选择：")) {
            throw new AssertionError("plan selection submit missing");
        }
        if (!js.contains("const slashCommands") || !js.contains("function updateSlashMenu") || !js.contains("function autocompleteSlashCommand")) {
            throw new AssertionError("slash command menu missing");
        }
        if (!js.contains("event.key === 'Tab'") || !js.contains("review") || !js.contains("status")) {
            throw new AssertionError("slash command tab completion missing");
        }
    }
}
