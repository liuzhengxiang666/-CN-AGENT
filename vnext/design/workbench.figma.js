await Promise.all(
  [
    { family: "Noto Sans SC", style: "Regular" },
    { family: "IBM Plex Mono", style: "Regular" },
    { family: "Inter", style: "Regular" },
  ].map((f) => figma.loadFontAsync(f)),
);
const buttonSet = await figma.importComponentSetByKeyAsync(
  "cc8b558dc7d9684011b6b99ce8e6509399bc836b",
);
const textareaSet = await figma.importComponentSetByKeyAsync(
  "45a146b674f858a2c626c056c58ddf0f67e192ee",
);
const page = await figma.getNodeByIdAsync("0:1");
await figma.setCurrentPageAsync(page);
page.name = "01 · Workbench";
const collection = await figma.variables.getVariableCollectionByIdAsync(
  "VariableCollectionId:3:2",
);
const vars = {};
for (const [name, id] of Object.entries({
  canvas: "VariableID:3:4",
  sidebar: "VariableID:3:6",
  surface: "VariableID:3:8",
  hover: "VariableID:3:10",
  ink: "VariableID:3:12",
  muted: "VariableID:3:14",
  line: "VariableID:3:16",
  action: "VariableID:3:18",
  onAction: "VariableID:3:20",
}))
  vars[name] = await figma.variables.getVariableByIdAsync(id);
const spaces = {};
for (const [n, id] of Object.entries({
  8: "VariableID:3:22",
  12: "VariableID:3:23",
  16: "VariableID:3:24",
  24: "VariableID:3:25",
  32: "VariableID:3:26",
}))
  spaces[n] = await figma.variables.getVariableByIdAsync(id);
const fill = (n, key) => {
  n.fills = [
    figma.variables.setBoundVariableForPaint(
      { type: "SOLID", color: { r: 0, g: 0, b: 0 } },
      "color",
      vars[key],
    ),
  ];
};
const line = (n) => {
  n.strokes = [
    figma.variables.setBoundVariableForPaint(
      { type: "SOLID", color: { r: 0, g: 0, b: 0 } },
      "color",
      vars.line,
    ),
  ];
  n.strokeWeight = 1;
};
function box(parent, name, dir, w, h, bg, pad = 0, gap = 16) {
  const n = figma.createAutoLayout(dir);
  n.name = name;
  parent.appendChild(n);
  n.resize(w, h);
  n.layoutSizingHorizontal = "FIXED";
  n.layoutSizingVertical = "FIXED";
  n.itemSpacing = gap;
  if (spaces[gap]) n.setBoundVariable("itemSpacing", spaces[gap]);
  for (const k of [
    "paddingTop",
    "paddingRight",
    "paddingBottom",
    "paddingLeft",
  ]) {
    n[k] = pad;
    if (spaces[pad]) n.setBoundVariable(k, spaces[pad]);
  }
  fill(n, bg);
  return n;
}
function text(parent, value, size = 14, tone = "ink", mono = false, width) {
  const n = figma.createText();
  n.fontName = {
    family: mono ? "IBM Plex Mono" : "Noto Sans SC",
    style: "Regular",
  };
  n.fontSize = size;
  n.lineHeight = { unit: "PERCENT", value: 155 };
  n.characters = value;
  fill(n, tone);
  parent.appendChild(n);
  if (width) {
    n.textAutoResize = "HEIGHT";
    n.resize(width, n.height);
    n.layoutSizingHorizontal = "FIXED";
  }
  return n;
}
function button(parent, label, primary = false) {
  const i = buttonSet.children[0].createInstance();
  parent.appendChild(i);
  i.setProperties({
    "Label#2:0": label,
    "Has Icon End#4:64": false,
    "Has Icon Start#4:128": false,
  });
  fill(i, primary ? "action" : "surface");
  for (const t of i.findAllWithCriteria({ types: ["TEXT"] }))
    fill(t, primary ? "onAction" : "ink");
  return i;
}
const roots = [];
for (const [mode, modeId, x] of [
  ["Dark", "3:0", 120],
  ["Light", "3:1", 1680],
]) {
  const root = box(
    page,
    "CN Code / " + mode,
    "HORIZONTAL",
    1440,
    920,
    "canvas",
    0,
    0,
  );
  root.x = x;
  root.y = 120;
  root.setExplicitVariableModeForCollection(collection, modeId);
  roots.push(root);
  const nav = box(root, "Sessions", "VERTICAL", 236, 920, "sidebar", 24, 24);
  text(nav, "CN / CODE", 18, "ink", true);
  text(nav, "个人编程工作台", 12, "muted");
  button(nav, "＋  新建会话", true);
  text(nav, "会话", 11, "muted");
  const selected = box(
    nav,
    "Selected session",
    "VERTICAL",
    188,
    82,
    "surface",
    12,
    8,
  );
  selected.cornerRadius = 8;
  text(selected, "phone-number 基线", 13);
  text(selected, "CN Code · 记忆关闭", 11, "muted");
  text(nav, "KV store gRPC", 13);
  text(nav, "DSH · 记忆关闭", 11, "muted");
  box(nav, "Flexible space", "VERTICAL", 188, 408, "sidebar", 0, 0);
  text(
    nav,
    "外观   " + (mode === "Dark" ? "深色 / 浅色" : "浅色 / 深色"),
    12,
    "muted",
  );
  text(nav, "本地数据 · vNext 0.2", 11, "muted");
  const main = box(root, "Workspace", "VERTICAL", 1204, 920, "canvas", 0, 0);
  const header = box(main, "Toolbar", "HORIZONTAL", 1204, 76, "canvas", 24, 24);
  line(header);
  text(header, "cn-code-vnext  /  phone-number", 14);
  text(header, "CN Code  ▾", 12, "muted");
  text(header, "deepseek-v4-flash", 12, "muted", true);
  const body = box(
    main,
    "Conversation and run details",
    "HORIZONTAL",
    1204,
    844,
    "canvas",
    0,
    0,
  );
  const chat = box(
    body,
    "Conversation",
    "VERTICAL",
    860,
    844,
    "canvas",
    32,
    24,
  );
  text(chat, "实现、检查，再继续。", 26);
  text(chat, "当前工作区  /app  ·  无记忆基线", 12, "muted", true);
  const user = box(chat, "User request", "VERTICAL", 796, 80, "surface", 16, 8);
  user.cornerRadius = 12;
  text(user, "运行 phone-number 的无记忆基线，保留真实记录。", 14);
  text(user, "设计示意 · 实际结果见右侧验证记录", 11, "muted");
  text(chat, "CN CODE", 11, "muted", true);
  text(
    chat,
    "运行已停止：模型提供方拒绝了当前凭据。\n请在本机配置中更新凭据后重试。",
    15,
    "ink",
    false,
    760,
  );
  const trace = box(
    chat,
    "Execution trace",
    "VERTICAL",
    796,
    132,
    "canvas",
    16,
    12,
  );
  line(trace);
  trace.cornerRadius = 8;
  text(trace, "运行轨迹", 12, "muted");
  text(trace, "01   准备工作区              已结束", 12, "ink", true);
  text(trace, "02   请求模型                HTTP 401", 12, "ink", true);
  text(trace, "03   交付验收                尚未执行", 12, "muted", true);
  box(chat, "Flexible space", "VERTICAL", 796, 94, "canvas", 0, 0);
  const composer = box(
    chat,
    "Composer",
    "VERTICAL",
    796,
    142,
    "surface",
    16,
    12,
  );
  composer.cornerRadius = 14;
  const area = textareaSet.children[0].createInstance();
  composer.appendChild(area);
  area.setProperties({
    "Has Label#57:0": false,
    "Has Description#611:14": false,
    "Has Error#72:6": false,
    "Value#630:0": "描述任务，或继续当前会话…",
  });
  area.resize(760, 52);
  for (const n of area.findAllWithCriteria({ types: ["TEXT"] }))
    fill(n, "muted");
  for (const n of area.findAllWithCriteria({ types: ["FRAME"] }))
    fill(n, "surface");
  fill(area, "surface");
  const actions = box(
    composer,
    "Composer actions",
    "HORIZONTAL",
    760,
    40,
    "surface",
    0,
    16,
  );
  text(actions, "CN Code  ·  记忆关闭", 12, "muted");
  button(actions, "发送 ↑", true);
  text(chat, "切换内核将创建新会话；当前会话可以再次打开。", 11, "muted");
  const details = box(
    body,
    "Run details",
    "VERTICAL",
    344,
    844,
    "sidebar",
    24,
    24,
  );
  line(details);
  text(details, "运行记录", 15);
  text(details, "执行  失败", 13);
  text(details, "验收  环境错误", 13);
  text(details, "提供方鉴权失败，不能据此\n判断任务的编码质量。", 12, "muted");
  text(details, "消耗与耗时", 11, "muted");
  for (const row of [
    "输入 token        未知",
    "输出 token        未知",
    "模型请求          1",
    "人工等待          0 ms",
    "记忆              关闭",
  ])
    text(details, row, 12, "ink", true);
  text(details, "交付物", 11, "muted");
  text(details, "尚无已验收交付物", 13, "muted");
  text(details, "运行事件可展开，\n执行结束不等于验收通过。", 12, "muted");
}
return {
  frames: roots.map((n) => ({
    id: n.id,
    name: n.name,
    width: n.width,
    height: n.height,
    textCount: n.findAllWithCriteria({ types: ["TEXT"] }).length,
    instances: n.findAllWithCriteria({ types: ["INSTANCE"] }).length,
  })),
  createdNodeIds: roots.flatMap((n) => [
    n.id,
    ...n.findAll(() => true).map((c) => c.id),
  ]),
  mutatedNodeIds: [page.id],
};
