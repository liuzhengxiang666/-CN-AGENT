package cncode.web;

public final class WebAssets {
    private WebAssets() {
    }

    public static String html() {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>CN Code</title>
                  <link rel="stylesheet" href="/app.css">
                </head>
                <body>
                  <div class="shell">
                    <aside class="sidebar">
                      <div class="brand">
                        <div class="mark">CN</div>
                        <div>
                          <h1>CN Code</h1>
                          <p>Local Coding Agent</p>
                        </div>
                      </div>
                      <section class="panel">
                        <div class="label">Model</div>
                        <div id="model" class="value">loading...</div>
                      </section>
                      <section class="panel">
                        <div class="label">Status</div>
                        <div id="status" class="value online">connecting</div>
                      </section>
                      <section class="panel">
                        <div class="label">Messages</div>
                        <div id="messageCount" class="value">0</div>
                      </section>
                      <section class="panel">
                        <div class="label">Tools</div>
                        <div id="tools" class="value">enabled</div>
                      </section>
                    </aside>
                    <main class="chat">
                      <div id="messages" class="messages">
                        <div class="empty">
                          <strong>CN Code</strong>
                          <span>输入任务，CN Code 会流式回复，并在需要时调用本地工具。</span>
                        </div>
                      </div>
                      <form id="composer" class="composer">
                        <div class="modebar">
                          <div class="segmented" aria-label="模式切换">
                            <button id="planMode" class="mode-button" type="button">Plan</button>
                            <button id="doMode" class="mode-button active" type="button">Do</button>
                          </div>
                          <span id="modeHint" class="mode-hint">执行模式：可以按任务需要调用工具。</span>
                        </div>
                        <div id="planOptions" class="plan-options hidden"></div>
                        <div class="input-row">
                          <textarea id="input" placeholder="给 CN Code 发送消息..." rows="3"></textarea>
                          <button id="send" type="submit">发送</button>
                        </div>
                      </form>
                    </main>
                  </div>
                  <script src="/app.js"></script>
                </body>
                </html>
                """;
    }

    public static String css() {
        return """
                :root {
                  color-scheme: light;
                  --bg: #f7f7f8;
                  --surface: #ffffff;
                  --sidebar: #f1f1f3;
                  --text: #202123;
                  --muted: #6b7280;
                  --line: #e5e7eb;
                  --soft-line: #eef0f3;
                  --accent: #10a37f;
                  --user-bg: #e7f8f2;
                  --system-bg: #f5f5f5;
                  --error-bg: #fff1f2;
                  --error: #b42318;
                  --code-bg: #f6f8fa;
                }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  height: 100vh;
                  overflow: hidden;
                  background: var(--bg);
                  color: var(--text);
                  font: 15px/1.6 ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                }
                .shell {
                  display: grid;
                  grid-template-columns: 260px minmax(0, 1fr);
                  height: 100vh;
                  min-height: 0;
                  overflow: hidden;
                }
                .sidebar {
                  border-right: 1px solid var(--line);
                  background: var(--sidebar);
                  padding: 24px 18px;
                  height: 100vh;
                  overflow-y: auto;
                }
                .brand {
                  display: flex;
                  gap: 12px;
                  align-items: center;
                  margin-bottom: 28px;
                }
                .mark {
                  display: grid;
                  place-items: center;
                  width: 44px;
                  height: 44px;
                  border-radius: 8px;
                  background: var(--accent);
                  color: #fff;
                  font-weight: 800;
                }
                h1 {
                  margin: 0;
                  font-size: 21px;
                  letter-spacing: 0;
                }
                .brand p {
                  margin: 2px 0 0;
                  color: var(--muted);
                  font-size: 13px;
                }
                .panel {
                  padding: 14px 0;
                  border-top: 1px solid var(--soft-line);
                }
                .label {
                  color: var(--muted);
                  font-size: 12px;
                  text-transform: uppercase;
                  margin-bottom: 6px;
                }
                .value {
                  word-break: break-word;
                }
                .online { color: var(--accent); }
                .chat {
                  display: grid;
                  grid-template-rows: minmax(0, 1fr) auto;
                  min-width: 0;
                  height: 100vh;
                  min-height: 0;
                  background: var(--surface);
                }
                .messages {
                  min-height: 0;
                  overflow-y: auto;
                  padding: 32px 18px 24px;
                }
                .empty {
                  display: grid;
                  gap: 6px;
                  max-width: 760px;
                  margin: 18vh auto 0;
                  color: var(--muted);
                  text-align: center;
                }
                .message {
                  max-width: 860px;
                  margin: 0 auto 20px;
                  display: grid;
                  gap: 6px;
                }
                .message.user {
                  justify-items: end;
                }
                .message.assistant {
                  justify-items: stretch;
                }
                .message.system,
                .message.error,
                .message.tool,
                .message.permission {
                  max-width: 860px;
                }
                .message.system .body {
                  color: var(--muted);
                  background: var(--system-bg);
                  border: 1px solid var(--line);
                  border-radius: 8px;
                  padding: 8px 12px;
                  font-size: 13px;
                }
                .message.error .body {
                  color: var(--error);
                  background: var(--error-bg);
                  border: 1px solid #fecdd3;
                  border-radius: 8px;
                  padding: 10px 12px;
                }
                .role {
                  display: block;
                  color: var(--muted);
                  font-size: 12px;
                  padding: 0 4px;
                }
                .body {
                  min-width: 0;
                }
                .message.user .body {
                  max-width: min(680px, 82%);
                  padding: 10px 14px;
                  border-radius: 18px;
                  background: var(--user-bg);
                  white-space: pre-wrap;
                }
                .message.assistant .body {
                  width: 100%;
                }
                .message.tool {
                  gap: 0;
                }
                .tool-activity {
                  width: 100%;
                  display: grid;
                  grid-template-columns: auto minmax(120px, auto) 1fr auto;
                  align-items: center;
                  gap: 10px;
                  padding: 10px 12px;
                  border: 1px solid var(--line);
                  border-radius: 8px;
                  background: #fbfbfc;
                  color: var(--text);
                  text-align: left;
                  cursor: pointer;
                  font: inherit;
                }
                .tool-status {
                  width: 9px;
                  height: 9px;
                  border-radius: 999px;
                  background: var(--muted);
                }
                .message.tool.running .tool-status {
                  background: #d97706;
                }
                .message.tool.success .tool-status {
                  background: var(--accent);
                }
                .message.tool.failure .tool-status {
                  background: var(--error);
                }
                .tool-name {
                  font-weight: 800;
                }
                .tool-state {
                  color: var(--muted);
                  font-size: 13px;
                }
                .tool-summary {
                  min-width: 0;
                  color: var(--muted);
                  overflow: hidden;
                  text-overflow: ellipsis;
                  white-space: nowrap;
                }
                .tool-details {
                  display: grid;
                  gap: 10px;
                  padding: 12px;
                  border: 1px solid var(--line);
                  border-top: 0;
                  border-radius: 0 0 8px 8px;
                  background: #fff;
                }
                .tool-details.hidden {
                  display: none;
                }
                .tool-detail-label {
                  color: var(--muted);
                  font-size: 12px;
                  font-weight: 800;
                  text-transform: uppercase;
                  margin-bottom: 4px;
                }
                .tool-detail-text {
                  white-space: pre-wrap;
                  overflow-wrap: anywhere;
                  border-radius: 8px;
                  background: var(--code-bg);
                  border: 1px solid var(--line);
                  padding: 8px;
                  max-height: 260px;
                  overflow: auto;
                }
                .file-buttons {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 6px;
                }
                .file-button {
                  width: auto;
                  max-width: 100%;
                  min-height: 30px;
                  padding: 5px 9px;
                  border: 1px solid var(--line);
                  border-radius: 8px;
                  background: var(--surface);
                  color: var(--text);
                  font-weight: 700;
                  overflow: hidden;
                  text-overflow: ellipsis;
                  white-space: nowrap;
                }
                .permission-card {
                  display: grid;
                  gap: 10px;
                  padding: 12px;
                  border: 1px solid #f59e0b;
                  border-radius: 8px;
                  background: #fffbeb;
                }
                .permission-title {
                  font-weight: 800;
                  color: #92400e;
                }
                .permission-meta {
                  color: var(--muted);
                  font-size: 13px;
                  white-space: pre-wrap;
                  overflow-wrap: anywhere;
                }
                .permission-actions {
                  display: flex;
                  flex-wrap: wrap;
                  gap: 8px;
                }
                .permission-actions button {
                  width: auto;
                  min-height: 32px;
                  padding: 6px 10px;
                  border-radius: 8px;
                }
                .markdown-body {
                  color: var(--text);
                  overflow-wrap: anywhere;
                }
                .markdown-body > *:first-child { margin-top: 0; }
                .markdown-body > *:last-child { margin-bottom: 0; }
                .markdown-body h1,
                .markdown-body h2,
                .markdown-body h3 {
                  line-height: 1.25;
                  margin: 20px 0 10px;
                }
                .markdown-body h1 { font-size: 28px; }
                .markdown-body h2 { font-size: 22px; }
                .markdown-body h3 { font-size: 18px; }
                .markdown-body p {
                  margin: 0 0 12px;
                }
                .markdown-body ul,
                .markdown-body ol {
                  margin: 0 0 12px 22px;
                  padding: 0;
                }
                .markdown-body blockquote {
                  margin: 0 0 12px;
                  padding: 0 0 0 14px;
                  border-left: 3px solid var(--line);
                  color: var(--muted);
                }
                .markdown-body code {
                  background: var(--code-bg);
                  border: 1px solid var(--line);
                  border-radius: 5px;
                  padding: 1px 5px;
                  font: 13px/1.5 ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace;
                }
                .markdown-body pre {
                  margin: 0 0 14px;
                  padding: 14px;
                  overflow-x: auto;
                  background: #0f172a;
                  color: #e5e7eb;
                  border-radius: 8px;
                }
                .markdown-body pre code {
                  padding: 0;
                  border: 0;
                  background: transparent;
                  color: inherit;
                }
                .markdown-body a {
                  color: #0f766e;
                  text-decoration: underline;
                }
                .composer {
                  display: grid;
                  gap: 10px;
                  width: min(860px, calc(100% - 32px));
                  justify-self: center;
                  margin: 0 16px 18px;
                  padding: 10px;
                  border: 1px solid var(--line);
                  border-radius: 18px;
                  background: var(--surface);
                  box-shadow: 0 12px 40px rgba(15, 23, 42, .12);
                }
                .modebar {
                  display: flex;
                  align-items: center;
                  justify-content: space-between;
                  gap: 12px;
                  padding: 2px 2px 0;
                }
                .segmented {
                  display: inline-flex;
                  padding: 3px;
                  border: 1px solid var(--line);
                  border-radius: 10px;
                  background: var(--system-bg);
                }
                .mode-button {
                  width: auto;
                  min-width: 62px;
                  height: 30px;
                  padding: 0 12px;
                  border-radius: 8px;
                  background: transparent;
                  color: var(--muted);
                  font-weight: 700;
                }
                .mode-button.active {
                  background: var(--surface);
                  color: var(--text);
                  box-shadow: 0 1px 4px rgba(15, 23, 42, .10);
                }
                .mode-hint {
                  color: var(--muted);
                  font-size: 13px;
                  text-align: right;
                }
                .plan-options {
                  border: 1px solid var(--line);
                  border-radius: 12px;
                  background: #fbfbfc;
                  padding: 10px;
                }
                .plan-options.hidden {
                  display: none;
                }
                .plan-question {
                  margin-bottom: 8px;
                  color: var(--text);
                  font-weight: 700;
                }
                .plan-choice-list {
                  display: grid;
                  gap: 6px;
                  margin-bottom: 10px;
                }
                .plan-choice {
                  display: flex;
                  align-items: flex-start;
                  gap: 8px;
                  color: var(--text);
                  font-size: 14px;
                }
                .plan-choice input {
                  margin-top: 4px;
                }
                .plan-actions {
                  display: flex;
                  justify-content: flex-end;
                }
                .plan-submit {
                  width: auto;
                  min-width: 96px;
                  height: 34px;
                  padding: 0 14px;
                }
                .input-row {
                  display: grid;
                  grid-template-columns: 1fr auto;
                  gap: 10px;
                }
                textarea {
                  resize: none;
                  width: 100%;
                  min-height: 52px;
                  max-height: 180px;
                  border: 0;
                  background: transparent;
                  color: var(--text);
                  padding: 11px 12px;
                  outline: none;
                  font: inherit;
                }
                button {
                  width: 72px;
                  border: 0;
                  border-radius: 12px;
                  background: var(--accent);
                  color: #fff;
                  font-weight: 700;
                  cursor: pointer;
                }
                button:disabled {
                  opacity: .55;
                  cursor: wait;
                }
                .mode-button {
                  width: auto;
                  background: transparent;
                  color: var(--muted);
                }
                .mode-button.active {
                  background: var(--surface);
                  color: var(--text);
                }
                .tool-activity {
                  width: 100%;
                  border: 1px solid var(--line);
                  background: #fbfbfc;
                  color: var(--text);
                  font-weight: 400;
                }
                .file-button {
                  width: auto;
                  border: 1px solid var(--line);
                  background: var(--surface);
                  color: var(--text);
                }
                .plan-submit {
                  width: auto;
                }
                .permission-actions button {
                  width: auto;
                }
                @media (max-width: 760px) {
                  .shell { grid-template-columns: 1fr; }
                  .sidebar { display: none; }
                  .messages { padding: 18px 14px 20px; }
                  .composer {
                    width: calc(100% - 28px);
                    margin: 0 14px 14px;
                  }
                  .modebar {
                    align-items: flex-start;
                    flex-direction: column;
                  }
                  .mode-hint {
                    text-align: left;
                  }
                  .message.user .body {
                    max-width: 92%;
                  }
                }
                """;
    }

    public static String js() {
        return """
                const messagesEl = document.querySelector('#messages');
                const form = document.querySelector('#composer');
                const input = document.querySelector('#input');
                const send = document.querySelector('#send');
                const modelEl = document.querySelector('#model');
                const statusEl = document.querySelector('#status');
                const countEl = document.querySelector('#messageCount');
                const toolsEl = document.querySelector('#tools');
                const planMode = document.querySelector('#planMode');
                const doMode = document.querySelector('#doMode');
                const modeHint = document.querySelector('#modeHint');
                const planOptionsEl = document.querySelector('#planOptions');

                let busy = false;
                let planOnly = false;
                let currentPlanOptions = null;
                const toolActivities = [];
                const slashCommands = [
                  { name: 'clear', aliases: [], description: 'Clear current chat view and history' },
                  { name: 'compact', aliases: ['c'], description: 'Compact current conversation' },
                  { name: 'commit', aliases: [], description: 'Analyze changes and create a careful commit' },
                  { name: 'do', aliases: [], description: 'Return to execution mode' },
                  { name: 'help', aliases: ['h', '?'], description: 'Show slash commands' },
                  { name: 'memory', aliases: [], description: 'Manage memories' },
                  { name: 'permission', aliases: ['perm'], description: 'Show permission mode' },
                  { name: 'plan', aliases: ['p'], description: 'Enter plan-only mode' },
                  { name: 'resume', aliases: ['r'], description: 'Resume a saved session' },
                  { name: 'review', aliases: [], description: 'Inject a code review prompt' },
                  { name: 'session', aliases: [], description: 'Manage chat sessions' },
                  { name: 'skills', aliases: [], description: 'List installed skills' },
                  { name: 'status', aliases: ['s'], description: 'Show runtime status' },
                  { name: 'test', aliases: [], description: 'Find and run relevant tests' }
                ];

                async function loadStatus() {
                  const res = await fetch('/api/status');
                  const data = await res.json();
                  modelEl.textContent = `${data.protocol}/${data.model}`;
                  countEl.textContent = data.messageCount;
                  toolsEl.textContent = `Tools: ${data.tools} · MCP: ${data.mcpServers || 0}/${data.mcpTools || 0}`;
                  updateMode(Boolean(data.planOnly));
                  statusEl.textContent = 'online';
                }

                function clearEmpty() {
                  const empty = messagesEl.querySelector('.empty');
                  if (empty) empty.remove();
                }

                function updateMode(nextPlanOnly) {
                  planOnly = nextPlanOnly;
                  planMode.classList.toggle('active', planOnly);
                  doMode.classList.toggle('active', !planOnly);
                  modeHint.textContent = planOnly
                    ? '计划模式：先澄清需求，只允许读取和搜索。'
                    : '执行模式：可以按任务需要调用工具。';
                  input.placeholder = planOnly
                    ? '描述你的想法，CN Code 会先一步步问清需求...'
                    : '给 CN Code 发送消息...';
                  if (!planOnly) {
                    clearPlanOptions();
                  }
                }

                function clearPlanOptions() {
                  currentPlanOptions = null;
                  planOptionsEl.innerHTML = '';
                  planOptionsEl.classList.add('hidden');
                }

                function commandPrefix() {
                  const value = input.value.trim();
                  if (!value.startsWith('/') || value.includes(' ')) return null;
                  return value.slice(1).toLowerCase();
                }

                function matchingSlashCommands() {
                  const prefix = commandPrefix();
                  if (prefix === null) return [];
                  return slashCommands
                    .filter(command => command.name.startsWith(prefix) || command.aliases.some(alias => alias.startsWith(prefix)))
                    .sort((left, right) => left.name.localeCompare(right.name))
                    .slice(0, 8);
                }

                function updateSlashMenu() {
                  const matches = matchingSlashCommands();
                  if (matches.length === 0) {
                    if (!currentPlanOptions) clearPlanOptions();
                    return;
                  }
                  currentPlanOptions = null;
                  planOptionsEl.innerHTML = `
                    <div class="plan-question">Slash commands</div>
                    <div class="plan-choice-list">
                      ${matches.map(command => `<div class="plan-choice"><span>/${escapeHtml(command.name)} - ${escapeHtml(command.description)}</span></div>`).join('')}
                    </div>
                  `;
                  planOptionsEl.classList.remove('hidden');
                }

                function autocompleteSlashCommand() {
                  const matches = matchingSlashCommands();
                  if (matches.length === 0) return false;
                  input.value = `/${matches[0].name} `;
                  clearPlanOptions();
                  return true;
                }

                function addMessage(role, text = '') {
                  clearEmpty();
                  const el = document.createElement('div');
                  el.className = `message ${role}`;
                  const label = document.createElement('span');
                  label.className = 'role';
                  label.textContent = role === 'user' ? 'You' : role === 'assistant' ? 'CN Code' : 'System';
                  const body = document.createElement('div');
                  body.className = role === 'assistant' ? 'body markdown-body' : 'body';
                  body.textContent = text;
                  el.append(label, body);
                  messagesEl.appendChild(el);
                  scrollBottom();
                  return body;
                }

                function escapeHtml(value) {
                  return String(value ?? '')
                    .replaceAll('&', '&amp;')
                    .replaceAll('<', '&lt;')
                    .replaceAll('>', '&gt;')
                    .replaceAll('"', '&quot;')
                    .replaceAll("'", '&#39;');
                }

                function safeHref(value) {
                  const text = String(value ?? '').trim();
                  if (text.startsWith('http://') || text.startsWith('https://') || text.startsWith('#')) {
                    return text;
                  }
                  return '#';
                }

                function renderInlineMarkdown(text) {
                  let html = escapeHtml(text);
                  html = html.replace(/`([^`]+)`/g, '<code>$1</code>');
                  html = html.replace(/\\*\\*([^*]+)\\*\\*/g, '<strong>$1</strong>');
                  html = html.replace(/\\[([^\\]]+)\\]\\(([^)]+)\\)/g, (_, label, href) => {
                    return `<a href="${escapeHtml(safeHref(href))}" target="_blank" rel="noreferrer">${label}</a>`;
                  });
                  return html;
                }

                function renderMarkdown(markdown) {
                  const source = String(markdown ?? '');
                  const parts = source.split(/```/);
                  let html = '';
                  for (let i = 0; i < parts.length; i++) {
                    if (i % 2 === 1) {
                      const code = parts[i].replace(/^\\w+\\n/, '');
                      html += `<pre><code>${escapeHtml(code)}</code></pre>`;
                      continue;
                    }
                    html += renderMarkdownBlock(parts[i]);
                  }
                  return html || '<p></p>';
                }

                function parsePlanOptions(markdown) {
                  const source = String(markdown ?? '');
                  const match = source.match(/```cncode-options\\s*([\\s\\S]*?)```/);
                  if (!match) {
                    return { text: source, options: null };
                  }
                  try {
                    const parsed = JSON.parse(match[1].trim());
                    if (!Array.isArray(parsed.options) || parsed.options.length === 0) {
                      return { text: source, options: null };
                    }
                    const options = parsed.options
                      .filter(option => option && String(option.label ?? '').trim())
                      .map((option, index) => ({
                        id: String(option.id ?? `option_${index + 1}`),
                        label: String(option.label).trim()
                      }));
                    if (options.length === 0) {
                      return { text: source, options: null };
                    }
                    return {
                      text: source.replace(match[0], '').trim(),
                      options: {
                        question: String(parsed.question ?? '').trim(),
                        multiple: parsed.multiple !== false,
                        options
                      }
                    };
                  } catch (error) {
                    return { text: source, options: null };
                  }
                }

                function renderPlanOptions(parsed) {
                  if (!parsed || !parsed.options || !planOnly) {
                    clearPlanOptions();
                    return;
                  }
                  currentPlanOptions = parsed.options;
                  const type = currentPlanOptions.multiple ? 'checkbox' : 'radio';
                  const name = `plan_option_${Date.now()}`;
                  const choices = currentPlanOptions.options.map(option => `
                    <label class="plan-choice">
                      <input type="${type}" name="${name}" value="${escapeHtml(option.id)}">
                      <span>${escapeHtml(option.label)}</span>
                    </label>
                  `).join('');
                  planOptionsEl.innerHTML = `
                    <div class="plan-question">${escapeHtml(currentPlanOptions.question || '请选择你的需求：')}</div>
                    <div class="plan-choice-list">${choices}</div>
                    <div class="plan-actions"><button class="plan-submit" type="button">提交选择</button></div>
                  `;
                  planOptionsEl.classList.remove('hidden');
                  planOptionsEl.querySelector('.plan-submit').addEventListener('click', submitPlanSelection);
                }

                function submitPlanSelection() {
                  if (!currentPlanOptions) return;
                  const checked = Array.from(planOptionsEl.querySelectorAll('input:checked'));
                  if (checked.length === 0) return;
                  const selected = checked.map(input => {
                    return currentPlanOptions.options.find(option => option.id === input.value);
                  }).filter(Boolean);
                  if (selected.length === 0) return;
                  const message = '我选择：\\n' + selected.map(option => `- ${option.label}`).join('\\n');
                  clearPlanOptions();
                  sendMessage(message);
                }

                function createToolActivity(toolName) {
                  clearEmpty();
                  const wrapper = document.createElement('div');
                  wrapper.className = 'message tool running';
                  const header = document.createElement('button');
                  header.className = 'tool-activity';
                  header.type = 'button';
                  header.innerHTML = `
                    <span class="tool-status"></span>
                    <span class="tool-name">${escapeHtml(toolName)}</span>
                    <span class="tool-summary">正在运行...</span>
                    <span class="tool-state">运行中</span>
                  `;
                  const details = document.createElement('div');
                  details.className = 'tool-details hidden';
                  header.addEventListener('click', () => details.classList.toggle('hidden'));
                  wrapper.append(header, details);
                  messagesEl.appendChild(wrapper);
                  const activity = { toolName, wrapper, header, details, complete: false };
                  toolActivities.push(activity);
                  scrollBottom();
                  return activity;
                }

                function finishToolActivity(payload) {
                  const activity = [...toolActivities].reverse()
                    .find(item => !item.complete && item.toolName === payload.toolName)
                    || [...toolActivities].reverse().find(item => !item.complete)
                    || createToolActivity(payload.toolName || 'tool');
                  activity.complete = true;
                  activity.wrapper.classList.remove('running');
                  activity.wrapper.classList.add(payload.success ? 'success' : 'failure');
                  const summary = payload.summary || payload.error || payload.output || '无摘要';
                  activity.header.querySelector('.tool-summary').textContent = summary;
                  activity.header.querySelector('.tool-state').textContent = payload.success ? '完成' : '失败';
                  activity.details.innerHTML = renderToolDetails(payload);
                  activity.details.querySelectorAll('.file-button').forEach(button => {
                    button.addEventListener('click', event => {
                      event.stopPropagation();
                      openFile(button.dataset.path, false);
                    });
                  });
                  scrollBottom();
                }

                function renderToolDetails(payload) {
                  const blocks = [];
                  if (payload.summary) blocks.push(toolDetailBlock('摘要', payload.summary));
                  if (payload.output) blocks.push(toolDetailBlock('输出', payload.output));
                  if (payload.error) blocks.push(toolDetailBlock('错误', payload.error));
                  const paths = extractToolPaths(payload);
                  if (paths.length > 0) {
                    blocks.push(`
                      <div>
                        <div class="tool-detail-label">文件</div>
                        <div class="file-buttons">
                          ${paths.map(path => `<button class="file-button" type="button" data-path="${escapeHtml(path)}">${escapeHtml(path)}</button>`).join('')}
                        </div>
                      </div>
                    `);
                  }
                  return blocks.join('') || toolDetailBlock('详情', JSON.stringify(payload, null, 2));
                }

                function toolDetailBlock(label, text) {
                  return `
                    <div>
                      <div class="tool-detail-label">${escapeHtml(label)}</div>
                      <div class="tool-detail-text">${escapeHtml(text)}</div>
                    </div>
                  `;
                }

                function extractToolPaths(payload) {
                  const candidates = [];
                  collectPathCandidates(payload, candidates);
                  for (const key of ['summary', 'output', 'error']) {
                    extractPathsFromText(payload[key], candidates);
                  }
                  return [...new Set(candidates)]
                    .map(path => path.trim())
                    .filter(path => path && !path.startsWith('http://') && !path.startsWith('https://'))
                    .slice(0, 12);
                }

                function collectPathCandidates(value, candidates) {
                  if (!value) return;
                  if (typeof value === 'string') return;
                  if (Array.isArray(value)) {
                    value.forEach(item => collectPathCandidates(item, candidates));
                    return;
                  }
                  if (typeof value === 'object') {
                    for (const [key, nested] of Object.entries(value)) {
                      if (['path', 'file'].includes(key) && typeof nested === 'string') {
                        candidates.push(nested);
                      } else {
                        collectPathCandidates(nested, candidates);
                      }
                    }
                  }
                }

                function extractPathsFromText(text, candidates) {
                  if (!text) return;
                  const source = String(text);
                  const windows = source.match(/[A-Za-z]:\\\\[^\\s"'<>|]+/g) || [];
                  const relatives = source.match(/(?:^|\\s)(?:\\.\\\\|\\.\\/|[\\w.-]+[\\\\/])[\\w.\\\\/ -]+/g) || [];
                  windows.forEach(path => candidates.push(path.replace(/[.,;，。；]+$/, '')));
                  relatives.forEach(path => candidates.push(path.trim().replace(/[.,;，。；]+$/, '')));
                }

                async function openFile(path, confirmedExternal) {
                  try {
                    const response = await fetch('/api/open-file', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ path, confirmedExternal })
                    });
                    const data = await response.json();
                    if (data.success) {
                      addMessage('system', data.message || '已打开文件');
                      return;
                    }
                    if (data.external && !confirmedExternal) {
                      const ok = confirm(`${data.message}\\n\\n是否继续打开这个项目外文件？`);
                      if (ok) {
                        await openFile(path, true);
                      }
                      return;
                    }
                    addMessage('error', data.message || '打开文件失败');
                  } catch (error) {
                    addMessage('error', `打开文件失败：${error.message}`);
                  }
                }

                function showPermissionRequest(payload) {
                  clearEmpty();
                  const wrapper = document.createElement('div');
                  wrapper.className = 'message permission';
                  wrapper.innerHTML = `
                    <div class="permission-card">
                      <div class="permission-title">需要权限确认：${escapeHtml(payload.toolName)}</div>
                      <div class="permission-meta">原因：${escapeHtml(payload.reason || '')}</div>
                      <div class="permission-meta">参数：${escapeHtml(payload.argumentSummary || '')}</div>
                      ${payload.path ? `<div class="permission-meta">路径：${escapeHtml(payload.path)}</div>` : ''}
                      <div class="permission-actions">
                        <button type="button" data-decision="once">本次允许</button>
                        <button type="button" data-decision="session">本会话允许</button>
                        <button type="button" data-decision="always">永久允许</button>
                        <button type="button" data-decision="deny">拒绝</button>
                      </div>
                    </div>
                  `;
                  wrapper.querySelectorAll('button[data-decision]').forEach(button => {
                    button.addEventListener('click', () => submitPermission(button.dataset.decision, wrapper));
                  });
                  messagesEl.appendChild(wrapper);
                  scrollBottom();
                }

                async function submitPermission(decision, wrapper) {
                  wrapper.querySelectorAll('button').forEach(button => button.disabled = true);
                  try {
                    const response = await fetch('/api/permission', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ decision })
                    });
                    const data = await response.json();
                    if (!data.success) {
                      addMessage('error', data.message || '权限选择提交失败');
                      return;
                    }
                    wrapper.querySelector('.permission-title').textContent = `权限选择已提交：${decision}`;
                  } catch (error) {
                    addMessage('error', `权限选择提交失败：${error.message}`);
                  }
                }

                function renderMarkdownBlock(block) {
                  const lines = block.split(/\\r?\\n/);
                  const html = [];
                  let listType = null;

                  function closeList() {
                    if (listType) {
                      html.push(`</${listType}>`);
                      listType = null;
                    }
                  }

                  for (const rawLine of lines) {
                    const line = rawLine.trimEnd();
                    const trimmed = line.trim();
                    if (!trimmed) {
                      closeList();
                      continue;
                    }
                    let match;
                    if ((match = trimmed.match(/^(#{1,3})\\s+(.+)$/))) {
                      closeList();
                      const level = match[1].length;
                      html.push(`<h${level}>${renderInlineMarkdown(match[2])}</h${level}>`);
                    } else if ((match = trimmed.match(/^>\\s?(.+)$/))) {
                      closeList();
                      html.push(`<blockquote>${renderInlineMarkdown(match[1])}</blockquote>`);
                    } else if ((match = trimmed.match(/^[-*]\\s+(.+)$/))) {
                      if (listType !== 'ul') {
                        closeList();
                        listType = 'ul';
                        html.push('<ul>');
                      }
                      html.push(`<li>${renderInlineMarkdown(match[1])}</li>`);
                    } else if ((match = trimmed.match(/^\\d+\\.\\s+(.+)$/))) {
                      if (listType !== 'ol') {
                        closeList();
                        listType = 'ol';
                        html.push('<ol>');
                      }
                      html.push(`<li>${renderInlineMarkdown(match[1])}</li>`);
                    } else {
                      closeList();
                      html.push(`<p>${renderInlineMarkdown(trimmed)}</p>`);
                    }
                  }
                  closeList();
                  return html.join('');
                }

                function setMarkdown(element, markdown) {
                  element.innerHTML = renderMarkdown(markdown);
                }

                function scrollBottom() {
                  messagesEl.scrollTop = messagesEl.scrollHeight;
                }

                function parseEvents(buffer, onEvent) {
                  let index;
                  while ((index = buffer.indexOf('\\n\\n')) >= 0) {
                    const raw = buffer.slice(0, index);
                    buffer = buffer.slice(index + 2);
                    const eventLine = raw.split('\\n').find(line => line.startsWith('event:'));
                    const dataLine = raw.split('\\n').find(line => line.startsWith('data:'));
                    if (eventLine && dataLine) {
                      onEvent(eventLine.slice(6).trim(), dataLine.slice(5).trim());
                    }
                  }
                  return buffer;
                }

                async function sendMessage(message, options = {}) {
                  if (busy) return;
                  busy = true;
                  send.disabled = true;
                  planMode.disabled = true;
                  doMode.disabled = true;
                  if (!options.hiddenUserMessage) {
                    addMessage('user', message);
                  }
                  const assistant = addMessage('assistant', '');
                  let assistantRaw = '';
                  try {
                    const response = await fetch('/api/chat', {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json' },
                      body: JSON.stringify({ message })
                    });
                    const reader = response.body.getReader();
                    const decoder = new TextDecoder();
                    let buffer = '';
                    while (true) {
                      const { value, done } = await reader.read();
                      if (done) break;
                      buffer += decoder.decode(value, { stream: true });
                      buffer = parseEvents(buffer, (event, data) => {
                        if (event === 'delta') {
                          assistantRaw += JSON.parse(data).text;
                          const parsed = parsePlanOptions(assistantRaw);
                          setMarkdown(assistant, parsed.text);
                          renderPlanOptions(parsed);
                          scrollBottom();
                        } else if (event === 'tool_start') {
                          const payload = JSON.parse(data);
                          createToolActivity(payload.toolName);
                        } else if (event === 'tool_result') {
                          const payload = JSON.parse(data);
                          finishToolActivity(payload);
                        } else if (event === 'system') {
                          if (!assistantRaw && assistant.closest('.message')) {
                            assistant.closest('.message').remove();
                          }
                          addMessage('system', JSON.parse(data).message || '');
                        } else if (event === 'mode') {
                          updateMode(Boolean(JSON.parse(data).planOnly));
                        } else if (event === 'compact') {
                          if (!assistantRaw && assistant.closest('.message')) {
                            assistant.closest('.message').remove();
                          }
                          addMessage('system', JSON.parse(data).message || 'Context compacted');
                        } else if (event === 'clear') {
                          messagesEl.innerHTML = '';
                          addMessage('system', 'Chat history cleared.');
                        } else if (event === 'permission_request') {
                          showPermissionRequest(JSON.parse(data));
                        } else if (event === 'thinking') {
                          // Thinking 暂不渲染，保留给后续 UI。
                        } else if (event === 'turn_complete' || event === 'loop_complete') {
                          // 状态事件保留给后续 UI 强化，当前界面安全忽略。
                        } else if (event === 'cancelled') {
                          addMessage('system', JSON.parse(data).message || 'Cancelled');
                        } else if (event === 'timeout') {
                          addMessage('error', JSON.parse(data).message || 'Timeout');
                        } else if (event === 'error') {
                          addMessage('error', JSON.parse(data).message);
                        }
                      });
                    }
                    await loadStatus();
                  } catch (error) {
                    addMessage('error', `请求失败：${error.message}`);
                  } finally {
                    busy = false;
                    send.disabled = false;
                    planMode.disabled = false;
                    doMode.disabled = false;
                    input.focus();
                  }
                }

                form.addEventListener('submit', event => {
                  event.preventDefault();
                  const message = input.value.trim();
                  if (!message) return;
                  input.value = '';
                  clearPlanOptions();
                  sendMessage(message);
                });

                input.addEventListener('keydown', event => {
                  if (event.key === 'Tab' && autocompleteSlashCommand()) {
                    event.preventDefault();
                    return;
                  }
                  if (event.key === 'Enter' && !event.shiftKey) {
                    event.preventDefault();
                    form.requestSubmit();
                  }
                });

                input.addEventListener('input', updateSlashMenu);

                planMode.addEventListener('click', () => {
                  if (!planOnly) {
                    sendMessage('/plan', { hiddenUserMessage: true });
                  }
                });

                doMode.addEventListener('click', () => {
                  if (planOnly) {
                    sendMessage('/do', { hiddenUserMessage: true });
                  }
                });

                loadStatus().catch(error => {
                  statusEl.textContent = 'offline';
                  addMessage('error', `状态读取失败：${error.message}`);
                });
                input.focus();
                """;
    }
}
