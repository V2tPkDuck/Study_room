'use strict';

/* 智慧自习室预约与管理系统：课程 Demo 前端。
   设计原则：无构建步骤、无外部 CDN。需求编号只保留在代码注释里，不在界面展示。 */

/* 需求编号只用于代码维护，不在界面显示：
   FR-01/02 自习室/座位查询；FR-03 预约；FR-04 取消；FR-05/06 签到签退；
   FR-07/08 个人预约/违约；FR-09 评价；FR-10/11 投诉反馈；FR-12/13 资源维护；
   FR-14 实时座位；FR-15 使用记录；FR-16/17 违约；FR-18/19 用户/黑名单；
   FR-20 评价管理；FR-21/22 投诉处理；FR-23/24/25 统计与刷新。
   业务规则 BR-01~12 在 Java 服务层校验，前端只负责展示结果。 */

const $ = (selector, parent = document) => parent.querySelector(selector);

const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, ch => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
}[ch]));

const state = {
  user: null,
  csrf: null,
  view: 'dashboard',
  roomId: null,
  roomData: [],
  seats: [],
  selected: null,
  page: 1,
  status: '',
  date: '',
  start: '',
  end: '',
  power: false,
  quiet: false,
  users: [],
  complaints: [],
  reviews: []
};

const bookingNames = {
  BOOKED: '已预约',
  PENDING: '待核查',
  CHECKED_IN: '已签到',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  VIOLATED: '已违约'
};

const bookingBadge = {
  BOOKED: 'blue',
  PENDING: 'amber',
  CHECKED_IN: 'green',
  COMPLETED: 'green',
  CANCELLED: '',
  VIOLATED: 'red'
};

const complaintNames = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  RESOLVED: '已解决',
  WITHDRAWN: '已撤回'
};

const complaintBadge = {
  PENDING: 'amber',
  PROCESSING: 'blue',
  RESOLVED: 'green',
  WITHDRAWN: ''
};


let modalSubmit = null;
let toastTimer = null;
let refreshTimer = null;

const isAdmin = () => state.user && state.user.role === 'ADMIN';
const prefix = () => isAdmin() ? '/admin' : '';

const stamp = value => value
  ? escapeHtml(String(value).replace('T', ' ').slice(0, 16))
  : '—';

const stampSeconds = value => value
  ? escapeHtml(String(value).replace('T', ' ').slice(0, 19))
  : '—';

const tag = (text, cls = '') => `<span class="tag ${cls}">${escapeHtml(text)}</span>`;

function bookingTag(status) {
  return tag(bookingNames[status] || status, bookingBadge[status] || '');
}

function complaintTag(status) {
  return tag(complaintNames[status] || status, complaintBadge[status] || '');
}

const button = (label, action, id = '', cls = '') =>
  `<button type="button" class="button compact ${cls}" data-action="${action}" data-id="${escapeHtml(id)}">${label}</button>`;

const empty = text => `<div class="empty">${escapeHtml(text)}</div>`;

const field = (label, name, value = '', type = 'text', extra = '') =>
  `<div class="field"><label for="f-${name}">${label}</label>
   <input id="f-${name}" name="${name}" type="${type}" value="${escapeHtml(value)}" ${extra}></div>`;

const area = (label, name, value = '', max = 2000, required = true) =>
  `<div class="field wide"><label for="f-${name}">${label}</label>
   <textarea id="f-${name}" name="${name}" maxlength="${max}" ${required ? 'required' : ''}>${escapeHtml(value)}</textarea></div>`;

const check = (label, name, value) =>
  `<label class="check"><input type="checkbox" name="${name}" ${value ? 'checked' : ''}>${label}</label>`;

const select = (label, name, items, value = '') =>
  `<div class="field"><label for="f-${name}">${label}</label><select name="${name}" id="f-${name}">
    ${items.map(([id, text]) => `<option value="${escapeHtml(id)}" ${String(id) === String(value) ? 'selected' : ''}>${escapeHtml(text)}</option>`).join('')}
   </select></div>`;

const number = (label, name, value, min, max) =>
  field(label, name, value, 'number', `required min="${min}" max="${max}"`);

function campusNow() {
  return new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Shanghai' }));
}

function localDateTimeValue(date) {
  const pad = n => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function notify(message) {
  clearTimeout(toastTimer);
  $('#toast').textContent = message;
  $('#toast').classList.add('show');
  toastTimer = setTimeout(() => $('#toast').classList.remove('show'), 3000);
}

async function api(path, method = 'GET', body) {
  if (!state.csrf) {
    const csrfResponse = await fetch('/api/auth/csrf', { credentials: 'same-origin' });
    if (!csrfResponse.ok) throw new Error('无法连接服务，请确认后端已启动');
    state.csrf = await csrfResponse.json();
  }
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') headers[state.csrf.headerName] = state.csrf.token;

  const response = await fetch('/api' + path, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  if (response.status === 204) return null;

  const data = await response.json().catch(() => ({ message: '服务器返回异常' }));
  if (!response.ok) {
    if (response.status === 401 && state.user) {
      state.user = null;
      showLogin();
    }
    throw new Error(data.message || '请求失败');
  }
  return data;
}

/* ---------------- 登录与框架 ---------------- */

function showLogin() {
  document.body.classList.remove('sidebar-expanded');
  clearInterval(refreshTimer);
  refreshTimer = null;
  state.user = null;
  state.csrf = null;
  $('#app').innerHTML = `
    <main class="auth">
      <section class="auth-card">
        <h1>智慧自习室预约与管理系统</h1>
        <p class="muted">软件工程课程 Demo</p>
        <form id="auth-form">
          ${field('账号', 'username', '', 'text', 'required autocomplete="username"')}
          ${field('密码', 'password', '', 'password', 'required autocomplete="current-password"')}
          <p class="error" id="auth-error" role="alert"></p>
          <button class="button primary" type="submit">登录</button>
        </form>
        <div class="auth-note">
          演示账号：<br>
          管理员 admin / Admin123!<br>
          学生 student / Student123!、student2 / Student123!<br>
          账号由管理员创建，不开放学生自助注册。
        </div>
      </section>
    </main>`;

  $('#auth-form').onsubmit = async event => {
    event.preventDefault();
    const submit = $('button[type=submit]', event.target);
    submit.disabled = true;
    $('#auth-error').textContent = '';
    try {
      const form = Object.fromEntries(new FormData(event.target));
      state.user = await api('/auth/login', 'POST', form);
      state.csrf = null;
      state.view = 'dashboard';
      state.roomId = null;
      state.selected = null;
      state.page = 1;
      state.status = '';
      state.date = '';
      state.start = '';
      state.end = '';
      shell();
      await navigate('dashboard');
    } catch (error) {
      $('#auth-error').textContent = error.message;
    } finally {
      submit.disabled = false;
    }
  };
}

function menus() {
  const common = [
    ['dashboard', '◷', '数据概览'],
    ['seats', '▦', isAdmin() ? '实时座位' : '预约座位'],
    ['bookings', '▤', isAdmin() ? '预约管理' : '我的预约'],
    ['violations', '⚑', isAdmin() ? '违约处理' : '我的违约'],
    ['complaints', '☷', '投诉反馈'],
    ['reviews', '☆', isAdmin() ? '评价管理' : '空间评价'],
    ['rules', '⚙', '规则说明']
  ];
  const adminOnly = [
    ['resources', '▧', '自习室与座位'],
    ['users', '♙', '用户与黑名单'],
    ['audits', '≡', '操作日志']
  ];
  return isAdmin()
    ? [common[0], common[1], common[2], common[3], ...adminOnly, common[4], common[5], common[6]]
    : common;
}

function updateSidebarToggle() {
  const toggle = document.querySelector('.sidebar-toggle');
  if (!toggle) return;
  const expanded = document.body.classList.contains('sidebar-expanded');
  toggle.textContent = expanded ? '‹' : '›';
  toggle.title = expanded ? '收起导航' : '展开导航';
  toggle.setAttribute('aria-label', toggle.title);
}

function shell() {
  $('#app').innerHTML = `
    <div class="shell">
      <aside class="sidebar">
        <div class="brand">
          <span class="brand-short">自习</span>
          <div class="brand-full">
            <strong>智慧自习室<br>预约与管理系统</strong>
            <small>课程 Demo</small>
          </div>
        </div>
        <nav class="nav" aria-label="主导航">
          ${menus().map(([view, icon, label]) => `<button type="button" data-view="${view}" title="${label}" aria-label="${label}">
            <span class="nav-icon" aria-hidden="true">${icon}</span><span class="nav-label">${label}</span>
          </button>`).join('')}
        </nav>
        <div class="sidebar-foot">
          <p>角色：${isAdmin() ? '管理员' : '学生'}</p>
          <p>版本：课程演示</p>
        </div>
        <button type="button" class="sidebar-toggle" data-action="toggle-sidebar"
          title="展开导航" aria-label="展开导航">›</button>
      </aside>
      <div class="main">
        <header class="topbar">
          <span class="muted small">${new Intl.DateTimeFormat('zh-CN', {
            timeZone: 'Asia/Shanghai', year: 'numeric', month: 'long', day: 'numeric', weekday: 'long'
          }).format(new Date())}</span>
          <div class="user">
            <span class="avatar">${escapeHtml((state.user.display_name || '?').slice(0, 1))}</span>
            <strong>${escapeHtml(state.user.display_name)}</strong>
            ${tag(isAdmin() ? '管理员' : '学生')}
            <button class="button compact" data-action="logout">退出</button>
          </div>
        </header>
        <main class="content" id="content"></main>
      </div>
    </div>`;
  updateSidebarToggle();
}

function heading(title, description, extra = '') {
  return `<div class="page-heading">
    <div>
      <h1>${escapeHtml(title)}</h1>
      <p>${escapeHtml(description)}</p>
    </div>
    ${extra}
  </div>`;
}

function statistic(label, value, note) {
  return `<div class="stat"><span class="stat-label">${escapeHtml(label)}</span>
    <strong>${escapeHtml(value)}</strong><small>${escapeHtml(note)}</small></div>`;
}

async function navigate(view) {
  state.view = view;
  clearInterval(refreshTimer);
  refreshTimer = null;
  document.querySelectorAll('[data-view]').forEach(el => el.classList.toggle('active', el.dataset.view === view));
  $('#content').innerHTML = '<div class="loading">正在读取数据……</div>';
  try {
    await render(view);
    if (view === 'dashboard' || view === 'seats') {
      refreshTimer = setInterval(() => refreshLive().catch(error => notify(error.message)), 10000);
    }
  } catch (error) {
    if ($('#content')) {
      $('#content').innerHTML = heading('读取失败', error.message, button('重试', 'refresh'));
    }
  }
}

async function render(view) {
  switch (view) {
    case 'dashboard': return dashboard();
    case 'seats': return seatPage();
    case 'bookings': return bookingPage();
    case 'violations': return violationPage();
    case 'complaints': return complaintPage();
    case 'reviews': return reviewPage();
    case 'resources': return resourcePage();
    case 'users': return userPage();
    case 'rules': return rulesPage();
    case 'audits': return auditPage();
  }
}

async function refreshLive() {
  if (state.view === 'dashboard') await dashboard();
  else if (state.view === 'seats' && !$('#modal').open) await loadSeats();
}

/* ---------------- 数据概览 ---------------- */

async function dashboard() {
  if (isAdmin()) return adminDashboard();
  return studentDashboard();
}

// FR-23/24/25：统计指标、柱状图/折线图、10 秒刷新。
async function adminDashboard() {
  const data = await api('/admin/stats');
  if (state.view !== 'dashboard') return;

  $('#content').innerHTML = heading(
    '数据概览',
    '统计每 10 秒从数据库刷新；今日预约按开始日期统计，包含取消记录。',
    `<div class="actions"><span class="tag green">10 秒刷新</span>${button('刷新', 'refresh')}</div>`
  ) + `
    <div class="stats">
      ${statistic('今日预约（含取消）', data.today_bookings, '按预约开始日期统计')}
      ${statistic('正在使用', data.occupied, '已签到且当前在时段内')}
      ${statistic('启用座位', data.seats, '已启用自习室与座位')}
      ${statistic('学生账号', data.users, '管理员创建的学生账号')}
    </div>
    <div class="charts">
      <section class="panel">
        <div class="panel-head"><h2>各自习室 · 今日预约</h2><span class="tag">柱状图</span></div>
        <div class="chart-wrap"><canvas id="bar-chart" aria-label="各自习室今日预约柱状图"></canvas></div>
        <div class="chart-caption">${data.rooms.map(r => `${escapeHtml(r.name)}：${r.bookings}`).join('　')}</div>
      </section>
      <section class="panel">
        <div class="panel-head"><h2>近 7 日 · 预约与违约</h2><span class="tag">折线图</span></div>
        <div class="chart-wrap"><canvas id="line-chart" aria-label="近七日预约和违约折线图"></canvas></div>
        <div class="chart-caption">${data.trend.map(r => `${r.date.slice(5)}：${r.bookings} / ${r.violations}`).join('　')}</div>
      </section>
    </div>
    <p class="notice" style="margin-top:14px">
      统计口径：今日预约 = 开始日期为今天的预约记录（含取消）；正在使用 = 已签到且未到期；
      启用座位 = 已启用资源数量，不等于瞬时空闲座位；违约按未撤销记录统计。
      <br>最后更新：${stampSeconds(data.updated_at)}
    </p>`;

  requestAnimationFrame(() => {
    drawChart('bar-chart', data.rooms.map(r => r.name), [data.rooms.map(r => Number(r.bookings))], 'bar');
    drawChart('line-chart', data.trend.map(r => r.date.slice(5)),
      [data.trend.map(r => Number(r.bookings)), data.trend.map(r => Number(r.violations))], 'line');
  });
}

// FR-07/08/09：学生个人预约、违约和评价入口。
async function studentDashboard() {
  const [rooms, result, policy, violations, me] = await Promise.all([
    api('/rooms'), api('/reservations?size=5'), api('/policy'), api('/violations'), api('/auth/me')
  ]);
  if (state.view !== 'dashboard') return;
  state.user = me;

  const upcoming = result.items.filter(r => ['BOOKED', 'CHECKED_IN'].includes(r.status)).length;
  const activeViolations = violations.filter(v => !v.revoked).length;
  const blacklisted = me.blacklist_until && new Date(me.blacklist_until) > campusNow();

  $('#content').innerHTML = heading(
    `你好，${me.display_name}`,
    '这里只展示与你有关的预约、违约和限制信息。',
    button('去预约座位', 'go-seats', '', 'primary')
  ) + `
    ${blacklisted ? `<p class="notice" style="margin-bottom:14px">你当前处于预约限制期，截止 ${stamp(me.blacklist_until)}。可在“投诉反馈”中说明情况。</p>` : ''}
    <div class="stats">
      ${statistic('我的预约', result.total, '含全部历史状态')}
      ${statistic('待使用', upcoming, '已预约或已签到')}
      ${statistic('有效违约', activeViolations, '未撤销的违约记录')}
      ${statistic('签到窗口', `提前 ${policy.check_in_before} 分钟`, `迟到宽限 ${policy.grace_minutes} 分钟`)}
    </div>
    <section class="panel">
      <div class="panel-head"><h2>最近的预约</h2>${button('查看全部', 'go-bookings')}</div>
      ${bookingTable(result.items, false)}
    </section>`;

  requestAnimationFrame(() => {
    const chart = document.getElementById('student-chart');
    if (chart) drawChart('student-chart', rooms.map(r => r.name), [rooms.map(() => 0)], 'bar');
  });
}

/* ---------------- 简单图表 ---------------- */

function drawChart(id, labels, series, type) {
  const canvas = document.getElementById(id);
  if (!canvas) return;
  const box = canvas.parentElement.getBoundingClientRect();
  const width = Math.max(320, box.width || 320);
  const height = Math.max(200, box.height || 240);
  const dpr = window.devicePixelRatio || 1;
  canvas.width = width * dpr;
  canvas.height = height * dpr;
  canvas.style.width = width + 'px';
  canvas.style.height = height + 'px';
  const ctx = canvas.getContext('2d');

  const left = 38;
  const top = 16;
  const bottom = 42;
  const chartWidth = width - left - 12;
  const chartHeight = height - top - bottom;
  const maxValue = Math.max(4, ...series.flat().map(Number));
  const upper = Math.ceil(maxValue / 4) * 4;
  const step = chartWidth / Math.max(1, labels.length);
  const colors = ['#245a7a', '#a97c1e'];

  function draw(activeIndex = -1) {
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, width, height);

    // 横向网格和纵轴刻度
    ctx.font = '11px "Microsoft YaHei", sans-serif';
    ctx.fillStyle = '#66727f';
    ctx.strokeStyle = '#e2e7eb';
    for (let i = 0; i <= 4; i++) {
      const y = top + chartHeight - (i * chartHeight / 4);
      ctx.beginPath();
      ctx.moveTo(left, y);
      ctx.lineTo(width - 12, y);
      ctx.stroke();
      ctx.fillText(String(Math.round(upper * i / 4)), 6, y + 4);
    }

    if (!labels.length) {
      ctx.fillText('暂无数据', left + 10, top + 20);
      return;
    }

    // 横轴标签
    ctx.textAlign = 'center';
    labels.forEach((label, i) => {
      ctx.fillStyle = '#66727f';
      const short = label.length > 7 ? label.slice(0, 7) : label;
      ctx.fillText(short, left + step * (i + 0.5), height - 14);
    });

    // 鼠标悬停时的竖向参考线
    if (type === 'line' && activeIndex >= 0) {
      const x = left + step * (activeIndex + 0.5);
      ctx.save();
      ctx.strokeStyle = '#b8c6d0';
      ctx.setLineDash([4, 4]);
      ctx.beginPath();
      ctx.moveTo(x, top);
      ctx.lineTo(x, top + chartHeight);
      ctx.stroke();
      ctx.restore();
    }

    series.forEach((values, index) => {
      ctx.strokeStyle = colors[index % colors.length];
      ctx.fillStyle = colors[index % colors.length];
      ctx.lineWidth = 2;

      if (type === 'bar') {
        values.forEach((value, i) => {
          const barHeight = Number(value) / upper * chartHeight;
          const barWidth = Math.min(step * 0.5, 60);
          const x = left + step * (i + 0.5) - barWidth / 2;
          ctx.fillRect(x, top + chartHeight - barHeight, barWidth, barHeight);
          ctx.textAlign = 'center';
          ctx.fillText(String(value), left + step * (i + 0.5), top + chartHeight - barHeight - 5);
        });
        return;
      }

      // 折线
      ctx.beginPath();
      values.forEach((value, i) => {
        const x = left + step * (i + 0.5);
        const y = top + chartHeight - Number(value) / upper * chartHeight;
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      });
      ctx.stroke();

      values.forEach((value, i) => {
        const x = left + step * (i + 0.5);
        const y = top + chartHeight - Number(value) / upper * chartHeight;
        const active = i === activeIndex;
        ctx.beginPath();
        ctx.arc(x, y, active ? 5 : 3, 0, Math.PI * 2);
        ctx.fillStyle = colors[index % colors.length];
        ctx.fill();
        if (active) {
          ctx.strokeStyle = '#fff';
          ctx.lineWidth = 2;
          ctx.stroke();
        }
      });
    });
  }

  draw();
  if (type === 'line' && labels.length) {
    attachLineTooltip(canvas, { labels, series, left, top, step, width, height, chartHeight, upper, draw });
  }
}

/* 折线图悬停提示：显示最近节点的日期、预约数和违约数。 */
function attachLineTooltip(canvas, config) {
  const wrap = canvas.parentElement;
  let tooltip = wrap.querySelector('.chart-tooltip');
  if (!tooltip) {
    tooltip = document.createElement('div');
    tooltip.className = 'chart-tooltip';
    wrap.appendChild(tooltip);
  }

  canvas.onmousemove = event => {
    const rect = canvas.getBoundingClientRect();
    const x = event.clientX - rect.left;
    const index = Math.max(0, Math.min(config.labels.length - 1,
      Math.floor((x - config.left) / config.step)));
    const values = config.series.map(list => Number(list[index] || 0));
    tooltip.innerHTML = `<strong>${escapeHtml(config.labels[index])}</strong>
      <span>预约：${escapeHtml(values[0])}</span>
      ${values[1] !== undefined ? `<span>违约：${escapeHtml(values[1])}</span>` : ''}`;
    tooltip.classList.add('show');

    const pointX = config.left + config.step * (index + 0.5);
    const pointY = config.top + config.chartHeight - (values[0] / config.upper) * config.chartHeight;
    tooltip.style.left = Math.min(config.width - 130, Math.max(8, pointX + 10)) + 'px';
    tooltip.style.top = Math.min(config.height - 70, Math.max(6, pointY - 10)) + 'px';
    config.draw(index);
  };

  canvas.onmouseleave = () => {
    tooltip.classList.remove('show');
    config.draw();
  };
}

/* ---------------- 座位与预约 ---------------- */

function defaultRange() {
  if (state.start && state.end) return;
  const now = campusNow();
  now.setSeconds(0, 0);
  now.setMinutes(Math.ceil((now.getMinutes() + 2) / 5) * 5);
  if (now.getHours() < 7) now.setHours(7, 0, 0, 0);
  if (now.getHours() >= 22) {
    now.setDate(now.getDate() + 1);
    now.setHours(8, 0, 0, 0);
  }
  state.start = localDateTimeValue(now);
  state.end = localDateTimeValue(new Date(now.getTime() + 60 * 60 * 1000));
}

// FR-01/02/03/14：自习室、座位状态、预约入口、实时刷新。
async function seatPage() {
  // 从“我的预约”跳转时会带入该预约的时段；只有没有时段时才用默认的“现在起 1 小时”。
  if (!state.start || !state.end) defaultRange();
  const rooms = await api(prefix() + '/rooms');
  if (state.view !== 'seats') return;
  state.roomData = rooms;

  // 选择“安静区”时，只保留安静区教室；普通教室不出现在选项里。
  const visibleRooms = state.quiet ? rooms.filter(room => room.is_quiet) : rooms;
  if (!visibleRooms.some(room => room.id === Number(state.roomId))) {
    state.roomId = visibleRooms.length ? visibleRooms[0].id : null;
    state.selected = null;
  }

  $('#content').innerHTML = heading(
    isAdmin() ? '实时座位' : '预约座位',
    isAdmin()
      ? '按时间查看座位占用；管理员只查看，不代学生预约。'
      : '选择时间、自习室和座位后提交预约；冲突以服务端校验为准。',
    `<span class="tag green">10 秒刷新</span>`
  ) + `
    <div class="toolbar">
      <div class="field"><label for="start">开始时间（北京时间）</label>
        <input id="start" type="datetime-local" value="${escapeHtml(state.start)}"></div>
      <div class="field"><label for="end">结束时间</label>
        <input id="end" type="datetime-local" value="${escapeHtml(state.end)}"></div>
      <button class="button primary" data-action="filter-seats">查询座位</button>
      <label class="check"><input type="checkbox" id="power" ${state.power ? 'checked' : ''}>有电源</label>
      <label class="check"><input type="checkbox" id="quiet" ${state.quiet ? 'checked' : ''}>安静区</label>
    </div>
    <div class="room-tabs">
      ${visibleRooms.length ? visibleRooms.map(room => `<button class="room-card ${room.id === Number(state.roomId) ? 'active' : ''}"
        data-action="pick-room" data-id="${room.id}">
        <strong>${escapeHtml(room.name)} ${room.enabled ? '' : '（停用）'}</strong>
        <small>${escapeHtml(room.building)} · ${room.floor_no} 层 · ${room.seat_count} 个座位<br>
        ${room.is_quiet ? '安静区' : '普通区'} · ${escapeHtml(room.open_time)} — ${escapeHtml(room.close_time)}</small></button>`).join('')
        : empty(state.quiet ? '没有安静区教室，请取消“安静区”筛选。' : '暂无自习室，请联系管理员添加。')}
    </div>
    <div id="seat-content"></div>`;

  if (!visibleRooms.length) {
    $('#seat-content').innerHTML = empty(state.quiet ? '没有安静区教室，请取消“安静区”筛选。' : '暂无自习室，请联系管理员添加。');
    return;
  }
  await loadSeats();
}

async function loadSeats() {
  if (!state.roomId) {
    const target = $('#seat-content');
    if (target) target.innerHTML = empty('暂无自习室，请联系管理员添加。');
    return;
  }
  const rows = await api(`${prefix()}/rooms/${state.roomId}/seats?start=${encodeURIComponent(state.start)}&end=${encodeURIComponent(state.end)}`);
  if (state.view !== 'seats') return;
  state.seats = rows;

  const matches = rows.filter(seat => (!state.power || seat.has_power) && (!state.quiet || seat.is_quiet));
  if (!matches.some(seat => seat.id === Number(state.selected) && seat.bookable)) state.selected = null;
  const room = state.roomData.find(r => r.id === Number(state.roomId));
  const seat = rows.find(s => s.id === Number(state.selected));
  const content = $('#seat-content');
  if (!content || !room) return;

  content.innerHTML = `
    <div class="seat-layout">
      <section class="panel">
        <div class="panel-head">
          <div>
            <h2>${escapeHtml(room.name)}</h2>
            <p class="muted small window-hint">当前时段：${stamp(state.start)} — ${stamp(state.end)}，座位状态按此时段计算</p>
          </div>
          <span class="muted small">${matches.length} 个符合条件 · 座位位置保持不变</span>
        </div>
        ${matches.length === 0 ? '<p class="notice">没有符合当前筛选条件的座位，请调整“有电源/安静区”。</p>' : ''}
        <div class="seat-grid">
          ${rows.map(s => {
            const visible = (!state.power || s.has_power) && (!state.quiet || s.is_quiet);
            if (!visible) {
              // 被筛掉的座位保留占位，后面的座位不会因为筛选而移动编号/位置。
              return '<button type="button" class="seat filtered" disabled aria-hidden="true"></button>';
            }
            const cls = s.state === '维护中' ? 'disabled'
              : s.state === '已预约' ? 'booked'
              : s.state === '使用中' ? 'inuse' : '';
            return `<button type="button" class="seat ${cls} ${s.id === Number(state.selected) ? 'selected' : ''}"
              data-action="pick-seat" data-id="${s.id}" ${s.bookable ? '' : 'disabled'}>
              <span>${escapeHtml(s.code)}</span>
              <small>${escapeHtml(s.state)}${s.has_power ? ' · 电源' : ''}</small>
            </button>`;
          }).join('')}
        </div>
        <div class="legend">
          <span><i class="free"></i>空闲</span>
          <span><i class="booked"></i>已预约</span>
          <span><i class="inuse"></i>使用中</span>
          <span><i class="disabled"></i>维护中</span>
        </div>
      </section>
      <aside class="panel booking-aside">
        <h3>${isAdmin() ? '座位详情' : '本次预约'}</h3>
        <div class="detail"><span class="muted">自习室</span><span>${escapeHtml(room.name)}</span></div>
        <div class="detail"><span class="muted">教室属性</span><span>${room.is_quiet ? '安静区' : '普通区'}</span></div>
        <div class="detail"><span class="muted">座位</span><span>${seat ? escapeHtml(seat.code) + ' 号' : '请点击选择'}</span></div>
        <div class="detail"><span class="muted">开始</span><span>${stamp(state.start)}</span></div>
        <div class="detail"><span class="muted">结束</span><span>${stamp(state.end)}</span></div>
        ${isAdmin() ? '' : `<p class="notice" style="margin-top:12px">${escapeHtml(room.description)}</p>`}
        ${isAdmin()
          ? ''
          : `<button class="button primary" data-action="confirm-book" ${!seat || !seat.bookable ? 'disabled' : ''}>确认预约</button>`}
      </aside>
    </div>`;
}

function bookingTable(rows, adminView) {
  if (!rows.length) return empty('暂无预约记录。');
  return `<div class="table-wrap"><table>
    <thead><tr>
      <th>预约 / 空间</th>${adminView ? '<th>预约人</th>' : ''}
      <th>预约时段</th><th>状态</th><th>签到 / 签退</th><th>操作</th>
    </tr></thead>
    <tbody>${rows.map(row => {
      const actions = [];
      if (adminView) {
        if (row.status === 'PENDING') {
          actions.push(button('确认违约', 'pending-confirm', row.id, 'danger'));
          actions.push(button('撤销待核查', 'pending-dismiss', row.id));
        } else if (['BOOKED', 'CHECKED_IN'].includes(row.status)) {
          actions.push(button('登记违约', 'violation', row.id, 'danger'));
        }
      } else {
        if (row.status === 'BOOKED') {
          actions.push(button('签到', 'check-in', row.id));
          actions.push(button('取消', 'cancel', row.id));
        }
        if (row.status === 'CHECKED_IN') actions.push(button('签退', 'check-out', row.id));
        if (row.status === 'COMPLETED') actions.push(button('评价', 'review', row.id));
        if (['BOOKED', 'CHECKED_IN'].includes(row.status)) {
          actions.push(`<button type="button" class="button compact" data-action="view-seat"
            data-room="${escapeHtml(row.room_id)}" data-start="${escapeHtml(String(row.start_at).slice(0, 16))}"
            data-end="${escapeHtml(String(row.end_at).slice(0, 16))}">查看座位</button>`);
        }
        actions.push(button('反馈', 'complaint', row.id));
      }
      return `<tr>
        <td><strong>#${row.id} · ${escapeHtml(row.room_name)}</strong>
          <span class="secondary">${escapeHtml(row.seat_code)} 号座位</span></td>
        ${adminView ? `<td>${escapeHtml(row.display_name)}<span class="secondary">${escapeHtml(row.username)}</span></td>` : ''}
        <td>${stamp(row.start_at)}<span class="secondary">至 ${stamp(row.end_at)}</span></td>
        <td>${bookingTag(row.status)}</td>
        <td>${stamp(row.check_in_at)}<span class="secondary">至 ${stamp(row.check_out_at)}</span></td>
        <td><div class="actions">${actions.join('') || '—'}</div></td>
      </tr>`;
    }).join('')}</tbody></table></div>`;
}

// FR-04/05/06/07/15/16/17：取消、签到签退、个人记录、使用记录、违约。
async function bookingPage() {
  const query = new URLSearchParams({ page: state.page, size: 20 });
  if (state.status) query.set('status', state.status);
  if (state.date) query.set('date', state.date);
  const result = await api(prefix() + '/reservations?' + query);
  if (state.view !== 'bookings') return;

  $('#content').innerHTML = heading(
    isAdmin() ? '预约管理' : '我的预约',
    isAdmin()
      ? '查看全部预约；待核查记录由管理员确认违约或撤销，不能代替学生签到。'
      : '签到、签退和取消会根据预约状态与北京时间校验。',
    isAdmin() ? button('执行履约检查', 'sweep') : button('去预约座位', 'go-seats')
  ) + `
    <section class="panel">
      <div class="toolbar">
        ${select('预约状态', 'status', [['', '全部状态'], ...Object.entries(bookingNames)], state.status)}
        ${field('开始日期', 'date', state.date, 'date')}
        <button class="button" data-action="filter-bookings">查询</button>
      </div>
      ${bookingTable(result.items, isAdmin())}
      <div class="pagination">
        <span>共 ${result.total} 条 · 第 ${state.page} 页</span>
        <button class="button compact" data-action="prev" ${state.page <= 1 ? 'disabled' : ''}>上一页</button>
        <button class="button compact" data-action="next" ${state.page * 20 >= result.total ? 'disabled' : ''}>下一页</button>
      </div>
    </section>`;
}

/* ---------------- 违约 ---------------- */

// FR-08/16/17：个人违约、待核查确认、手动违约与撤销。
async function violationPage() {
  const rows = await api(prefix() + '/violations');
  if (state.view !== 'violations') return;

  $('#content').innerHTML = heading(
    isAdmin() ? '违约处理' : '我的违约',
    isAdmin()
      ? '自动来源只表示“系统标记后经管理员确认”；手动登记同样写入审计日志。'
      : '展示本人违约记录；如有异议，可通过投诉说明预约号。',
    isAdmin() ? '' : button('提交投诉', 'complaint')
  ) + `<section class="panel">
    ${rows.length ? `<div class="table-wrap"><table>
      <thead><tr><th>预约</th>${isAdmin() ? '<th>学生</th>' : ''}<th>来源</th><th>原因 / 时间</th>
        <th>状态</th>${isAdmin() ? '<th>操作</th>' : ''}</tr></thead>
      <tbody>${rows.map(row => `<tr>
        <td>#${row.reservation_id}</td>
        ${isAdmin() ? `<td>${escapeHtml(row.display_name)}<span class="secondary">${escapeHtml(row.username)}</span></td>` : ''}
        <td>${row.source === 'AUTO' ? '系统待核查确认' : '管理员登记'}</td>
        <td>${escapeHtml(row.reason)}<span class="secondary">${stamp(row.created_at)}</span>
          ${row.revoke_reason ? `<span class="secondary">撤销：${escapeHtml(row.revoke_reason)}</span>` : ''}</td>
        <td>${row.revoked ? tag('已撤销') : tag('有效', 'red')}</td>
        ${isAdmin() ? `<td>${row.revoked ? '—' : button('撤销', 'revoke', row.id)}</td>` : ''}
      </tr>`).join('')}</tbody></table></div>` : empty('暂无违约记录。')}
    <p class="notice" style="margin-top:12px">
      黑名单规则：未撤销违约累计达到阈值（当前 ${escapeHtml('2')} 次）自动限制；撤销违约不会自动解除已有黑名单，需在“用户与黑名单”中单独处理。
    </p>
  </section>`;
}

/* ---------------- 投诉与评价 ---------------- */

// FR-10/11/21/22：投诉提交、查询、回复和状态维护。
async function complaintPage() {
  const rows = await api(prefix() + '/complaints');
  if (state.view !== 'complaints') return;
  state.complaints = rows;

  $('#content').innerHTML = heading(
    '投诉反馈',
    isAdmin() ? '处理学生投诉；状态不一致时服务端会拒绝覆盖。' : '提交后可在“待处理”阶段撤回；管理员回复会显示在这里。',
    isAdmin() ? '' : button('提交投诉 / 反馈', 'complaint', '', 'primary')
  ) + `<section class="panel">
    ${rows.length ? rows.map(row => `<article class="feedback-card">
      <div class="panel-head">
        <div><h3>#${row.id} · ${escapeHtml(row.topic)}</h3>
          <div class="feedback-meta">${isAdmin() ? escapeHtml(row.display_name) + ' · ' : ''}
            ${stamp(row.created_at)}${row.reservation_id ? ' · 关联预约 #' + row.reservation_id : ''}</div></div>
        <div class="actions">${complaintTag(row.status)}
          ${isAdmin() && ['PENDING', 'PROCESSING'].includes(row.status) ? button('处理 / 回复', 'reply', row.id) : ''}
          ${!isAdmin() && row.status === 'PENDING' ? button('撤回', 'withdraw', row.id) : ''}
        </div>
      </div>
      <p>${escapeHtml(row.content)}</p>
      ${row.reply ? `<div class="reply"><strong>管理员回复</strong> · ${stamp(row.updated_at)}<br>${escapeHtml(row.reply)}</div>` : ''}
    </article>`).join('') : empty('暂无投诉或反馈。')}
    <p class="muted small" style="margin-top:12px">最多展示最近 500 条。</p>
  </section>`;
}

function complaintModal(reservationId = '') {
  openModal('提交投诉 / 反馈', `
    ${field('关联预约号（选填，仅限本人预约）', 'reservationId', reservationId, 'number', 'min="1"')}
    ${field('主题', 'topic', '', 'text', 'required maxlength="80"')}
    ${area('问题描述', 'content')}`,
    async form => {
      await api('/complaints', 'POST', {
        reservationId: form.get('reservationId') ? Number(form.get('reservationId')) : null,
        topic: form.get('topic'),
        content: form.get('content')
      });
      await navigate('complaints');
    });
}

function replyModal(row) {
  openModal('回复投诉 #' + row.id, `
    <p class="notice">${escapeHtml(row.topic)}<br>${escapeHtml(row.content)}</p>
    <input type="hidden" name="expectedStatus" value="${escapeHtml(row.status)}">
    ${select('处理状态', 'status', [
      ['PENDING', '待处理'], ['PROCESSING', '处理中'], ['RESOLVED', '已解决']
    ], row.status)}
    ${area('回复内容', 'reply', row.reply || '')}`,
    async form => {
      await api('/admin/complaints/' + row.id, 'PUT', {
        status: form.get('status'),
        reply: form.get('reply'),
        expectedStatus: form.get('expectedStatus')
      });
      await complaintPage();
    });
}

// FR-09/20：提交评价和管理员隐藏/公开。
async function reviewPage() {
  const rows = await api(prefix() + '/reviews');
  if (state.view !== 'reviews') return;
  state.reviews = rows;

  $('#content').innerHTML = heading(
    isAdmin() ? '评价管理' : '空间评价',
    isAdmin() ? '隐藏或公开评价；隐藏不会删除记录。' : '完成使用后，在“我的预约”中提交一次评价。'
  ) + `<section class="panel">
    ${rows.length ? rows.map(row => `<article class="feedback-card">
      <div class="panel-head">
        <div><h3>${escapeHtml(row.room_name)} · ${escapeHtml(row.seat_code)} 号</h3>
          <div class="feedback-meta">${escapeHtml(row.display_name)} · ${stamp(row.created_at)}
            ${isAdmin() && !row.visible ? ' · 已隐藏' : ''}</div></div>
        <div class="actions"><span class="stars">${'★'.repeat(row.rating)}${'☆'.repeat(5 - row.rating)}</span>
          ${isAdmin() ? button(row.visible ? '隐藏' : '公开', 'visibility', row.id) : ''}</div>
      </div>
      <p>${escapeHtml(row.content)}</p>
    </article>`).join('') : empty('还没有评价。')}
    <p class="muted small" style="margin-top:12px">最多展示最近 500 条。</p>
  </section>`;
}

/* ---------------- 资源管理 ---------------- */

// FR-12/13：自习室与座位属性维护。
async function resourcePage() {
  const rooms = await api('/admin/rooms');
  if (state.view !== 'resources') return;
  state.roomData = rooms;

  $('#content').innerHTML = heading(
    '自习室与座位',
    '维护空间属性与开放状态；存在有效预约时不能修改或删除资源。',
    button('新建自习室', 'room-add', '', 'primary')
  ) + `<div class="stack">
    ${rooms.length ? rooms.map(room => `<section class="panel">
      <div class="panel-head">
        <div><h2>${escapeHtml(room.name)} ${room.enabled ? tag('开放', 'green') : tag('停用', 'red')}</h2>
          <p class="muted small" style="margin:4px 0 0">${escapeHtml(room.building)} · ${room.floor_no} 层 ·
            ${escapeHtml(room.open_time)}—${escapeHtml(room.close_time)} · ${room.seat_count} 个座位 ·
            ${room.is_quiet ? '安静区' : '普通区'}</p></div>
        <div class="actions">${button('管理座位', 'seat-admin', room.id)}
          ${button('编辑', 'room-edit', room.id)}${button('删除', 'room-delete', room.id, 'danger')}</div>
      </div>
      <div id="room-seats-${room.id}"></div>
    </section>`).join('') : empty('请新建自习室。')}
  </div>`;
}

async function adminSeats(roomId) {
  const rows = await api(`/admin/rooms/${roomId}/seats`);
  state.seats = rows;
  state.roomId = Number(roomId);
  document.querySelectorAll('[id^="room-seats-"]').forEach(el => { el.innerHTML = ''; });
  const target = $('#room-seats-' + roomId);
  if (!target) return;
  target.innerHTML = `
    <div class="toolbar" style="margin-top:12px">
      ${button('添加座位', 'seat-add', roomId)}<span class="muted small">有历史预约的座位请停用，不要删除。</span>
    </div>
    <div class="table-wrap"><table>
      <thead><tr><th>座位号</th><th>属性</th><th>状态</th><th>操作</th></tr></thead>
      <tbody>${rows.map(seat => `<tr>
        <td>${escapeHtml(seat.code)}</td>
        <td>${seat.has_power ? '有电源' : '普通位'}</td>
        <td>${seat.enabled ? tag('启用', 'green') : tag('停用', 'red')}</td>
        <td><div class="actions">${button('编辑', 'seat-edit', seat.id)}${button('删除', 'seat-delete', seat.id, 'danger')}</div></td>
      </tr>`).join('')}</tbody></table></div>`;
}

function roomModal(room) {
  const value = room || {
    name: '', building: '图书馆', floor_no: 1, open_time: '07:00', close_time: '23:00',
    description: '', enabled: true, is_quiet: true
  };
  openModal(room ? '编辑自习室' : '新建自习室', `<div class="form-grid">
      ${field('名称', 'name', value.name, 'text', 'required maxlength="60"')}
      ${field('楼宇', 'building', value.building, 'text', 'required maxlength="80"')}
      ${number('楼层', 'floorNo', value.floor_no, -5, 150)}
      ${field('开放时间', 'openTime', value.open_time, 'time', 'required')}
      ${field('关闭时间', 'closeTime', value.close_time, 'time', 'required')}
      ${check('启用自习室', 'enabled', value.enabled)}
      ${check('安静区教室', 'isQuiet', value.is_quiet)}
      ${area('空间说明', 'description', value.description, 500, false)}
    </div>`,
    async form => {
      await api('/admin/rooms' + (room ? '/' + room.id : ''), room ? 'PUT' : 'POST', {
        name: form.get('name'), building: form.get('building'), floorNo: Number(form.get('floorNo')),
        openTime: form.get('openTime'), closeTime: form.get('closeTime'),
        description: form.get('description') || '', enabled: form.has('enabled'),
        isQuiet: form.has('isQuiet')
      });
      await resourcePage();
    });
}

function seatModal(seat, roomId) {
  const value = seat || { code: '', has_power: true, enabled: true };
  openModal(seat ? '编辑座位' : '添加座位', `
      ${field('座位编号', 'code', value.code, 'text', 'required maxlength="20"')}
      ${check('配备电源', 'hasPower', value.has_power)}
      ${check('启用座位', 'enabled', value.enabled)}`,
    async form => {
      await api('/admin/seats' + (seat ? '/' + seat.id : ''), seat ? 'PUT' : 'POST', {
        roomId: Number(roomId), code: form.get('code'),
        hasPower: form.has('hasPower'), enabled: form.has('enabled')
      });
      await resourcePage();
      await adminSeats(roomId);
    });
}

/* ---------------- 用户与黑名单 ---------------- */

async function userPage(query = '') {
  const users = await api('/admin/users?query=' + encodeURIComponent(query));
  if (state.view !== 'users') return;
  state.users = users;

  $('#content').innerHTML = heading(
    '用户与黑名单',
    '维护学生账号、启停状态和预约限制；黑名单记录保留原因和解除痕迹。',
    button('新增学生', 'user-add', '', 'primary')
  ) + `<section class="panel">
    <div class="toolbar">
      ${field('搜索账号 / 姓名', 'query', query, 'search')}
      <button class="button" data-action="search-users">搜索</button>
    </div>
    <div class="table-wrap"><table>
      <thead><tr><th>账号</th><th>姓名</th><th>角色 / 状态</th><th>黑名单截止</th><th>操作</th></tr></thead>
      <tbody>${users.map(user => `<tr>
        <td>${escapeHtml(user.username)}</td>
        <td>${escapeHtml(user.display_name)}</td>
        <td>${user.role === 'ADMIN' ? '管理员' : '学生'} ${user.enabled ? tag('正常', 'green') : tag('停用', 'red')}</td>
        <td>${stamp(user.blacklist_until)}</td>
        <td><div class="actions">
          ${button('编辑', 'user-edit', user.id)}
          ${button('加入黑名单', 'blacklist', user.id, 'danger')}
          ${button('解除限制', 'release-blacklist', user.id)}
          ${button('记录', 'blacklist-history', user.id)}
        </div></td>
      </tr>`).join('')}</tbody></table></div>
  </section>`;
}

function userAddModal() {
  openModal('新增学生账号', `
      ${field('账号', 'username', '', 'text', 'required pattern="[a-zA-Z0-9_]{4,32}" maxlength="32"')}
      ${field('姓名 / 昵称', 'displayName', '', 'text', 'required maxlength="40"')}
      ${field('初始密码', 'password', '', 'password', 'required minlength="8" maxlength="72"')}
      <p class="notice">新账号固定为学生角色；账号和密码由管理员创建。</p>`,
    async form => {
      await api('/admin/users', 'POST', {
        username: form.get('username'), displayName: form.get('displayName'), password: form.get('password')
      });
      await userPage();
    });
}

function userEditModal(user) {
  openModal('编辑用户 · ' + user.username, `
      ${field('姓名 / 昵称', 'displayName', user.display_name, 'text', 'required maxlength="40"')}
      ${check('账号启用', 'enabled', user.enabled)}
      <p class="notice">停用账号会立即限制登录和业务接口；已有预约仍需在预约管理中处理。</p>`,
    async form => {
      await api('/admin/users/' + user.id, 'PUT', {
        displayName: form.get('displayName'), enabled: form.has('enabled')
      });
      await userPage();
    });
}

function blacklistModal(user) {
  openModal('加入黑名单 · ' + user.username, `
      ${number('限制天数', 'days', 7, 1, 365)}
      ${area('限制原因', 'reason')}
      <p class="notice">写入 blacklist_records 表；到期自动失效，也可在用户列表手动解除。</p>`,
    async form => {
      await api('/admin/users/' + user.id + '/blacklist', 'POST', {
        days: Number(form.get('days')), reason: form.get('reason')
      });
      await userPage();
    });
}

function releaseBlacklistModal(user) {
  openModal('解除预约限制 · ' + user.username, `${area('解除原因', 'reason')}`,
    async form => {
      await api('/admin/users/' + user.id + '/blacklist/release', 'POST', { reason: form.get('reason') });
      await userPage();
    });
}

async function blacklistHistoryModal(user) {
  const rows = await api('/admin/users/' + user.id + '/blacklist');
  const body = rows.length ? `<div class="table-wrap"><table>
      <thead><tr><th>原因</th><th>起止</th><th>状态</th><th>解除</th></tr></thead>
      <tbody>${rows.map(row => `<tr>
        <td>${escapeHtml(row.reason)}</td>
        <td>${stamp(row.start_at)}<span class="secondary">至 ${stamp(row.end_at)}</span></td>
        <td>${row.active ? tag('有效', 'red') : tag('已解除')}</td>
        <td>${row.release_reason ? escapeHtml(row.release_reason) : '—'}</td>
      </tr>`).join('')}</tbody></table></div>` : empty('暂无黑名单记录。');
  openModal('黑名单记录 · ' + user.username, body, null);
}

/* ---------------- 规则与审计 ---------------- */

// 规则说明页：展示教学参数，不显示需求编号。
async function rulesPage() {
  const policy = await api('/policy');
  if (state.view !== 'rules') return;
  const row = (item, value, note) => `<tr>
    <td>${item}</td><td>${escapeHtml(value)}</td><td>${escapeHtml(note)}</td></tr>`;

  $('#content').innerHTML = heading(
    '规则说明',
    '以下为当前演示系统使用的教学参数，可按课程要求调整。'
  ) + `<section class="panel">
    <div class="table-wrap"><table>
      <thead><tr><th>项目</th><th>当前值</th><th>说明</th></tr></thead>
      <tbody>
        ${row('可提前预约天数', policy.max_days + ' 天（含今天）', '限制预约日期范围')}
        ${row('单次预约时长', '至少 15 分钟，最多 ' + policy.max_hours + ' 小时', '过短或过长的预约会被拒绝')}
        ${row('开放时间', '07:00—23:00，不支持跨夜', '自习室属性')}
        ${row('签到窗口', '开始前 ' + policy.check_in_before + ' 分钟至开始后 ' + policy.grace_minutes + ' 分钟', '超出窗口不能签到')}
        ${row('取消规则', '仅“已预约”且开始前可由本人取消', '已开始或已签到的预约不能取消')}
        ${row('每人待使用预约上限', policy.max_active + ' 条', '防止大量占座')}
        ${row('未签到处理', '30 秒巡检转为“待核查”，管理员确认后才记违约', '不会直接处罚')}
        ${row('违约阈值', policy.blacklist_threshold + ' 次有效违约', '达到后限制新预约')}
        ${row('黑名单期限', policy.blacklist_days + ' 天', '到期自动解除，也可管理员手动解除')}
        ${row('统计刷新', '10 秒', '数据概览和座位页自动刷新')}
        ${row('后台巡检', '30 秒（演示用）', '便于课堂观察')}
      </tbody></table></div>
    <p class="notice" style="margin-top:12px">
      这些是演示参数，不是生产规则。修改参数后需要重启服务或重新构建程序才能生效。
    </p>
  </section>`;
}

async function auditPage() {
  const rows = await api('/admin/audits');
  if (state.view !== 'audits') return;
  $('#content').innerHTML = heading('操作日志', '关键业务操作和自动任务留痕。',
    button('刷新', 'refresh')) + `<section class="panel">
    ${rows.length ? `<div class="table-wrap"><table>
      <thead><tr><th>时间</th><th>操作者</th><th>动作</th><th>对象</th><th>说明</th></tr></thead>
      <tbody>${rows.map(row => `<tr>
        <td>${stamp(row.created_at)}</td><td>${escapeHtml(row.actor)}</td>
        <td>${escapeHtml(row.action)}</td><td>${escapeHtml(row.target_type)} #${row.target_id ?? ''}</td>
        <td>${escapeHtml(row.detail)}</td></tr>`).join('')}</tbody></table></div>` : empty('暂无操作日志。')}
  </section>`;
}

/* ---------------- 弹窗与事件 ---------------- */

function openModal(title, body, onSubmit = async () => {}, submitLabel = '保存') {
  $('#modal-title').textContent = title;
  $('#modal-body').innerHTML = body;
  $('#modal-error').textContent = '';
  const submit = $('#modal-form button[type=submit]');
  submit.textContent = submitLabel;
  submit.style.display = onSubmit ? '' : 'none';
  modalSubmit = onSubmit;
  $('#modal').showModal();
}

async function runBusy(buttonEl, task) {
  if (buttonEl) buttonEl.disabled = true;
  try {
    await task();
  } catch (error) {
    notify(error.message);
  } finally {
    if (buttonEl) buttonEl.disabled = false;
  }
}

$('#modal-form').onsubmit = async event => {
  event.preventDefault();
  const submit = event.submitter;
  submit.disabled = true;
  $('#modal-error').textContent = '';
  try {
    if (!modalSubmit) {
      $('#modal').close();
      return;
    }
    await modalSubmit(new FormData(event.target));
    $('#modal').close();
    notify('操作成功');
  } catch (error) {
    $('#modal-error').textContent = error.message;
  } finally {
    submit.disabled = false;
  }
};

document.addEventListener('click', async event => {
  const close = event.target.closest('[data-close]');
  if (close) {
    $('#modal').close();
    return;
  }

  const nav = event.target.closest('[data-view]');
  if (nav) {
    if (nav.dataset.view === 'bookings') {
      state.page = 1;
      state.status = '';
      state.date = '';
    }
    await navigate(nav.dataset.view);
    return;
  }

  const trigger = event.target.closest('[data-action]');
  if (!trigger) return;
  const action = trigger.dataset.action;
  const id = trigger.dataset.id;

  await runBusy(trigger, async () => {
    switch (action) {
      case 'logout':
        await api('/auth/logout', 'POST');
        showLogin();
        break;
      case 'toggle-sidebar':
        document.body.classList.toggle('sidebar-expanded');
        updateSidebarToggle();
        break;
      case 'refresh':
        await navigate(state.view);
        break;
      case 'go-seats':
        await navigate('seats');
        break;
      case 'view-seat':
        // 以该预约的时段和自习室为准，窗口对齐后座位才会显示“已预约”。
        state.roomId = Number(trigger.dataset.room);
        state.start = trigger.dataset.start;
        state.end = trigger.dataset.end;
        state.quiet = false;
        state.selected = null;
        await navigate('seats');
        break;
      case 'go-bookings':
        state.page = 1;
        await navigate('bookings');
        break;
      case 'filter-seats':
        state.start = $('#start').value;
        state.end = $('#end').value;
        state.power = $('#power').checked;
        state.quiet = $('#quiet').checked;
        state.selected = null;
        if (!state.start || !state.end) throw new Error('请选择完整的时间范围');
        await seatPage();
        break;
      case 'pick-room':
        state.start = $('#start').value;
        state.end = $('#end').value;
        state.power = $('#power').checked;
        state.quiet = $('#quiet').checked;
        state.roomId = Number(id);
        state.selected = null;
        await seatPage();
        break;
      case 'pick-seat':
        state.selected = Number(id);
        await loadSeats();
        break;
      case 'confirm-book':
        if (!state.selected) throw new Error('请先选择座位');
        await api('/reservations', 'POST', { seatId: state.selected, startAt: state.start, endAt: state.end });
        state.selected = null;
        state.page = 1;
        state.status = '';
        state.date = '';
        await navigate('bookings');
        notify('预约成功，请按时签到');
        break;
      case 'filter-bookings':
        state.status = $('#f-status').value;
        state.date = $('#f-date').value;
        state.page = 1;
        await bookingPage();
        break;
      case 'prev':
        state.page--;
        await bookingPage();
        break;
      case 'next':
        state.page++;
        await bookingPage();
        break;
      case 'check-in':
      case 'check-out':
        await api('/reservations/' + id + '/' + action, 'POST');
        await navigate(state.view);
        notify(action === 'check-in' ? '签到成功' : '签退成功');
        break;
      case 'cancel':
        openModal('确认取消预约？', `<p>预约 #${escapeHtml(id)} 将变为“已取消”，座位释放。</p>`,
          async () => {
            await api('/reservations/' + id + '/cancel', 'POST');
            await navigate(state.view);
          });
        break;
      case 'sweep':
        const result = await api('/admin/sweep', 'POST');
        await bookingPage();
        notify('履约检查完成，处理 ' + result.processed + ' 条记录');
        break;
      case 'pending-confirm':
        openModal('确认未签到违约 #' + id, `${area('确认理由', 'reason', '管理员确认超时未签到')}`,
          async form => {
            await api('/admin/reservations/' + id + '/confirm-violation', 'POST', { reason: form.get('reason') });
            await bookingPage();
          });
        break;
      case 'pending-dismiss':
        openModal('撤销待核查 #' + id, `${area('撤销理由', 'reason')}`,
          async form => {
            await api('/admin/reservations/' + id + '/dismiss-pending', 'POST', { reason: form.get('reason') });
            await bookingPage();
          });
        break;
      case 'violation':
        openModal('手动登记违约 #' + id, `${area('违约原因', 'reason')}
          <p class="notice">若预约仍在“已预约/已签到/待核查”，会同时转为“已违约”；每个预约最多一条违约记录。</p>`,
          async form => {
            await api('/admin/violations', 'POST', { reservationId: Number(id), reason: form.get('reason') });
            await navigate(state.view);
          });
        break;
      case 'revoke':
        openModal('撤销违约', `${area('撤销原因', 'reason')}`,
          async form => {
            await api('/admin/violations/' + id + '/revoke', 'POST', { reason: form.get('reason') });
            await violationPage();
          });
        break;
      case 'complaint':
        complaintModal(id || '');
        break;
      case 'withdraw':
        openModal('撤回投诉 #' + id, '<p>撤回后记录保留，但不再进入管理员待处理列表。</p>',
          async () => {
            await api('/complaints/' + id + '/withdraw', 'POST');
            await complaintPage();
          });
        break;
      case 'reply':
        replyModal(state.complaints.find(item => item.id === Number(id)));
        break;
      case 'review':
        openModal('评价本次自习 #' + id, `
            ${select('评分', 'rating', [[5, '5 分'], [4, '4 分'], [3, '3 分'], [2, '2 分'], [1, '1 分']], 5)}
            ${area('使用体验', 'content', '', 1000)}`,
          async form => {
            await api('/reviews', 'POST', {
              reservationId: Number(id), rating: Number(form.get('rating')), content: form.get('content')
            });
            await navigate('reviews');
          });
        break;
      case 'visibility':
        const review = state.reviews.find(item => item.id === Number(id));
        await api('/admin/reviews/' + id, 'PUT', { visible: !review.visible });
        await reviewPage();
        notify('评价展示状态已更新');
        break;
      case 'room-add':
        roomModal(null);
        break;
      case 'room-edit':
        roomModal(state.roomData.find(item => item.id === Number(id)));
        break;
      case 'room-delete':
      case 'seat-delete':
        openModal('确认删除？', '<p>仅无历史关联的资源可删除；有历史预约的资源请停用。</p>',
          async () => {
            await api('/admin/' + (action === 'room-delete' ? 'rooms/' : 'seats/') + id, 'DELETE');
            await resourcePage();
          });
        break;
      case 'seat-admin':
        await adminSeats(id);
        break;
      case 'seat-add':
        seatModal(null, id);
        break;
      case 'seat-edit':
        seatModal(state.seats.find(item => item.id === Number(id)), state.roomId);
        break;
      case 'search-users':
        await userPage($('#f-query').value);
        break;
      case 'user-add':
        userAddModal();
        break;
      case 'user-edit':
        userEditModal(state.users.find(item => item.id === Number(id)));
        break;
      case 'blacklist':
        blacklistModal(state.users.find(item => item.id === Number(id)));
        break;
      case 'release-blacklist':
        releaseBlacklistModal(state.users.find(item => item.id === Number(id)));
        break;
      case 'blacklist-history':
        await blacklistHistoryModal(state.users.find(item => item.id === Number(id)));
        break;
    }
  });
});

window.addEventListener('resize', () => {
  if (state.view === 'dashboard' && isAdmin()) {
    clearTimeout(window.chartResize);
    window.chartResize = setTimeout(() => dashboard().catch(() => {}), 200);
  }
});

(async () => {
  try {
    state.user = await api('/auth/me');
    shell();
    await navigate('dashboard');
  } catch {
    showLogin();
  }
})();
