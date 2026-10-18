// 在本地 DOM 中验证用户流程；不启动浏览器、不连接网络，也不模拟视觉验收。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require(process.env.ERGOU_JSDOM || 'jsdom');
const root = path.resolve(__dirname, '..');

/** 每个用例使用独立 DOM 和可手动推进的计时器，避免状态串扰。 */
function fixture() {
  const dom = new JSDOM(fs.readFileSync(path.join(root, 'index.html'), 'utf8'), {
    runScripts: 'outside-only', url: 'https://prototype.invalid/',
  });
  const w = dom.window;
  const errors = [];
  w.addEventListener('error', e => errors.push(e.error));
  w.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  w.HTMLDialogElement.prototype.close = function () { this.open = false; this.dispatchEvent(new w.Event('close')); };
  const timers = new Map(); let next = 0;
  w.setInterval = f => { timers.set(++next, f); return next; };
  w.clearInterval = id => timers.delete(id);
  w.setTimeout = () => ++next;
  w.clearTimeout = () => {};
  w.eval(fs.readFileSync(path.join(root, 'app.js'), 'utf8'));
  const get = s => { const e = w.document.querySelector(s); assert.ok(e, `缺少元素 ${s}`); return e; };
  const click = s => { get(s).click(); assert.deepEqual(errors, []); };
  const change = (s, value) => { const e = get(s); if (e.type === 'checkbox') e.checked = value; else e.value = value; e.dispatchEvent(new w.Event('change', { bubbles: true })); assert.deepEqual(errors, []); };
  const action = name => click(`[data-action="${name}"]`);
  const nav = view => click(`[data-view="${view}"]`);
  const text = () => get('#main').textContent;
  const tick = n => { for (let i = 0; i < n; i++) for (const f of [...timers.values()]) f(); assert.deepEqual(errors, []); };
  return { dom, w, get, click, change, action, nav, text, tick };
}

test('五个页面、无重复 ID、搜索焦点与主题切换', () => {
  const f = fixture(); try {
    for (const view of ['workspace','queue','history','storage','settings']) {
      f.nav(view); const ids = [...f.w.document.querySelectorAll('[id]')].map(e => e.id);
      assert.equal(new Set(ids).size, ids.length); assert.ok(f.get('h1').textContent);
    }
    f.change('#theme-select','dark'); assert.ok(f.w.document.body.classList.contains('dark'));
    f.nav('workspace'); const search=f.get('#file-search'); search.focus(); search.value='课程';
    search.dispatchEvent(new f.w.Event('input',{bubbles:true}));
    assert.equal(f.w.document.activeElement.id,'file-search'); assert.equal(f.get('tbody').children.length,1);
  } finally { f.dom.window.close(); }
});

test('容器冲突阻止创建，逐流 Drop 后才允许冻结', () => {
  const f=fixture(); try {
    f.change('#container','MP4'); f.action('next'); f.action('next');
    assert.ok(f.get('[data-action="create-task"]').disabled); assert.match(f.text(),/ASS/);
    f.click('[data-step="1"]'); f.click('[data-streams="4"]');
    f.change('#stream-4-2','drop'); f.change('#stream-4-3','drop'); f.action('close-drawer');
    f.click('[data-step="3"]'); assert.equal(f.get('[data-action="create-task"]').disabled,false);
    f.action('create-task'); assert.match(f.get('h1').textContent,/队列/);
  } finally { f.dom.window.close(); }
});

test('原地替换确认、停止重跑和完成当前后暂停', () => {
  const f=fixture(); try {
    f.action('next'); f.change('[data-setting="mode"]','REPLACE'); f.action('next');
    assert.ok(f.get('[data-action="create-task"]').disabled);
    f.click('[data-step="2"]'); f.change('#inplace-ack',true); f.action('next'); f.action('create-task');
    f.action('play'); f.tick(3); f.action('stop'); f.action('confirm-stop');
    assert.match(f.text(),/从头|重新/); f.action('resume'); f.action('pause-after'); f.action('play'); f.tick(80);
    assert.match(f.text(),/暂停/);
  } finally { f.dom.window.close(); }
});

test('清理预览保护证据，删除备份需额外确认并失去恢复入口', () => {
  const f=fixture(); try {
    f.nav('storage'); f.change('[data-cleanup="backups"]',true); f.action('cleanup-preview');
    assert.ok(f.get('[data-action="execute-cleanup"]').disabled);
    assert.match(f.get('#modal-body').textContent,/受保护/); f.change('#backup-loss-ack',true); f.action('execute-cleanup');
    f.nav('history'); f.click('[data-history="H1"]');
    const restore=f.w.document.querySelector('[data-restore="H1"]'); assert.ok(!restore || restore.disabled);
  } finally { f.dom.window.close(); }
});

test('活动任务阻止迁移，取消后可以演示完整迁移', () => {
  const f=fixture(); try {
    f.nav('storage'); f.action('migration'); assert.match(f.get('#modal-title').textContent,/完成任务/);
    f.action('migration-to-queue'); f.action('cancel'); f.action('confirm-cancel');
    f.nav('storage'); f.action('migration'); f.action('start-migration'); f.tick(4);
    assert.match(f.get('#modal-body').textContent,/重启/); f.action('finish-migration'); assert.match(f.text(),/D:\\ErgouCompressData/);
  } finally { f.dom.window.close(); }
});

test('历史恢复、诊断说明和保留设置校验', () => {
  const f=fixture(); try {
    f.nav('history'); f.click('[data-history="H1"]'); f.click('[data-restore="H1"]'); f.click('[data-confirm-restore="H1"]');
    assert.match(f.text(),/已恢复/); f.nav('storage'); f.action('support');
    assert.match(f.get('#modal-body').textContent,/不含数据库/); f.action('close-dialog');
    f.nav('settings'); f.change('#cache-days',-1); f.action('save-settings'); assert.match(f.get('#toast').textContent,/0–3650/);
    f.change('#cache-days',45); f.action('save-settings'); assert.match(f.get('#toast').textContent,/已保存/);
  } finally { f.dom.window.close(); }
});
