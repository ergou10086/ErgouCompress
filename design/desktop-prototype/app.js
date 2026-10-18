// 独立交互原型：状态均为模拟，不调用生产后端，不读取媒体内容。
/* 原型只驱动模拟交互，不调用后端、不读取媒体内容、不上传文件。 */
"use strict";
const $ = (s) => document.querySelector(s);
const icon = (n) => `<svg aria-hidden="true"><use href="#${n}"/></svg>`;
const esc = (v) =>
  String(v).replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const fmt = (n) => n.toFixed(2).replace(/0$/, "").replace(/\.$/, "") + " GB";
const badge = (s, t = "") => `<span class="badge ${t}">${esc(s)}</span>`;
const track = (kind, name, spec, action = "copy", def = false) => ({
  kind,
  name,
  spec,
  action,
  default: def,
});
const examples = () => [
  {
    id: 1,
    name: "周末山行_4K.mp4",
    size: 3.2,
    duration: "09:41",
    resolution: "3840 × 2160",
    fps: "29.97",
    codec: "H.264",
    color: "green",
    eligible: true,
    streams: [
      track("video", "主视频", "H.264 · 4K · 29.97 fps · SDR", "encode", true),
      track("audio", "音轨 1 · 中文", "AAC · 48 kHz · 立体声", "copy", true),
    ],
  },
  {
    id: 2,
    name: "桌面录屏_课程.mp4",
    size: 1.86,
    duration: "37:15",
    resolution: "1920 × 1080",
    fps: "60",
    codec: "H.264",
    color: "blue",
    eligible: true,
    streams: [
      track("video", "主视频", "H.264 · 1080p · 60 fps", "encode", true),
      track("audio", "音轨 1 · 原声", "AAC · 48 kHz · 立体声", "copy", true),
      track("audio", "音轨 2 · 解说", "AAC · 44.1 kHz · 立体声"),
    ],
  },
  {
    id: 3,
    name: "海边_日落.mov",
    size: 0.94,
    duration: "04:30",
    resolution: "3840 × 2160",
    fps: "25",
    codec: "H.264",
    color: "sand",
    eligible: true,
    streams: [
      track("video", "主视频", "H.264 · 4K · 25 fps · SDR", "encode", true),
      track("audio", "音轨 1", "AAC · 48 kHz · 立体声", "copy", true),
    ],
  },
  {
    id: 4,
    name: "旅行_精选.mkv",
    size: 2.4,
    duration: "18:12",
    resolution: "2560 × 1440",
    fps: "23.976",
    codec: "HEVC",
    color: "green",
    eligible: true,
    streams: [
      track(
        "video",
        "主视频",
        "HEVC · 1440p · 23.976 fps · SDR",
        "encode",
        true,
      ),
      track("audio", "音轨 1 · 中文", "AAC · 48 kHz · 立体声", "copy", true),
      track("subtitle", "字幕 · 中文", "ASS 文字字幕"),
      track("attachment", "字体附件", "TTF · 字幕字体"),
    ],
  },
  {
    id: 5,
    name: "演出_HDR10.mkv",
    size: 8.6,
    duration: "42:39",
    resolution: "3840 × 2160",
    fps: "25",
    codec: "HEVC · HDR",
    color: "rose",
    eligible: false,
    reason: "HDR 色彩与 10bit 路径尚未验证",
    streams: [],
  },
  {
    id: 6,
    name: "封面音频.m4a",
    size: 0.082,
    duration: "08:20",
    resolution: "仅附图",
    fps: "—",
    codec: "AAC",
    color: "gray",
    eligible: false,
    reason: "没有真实主视频，当前视频入口不支持",
    streams: [],
  },
  {
    id: 7,
    name: "活动记录_1080p.mp4",
    size: 1.1,
    duration: "12:01",
    resolution: "1920 × 1080",
    fps: "30",
    codec: "H.264",
    color: "blue",
    eligible: true,
    streams: [
      track("video", "主视频", "H.264 · 1080p · 30 fps", "encode", true),
      track(
        "audio",
        "音轨 1 · 环绕声",
        "AAC · 48 kHz · 5.1 声道",
        "copy",
        true,
      ),
    ],
  },
];
const histories = [
  {
    id: "H1",
    title: "周末山行 · 4 个文件",
    time: "今天 10:42",
    before: "8.56 GB",
    after: "3.78 GB",
    status: "已完成",
    backup: true,
  },
  {
    id: "H2",
    title: "课程录屏 · 6 个文件",
    time: "昨天 21:18",
    before: "12.4 GB",
    after: "5.9 GB",
    status: "已完成",
    backup: true,
  },
  {
    id: "H3",
    title: "旅行整理 · 3 个文件",
    time: "昨天 16:03",
    before: "6.8 GB",
    after: "3.1 GB",
    status: "需处理",
    backup: true,
  },
  {
    id: "H4",
    title: "活动记录 · 2 个文件",
    time: "10 月 15 日",
    before: "4.2 GB",
    after: "2.1 GB",
    status: "已完成",
    backup: true,
  },
];
const state = {
  view: "workspace",
  step: 1,
  files: examples(),
  selected: new Set([1, 2, 4, 7]),
  search: "",
  filter: "all",
  preset: "balanced",
  quality: 23,
  backend: "X264",
  container: "MKV",
  resize: "keep",
  fps: "keep",
  mode: "COPY",
  output: "D:\\媒体\\压缩输出",
  ack: false,
  smaller: true,
  metadata: true,
  chapters: true,
  operation: "ENCODE",
  historyFilter: "all",
  historySearch: "",
  queue: {
    progress: 46,
    status: "running",
    playing: false,
    pauseAfter: false,
    cancelled: false,
  },
  storage: {
    cache: 3.1,
    logs: 0.148,
    candidates: 0.64,
    backups: 28.6,
    root: "C:\\ErgouCompress\\ErgouCompressData",
  },
  cleanup: { caches: true, logs: true, candidates: false, backups: false },
  workspaceD: true,
  cacheDays: 30,
  logDays: 14,
  backupDays: 30,
};
let toastTimer, simulationTimer, migrationTimer, activeFile;
const chosen = () =>
  state.files.filter((f) => state.selected.has(f.id) && f.eligible);
const total = () => chosen().reduce((s, f) => s + f.size, 0);
const opts = (values, current) =>
  values
    .map(
      ([v, l]) =>
        `<option value="${esc(v)}"${v === current ? " selected" : ""}>${esc(l)}</option>`,
    )
    .join("");
function toast(s) {
  $("#toast").textContent = s;
  $("#toast").classList.add("visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $("#toast").classList.remove("visible"), 4500);
}
function heading(title, copy, path) {
  return `<div class="page-heading"><div><span class="eyebrow">${path}</span><h1>${title}</h1><p>${copy}</p></div><div class="ready-chip">${icon("check")}工具已就绪 · CPU / NVIDIA（示例）</div></div>`;
}
// 容器与逐轨策略的冲突必须在冻结确认前显式呈现。
function issues() {
  const result = [];
  for (const f of chosen()) {
    const v = f.streams.filter(
      (s) => s.kind === "video" && s.action !== "drop",
    );
    if (v.length !== 1) result.push(`${f.name}：需保留一个真实主视频`);
    if (
      v.some((s) => s.action === "copy") &&
      (state.resize !== "keep" || state.fps !== "keep")
    )
      result.push(`${f.name}：Copy 与缩放/改帧率冲突`);
    if (
      state.container === "MP4" &&
      f.streams.some(
        (s) =>
          ["subtitle", "attachment"].includes(s.kind) && s.action !== "drop",
      )
    )
      result.push(
        `${f.name}：ASS 字幕 / 字体不能复制到 MP4，请改 MKV 或明确 Drop`,
      );
    const audio = f.streams.filter(
      (s) => s.kind === "audio" && s.action !== "drop",
    );
    if (
      state.container === "MP4" &&
      audio.length &&
      !audio.some((s) => s.default)
    )
      result.push(`${f.name}：请为保留音轨指定默认轨`);
    if (
      state.operation === "REMUX" &&
      f.streams.some((s) => s.action === "encode")
    )
      result.push(`${f.name}：转封装只接受 Copy / Drop`);
  }
  if (state.mode !== "COPY" && !state.ack)
    result.push("原地模式需要明确确认备份与替换行为");
  return result;
}
function issueBlock() {
  const list = issues();
  return `<div class="info-strip ${list.length ? "warn" : ""}" style="margin-top:18px">${icon(list.length ? "info" : "check")}<div>${list.length ? '<strong>创建任务前还需处理</strong><ul class="compact-list">' + list.map((s) => `<li>${esc(s)}</li>`).join("") + "</ul>" : "逐流策略与目标容器匹配，可以继续确认任务。"}</div></div>`;
}
function settingsPanel() {
  const ratio = Math.max(0.2, 0.54 - (state.quality - 18) * 0.018);
  return `<div class="side-stack"><section class="card"><div class="section-title"><h3>任务参数</h3>${badge("默认保留规格", "neutral")}</div><div class="mini-segment">${[
    ["quality", "画质优先"],
    ["balanced", "均衡"],
    ["small", "体积优先"],
  ]
    .map(
      ([v, l]) =>
        `<button data-preset="${v}" class="${state.preset === v ? "selected" : ""}">${l}</button>`,
    )
    .join(
      "",
    )}</div><div class="setting-field"><label for="backend">视频编码器<button class="tip" data-tip="backend" aria-label="编码器说明">?</button></label><select id="backend" data-setting="backend">${opts(
    [
      ["X264", "CPU · H.264 / x264"],
      ["NVENC", "NVIDIA · HEVC / NVENC"],
    ],
    state.backend,
  )}</select></div><div class="setting-field"><div class="quality-head"><label for="quality">固定质量 ${state.backend === "X264" ? "CRF" : "CQ"}</label><b id="quality-value">${state.quality}</b></div><input id="quality" type="range" min="16" max="35" value="${state.quality}" data-setting="quality" aria-label="固定质量值"><div class="range-labels"><span>更高画质</span><span>更小体积</span></div></div><div class="setting-field"><label for="container">输出容器<button class="tip" data-tip="container" aria-label="容器说明">?</button></label><select id="container" data-setting="container">${opts(
    [
      ["MKV", "MKV · 保留音轨与字幕"],
      ["MP4", "MP4 · 广泛播放兼容"],
    ],
    state.container,
  )}</select></div><div class="recommendation">${icon("shield")}<span>默认复制音频，保留声道和采样率。逐轨变化需要明确选择。</span></div></section><section class="card estimate-card"><div class="section-title"><h3>预计输出大小</h3>${badge("模拟样本", "neutral")}</div><div class="estimate-line"><strong>${(total() * (ratio - 0.12)).toFixed(1)} – ${(total() * (ratio + 0.12)).toFixed(1)} <span>GB</span></strong></div><div class="estimate-range"><i></i><i></i><i></i></div><small>设计示例范围，不是实际预测。正式使用时显示采样成本与依据；固定质量不保证最终大小。</small><button class="row-action" data-action="estimate" style="margin-top:9px">查看采样依据 ${icon("arrow")}</button></section></div>`;
}
function fileTable() {
  const visible = state.files.filter(
    (f) =>
      f.name.toLowerCase().includes(state.search.toLowerCase()) &&
      (state.filter === "all" ||
        (state.filter === "eligible" && f.eligible) ||
        (state.filter === "review" && !f.eligible)),
  );
  return `<div class="toolbar"><label class="search-field">${icon("search")}<input id="file-search" placeholder="搜索文件名" value="${esc(state.search)}"><kbd>Ctrl F</kbd></label><select id="file-filter" aria-label="筛选文件">${opts(
    [
      ["all", "全部文件"],
      ["eligible", "可处理"],
      ["review", "需审核 / 不支持"],
    ],
    state.filter,
  )}</select><span class="count-label">${state.files.length} 个文件</span></div><div class="table-container"><table><thead><tr><th><input id="select-all" type="checkbox" aria-label="选择所有可处理文件" ${chosen().length && state.files.filter((f) => f.eligible).every((f) => state.selected.has(f.id)) ? "checked" : ""}></th><th>文件名称</th><th>体积</th><th>视频规格</th><th>预检状态</th><th></th></tr></thead><tbody>${visible.map((f) => `<tr class="${state.selected.has(f.id) ? "selected" : ""}"><td><input id="check-${f.id}" data-file="${f.id}" type="checkbox" aria-label="选择 ${esc(f.name)}" ${state.selected.has(f.id) ? "checked" : ""} ${f.eligible ? "" : "disabled"}></td><td><div class="file-cell"><span class="thumb ${f.color || "gray"}">${icon("play")}</span><div><div class="file-name">${esc(f.name)}</div><div class="file-meta">${esc(f.duration)} · ${esc(f.codec)}</div></div></div></td><td>${fmt(f.size)}</td><td>${esc(f.resolution)}<div class="file-meta">${esc(f.fps)} fps</div></td><td>${f.eligible ? badge("可处理") : badge(f.id === 5 ? "需审核" : "不支持", f.id === 5 ? "warn" : "neutral")}</td><td><button class="row-action" data-streams="${f.id}">${f.eligible ? "逐轨策略" : "查看原因"}</button></td></tr>`).join("") || '<tr><td colspan="6"><div class="empty">没有匹配的文件</div></td></tr>'}</tbody></table></div>`;
}
function workspace() {
  let body;
  if (state.step === 1)
    body = `<div class="work-grid"><section><div class="drop-zone" id="drop-zone"><div class="drop-illustration">${icon("folder")}</div><div><h3>把文件或文件夹放到这里</h3><p>MP4、MKV、MOV 等 · 检查全部音轨与字幕</p></div><div class="drop-buttons"><button class="secondary-accent" data-action="pick-files">${icon("plus")}添加文件</button><button data-action="pick-folder">添加文件夹</button></div></div>${fileTable()}<div class="table-note">${icon("info")}<span>HDR 与仅附图媒体不会进入普通压制路径。${state.files.filter((f) => !f.eligible).length} 个文件未勾选，可查看具体原因。</span></div><div class="button-row"><button class="text-button" data-action="examples">重新加载示例</button><button class="text-button" data-action="clear-list">清空列表</button></div></section>${settingsPanel()}</div>`;
  else body = state.step === 2 ? planning() : confirmation();
  return (
    heading(
      "新建压缩任务",
      "先选文件，再确认媒体策略与输出位置。源文件默认保留。",
      "工作台 / 新建任务",
    ) +
    `<div class="steps" aria-label="新建任务步骤">${[1, 2, 3].map((n, i) => `${i ? '<span class="step-line"></span>' : ""}<button class="step ${state.step === n ? "current" : state.step > n ? "completed" : ""}" data-step="${n}" ${n > 1 && !chosen().length ? "disabled" : ""} ${state.step === n ? 'aria-current="step"' : ""}><i>${state.step > n ? "✓" : n}</i>${["导入与选择", "参数与预检", "确认任务"][i]}</button>`).join("")}</div>` +
    body +
    `<footer class="action-footer"><div><strong><span class="selected-count">${chosen().length}</span> 个文件已选择 · ${fmt(total())}</strong><small>${state.step === 3 ? "创建后参数冻结，后续设置不影响此任务。" : "未选择的媒体不会加入任务。"}</small></div><div class="button-row">${state.step > 1 ? '<button data-action="previous">上一步</button>' : ""}<button class="primary" data-action="${state.step === 3 ? "create-task" : "next"}" ${!chosen().length || (state.step === 3 && issues().length) ? "disabled" : ""}>${state.step === 3 ? "加入任务队列" : "下一步"} ${icon("arrow")}</button></div></footer>`
  );
}
function planning() {
  return `<div class="review-grid"><section class="card"><div class="review-heading"><h3>输出与处理模式</h3><p>先解决兼容问题，不让编码器静默丢轨。</p></div><div class="setting-field"><label for="operation">处理方式</label><select id="operation" data-setting="operation">${opts(
    [
      ["ENCODE", "重新编码主视频"],
      ["REMUX", "兼容转封装 · 仅复制内容"],
    ],
    state.operation,
  )}</select><small>转封装不接受缩放、改帧率或音频重编码。</small></div><div class="setting-field"><label for="mode">输出模式</label><select id="mode" data-setting="mode">${opts(
    [
      ["COPY", "输出副本（推荐）"],
      ["REPLACE", "同名替换 · 保留原始备份"],
      ["CHANGE_EXTENSION", "原目录改扩展名 · 归档原始版本"],
    ],
    state.mode,
  )}</select></div>${state.mode === "COPY" ? `<div class="setting-field"><label for="output">输出文件夹</label><div class="form-row"><input id="output" data-setting="output" value="${esc(state.output)}"><button data-action="choose-output">选择</button></div><small>同名冲突会阻止发布，不覆盖已有文件。</small></div>` : `<div class="info-strip warn">${icon("shield")}原始版本保留为备份。媒体变小不等于磁盘已释放。</div><label class="check-line"><input id="inplace-ack" type="checkbox" ${state.ack ? "checked" : ""}>我已确认原目录提交，并保留原始版本备份。</label><label class="check-line"><input id="only-smaller" type="checkbox" ${state.smaller ? "checked" : ""}>只在候选更小时替换</label>`}<div class="spacer"></div><h3>全部保留流</h3><div class="file-plan-list" style="margin-top:12px">${chosen()
    .map(
      (f) =>
        `<div class="file-plan"><div><div class="file-name">${esc(f.name)}</div><small>${f.streams.filter((s) => s.action !== "drop").length} 条流保留${f.streams.some((s) => s.action === "drop") ? " · 存在明确 Drop" : ""}</small></div><button class="row-action" data-streams="${f.id}">逐轨检查</button></div>`,
    )
    .join(
      "",
    )}</div></section><div class="side-stack"><section class="card"><h3>视频与保留策略</h3><div class="spacer"></div><div class="form-grid"><div class="setting-field"><label for="resize">尺寸上限</label><select id="resize" data-setting="resize">${opts(
    [
      ["keep", "保持源尺寸"],
      ["1080", "不超过 1080p"],
      ["720", "不超过 720p"],
    ],
    state.resize,
  )}</select></div><div class="setting-field"><label for="fps">帧率</label><select id="fps" data-setting="fps">${opts(
    [
      ["keep", "保持源时间线"],
      ["30", "显式 CFR 30"],
      ["24", "显式 CFR 24"],
    ],
    state.fps,
  )}</select></div></div><small>不放大视频；降低尺寸或帧率是明确的有损决定。</small><label class="check-line"><input id="keep-metadata" type="checkbox" ${state.metadata ? "checked" : ""}>保留标题、语言等元数据</label><label class="check-line"><input id="keep-chapters" type="checkbox" ${state.chapters ? "checked" : ""}>保留章节</label></section><section class="card"><h3>按卷空间预检</h3><div class="detail-row"><span>C: 候选与档案</span><b>可用 256 GB</b></div><div class="detail-row"><span>D: staging 工作区</span><b>${badge(state.workspaceD ? "已登记" : "尚未登记", state.workspaceD ? "" : "warn")}</b></div><small>候选、跨卷 staging 和安全余量计入预算，备份保留量另列。</small><button class="row-action" data-action="register-workspace">配置按卷工作区</button></section></div></div>${issueBlock()}`;
}
function confirmation() {
  return `<div class="info-strip">${icon("shield")}确认完整任务快照，编码到候选，完整验证后才发布。</div><div class="review-grid"><section class="card"><div class="review-heading"><h3>本次任务</h3><p>${chosen().length} 个文件 · ${fmt(total())} · ${state.mode === "COPY" ? "输出副本" : "原目录提交并保留备份"}</p></div>${chosen()
    .map(
      (f) =>
        `<div class="file-plan" style="margin-bottom:10px"><div><div class="file-name">${esc(f.name)}</div><small>→ ${esc(f.name.replace(/\.[^.]+$/, "." + state.container.toLowerCase()))}</small></div>${badge(f.streams.some((s) => s.action === "drop") ? "明确丢轨" : "保留所选流", f.streams.some((s) => s.action === "drop") ? "warn" : "")}</div>`,
    )
    .join(
      "",
    )}<div class="path-box">${esc(state.mode === "COPY" ? state.output : "原始文件所在目录 · 受控提交")}</div></section><section class="card"><h3>处理策略</h3>${[
    [
      "视频",
      state.operation === "REMUX"
        ? "Copy · 转封装"
        : `${state.backend === "X264" ? "x264 / CRF" : "NVENC / CQ"} ${state.quality}`,
    ],
    ["音频", "逐轨处理，未覆盖参数保留源规格"],
    [
      "尺寸 / 帧率",
      `${state.resize === "keep" ? "保持尺寸" : state.resize + "p 上限"} / ${state.fps === "keep" ? "保持时间线" : state.fps + " fps CFR"}`,
    ],
    [
      "元数据 / 章节",
      `${state.metadata ? "保留" : "删除"} / ${state.chapters ? "保留" : "删除"}`,
    ],
    ["原始版本", state.mode === "COPY" ? "源文件保留" : "提交后保留备份"],
    ["失败与中断", "当前文件重跑，候选与证据保留"],
  ]
    .map(
      ([k, v]) =>
        `<div class="detail-row"><span>${k}</span><b>${esc(v)}</b></div>`,
    )
    .join(
      "",
    )}<small>示例范围不保证实际大小；封装、完整验证与发布后才显示实际字节。</small></section></div>${issueBlock()}`;
}

function queueView() {
  const q = state.queue;
  const stopped = ["interrupted", "paused", "cancelled"].includes(q.status);
  const stage =
    q.status === "done"
      ? "已验证完成"
      : stopped
        ? q.status === "cancelled"
          ? "任务已取消"
          : "任务已暂停"
        : q.progress >= 97
          ? "发布与核对"
          : q.progress >= 90
            ? "完整校验中"
            : "编码中";
  $("#queue-nav-count").textContent = q.cancelled
    ? "0"
    : String(q.status === "done" ? 2 : 3);
  return (
    heading(
      "任务队列",
      "编码完成后还会验证和提交。暂停或取消不会删除已完成媒体。",
      "执行 / 当前会话",
    ) +
    `<p class="demo-note">速度、进度和任务均为示例。点击“运行进度演示”可体验完整阶段变化。</p><section class="card queue-card"><div class="queue-title"><div><h3>旅行整理 · ${state.created ? "新建任务" : "2026 秋季"}</h3><p>${state.created ? chosen().length : 3} 个文件 · ${state.backend === "X264" ? "CPU / x264" : "NVIDIA / HEVC"} · ${state.mode === "COPY" ? "副本输出" : "带备份提交"}</p></div>${badge(stage, stopped ? "warn" : "")}</div><div class="progress-top"><span>${q.status === "cancelled" ? "未完成项保留在历史中" : "当前文件：旅行_精选.mkv"}</span><b>${Math.floor(q.progress)}<small> %</small></b></div><div class="progress" role="progressbar" aria-valuenow="${Math.floor(q.progress)}" aria-valuemin="0" aria-valuemax="100" aria-label="当前文件阶段进度"><i style="width:${q.progress}%"></i></div><div class="progress-details"><div><label>处理速度（示例）</label><b>${stopped ? "—" : "1.8 ×"}</b></div><div><label>预计编码剩余（示例）</label><b>${stopped ? "等待继续" : "约 05:20"}</b></div><div><label>运行中体积预测</label><b>${q.status === "done" ? "1.42 GB · 实际（模拟）" : "1.3 – 1.8 GB · 外推（模拟）"}</b></div><div><label>当前档案</label><b>候选与日志已定位</b></div></div><div class="phase-path"><span class="${q.progress < 90 ? "phase-active" : ""}">1 编码</span><span>→</span><span class="${q.progress >= 90 && q.progress < 97 ? "phase-active" : ""}">2 完整验证</span><span>→</span><span class="${q.progress >= 97 ? "phase-active" : ""}">3 发布与核对</span></div><div class="queue-actions"><small>${q.status === "interrupted" ? "继续会从头重跑当前文件，不追加半成品。" : q.pauseAfter ? "完成当前文件后停止派发下一项。" : "任务参数已冻结，后续设置不影响执行。"}</small><div class="button-row">${stopped ? '<button class="primary" data-action="resume">继续演示</button>' : `<button data-action="play">${icon(q.playing ? "pause" : "play")}${q.playing ? "暂停进度演示" : "运行进度演示"}</button><button data-action="pause-after" ${q.status === "done" ? "disabled" : ""}>完成当前后暂停</button><button data-action="stop" ${q.status === "done" ? "disabled" : ""}>立即停止</button>`}</div></div></section><div class="section-heading"><div><h3>后续文件</h3><p>低空间会停止派发，恢复前重新检查预算。</p></div><button class="text-button" data-action="cancel" ${q.cancelled ? "disabled" : ""}>取消未完成任务</button></div><section class="card">${["周末山行_4K.mp4", "活动记录_1080p.mp4"].map((name, i) => `<div class="queue-row"><span class="number">0${i + 2}</span><span class="thumb ${i ? "blue" : ""}">${icon("play")}</span><div><strong class="file-name">${name}</strong><small>${i ? "1.1 GB · 1080p" : "3.2 GB · 4K"} · 音频 Copy · 原规格保留</small></div>${badge(q.cancelled ? "已取消" : "等待派发", q.cancelled ? "neutral" : "")}</div>`).join("")}</section><div class="info-strip" style="margin-top:18px">${icon("info")}“立即停止”保留独立候选；发布开始后先完成核对，再停止后续派发。普通半成品不能追加续跑。</div>`
  );
}
function historyView() {
  const rows = histories.filter(
    (r) =>
      !r.hidden &&
      r.title.includes(state.historySearch) &&
      (state.historyFilter === "all" || state.historyFilter === r.status),
  );
  return (
    heading(
      "处理历史",
      "查看结果、校验和版本备份。隐藏记录不会删除媒体。",
      "档案 / 处理历史",
    ) +
    `<div class="tabs">${[
      ["all", "全部任务"],
      ["已完成", "已完成"],
      ["需处理", "需处理"],
    ]
      .map(
        ([k, l]) =>
          `<button class="tab ${state.historyFilter === k ? "active" : ""}" data-history-filter="${k}">${l}</button>`,
      )
      .join(
        "",
      )}</div><div class="toolbar"><label class="search-field">${icon("search")}<input id="history-search" placeholder="搜索任务名称" value="${esc(state.historySearch)}"></label><button data-action="support">${icon("download")}预览脱敏诊断</button></div><div class="table-container"><table><thead><tr><th>任务</th><th>结果</th><th>输入 → 输出</th><th>版本备份</th><th></th></tr></thead><tbody>${rows.map((r) => `<tr><td><div class="file-name">${r.title}</div><div class="file-meta">${r.time}</div></td><td>${badge(r.status, r.status === "需处理" ? "warn" : "")}</td><td>${r.before} → ${r.after}</td><td>${badge(r.backup ? "已保留" : "已释放", r.backup ? "" : "neutral")}</td><td><button class="row-action" data-history="${r.id}">查看详情</button></td></tr>`).join("") || '<tr><td colspan="5"><div class="empty">没有匹配的历史记录</div></td></tr>'}</tbody></table></div><div class="table-note">${icon("shield")}输入、输出或备份变化时进入“需处理”，不会盲目二次压制。</div>`
  );
}
function historyDetails(id, tab = "result") {
  const row = histories.find((r) => r.id === id);
  $("#drawer-title").textContent = row.title;
  $("#drawer-eyebrow").textContent = "任务详情 / " + row.time;
  $("#drawer-body").innerHTML = `<div class="tabs">${[
    ["result", "结果"],
    ["events", "事件时间线"],
    ["artifacts", "文件与备份"],
  ]
    .map(
      ([k, l]) =>
        `<button class="tab ${k === tab ? "active" : ""}" data-history-tab="${k}" data-history-id="${id}">${l}</button>`,
    )
    .join("")}</div>${
    tab === "events"
      ? [
          "任务参数已冻结",
          "独立候选编码完成",
          "全片解码与所选流验证通过",
          "目标 / 备份核对完成",
        ]
          .map(
            (s, i) =>
              `<div class="event"><small>10:${38 + i}</small><p>${s}</p></div>`,
          )
          .join("")
      : tab === "artifacts"
        ? `<div class="info-strip">${icon("shield")}验证和提交凭据长期保留，详细日志按保留期处理。</div><div class="detail-row"><span>验证与提交记录</span><b>${badge("已保留")}</b></div><div class="detail-row"><span>版本备份</span><b>${badge(row.backup ? "可恢复" : "已明确释放", row.backup ? "" : "warn")}</b></div><div class="spacer"></div><button data-restore="${id}" ${row.backup ? "" : "disabled"}>恢复原始版本</button>`
        : `<div class="info-strip ${row.status === "需处理" ? "warn" : ""}">${icon(row.status === "需处理" ? "info" : "check")}${row.status === "需处理" ? "输出证据需要人工核对；不会自动重新编码。" : "编码、验证和提交均已完成（示例）。"}</div>${[
            ["输入体积", row.before],
            ["输出体积", row.after],
            ["音频校验", "所选 Copy 音轨包一致"],
            ["视频校验", "完整解码与时间线通过"],
            ["备份", row.backup ? "版本仍保留" : "已释放，无法从此备份恢复"],
          ]
            .map(
              ([k, v]) =>
                `<div class="detail-row"><span>${k}</span><b>${v}</b></div>`,
            )
            .join(
              "",
            )}<div class="button-row" style="margin-top:22px"><button data-restore="${id}" ${row.backup ? "" : "disabled"}>恢复原始版本</button><button data-hide-history="${id}">隐藏此记录</button></div>`
  }`;
  if (!$("#drawer").open) $("#drawer").showModal();
}
function storageView() {
  const s = state.storage;
  return (
    heading(
      "存储与档案",
      "把“视频变小”和“磁盘释放”分开看，清理前先确认哪些文件仍被需要。",
      "维护 / 存储管理",
    ) +
    `<div class="stats-grid"><div class="card stat highlight"><span class="eyebrow">媒体逻辑减少量</span><strong>12.4 <span>GB</span></strong><small>不是磁盘实际回收量 · 示例</small></div><div class="card stat"><span class="eyebrow">版本备份仍保留</span><strong>${s.backups.toFixed(1)} <span>GB</span></strong><small>默认保留，可恢复原始版本</small></div><div class="card stat"><span class="eyebrow">可清理临时产物</span><strong>${(s.cache + s.logs + s.candidates).toFixed(1)} <span>GB</span></strong><small>缓存、日志和无活动引用候选</small></div><div class="card stat"><span class="eyebrow">实际回收量</span><strong>—</strong><small>不把逻辑删除量冒充实测结果</small></div></div><div class="volume-grid"><section class="card volume-card"><div class="volume-top"><span class="volume-icon">${icon("storage")}</span><div><h3>C: 系统卷</h3><small>候选、分析与主档案</small></div>${badge("可用 256 GB")}</div><div class="volume-meter"><i style="width:46%"></i><i class="backup" style="width:5%"></i><i class="temp" style="width:2%"></i></div><div class="volume-legend"><span>已用 268 GB / 524 GB</span><span>应用占用单独索引</span></div></section><section class="card volume-card"><div class="volume-top"><span class="volume-icon">${icon("storage")}</span><div><h3>D: 媒体卷</h3><small>跨卷 staging 工作区</small></div>${badge(state.workspaceD ? "已登记" : "需配置", state.workspaceD ? "" : "warn")}</div><div class="volume-meter"><i style="width:59%"></i><i class="backup" style="width:3%"></i></div><div class="volume-legend"><span>可用 384 GB / 1 TB</span><button class="row-action" data-action="register-workspace">配置工作区</button></div></section></div><div class="section-heading"><div><h3>安全清理</h3><p>只处理已登记的文件；活动和未决提交产物始终受保护。</p></div><button class="text-button" data-action="refresh-space">重新检测（演示）</button></div><div class="storage-grid"><section class="card card-padding">${[
      ["caches", "采样与分析缓存", "可再生结果，按年龄和容量管理", s.cache],
      ["logs", "过期详细日志", "紧凑摘要与提交证据保留", s.logs],
      [
        "candidates",
        "无活动引用的候选与 staging",
        "再次核对源或已验证的最终输出",
        s.candidates,
      ],
      [
        "backups",
        "原始 / 上一版本备份",
        "删除后失去对应版本恢复能力",
        s.backups,
      ],
    ]
      .map(
        ([k, t, d, n]) =>
          `<label class="cleanup-row"><input type="checkbox" data-cleanup="${k}" ${state.cleanup[k] ? "checked" : ""} ${n ? "" : "disabled"}><div>${t}<small>${d}</small></div><b>${fmt(n)}</b></label>`,
      )
      .join(
        "",
      )}<div class="card-bottom"><small>先展示具体范围与保护原因。</small><button class="primary" data-action="cleanup-preview">预览清理 ${icon("arrow")}</button></div></section><section class="card card-padding"><div class="section-title"><h3>受保护产物</h3>${badge("不会参与清理", "neutral")}</div><div class="cleanup-row">${icon("lock")}<div>当前任务候选<small>旅行_精选.mkv · 活动引用或等待重新核对</small></div><b>1.2 GB</b></div><div class="cleanup-row">${icon("lock")}<div>提交与验证凭据<small>用于追溯、启动对账和异常定位</small></div></div><ul class="compact-list"><li>先核对引用，再验证路径与内容。</li><li>文件或策略变化，需要重新预览。</li><li>隐藏历史不会解除这些保护。</li></ul></section></div><div class="section-heading"><div><h3>数据位置与诊断</h3><p>工作文件集中保存，不在素材旁散落日志。</p></div></div><section class="card card-padding no-bottom"><div class="setting-row"><div><h3>当前主数据根</h3><p class="mono">${esc(s.root)}</p></div><button data-action="migration">迁移数据位置</button></div><div class="setting-row"><div><h3>诊断包</h3><p>默认脱敏，不含视频、备份或数据库。</p></div><button data-action="support">${icon("download")}预览脱敏内容</button></div></section><div class="spacer"></div>`
  );
}
function settingsView() {
  return (
    heading(
      "工具与设置",
      "设置只影响新任务，已有任务沿用冻结参数。",
      "偏好 / 本机工具",
    ) +
    `<div class="settings-stack"><section class="card card-padding"><div class="settings-card-title">${icon("settings")}<h3>工具链与编码能力</h3></div><div class="setting-row"><div><strong>FFmpeg</strong><p>本机工具路径 · 设计示例</p></div><input type="text" aria-label="FFmpeg 路径" value="D:\\Tools\\FFmpeg\\ffmpeg.exe"></div><div class="setting-row"><div><strong>ffprobe</strong><p>用于媒体探测与验证</p></div><input type="text" aria-label="ffprobe 路径" value="D:\\Tools\\FFmpeg\\ffprobe.exe"></div><div class="setting-row"><div><strong>真实自检</strong><p>正式版会编码短片，不只看编码器列表。</p></div>${badge("CPU / NVIDIA / AAC · 示例")}<button data-action="validate-tools">运行自检演示</button></div></section><section class="card card-padding"><div class="settings-card-title">${icon("storage")}<h3>保留策略</h3></div>${[
      [
        "cache-days",
        "cacheDays",
        "分析缓存保留",
        "过期或超容量时进入清理预览。",
      ],
      ["log-days", "logDays", "详细日志保留", "必要验证与提交凭据长期保留。"],
      [
        "backup-days",
        "backupDays",
        "原始备份最短保留",
        "没有自动删除开关，到期仍需明确确认。",
      ],
    ]
      .map(
        ([id, key, title, copy]) =>
          `<div class="setting-row"><div><strong>${title}</strong><p>${copy}</p></div><input id="${id}" type="number" min="0" max="3650" value="${state[key]}" aria-label="${title}天数"><small>天</small></div>`,
      )
      .join(
        "",
      )}</section><section class="card card-padding"><div class="settings-card-title">${icon("sun")}<h3>外观与操作</h3></div><div class="setting-row"><div><strong>主题</strong><p>支持深浅主题，生产桌面版适配系统。</p></div><select id="theme-select" aria-label="主题">${opts(
      [
        ["light", "浅色"],
        ["dark", "深色"],
      ],
      document.body.classList.contains("dark") ? "dark" : "light",
    )}</select></div><div class="setting-row"><div><strong>键盘操作</strong><p><kbd>Ctrl O</kbd> 导入　<kbd>Ctrl F</kbd> 搜索　<kbd>Alt 1–5</kbd> 导航　<kbd>Esc</kbd> 关闭浮层</p></div></div></section><div class="button-row" style="justify-content:flex-end"><button class="primary" data-action="save-settings">保存设置（演示）</button></div></div>`
  );
}

function render() {
  const id = document.activeElement?.id;
  const cursor = document.activeElement?.selectionStart;
  $("#main").innerHTML = {
    workspace,
    queue: queueView,
    history: historyView,
    storage: storageView,
    settings: settingsView,
  }[state.view]();
  document.querySelectorAll("[data-view]").forEach((n) => {
    n.classList.toggle("active", n.dataset.view === state.view);
    n.setAttribute(
      "aria-current",
      n.dataset.view === state.view ? "page" : "false",
    );
  });
  if (id) {
    const next = document.getElementById(id);
    next?.focus({ preventScroll: true });
    if (next?.type === "text" && typeof cursor === "number")
      next.setSelectionRange(cursor, cursor);
  }
}
function navigate(view) {
  state.view = view;
  render();
  $("#main").scrollTop = 0;
  try {
    history.replaceState(null, "", "#" + view);
  } catch {}
  $("#main").focus({ preventScroll: true });
}
function modal(title, body, footer = "", eyebrow = "ErgouCompress") {
  $("#modal-title").textContent = title;
  $("#modal-eyebrow").textContent = eyebrow;
  $("#modal-body").innerHTML = body;
  $("#modal-footer").innerHTML =
    footer ||
    '<button class="primary" data-action="close-dialog">知道了</button>';
  if (!$("#modal").open) $("#modal").showModal();
}
function openStreams(id) {
  const f = state.files.find((f) => f.id === id);
  activeFile = id;
  $("#drawer-title").textContent = f.name;
  $("#drawer-eyebrow").textContent = "逐轨策略 / 设计示例";
  $("#drawer-body").innerHTML = !f.eligible
    ? `<div class="info-strip warn">${icon("info")}${esc(f.reason)}</div><p class="dialog-copy">保持排除，不以强制转换隐藏尚未验证的风险。</p>`
    : `<div class="path-box">${esc(f.resolution)} · ${esc(f.fps)} fps · ${esc(f.codec)}</div><p class="dialog-copy" style="margin-top:14px">非视频流默认复制。Encode / Drop 会改变内容，需要明确选择。</p>${f.streams
        .map(
          (s, i) =>
            `<section class="stream-card"><div class="stream-head"><strong>${esc(s.name)}</strong><select id="stream-${id}-${i}" data-stream-index="${i}" aria-label="${esc(s.name)}处理方式">${opts(
              ["video", "audio"].includes(s.kind)
                ? [
                    ["copy", "Copy · 复制"],
                    [
                      "encode",
                      s.kind === "video" ? "Encode · 编码" : "AAC · 重编码",
                    ],
                    ["drop", "Drop · 丢弃"],
                  ]
                : [
                    ["copy", "Copy · 复制"],
                    ["drop", "Drop · 丢弃"],
                  ],
              s.action,
            )}</select></div><p>${esc(s.spec)}</p>${s.action === "drop" ? badge("明确丢弃此流", "warn") : s.kind === "audio" ? `<label class="check-line"><input type="checkbox" data-default-stream="${i}" ${s.default ? "checked" : ""}>设为默认音轨</label>` : ""}${
              s.kind === "audio" && s.action === "encode"
                ? `<div class="stream-settings">${[
                    [
                      "bitrate",
                      "码率",
                      [
                        ["128", "128 kbit/s"],
                        ["192", "192 kbit/s"],
                        ["256", "256 kbit/s"],
                      ],
                      "192",
                    ],
                    [
                      "sampleRate",
                      "采样率",
                      [
                        ["keep", "保持源规格"],
                        ["44100", "44.1 kHz"],
                        ["48000", "48 kHz"],
                      ],
                      "keep",
                    ],
                    [
                      "channels",
                      "声道",
                      [
                        ["keep", "保持源布局"],
                        ["1", "单声道"],
                        ["2", "立体声"],
                      ],
                      "keep",
                    ],
                  ]
                    .map(
                      ([key, label, values, def]) =>
                        `<label>${label}<select data-aac="${key}" data-aac-index="${i}" aria-label="${label}">${opts(values, s[key] || def)}</select></label>`,
                    )
                    .join(
                      "",
                    )}</div><small>AAC 有损，未覆盖时保持源采样率和声道。</small>`
                : ""
            }${["subtitle", "attachment"].includes(s.kind) ? "<small>字幕 / 字体只支持兼容容器 Copy 或明确 Drop。</small>" : ""}</section>`,
        )
        .join(
          "",
        )}<div class="info-strip ${issues().some((s) => s.startsWith(f.name)) ? "warn" : ""}">${icon("info")}${esc(
        issues()
          .filter((s) => s.startsWith(f.name))
          .join("；") || "当前逐轨策略与目标容器匹配。",
      )}</div>`;
  if (!$("#drawer").open) $("#drawer").showModal();
}
// 清理先预览；原始备份必须额外确认失去恢复能力。
function cleanupPreview() {
  const entries = [
    ["caches", "分析与采样缓存", state.storage.cache],
    ["logs", "过期详细日志", state.storage.logs],
    ["candidates", "无活动引用候选", state.storage.candidates],
    ["backups", "版本备份", state.storage.backups],
  ].filter(([k, , n]) => state.cleanup[k] && n > 0);
  const sum = entries.reduce((s, x) => s + x[2], 0);
  const backup = entries.some((x) => x[0] === "backups");
  modal(
    "确认清理范围",
    `<p class="dialog-copy">只处理已登记且复核通过的文件。下面是模拟预览，不删除实际文件。</p>${entries.map(([k, t, n]) => `<div class="cleanup-preview-item"><div>${t}<div>${badge(k === "backups" ? "不可恢复的版本删除" : "引用与证据已核对", k === "backups" ? "warn" : "")}</div></div><b>${fmt(n)}</b></div>`).join("") || '<div class="empty">没有可清理的已选内容</div>'}<div class="cleanup-preview-item"><div>当前任务与提交证据<div>${badge("受保护 · 不清理", "neutral")}</div></div>${icon("lock")}</div><div class="spacer"></div><strong>预计移除逻辑文件 ${fmt(sum)}</strong><p class="dialog-copy">这不是精确磁盘回收量，硬链接和文件系统分配会影响实际释放空间。</p>${backup ? '<label class="check-line"><input type="checkbox" id="backup-loss-ack">我理解，删除版本备份后将失去对应版本的恢复能力。</label>' : ""}`,
    `<button data-action="close-dialog">返回</button><button class="${backup ? "danger-button" : "primary"}" data-action="execute-cleanup" ${!sum || backup ? "disabled" : ""}>确认清理（演示）</button>`,
    "存储管理 / 清理预览",
  );
}
function migrationDialog() {
  if (!state.queue.cancelled) {
    modal(
      "迁移前需要完成任务",
      `<div class="info-strip warn">${icon("lock")}存在未完成任务，当前不能迁移数据根。</div><p class="dialog-copy">先完成或取消未完成任务，并处理未决提交。不会把正在使用的候选或备份跨卷搬走。</p>`,
      `<button data-action="close-dialog">稍后</button><button class="primary" data-action="migration-to-queue">前往任务队列</button>`,
    );
    return;
  }
  modal(
    "迁移数据位置",
    `<p class="dialog-copy">复制并完整校验成功后才切换，旧数据根保留为回退副本。</p><label class="setting-label" for="migration-target">新的空目录（设计示例）</label><input id="migration-target" style="width:100%" value="D:\\ErgouCompressData"><div class="migration-stages"><span>1 检查</span><span>2 复制</span><span>3 校验</span><span>4 切换</span></div><div class="path-box">原位置：${esc(state.storage.root)}<br>原始版本备份与历史保持可追溯。</div>`,
    `<button data-action="close-dialog">取消</button><button class="primary" data-action="start-migration">开始迁移演示</button>`,
  );
}
function supportDialog() {
  modal(
    "脱敏诊断包预览",
    `<p class="dialog-copy">不含数据库、视频和版本备份；路径、素材名称、邮箱和凭证被移除。日志使用匿名编号。</p><div class="diagnostic-preview">summary.json\n  任务：4 · 待处理：1\n  数据位置：&lt;DATA_ROOT&gt;\n\nlogs/1.txt\n  source=&lt;PATH_1&gt;\n  frame=42 speed=1.2x\n  &lt;敏感字段已移除&gt;</div><div class="info-strip" style="margin-top:15px">${icon("shield")}原型只展示诊断结构，不收集或上传真实数据。</div>`,
  );
}
function runDemo() {
  if (simulationTimer) {
    clearInterval(simulationTimer);
    simulationTimer = null;
    state.queue.playing = false;
    render();
    return;
  }
  if (state.queue.status === "done") state.queue.progress = 0;
  state.queue.status = "running";
  state.queue.playing = true;
  simulationTimer = setInterval(() => {
    const q = state.queue;
    q.progress = Math.min(
      100,
      q.progress + (q.progress < 90 ? 4 : q.progress < 97 ? 1.5 : 1),
    );
    if (q.progress >= 100) {
      clearInterval(simulationTimer);
      simulationTimer = null;
      q.playing = false;
      q.status = q.pauseAfter ? "paused" : "done";
    }
    if (state.view === "queue") render();
  }, 800);
  render();
}
const tips = {
  backend: [
    "选择编码器",
    "x264 使用 CPU，NVENC 使用已实际自检的 NVIDIA HEVC 编码器。CRF 和 CQ 不能当作同等画质数值。",
  ],
  container: [
    "先解决容器兼容性",
    "MKV 可保留更多音轨、字幕和字体。MP4 需每条流都兼容；不兼容时改容器或明确 Drop，不静默丢流。",
  ],
};
document.addEventListener("click", (event) => {
  const b = event.target.closest("button,a");
  if (!b || b.disabled) return;
  if (b.matches(".brand")) {
    event.preventDefault();
    navigate("workspace");
    return;
  }
  if (b.dataset.view) {
    navigate(b.dataset.view);
    return;
  }
  if (b.dataset.step) {
    state.step = +b.dataset.step;
    render();
    $("#main").scrollTop = 0;
    return;
  }
  if (b.dataset.preset) {
    state.preset = b.dataset.preset;
    state.quality = { quality: 18, balanced: 23, small: 28 }[state.preset];
    render();
    return;
  }
  if (b.dataset.streams) {
    openStreams(+b.dataset.streams);
    return;
  }
  if (b.dataset.tip) {
    const [title, copy] = tips[b.dataset.tip];
    modal(title, `<p class="dialog-copy">${copy}</p>`);
    return;
  }
  if (b.dataset.historyFilter) {
    state.historyFilter = b.dataset.historyFilter;
    render();
    return;
  }
  if (b.dataset.history) {
    historyDetails(b.dataset.history);
    return;
  }
  if (b.dataset.historyTab) {
    historyDetails(b.dataset.historyId, b.dataset.historyTab);
    return;
  }
  if (b.dataset.restore) {
    const id = b.dataset.restore;
    $("#drawer").close();
    modal(
      "恢复原始版本",
      `<p class="dialog-copy">原始备份仍保留，当前版本另存为新备份。当前文件改变或原路径冲突时，正式版本会拒绝覆盖。</p><div class="path-box">${esc(histories.find((r) => r.id === id).title)}</div>`,
      `<button data-action="close-dialog">返回</button><button class="primary" data-confirm-restore="${id}">确认恢复（演示）</button>`,
    );
    return;
  }
  if (b.dataset.confirmRestore) {
    histories.find((r) => r.id === b.dataset.confirmRestore).status = "已恢复";
    $("#modal").close();
    render();
    toast("原始版本已恢复，当前版本另存为备份（模拟）");
    return;
  }
  if (b.dataset.hideHistory) {
    histories.find((r) => r.id === b.dataset.hideHistory).hidden = true;
    $("#drawer").close();
    render();
    toast("已隐藏记录，媒体和备份没有删除（模拟）");
    return;
  }
  if (b.dataset.outputChoice) {
    state.output = b.dataset.outputChoice;
    $("#modal").close();
    render();
    return;
  }
  switch (b.dataset.action) {
    case "close-dialog":
      $("#modal").close();
      break;
    case "close-drawer":
      $("#drawer").close();
      break;
    case "theme":
      document.body.classList.toggle("dark");
      if (state.view === "settings") render();
      break;
    case "design-notes":
      modal(
        "先把流程设计清楚",
        `<p class="dialog-copy">独立于现有 Compose UI 的桌面体验设计。所有状态和容量都是模拟数据。</p><ul class="design-list"><li>导入 → 逐轨策略 → 输出预检 → 冻结确认 → 队列。</li><li>编码、验证、发布核对分别呈现。</li><li>视频变小、备份占用、实际回收分开统计。</li><li>清理先预览，备份删除增加不可恢复确认。</li><li>未完成任务阻止迁移，旧根保留回退。</li><li>支持键盘焦点、深浅主题与窄窗口。</li></ul>`,
      );
      break;
    case "pick-files":
      $("#file-picker").click();
      break;
    case "pick-folder":
      $("#folder-picker").click();
      break;
    case "examples":
      state.files = examples();
      state.selected = new Set([1, 2, 4, 7]);
      state.search = "";
      state.filter = "all";
      render();
      toast("已加载 7 个设计示例");
      break;
    case "clear-list":
      modal(
        "清空导入清单",
        `<p class="dialog-copy">只清空原型清单，不删除源文件。可随时重新加载示例。</p>`,
        `<button data-action="close-dialog">返回</button><button class="primary" data-action="confirm-clear">清空清单</button>`,
      );
      break;
    case "confirm-clear":
      state.files = [];
      state.selected.clear();
      $("#modal").close();
      render();
      break;
    case "next":
      state.step = Math.min(3, state.step + 1);
      render();
      $("#main").scrollTop = 0;
      break;
    case "previous":
      state.step--;
      render();
      $("#main").scrollTop = 0;
      break;
    case "create-task":
      if (issues().length) {
        toast("请先处理预检问题");
        break;
      }
      state.created = true;
      state.queue = {
        progress: 0,
        status: "running",
        playing: false,
        pauseAfter: false,
        cancelled: false,
      };
      navigate("queue");
      toast("快照已冻结并加入队列（模拟）");
      break;
    case "choose-output":
      modal(
        "选择输出位置",
        `<p class="dialog-copy">下面是设计示例，不选择或创建真实目录。</p><div class="format-choice">${["D:\\媒体\\压缩输出", "E:\\视频档案", "C:\\桌面\\压缩结果"].map((path) => `<button data-output-choice="${esc(path)}"><strong>${esc(path.slice(0, 2))}</strong><small>${esc(path)}</small></button>`).join("")}</div>`,
      );
      break;
    case "estimate":
      modal(
        "估算依据与范围",
        `<p class="dialog-copy">正式采样复用视频参数，音频独立计入。下面只演示报告结构。</p><div class="detail-row"><span>采样位置</span><b>头部 / 中部 / 尾部</b></div><div class="detail-row"><span>媒体预算</span><b>3 段 × 8 秒（示例）</b></div><div class="detail-row"><span>依据</span><b>视频有效包 + 所选音频预算</b></div><div class="info-strip warn" style="margin-top:18px">${icon("info")}这是启发式范围，不是 95% 置信区间。固定质量无采样时可显示“未知”。</div>`,
      );
      break;
    case "play":
      runDemo();
      break;
    case "pause-after":
      state.queue.pauseAfter = true;
      render();
      toast("当前文件验证提交后暂停派发（模拟）");
      break;
    case "stop":
      modal(
        "停止当前文件",
        `<p class="dialog-copy">当前候选保留，继续时从头编码，不追加普通半成品。</p><div class="info-strip warn">${icon("info")}发布已开始时，先完成核对再停止后续派发。</div>`,
        `<button data-action="close-dialog">继续当前任务</button><button class="primary" data-action="confirm-stop">确认停止（演示）</button>`,
      );
      break;
    case "confirm-stop":
      clearInterval(simulationTimer);
      simulationTimer = null;
      state.queue.playing = false;
      state.queue.status = "interrupted";
      $("#modal").close();
      render();
      toast("已停止，候选保留（模拟）");
      break;
    case "resume":
      state.queue.progress = 0;
      state.queue.status = "running";
      state.queue.cancelled = false;
      state.queue.pauseAfter = false;
      render();
      toast("当前文件将从头重跑（模拟）");
      break;
    case "cancel":
      modal(
        "取消未完成任务",
        `<p class="dialog-copy">已完成媒体保持不变。候选和备份仍由单独清理流程管理。</p>`,
        `<button data-action="close-dialog">返回</button><button class="primary" data-action="confirm-cancel">确认取消（演示）</button>`,
      );
      break;
    case "confirm-cancel":
      clearInterval(simulationTimer);
      simulationTimer = null;
      state.queue.playing = false;
      state.queue.cancelled = true;
      state.queue.status = "cancelled";
      $("#modal").close();
      render();
      toast("未完成任务已取消（模拟）");
      break;
    case "cleanup-preview":
      cleanupPreview();
      break;
    case "execute-cleanup": {
      let n = 0;
      for (const k of ["caches", "logs", "candidates", "backups"])
        if (state.cleanup[k]) {
          const key = k === "caches" ? "cache" : k;
          n += state.storage[key];
          state.storage[key] = 0;
        }
      if (state.cleanup.backups) histories.forEach((r) => (r.backup = false));
      $("#modal").close();
      render();
      toast(`已移除 ${fmt(n)} 逻辑文件（模拟），不承诺等量磁盘回收`);
      break;
    }
    case "register-workspace":
      modal(
        "登记按卷工作区",
        `<p class="dialog-copy">集中保存该卷 staging 与版本备份，不在素材旁散落日志。</p><div class="path-box">D:\\ErgouCompressWorkspace（示例）</div>`,
        `<button data-action="close-dialog">返回</button><button class="primary" data-action="confirm-workspace">确认登记（演示）</button>`,
      );
      break;
    case "confirm-workspace":
      state.workspaceD = true;
      $("#modal").close();
      render();
      toast("D: 工作区已登记（模拟）");
      break;
    case "refresh-space":
      toast("容量卡片展示示例，原型不读取真实磁盘");
      break;
    case "support":
      supportDialog();
      break;
    case "migration":
      migrationDialog();
      break;
    case "migration-to-queue":
      $("#modal").close();
      navigate("queue");
      break;
    case "start-migration": {
      const target = $("#migration-target").value.trim();
      if (!target) {
        toast("请填写新位置");
        break;
      }
      let phase = 0;
      $("#modal-body").innerHTML =
        `<p class="dialog-copy">演示迁移：完整验证成功前不切换，旧档案保留。</p><div class="migration-stages">${["检查", "复制", "校验", "切换"].map((s, i) => `<span id="migration-phase-${i}">${i + 1} ${s}</span>`).join("")}</div><div class="progress"><i id="migration-meter" style="width:0%"></i></div>`;
      $("#modal-footer").innerHTML = "<button disabled>迁移演示中</button>";
      migrationTimer = setInterval(() => {
        $(`#migration-phase-${phase}`).classList.add("done");
        $("#migration-meter").style.width = `${++phase * 25}%`;
        if (phase === 4) {
          clearInterval(migrationTimer);
          state.storage.root = target;
          $("#modal-body").insertAdjacentHTML(
            "beforeend",
            `<div class="info-strip" style="margin-top:20px">${icon("check")}新位置已验证（模拟），旧位置保留回退。正式版本需要重启。</div>`,
          );
          $("#modal-footer").innerHTML =
            '<button class="primary" data-action="finish-migration">完成演示</button>';
        }
      }, 900);
      break;
    }
    case "finish-migration":
      $("#modal").close();
      render();
      toast("流程演示完成，没有移动真实文件");
      break;
    case "save-settings":
      for (const [id, key] of [
        ["cache-days", "cacheDays"],
        ["log-days", "logDays"],
        ["backup-days", "backupDays"],
      ]) {
        const v = Number(document.getElementById(id).value);
        if (!Number.isInteger(v) || v < 0 || v > 3650) {
          toast("保留天数应为 0–3650 的整数");
          return;
        }
        state[key] = v;
      }
      toast("原型会话设置已保存，不写生产配置");
      break;
    case "validate-tools":
      b.disabled = true;
      b.textContent = "自检演示中…";
      setTimeout(() => {
        b.disabled = false;
        b.textContent = "运行自检演示";
        toast("状态仅为设计示例，不代表本机实际能力");
      }, 1000);
      break;
  }
});
document.addEventListener("change", (event) => {
  const e = event.target;
  if (e.dataset.setting) {
    state[e.dataset.setting] =
      e.dataset.setting === "quality" ? +e.value : e.value;
    if (e.dataset.setting === "operation" && e.value === "REMUX") {
      chosen().forEach((f) =>
        f.streams.forEach((s) => {
          if (s.action === "encode") s.action = "copy";
        }),
      );
      state.resize = "keep";
      state.fps = "keep";
    }
    if (e.dataset.setting === "quality") state.preset = "custom";
    render();
  }
  if (e.dataset.file) {
    e.checked
      ? state.selected.add(+e.dataset.file)
      : state.selected.delete(+e.dataset.file);
    render();
  }
  if (e.id === "select-all") {
    state.files
      .filter((f) => f.eligible)
      .forEach((f) =>
        e.checked ? state.selected.add(f.id) : state.selected.delete(f.id),
      );
    render();
  }
  if (e.id === "file-filter") {
    state.filter = e.value;
    render();
  }
  for (const [id, key] of [
    ["inplace-ack", "ack"],
    ["only-smaller", "smaller"],
    ["keep-metadata", "metadata"],
    ["keep-chapters", "chapters"],
  ])
    if (e.id === id) {
      state[key] = e.checked;
      render();
    }
  if (e.dataset.streamIndex !== undefined) {
    state.files.find((f) => f.id === activeFile).streams[
      +e.dataset.streamIndex
    ].action = e.value;
    render();
    openStreams(activeFile);
  }
  if (e.dataset.defaultStream !== undefined) {
    const streams = state.files.find((f) => f.id === activeFile).streams;
    streams
      .filter((s) => s.kind === "audio")
      .forEach((s) => (s.default = false));
    streams[+e.dataset.defaultStream].default = e.checked;
    render();
    openStreams(activeFile);
  }
  if (e.dataset.aac)
    state.files.find((f) => f.id === activeFile).streams[+e.dataset.aacIndex][
      e.dataset.aac
    ] = e.value;
  if (e.dataset.cleanup) state.cleanup[e.dataset.cleanup] = e.checked;
  if (e.id === "backup-loss-ack")
    $('[data-action="execute-cleanup"]').disabled = !e.checked;
  if (e.id === "theme-select")
    document.body.classList.toggle("dark", e.value === "dark");
});
document.addEventListener("input", (event) => {
  const e = event.target;
  if (e.id === "file-search") {
    state.search = e.value;
    render();
  }
  if (e.id === "history-search") {
    state.historySearch = e.value;
    render();
  }
  if (e.id === "quality") $("#quality-value").textContent = e.value;
});
// 只展示用户选择的名称和大小，不读取内容或发起上传。
function importFiles(files) {
  let id = Date.now();
  for (const file of files)
    state.files.push({
      id: id++,
      name: file.name,
      size: file.size / 1e9,
      duration: "待探测",
      resolution: "未探测",
      fps: "—",
      codec: "未连接后端",
      color: "gray",
      eligible: false,
      reason: "只展示文件名，原型不读取或上传媒体；真实探测待接入后端",
      streams: [],
    });
  render();
  toast(`已展示 ${files.length} 个本地文件名，未读取或上传内容`);
}
$("#file-picker").addEventListener("change", (e) =>
  importFiles(e.target.files),
);
$("#folder-picker").addEventListener("change", (e) =>
  importFiles(e.target.files),
);
document.addEventListener("dragover", (e) => {
  if (e.target.closest("#drop-zone")) {
    e.preventDefault();
    $("#drop-zone").classList.add("drag-over");
  }
});
document.addEventListener("dragleave", (e) => {
  if (e.target.closest("#drop-zone"))
    $("#drop-zone").classList.remove("drag-over");
});
document.addEventListener("drop", (e) => {
  if (e.target.closest("#drop-zone")) {
    e.preventDefault();
    importFiles(e.dataTransfer.files);
  }
});
document.addEventListener("keydown", (e) => {
  if ($("#modal").open || $("#drawer").open) return;
  if (e.altKey && /^[1-5]$/.test(e.key)) {
    e.preventDefault();
    navigate(
      ["workspace", "queue", "history", "storage", "settings"][+e.key - 1],
    );
  }
  if (e.ctrlKey && e.key.toLowerCase() === "o") {
    e.preventDefault();
    $("#file-picker").click();
  }
  if (e.ctrlKey && e.key.toLowerCase() === "f") {
    const input = $("#file-search") || $("#history-search");
    if (input) {
      e.preventDefault();
      input.focus();
    }
  }
});
$("#modal").addEventListener("close", () => clearInterval(migrationTimer));
if (
  ["workspace", "queue", "history", "storage", "settings"].includes(
    location.hash.slice(1),
  )
)
  state.view = location.hash.slice(1);
render();
