/* ===== SOUNDWAVE RESONANCE — 旧聊音乐广场 原生 JS（无依赖） ===== */
(function () {
  'use strict';

  var API = '/v1';
  var PAGE_SIZE = 30;

  /* ---------- 认证 ---------- */
  function loadAuth() {
    try {
      var raw = localStorage.getItem('oldchat_web_auth');
      if (raw) {
        var parsed = JSON.parse(raw);
        if (parsed && parsed.access_token) return parsed;
      }
    } catch (e) {}
    return null;
  }

  function getToken() {
    var auth = loadAuth();
    return auth ? auth.access_token : '';
  }

  /* ---------- 工具 ---------- */
  function $(sel) { return document.querySelector(sel); }
  function $$(sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); }

  function apiRequest(path, options) {
    options = options || {};
    var method = options.method || 'GET';
    var headers = options.headers || {};
    var token = getToken();
    if (token) headers['Authorization'] = 'Bearer ' + token;
    var body = options.body;
    if (body && !(body instanceof FormData)) {
      headers['Content-Type'] = headers['Content-Type'] || 'application/json';
      body = JSON.stringify(body);
    }
    return fetch(API + path, { method: method, headers: headers, body: body }).then(function (resp) {
      return resp.json().catch(function () { return {}; }).then(function (data) {
        return { code: resp.status, data: data };
      });
    });
  }

  function esc(str) {
    return String(str == null ? '' : str)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function fmtTime(sec) {
    if (isNaN(sec) || sec < 0) return '00:00';
    sec = Math.floor(sec);
    var m = Math.floor(sec / 60);
    var s = sec % 60;
    return (m < 10 ? '0' + m : m) + ':' + (s < 10 ? '0' + s : s);
  }

  function fmtSize(bytes) {
    if (!bytes) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1048576).toFixed(1) + ' MB';
  }

  function fmtCount(n) {
    if (!n) return '0';
    if (n >= 10000) return (n / 10000).toFixed(1) + 'w';
    if (n >= 1000) return (n / 1000).toFixed(1) + 'k';
    return String(n);
  }

  function toast(msg, isErr) {
    var el = $('#toast');
    if (!el) {
      el = document.createElement('div');
      el.id = 'toast';
      document.body.appendChild(el);
    }
    el.textContent = msg;
    el.className = 'show' + (isErr ? ' err' : '');
    clearTimeout(toast._t);
    toast._t = setTimeout(function () { el.className = ''; }, 2600);
  }

  /* ---------- 多源回源（对齐安卓 MediaUrlResolver） ----------
     候选线路优先级：
     1. ocf.oss-cn-shanghai.aliyuncs.com（阿里云 OSS 直连，华东2 上海）
     2. 60.205.94.101:8080（60 服务器，反代但保留迁移前资源）
     3. oc.mcl0.dpdns.org（主域名）
     4. 当前页面源站
     播放失败自动切下一条；线路故障冷却 60 秒；成功恢复优先级。 */
  var MEDIA_ORIGINS = [
    'https://ocf.oss-cn-shanghai.aliyuncs.com',
    'http://60.205.94.101:8080',
    'https://oc.mcl0.dpdns.org'
  ];
  var LINE_FAILURE_COOLDOWN_MS = 60 * 1000;
  var failedLines = {}; // origin -> until(ms)

  function currentOrigin() {
    return window.location.protocol + '//' + window.location.host;
  }

  /** 回源线路（随页面协议动态）：HTTPS 页面跳过 http-only 的 60 线路（避免 Mixed Content 拦截）。 */
  function mediaOrigins() {
    var isHttps = window.location.protocol === 'https:';
    var out = [];
    // ocf OSS 直连优先（https 页面也用 https）
    out.push('https://ocf.oss-cn-shanghai.aliyuncs.com');
    // 60 仅 http，https 页面跳过
    if (!isHttps) out.push('http://60.205.94.101:8080');
    // oc 主域 https
    out.push('https://oc.mcl0.dpdns.org');
    return out;
  }

  function normOrigin(o) {
    return String(o || '').trim().replace(/\/+$/, '');
  }

  function buildOriginFrom(url) {
    try {
      var u = new URL(url, currentOrigin());
      return normOrigin(u.protocol + '//' + u.host);
    } catch (e) { return ''; }
  }

  function isUploadPath(p) {
    var s = String(p || '');
    return s.indexOf('/uploads/') >= 0 || s.indexOf('/v1/uploads/') >= 0 ||
      s.indexOf('/media/') >= 0 || s.indexOf('/music/') >= 0 ||
      s.indexOf('/lyrics/') >= 0 || s.indexOf('/avatars/') >= 0 ||
      s.indexOf('/covers/') >= 0 || s.indexOf('/oss/') >= 0;
  }

  /** 提取上传资源相对路径（去掉 host 和 /v1 前缀）。 */
  function extractUploadPath(p) {
    var s = String(p || '');
    var u;
    try { u = new URL(s, currentOrigin()); } catch (e) { return s; }
    var path = u.pathname;
    // 对齐安卓：/v1/uploads/ 剥掉后 files 域名用 /music/xxx 对象路径
    if (path.indexOf('/v1/uploads/') === 0) return path.substring(11);
    if (path.indexOf('/uploads/') === 0) return path.substring(8);
    if (path.indexOf('/oss/') === 0) return path.substring(4);
    if (path.indexOf('/media/') === 0 || path.indexOf('/music/') === 0 ||
      path.indexOf('/lyrics/') === 0 || path.indexOf('/avatars/') === 0 ||
      path.indexOf('/covers/') === 0) return path;
    return path;
  }

  /** 相对上传路径 → 应用内完整路径（/v1/uploads/...）。 */
  function toAppUploadPath(p) {
    var s = String(p || '');
    var u;
    try { u = new URL(s, currentOrigin()); } catch (e) { u = null; }
    if (u) s = u.pathname;
    if (s.indexOf('/v1/uploads/') === 0) return s;
    if (s.indexOf('/uploads/') === 0) return '/v1' + s;
    if (s.indexOf('/oss/') === 0) return '/v1/uploads/' + s.substring(5);
    if (s.indexOf('/media/') === 0 || s.indexOf('/music/') === 0 ||
      s.indexOf('/lyrics/') === 0 || s.indexOf('/avatars/') === 0 ||
      s.indexOf('/covers/') === 0) return '/v1/uploads' + s;
    return s;
  }

  /** 生成上传资源的全部候选 URL（按优先级）。 */
  function buildUploadCandidates(url) {
    var raw = String(url || '').trim();
    if (!raw) return [];
    var isAbs = /^https?:\/\//i.test(raw);
    var ossPath, appPath;
    var ossPathRaw = extractUploadPath(raw);
    ossPath = ossPathRaw.indexOf('/') === 0 ? ossPathRaw : '/' + ossPathRaw;
    if (isAbs) {
      var origin = buildOriginFrom(raw);
      // 已知官方源/OSS 才改写；第三方绝对地址保留原样
      var known = mediaOrigins().concat([currentOrigin()]).some(function (o) {
        return normOrigin(o) === origin;
      });
      if (!known) return [raw];
      appPath = toAppUploadPath(ossPath);
    } else {
      appPath = toAppUploadPath(raw);
    }
    var cands = [];
    var origins = mediaOrigins();
    if (/^https?:\/\//i.test(appPath)) {
      cands.push(appPath);
    } else {
      for (var oi = 0; oi < origins.length; oi++) {
        // files 用 OSS 对象路径，其余用 app 路径
        cands.push(origins[oi] + (oi === 0 ? ossPath : appPath));
      }
      cands.push(currentOrigin() + appPath);
    }
    // 去重
    var seen = {}, out = [];
    cands.forEach(function (c) { if (c && !seen[c]) { seen[c] = 1; out.push(c); } });
    return out;
  }

  function lineKeyOf(url) {
    return buildOriginFrom(url);
  }

  function prioritizeHealthy(cands) {
    if (!cands || cands.length < 2) return cands || [];
    var now = Date.now();
    var healthy = [], cooling = [];
    cands.forEach(function (c) {
      var key = lineKeyOf(c);
      if (key && failedLines[key] && failedLines[key] > now) cooling.push(c);
      else { healthy.push(c); if (key) delete failedLines[key]; }
    });
    return healthy.concat(cooling);
  }

  function reportLineFailure(url) {
    var key = lineKeyOf(url);
    if (key) failedLines[key] = Date.now() + LINE_FAILURE_COOLDOWN_MS;
  }

  function reportLineSuccess(url) {
    var key = lineKeyOf(url);
    if (key) delete failedLines[key];
  }

  /* ---------- 封面图片安全解析与回退机制 ---------- */
  var DEFAULT_COVER_SVG = 'data:image/svg+xml;utf8,<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 200 200"><rect width="200" height="200" fill="%2318181b"/><circle cx="100" cy="100" r="75" fill="%23111114" stroke="%2333333a" stroke-width="3"/><circle cx="100" cy="100" r="50" fill="none" stroke="%2327272a" stroke-width="1.5"/><circle cx="100" cy="100" r="25" fill="%23e05a47"/><circle cx="100" cy="100" r="8" fill="%23111114"/></svg>';

  function resolveCoverUrl(url) {
    if (!url || typeof url !== 'string') return DEFAULT_COVER_SVG;
    var trimmed = url.trim();
    if (!trimmed) return DEFAULT_COVER_SVG;
    if (/^(https?:|\/\/|data:)/i.test(trimmed)) {
      return trimmed;
    }
    if (trimmed.indexOf('/') === 0) {
      return trimmed;
    }
    return '/' + trimmed;
  }

  function setCoverImage(img, url) {
    if (!img) return;
    var finalUrl = resolveCoverUrl(url);
    img.onerror = function () {
      this.onerror = null;
      this.src = DEFAULT_COVER_SVG;
      this.style.opacity = '0.85';
    };
    img.src = finalUrl;
  }

  /** 解析出最优可用音频候选（首条）。 */
  function resolveMediaUrl(url) {
    var cands = prioritizeHealthy(buildUploadCandidates(url));
    return cands.length ? cands[0] : (url || '');
  }

  /** 从当前 URL 切到下一条候选（播放失败回退）。 */
  function resolveNextCandidate(url) {
    var cands = prioritizeHealthy(buildUploadCandidates(url));
    var cur = String(url || '');
    for (var i = 0; i < cands.length; i++) {
      if (cur === cands[i]) return i + 1 < cands.length ? cands[i + 1] : '';
    }
    return cands.length > 1 ? cands[1] : '';
  }

  /** 给媒体资源绑定回源（封面等 img）：加载失败自动切下一条候选。 */
  function bindMediaFallback(img, url) {
    setCoverImage(img, url);
  }

  /* ---------- 图标：emoji → SVG 矢量 ---------- */
  var ICON_SVG = {
    discover: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="3" fill="currentColor" stroke="none"/></svg>',
    heart: '<svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" stroke="currentColor" stroke-width="1.5"><path d="M12 21s-7-4.6-9.3-9C1 8.5 3 5 6.5 5c2 0 3.4 1 4 2 .6-1 2-2 4-2 3.5 0 5.5 3.5 3.8 7C19 16.4 12 21 12 21z"/></svg>',
    playlist: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 6h12M4 12h8M4 18h10"/><path d="M18 9l4-2v8"/><circle cx="17" cy="15" r="2.5"/></svg>',
    mine: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 15a4 4 0 0 0 4-4V7a4 4 0 0 0-8 0v4a4 4 0 0 0 4 4z"/><path d="M4 12v1a8 8 0 0 0 16 0v-1"/><line x1="12" y1="21" x2="12" y2="19"/></svg>',
    upload: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M12 16V4"/><path d="M6 10l6-6 6 6"/><path d="M4 20h16"/></svg>',
    search: '<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16" y2="16"/></svg>',
    refresh: '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 12a9 9 0 1 1-2.6-6.3"/><polyline points="21 3 21 9 15 9"/></svg>',
    shuffle: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><polyline points="16 3 21 3 21 8"/><line x1="4" y1="20" x2="21" y2="3"/><polyline points="21 16 21 21 16 21"/><line x1="15" y1="15" x2="21" y2="21"/><line x1="4" y1="4" x2="9" y2="9"/></svg>',
    prev: '<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M6 5h2v14H6z"/><path d="M20 5l-10 7 10 7z"/></svg>',
    play: '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>',
    pause: '<svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/></svg>',
    next: '<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M16 5h2v14h-2z"/><path d="M4 5l10 7-10 7z"/></svg>',
    loop: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M17 2l4 4-4 4"/><path d="M3 11v-1a4 4 0 0 1 4-4h14"/><path d="M7 22l-4-4 4-4"/><path d="M21 13v1a4 4 0 0 1-4 4H3"/></svg>',
    mute: '<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" fill="currentColor" stroke="none"/><line x1="15" y1="9" x2="21" y2="15"/><line x1="21" y1="9" x2="15" y2="15"/></svg>',
    volume: '<svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" fill="currentColor" stroke="none"/><path d="M15.5 8.5a5 5 0 0 1 0 7"/><path d="M18.5 5.5a9 9 0 0 1 0 13"/></svg>',
    expand: '<svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 9V4h5"/><path d="M20 15v5h-5"/><path d="M4 15v5h5"/><path d="M20 9V4h-5"/></svg>',
    back: '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"><polyline points="15 18 9 12 15 6"/></svg>',
    star: '<svg viewBox="0 0 24 24" width="17" height="17" fill="currentColor"><path d="M12 2l3 6.6 7 .9-5.2 4.8 1.4 7L12 17.7 5.8 21.3l1.4-7L2 9.5l7-.9z"/></svg>',
    like: '<svg viewBox="0 0 24 24" width="17" height="17" fill="currentColor" stroke="currentColor" stroke-width="1.5"><path d="M12 21s-7-4.6-9.3-9C1 8.5 3 5 6.5 5c2 0 3.4 1 4 2 .6-1 2-2 4-2 3.5 0 5.5 3.5 3.8 7C19 16.4 12 21 12 21z"/></svg>'
  };

  function svgIcon(name) {
    return ICON_SVG[name] || '';
  }

  function renderIcons() {
    // 播放按钮（动态切换播放/暂停）
    var playBtns = $$('.play-btn, .fs-play');
    // 侧边栏/移动端 tab 图标
    var tabMap = { explore: 'discover', favorites: 'heart', playlists: 'playlist', mine: 'mine', upload: 'upload' };
    $$('.menu-item').forEach(function (b) {
      var icon = b.querySelector('.mi-icon');
      if (icon && tabMap[b.dataset.tab]) icon.innerHTML = svgIcon(tabMap[b.dataset.tab]);
    });
    $$('.mobile-tabs button').forEach(function (b) {
      if (tabMap[b.dataset.tab]) b.innerHTML = svgIcon(tabMap[b.dataset.tab]);
    });
    // 搜索
    var s = document.querySelector('.search-icon');
    if (s) s.innerHTML = svgIcon('search');
    // 刷新按钮
    if (els.btnPlaylistRefresh) els.btnPlaylistRefresh.innerHTML = svgIcon('refresh') + ' 刷新';
    if (els.btnMineRefresh) els.btnMineRefresh.innerHTML = svgIcon('refresh') + ' 刷新';
    // 底部播放条控制
    if (els.btnShuffle) els.btnShuffle.innerHTML = svgIcon('shuffle');
    if (els.btnPrev) els.btnPrev.innerHTML = svgIcon('prev');
    if (els.btnPlay) els.btnPlay.innerHTML = svgIcon(state.playback.isPlaying ? 'pause' : 'play');
    if (els.btnNext) els.btnNext.innerHTML = svgIcon('next');
    if (els.btnLoop) els.btnLoop.innerHTML = svgIcon('loop');
    if (els.btnMute) els.btnMute.innerHTML = svgIcon(state.playback.isMuted || state.playback.volume === 0 ? 'mute' : 'volume');
    if (els.btnExpand) els.btnExpand.innerHTML = svgIcon('expand');
    if (els.btnPlayerFav) els.btnPlayerFav.innerHTML = svgIcon('heart');
    // 全屏顶部
    if (els.fsBack) els.fsBack.innerHTML = svgIcon('back');
    if (els.fsLike) els.fsLike.innerHTML = svgIcon('like');
    if (els.fsFav) els.fsFav.innerHTML = svgIcon('star');
    if (els.fsShuffle) els.fsShuffle.innerHTML = svgIcon('shuffle');
    if (els.fsPrev) els.fsPrev.innerHTML = svgIcon('prev');
    if (els.fsPlay) els.fsPlay.innerHTML = svgIcon(state.playback.isPlaying ? 'pause' : 'play');
    if (els.fsNext) els.fsNext.innerHTML = svgIcon('next');
    if (els.fsLoop) els.fsLoop.innerHTML = svgIcon('loop');
    // 歌曲卡片上的收藏按钮（动态创建，用 CSS 画心形即可，JS 不处理）
    // 评论标题
    var cTitle = document.querySelector('.comments-card .lyrics-mini-title');
    if (cTitle) cTitle.innerHTML = svgIcon('mine') + ' Comments / 评论';
  }

  /* ---------- 状态 ---------- */
  var state = {
    activeTab: 'explore',
    songs: [],          // 当前列表（含分页累积）
    total: 0,
    hasMore: false,
    offset: 0,
    search: '',
    currentSong: null,
    queue: null,          // 播放队列（歌单等），nextSong 优先
    playCandidates: [],   // 多源回源候选线路
    playCandidateIdx: 0,
    playCandidateFailed: false,
    favIds: {},         // id -> true
    favoritesMeta: {},  // id -> {title, subtitle, media_url}
    playback: {
      isPlaying: false,
      currentTime: 0,
      duration: 0,
      volume: 0.8,
      isMuted: false,
      loopMode: 'all',   // all | one | none
      isShuffle: false
    },
    lyricsCache: {},    // songId -> [{time, text}]
    commentsCache: {}   // songId -> [comment]
  };

  var els = {
    ambient: $('#ambient-backdrop'),
    mobileTabs: $('#mobileTabs'),
    menuItems: $('#menuItems'),
    favBadge: $('#favBadge'),
    playlistBadge: $('#playlistBadge'),
    playlistList: $('#playlistList'),
    btnPlaylistRefresh: $('#btnPlaylistRefresh'),
    btnPlaylistSave: $('#btnPlaylistSave'),
    contentHeader: $('#content-header'),
    pageTitle: $('#pageTitle'),
    pageSubtitle: $('#pageSubtitle'),
    searchBox: $('#searchBox'),
    searchInput: $('#searchInput'),
    songGrid: $('#songGrid'),
    loadMoreWrap: $('#loadMoreWrap'),
    btnLoadMore: $('#btnLoadMore'),
    vinylDisc: $('#vinylDisc'),
    vinylCover: $('#vinylCover'),
    vinylGenre: $('#vinylGenre'),
    vinylTitle: $('#vinylTitle'),
    vinylArtist: $('#vinylArtist'),
    vinylMeta: $('#vinylMeta'),
    lyricsMini: $('#lyricsMini'),
    commentsList: $('#commentsList'),
    commentsCount: $('#commentsCount'),
    commentInput: $('#commentInput'),
    btnComment: $('#btnComment'),
    favList: $('#favList'),
    rankList: $('#rankList'),
    mineGrid: $('#mineGrid'),
    btnMineRefresh: $('#btnMineRefresh'),
    playerCover: $('#playerCover'),
    playerLeftArea: $('#playerLeftArea'),
    playerTitle: $('#playerTitle'),
    playerArtist: $('#playerArtist'),
    btnPlayerFav: $('#btnPlayerFav'),
    btnShuffle: $('#btnShuffle'),
    btnPrev: $('#btnPrev'),
    btnPlay: $('#btnPlay'),
    btnNext: $('#btnNext'),
    btnLoop: $('#btnLoop'),
    timeCur: $('#timeCur'),
    timeDur: $('#timeDur'),
    progressBar: $('#progressBar'),
    progressFill: $('#progressFill'),
    progressThumb: $('#progressThumb'),
    btnMute: $('#btnMute'),
    btnExpand: $('#btnExpand'),
    volumeBar: $('#volumeBar'),
    volumeFill: $('#volumeFill'),
    audio: $('#main-audio-player'),
    uploadForm: $('#uploadForm'),
    upName: $('#upName'),
    upSong: $('#upSong'),
    upCover: $('#upCover'),
    upLyrics: $('#upLyrics'),
    btnUpload: $('#btnUpload'),
    uploadMsg: $('#uploadMsg'),
    btnLogout: $('#btnLogout'),
    // 登录与用户状态元素
    userLoggedOut: $('#userLoggedOut'),
    userLoggedIn: $('#userLoggedIn'),
    userNameDisplay: $('#userNameDisplay'),
    btnLogin: $('#btnLogin'),
    loginModal: $('#musicLoginModal'),
    btnCloseLoginModal: $('#btnCloseLoginModal'),
    loginForm: $('#musicLoginForm'),
    loginIdentifier: $('#loginIdentifier'),
    loginPassword: $('#loginPassword'),
    btnSubmitLogin: $('#btnSubmitLogin'),
    loginModalError: $('#loginModalError'),
    // 全屏播放器
    fsPlayer: $('#fullscreen-player'),
    fsBg: $('#fsBg'),
    fsBgGradient: $('#fsBgGradient'),
    fsBack: $('#fsBack'),
    fsFav: $('#fsFav'),
    fsLike: $('#fsLike'),
    fsTopTitle: $('#fsTopTitle'),
    fsTopArtist: $('#fsTopArtist'),
    fsCover: $('#fsCover'),
    fsSongName: $('#fsSongName'),
    fsOwner: $('#fsOwner'),
    fsStats: $('#fsStats'),
    fsLyricSong: $('#fsLyricSong'),
    fsLyricArtist: $('#fsLyricArtist'),
    fsLyricView: $('#fsLyricView'),
    fsTimeCur: $('#fsTimeCur'),
    fsTimeDur: $('#fsTimeDur'),
    fsProgressBar: $('#fsProgressBar'),
    fsProgressFill: $('#fsProgressFill'),
    fsProgressThumb: $('#fsProgressThumb'),
    fsShuffle: $('#fsShuffle'),
    fsPrev: $('#fsPrev'),
    fsPlay: $('#fsPlay'),
    fsNext: $('#fsNext'),
    fsLoop: $('#fsLoop')
  };

  /* ---------- 视图切换 ---------- */
  var VIEWS = ['explore', 'ranking', 'favorites', 'playlists', 'mine', 'upload'];

  function setActiveTab(tab) {
    state.activeTab = tab;
    $$('.menu-item').forEach(function (b) {
      b.classList.toggle('active', b.dataset.tab === tab);
    });
    $$('.mobile-tabs button').forEach(function (b) {
      b.classList.toggle('active', b.dataset.tab === tab);
    });
    VIEWS.forEach(function (v) {
      $('#view-' + v).classList.toggle('hidden', v !== tab);
    });
    // 头部与搜索框只在 explore / favorites 显示
    var showHeader = (tab === 'explore' || tab === 'favorites' || tab === 'ranking');
    els.contentHeader.classList.toggle('hidden', !showHeader);
    els.searchBox.classList.toggle('hidden', tab !== 'explore');
    if (tab === 'explore') {
      els.pageTitle.textContent = 'Curations & Resonance';
      els.pageSubtitle.textContent = 'No dependencies. Pure audio purity for the modern browser.';
    } else if (tab === 'favorites') {
      els.pageTitle.textContent = 'The Archives';
      els.pageSubtitle.textContent = 'Your curated personal collection of high fidelity soundscapes.';
    } else if (tab === 'ranking') {
      els.pageTitle.textContent = 'Hot Ranking';
      els.pageSubtitle.textContent = 'Most played tracks across the plaza.';
    } else if (tab === 'playlists') {
      els.pageTitle.textContent = 'My Playlists';
      els.pageSubtitle.textContent = 'Cloud synced playlists. At most 5 playlists, 200 tracks each.';
    } else if (tab === 'mine') {
      els.pageTitle.textContent = 'My Uploads';
      els.pageSubtitle.textContent = 'Tracks you published to the plaza.';
    }
    if (tab === 'favorites') renderFavorites();
    if (tab === 'playlists') renderPlaylists();
    if (tab === 'ranking') renderRanking();
    if (tab === 'mine') renderMine();
  }

  function fmtTimeAgo(ts) {
    if (!ts) return '';
    var diff = Math.floor(Date.now() / 1000) - ts;
    if (diff < 60) return '刚刚';
    if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前';
    if (diff < 86400) return Math.floor(diff / 3600) + ' 小时前';
    if (diff < 86400 * 30) return Math.floor(diff / 86400) + ' 天前';
    var d = new Date(ts * 1000);
    return (d.getFullYear()) + '-' + (d.getMonth() + 1 < 10 ? '0' + (d.getMonth() + 1) : d.getMonth() + 1) + '-' + (d.getDate() < 10 ? '0' + d.getDate() : d.getDate());
  }

  /* ---------- 歌曲渲染 ---------- */
  function renderSongItem(song) {
    var item = document.createElement('div');
    item.className = 'song-item' + (state.currentSong && state.currentSong.id === song.id ? ' current' : '');
    item.dataset.id = song.id;

    var cover = document.createElement('div');
    cover.className = 'song-cover';
    var img = document.createElement('img');
    img.alt = song.name || '';
    setCoverImage(img, song.cover_url);
    var play = document.createElement('div');
    play.className = 'cover-play';
    play.textContent = '▶';
    cover.appendChild(img);
    cover.appendChild(play);

    var info = document.createElement('div');
    info.className = 'song-info';
    var tag = document.createElement('span');
    tag.className = 'song-tag';
    tag.textContent = 'OLDCHAT MUSIC';
    var title = document.createElement('h3');
    title.className = 'song-title';
    title.textContent = song.name || '未知歌曲';
    title.title = song.name || '';
    var artist = document.createElement('p');
    artist.className = 'song-artist';
    artist.textContent = song.owner_name || song.owner_uid || '';
    var stats = document.createElement('p');
    stats.className = 'song-stats';
    var d = song.duration_ms ? Math.round(song.duration_ms / 1000) : 0;
    stats.innerHTML = '<span>▶ ' + fmtCount(song.play_count) + '</span>' +
      '<span>♥ ' + fmtCount(song.likes) + '</span>' +
      '<span>✎ ' + fmtCount(song.comments) + '</span>' +
      (d ? '<span>⏱ ' + fmtTime(d) + '</span>' : '') +
      (song.size_bytes ? '<span>· ' + fmtSize(song.size_bytes) + '</span>' : '');
    info.appendChild(tag);
    info.appendChild(title);
    info.appendChild(artist);
    info.appendChild(stats);

    var favBtn = document.createElement('button');
    favBtn.className = 'song-fav' + (state.favIds[song.id] ? ' faved' : '');
    favBtn.innerHTML = svgIcon('heart');
    favBtn.title = '收藏';
    favBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      toggleFavorite(song);
    });

    // 点赞按钮
    var likeBtn = document.createElement('button');
    likeBtn.className = 'song-fav like-btn' + (song.liked ? ' liked' : '');
    likeBtn.innerHTML = svgIcon('like');
    likeBtn.title = song.liked ? '取消点赞' : '点赞';
    likeBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      toggleLike(song, likeBtn);
    });

    item.appendChild(cover);
    item.appendChild(info);
    item.appendChild(likeBtn);
    item.appendChild(favBtn);
    item.addEventListener('click', function () { selectSong(song); });
    return item;
  }

  /* ---------- 点赞（/music/plaza/like | unlike） ---------- */
  function toggleLike(song, btn) {
    if (!song) return;
    var path = song.liked ? '/music/plaza/unlike' : '/music/plaza/like';
    apiRequest(path, { method: 'POST', body: { item_id: song.id } }).then(function (res) {
      if (res.code === 200 || res.code === 201) {
        song.liked = !song.liked;
        song.likes = (song.likes || 0) + (song.liked ? 1 : -1);
        if (song.likes < 0) song.likes = 0;
        if (btn) btn.classList.toggle('liked', song.liked);
        // 刷新统计显示
        $$('.song-item').forEach(function (it) {
          if (it.dataset.id === song.id) {
            var s = it.querySelector('.song-stats');
            if (s) s.innerHTML = '<span>▶ ' + fmtCount(song.play_count) + '</span>' +
              '<span>♥ ' + fmtCount(song.likes) + '</span>' +
              '<span>✎ ' + fmtCount(song.comments) + '</span>' +
              (song.duration_ms ? '<span>⏱ ' + fmtTime(Math.round(song.duration_ms / 1000)) + '</span>' : '');
          }
        });
        toast(song.liked ? '已点赞' : '已取消点赞');
      } else {
        toast('点赞失败 (' + res.code + ')', true);
      }
    });
  }

  function renderSongs() {
    els.songGrid.innerHTML = '';
    if (!state.songs.length) {
      var tip = document.createElement('div');
      tip.className = 'empty-tip';
      tip.textContent = 'No matching recordings found';
      els.songGrid.appendChild(tip);
    } else {
      state.songs.forEach(function (s) {
        els.songGrid.appendChild(renderSongItem(s));
      });
    }
    els.loadMoreWrap.classList.toggle('hidden', !state.hasMore);
  }

  /* ---------- 热门排行 ---------- */
  var rankCache = [];

  function loadRanking() {
    return apiRequest('/music/plaza/ranking?limit=50').then(function (res) {
      if (res.code !== 200) return;
      rankCache = res.data.items || [];
      if (state.activeTab === 'ranking') renderRanking();
    });
  }

  function renderRanking() {
    els.rankList.innerHTML = '';
    if (!rankCache.length) {
      var empty = document.createElement('div');
      empty.className = 'fav-empty';
      empty.innerHTML = '<div class="heart-big">☰</div><h3>No Data Yet</h3><p>暂无排行数据。</p>';
      els.rankList.appendChild(empty);
      return;
    }
    rankCache.forEach(function (song, i) {
      var row = document.createElement('div');
      row.className = 'rank-row' + (state.currentSong && state.currentSong.id === song.id ? ' current' : '');
      row.dataset.id = song.id;
      var idx = document.createElement('span');
      idx.className = 'rank-index' + (i < 3 ? ' top' : '');
      idx.textContent = i + 1;
      var cover = document.createElement('div');
      cover.className = 'song-cover';
      var img = document.createElement('img');
      setCoverImage(img, song.cover_url);
      cover.appendChild(img);
      var info = document.createElement('div');
      info.className = 'song-info';
      var t = document.createElement('p');
      t.className = 'song-title';
      t.textContent = song.name || '未知歌曲';
      var a = document.createElement('p');
      a.className = 'song-artist';
      a.textContent = song.owner_name || song.owner_uid || '';
      info.appendChild(t);
      info.appendChild(a);
      var stats = document.createElement('p');
      stats.className = 'song-stats';
      stats.innerHTML = '<span>▶ ' + fmtCount(song.play_count) + '</span><span>♥ ' + fmtCount(song.likes) + '</span>';
      info.appendChild(stats);
      row.appendChild(idx);
      row.appendChild(cover);
      row.appendChild(info);
      row.addEventListener('click', function () { selectSong(song); });
      els.rankList.appendChild(row);
    });
  }

  /* ---------- 我的发布 ---------- */
  var mineCache = [];

  function loadMine() {
    return apiRequest('/music/plaza?mine=1&limit=100&offset=0').then(function (res) {
      if (res.code !== 200) return;
      mineCache = res.data.items || [];
      if (state.activeTab === 'mine') renderMine();
    });
  }

  function renderMine() {
    els.mineGrid.innerHTML = '';
    if (!mineCache.length) {
      var empty = document.createElement('div');
      empty.className = 'empty-tip';
      empty.textContent = '还没有发布过歌曲，去「上传音乐」发布第一首吧';
      els.mineGrid.appendChild(empty);
      return;
    }
    mineCache.forEach(function (song) {
      var item = document.createElement('div');
      item.className = 'song-item' + (state.currentSong && state.currentSong.id === song.id ? ' current' : '');
      item.dataset.id = song.id;

      var cover = document.createElement('div');
      cover.className = 'song-cover';
      var img = document.createElement('img');
      setCoverImage(img, song.cover_url);
      cover.appendChild(img);
      var info = document.createElement('div');
      info.className = 'song-info';
      var title = document.createElement('h3');
      title.className = 'song-title';
      title.textContent = song.name || '未知歌曲';
      var artist = document.createElement('p');
      artist.className = 'song-artist';
      artist.textContent = song.owner_name || song.owner_uid || '';
      var stats = document.createElement('p');
      stats.className = 'song-stats';
      stats.innerHTML = '<span>▶ ' + fmtCount(song.play_count) + '</span>' +
        '<span>♥ ' + fmtCount(song.likes) + '</span>' +
        '<span>✎ ' + fmtCount(song.comments) + '</span>';
      info.appendChild(title);
      info.appendChild(artist);
      info.appendChild(stats);
      // 删除按钮（can_delete）
      var delBtn = document.createElement('button');
      delBtn.className = 'song-fav del-btn';
      delBtn.innerHTML = '<svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M18 6L6 18"/><path d="M6 6l12 12"/></svg>';
      delBtn.title = '删除';
      delBtn.addEventListener('click', function (e) {
        e.stopPropagation();
        if (window.confirm('确定删除「' + (song.name || '') + '」吗？')) {
          apiRequest('/music/plaza/delete', { method: 'POST', body: { item_id: song.id } }).then(function (res) {
            if (res.code === 200 || res.code === 201) {
              toast('已删除');
              loadMine();
            } else {
              toast('删除失败 (' + res.code + ')', true);
            }
          });
        }
      });
      item.appendChild(cover);
      item.appendChild(info);
      item.appendChild(delBtn);
      item.addEventListener('click', function () { selectSong(song); });
      els.mineGrid.appendChild(item);
    });
  }

  /* ---------- 加载歌曲列表 ---------- */
  function loadSongs(reset) {
    if (reset) {
      state.offset = 0;
      state.songs = [];
    }
    var params = 'limit=' + PAGE_SIZE + '&offset=' + state.offset;
    if (state.search) params += '&q=' + encodeURIComponent(state.search);
    return apiRequest('/music/plaza?' + params).then(function (res) {
      if (res.code !== 200) {
        if (res.code === 401) { redirectLogin(); return; }
        toast('加载歌曲失败 (' + res.code + ')', true);
        return;
      }
      var items = res.data.items || [];
      state.total = res.data.total || 0;
      state.hasMore = !!res.data.has_more;
      state.offset += items.length;
      // 合并去重
      var seen = {};
      state.songs.forEach(function (s) { seen[s.id] = true; });
      items.forEach(function (s) { if (!seen[s.id]) { seen[s.id] = true; state.songs.push(s); } });
      // 用服务端 liked 字段初始化本地收藏态（服务端没有收藏列表接口，liked 是点赞；收藏走通用收藏接口）
      items.forEach(function (s) {
        if (!(s.id in state.favIds)) state.favIds[s.id] = false;
        state.favoritesMeta[s.id] = {
          title: s.name,
          subtitle: s.owner_name || s.owner_uid,
          media_url: s.cover_url || '',
          extra: s.song_url || ''
        };
      });
      renderSongs();
    });
  }

  /* ---------- 收藏（通用收藏接口 music_song） ---------- */
  function toggleFavorite(song) {
    if (!song) return;
    var isFav = !!state.favIds[song.id];
    var path = isFav ? '/favorites/remove' : '/favorites/add';
    var body = {
      type: 'music_song',
      target_id: song.id,
      title: song.name,
      subtitle: song.owner_name || song.owner_uid || '',
      media_url: song.cover_url || '',
      extra: song.song_url || ''
    };
    apiRequest(path, { method: 'POST', body: body }).then(function (res) {
      if (res.code === 200 || res.code === 201) {
        state.favIds[song.id] = !isFav;
        toast(isFav ? '已取消收藏' : '已加入收藏');
        updateFavUI();
        if (state.activeTab === 'favorites') renderFavorites();
      } else {
        toast('操作失败 (' + res.code + ')', true);
      }
    });
  }

  function updateFavUI() {
    var count = Object.keys(state.favIds).filter(function (k) { return state.favIds[k]; }).length;
    els.favBadge.textContent = String(count);
    els.favBadge.classList.toggle('hidden', count === 0);
    // 刷新当前列表的收藏按钮
    $$('.song-item').forEach(function (item) {
      var id = item.dataset.id;
      var btn = item.querySelector('.song-fav');
      if (btn && id) btn.classList.toggle('faved', !!state.favIds[id]);
    });
    els.btnPlayerFav.classList.toggle('faved', !!(state.currentSong && state.favIds[state.currentSong.id]));
  }

  function loadFavorites() {
    // 从通用收藏接口拉取 music_song 类型，重建 favIds
    return apiRequest('/favorites?type=music_song&limit=200&offset=0').then(function (res) {
      if (res.code !== 200) return;
      var items = res.data.items || [];
      state.favIds = {};
      items.forEach(function (it) {
        state.favIds[it.target_id] = true;
        // extra 里可能存了 {song_url, cover_url} JSON，优先用它还原封面
        var cover = it.media_url || '';
        try {
          if (it.extra) {
            var extra = JSON.parse(it.extra);
            if (extra.cover_url) cover = extra.cover_url;
          }
        } catch (e) {}
        state.favoritesMeta[it.target_id] = {
          title: it.title,
          subtitle: it.subtitle,
          media_url: cover,
          extra: it.extra
        };
      });
      updateFavUI();
      if (state.activeTab === 'favorites') renderFavorites();
    });
  }

  /* ---------- 收藏列表渲染 ---------- */
  function renderFavorites() {
    els.favList.innerHTML = '';
    var favSongIds = Object.keys(state.favIds).filter(function (k) { return state.favIds[k]; });
    if (!favSongIds.length) {
      var empty = document.createElement('div');
      empty.className = 'fav-empty';
      empty.innerHTML = '<div class="heart-big">♥</div><h3>The Archives are Empty</h3>' +
        '<p>浏览曲库时，点击心形图标即可将喜爱的歌曲收藏进个人档案。</p>';
      els.favList.appendChild(empty);
      return;
    }
    // 尝试从已加载列表补全歌曲详情
    var list = favSongIds.map(function (id) {
      var found = null;
      state.songs.forEach(function (s) { if (s.id === id) found = s; });
      return found || { id: id, name: (state.favoritesMeta[id] || {}).title || id, cover_url: (state.favoritesMeta[id] || {}).media_url || '', owner_name: (state.favoritesMeta[id] || {}).subtitle || '' };
    });
    list.forEach(function (song, index) {
      var row = document.createElement('div');
      row.className = 'fav-item' + (state.currentSong && state.currentSong.id === song.id ? ' current' : '');
      row.style.cursor = 'pointer';
      var idx = document.createElement('span');
      idx.className = 'fav-index';
      idx.textContent = (index + 1 < 10 ? '0' + (index + 1) : index + 1);
      var cover = document.createElement('div');
      cover.className = 'song-cover';
      var img = document.createElement('img');
      setCoverImage(img, song.cover_url);
      cover.appendChild(img);
      var info = document.createElement('div');
      info.style.minWidth = '0';
      info.style.flex = '1';
      var title = document.createElement('h4');
      title.className = 'song-title';
      title.textContent = song.name || '未知歌曲';
      var meta = document.createElement('p');
      meta.className = 'fav-meta';
      meta.textContent = song.owner_name || '—';
      info.appendChild(title);
      info.appendChild(meta);
      var favBtn = document.createElement('button');
      favBtn.className = 'song-fav faved';
      favBtn.innerHTML = svgIcon('heart');
      favBtn.addEventListener('click', function (e) {
        e.stopPropagation();
        // 收藏列表里点击即取消收藏
        toggleFavorite(song);
      });
      row.appendChild(idx);
      row.appendChild(cover);
      row.appendChild(info);
      row.appendChild(favBtn);
      row.addEventListener('click', function () {
        var full = state.songs.find(function (s) { return s.id === song.id; });
        if (full) {
          selectSong(full);
        } else {
          // 收藏的歌可能不在当前加载列表：用元数据构造可播放对象
          var meta = state.favoritesMeta[song.id] || {};
          var extra = {};
          try { if (meta.extra) extra = JSON.parse(meta.extra); } catch (e) {}
          selectSong({
            id: song.id,
            name: meta.title || song.name || '未知歌曲',
            owner_name: meta.subtitle || '',
            cover_url: meta.media_url || '',
            song_url: extra.song_url || '',
            lyrics_url: extra.lyrics_url || '',
            duration_ms: 0,
            play_count: 0,
            likes: 0,
            comments: 0
          });
          toast('收藏的歌曲可能已被移除，若无法播放请到发现页查找');
        }
      });
      els.favList.appendChild(row);
    });
  }

  /* ---------- 播放 ---------- */
  function selectSong(song) {
    state.currentSong = song;
    // 切歌重置播放进度，避免新歌词被旧时间戳定位到末行
    state.playback.currentTime = 0;
    state.playback.duration = 0;
    var audio = els.audio;
    // 多源回源：先生成候选线路，优先健康线路
    state.playCandidates = buildUploadCandidates(song.song_url);
    state.playCandidateIdx = 0;
    state.playCandidateFailed = false;
    tryPlayCurrentCandidate(song);
    // 更新界面
    setCoverImage(els.vinylCover, song.cover_url);
    els.vinylTitle.textContent = song.name || '未知歌曲';
    els.vinylArtist.textContent = song.owner_name || song.owner_uid || '';
    els.vinylGenre.textContent = 'OLDCHAT';
    els.vinylMeta.textContent = '♥ ' + fmtCount(song.likes) + '  ▶ ' + fmtCount(song.play_count) +
      (song.duration_ms ? '  ⏱ ' + fmtTime(Math.round(song.duration_ms / 1000)) : '');
    setCoverImage(els.playerCover, song.cover_url);
    els.playerTitle.textContent = song.name || '未知歌曲';
    els.playerArtist.textContent = song.owner_name || song.owner_uid || '';
    els.btnPlayerFav.classList.toggle('faved', !!state.favIds[song.id]);
    var ambientUrl = resolveCoverUrl(song.cover_url);
    if (song.cover_url && ambientUrl !== DEFAULT_COVER_SVG) {
      els.ambient.style.backgroundImage = 'url(' + ambientUrl + ')';
    } else {
      els.ambient.style.backgroundImage = '';
    }
    // 全屏时只渲染全屏歌词，非全屏渲染迷你歌词（避免两次异步渲染抢同一引擎）
    if (fsState.open) {
      syncFullscreenUI();
      renderLyricsFull();          // 全屏：重新加载歌词
      animateFullscreenSwitch(song); // 封面下翻 + 背景渐变过渡
    } else {
      renderLyricsMini();
    }
    loadComments(song.id);
    // 高亮列表
    $$('.song-item').forEach(function (item) {
      item.classList.toggle('current', item.dataset.id === song.id);
    });
    $$('.fav-item').forEach(function (item) {
      item.classList.toggle('current', item.dataset.id === song.id);
    });
    // 播放计数
    apiRequest('/music/plaza/play', { method: 'POST', body: { item_id: song.id } }).then(function (res) {
      if (res.code === 200) {
        song.play_count = res.data.play_count || song.play_count;
        if (state.songs.some(function (s) { return s.id === song.id; })) renderSongs();
      }
    });
  }

  /** 按候选线路顺序尝试播放；失败自动切下一条（对齐安卓 resolveNextCandidate）。 */
  function tryPlayCurrentCandidate(song) {
    var cands = state.playCandidates || [];
    var idx = state.playCandidateIdx || 0;
    if (!cands.length) { toast('歌曲资源不可用', true); return; }
    if (idx >= cands.length) {
      state.playback.isPlaying = false;
      updatePlayUI();
      toast('所有线路均无法播放', true);
      return;
    }
    var url = cands[idx];
    var audio = els.audio;
    // 移除旧的 error 处理器再绑定（避免换歌时残留）
    audio.onerror = null;
    audio.onerror = function () {
      // 当前线路失败：冷却 + 切下一条
      reportLineFailure(url);
      state.playCandidateIdx++;
      state.playCandidateFailed = true;
      tryPlayCurrentCandidate(song);
    };
    audio.src = url;
    audio.load();
    audio.play().then(function () {
      state.playback.isPlaying = true;
      reportLineSuccess(url);
      updatePlayUI();
    }).catch(function () {
      // 播放被拦截（非资源问题）时，若从未成功过则尝试切线路
      if (!state.playCandidateFailed) {
        audio.onerror && audio.onerror();
      } else {
        state.playback.isPlaying = false;
        updatePlayUI();
      }
    });
  }

  function togglePlay() {
    var audio = els.audio;
    if (!state.currentSong) {
      if (state.songs.length) selectSong(state.songs[0]);
      else return;
    }
    if (state.playback.isPlaying) {
      audio.pause();
      state.playback.isPlaying = false;
    } else {
      audio.play().then(function () {
        state.playback.isPlaying = true;
      }).catch(function (err) {
        toast('播放被浏览器拦截，请再次点击', true);
      });
    }
    updatePlayUI();
  }

  function nextSong() {
    var pool = state.queue && state.queue.length ? state.queue : state.songs;
    if (!pool.length) return;
    var cur = state.currentSong;
    var idx = cur ? pool.findIndex(function (s) { return s.id === cur.id; }) : -1;
    var next;
    if (state.playback.isShuffle && pool.length > 1) {
      do { next = pool[Math.floor(Math.random() * pool.length)]; } while (pool.length > 1 && next.id === cur.id);
    } else {
      // 队列播到最后一首回到队列头；非队列模式用全局循环
      next = pool[(idx + 1) % pool.length];
    }
    selectSong(next);
    state.playback.isPlaying = true;
    els.audio.play().catch(function () {});
    updatePlayUI();
  }

  function prevSong() {
    var pool = state.songs.length ? state.songs : [];
    if (!pool.length) return;
    var cur = state.currentSong;
    var idx = cur ? pool.findIndex(function (s) { return s.id === cur.id; }) : -1;
    var prev = pool[(idx - 1 + pool.length) % pool.length];
    selectSong(prev);
    state.playback.isPlaying = true;
    els.audio.play().catch(function () {});
    updatePlayUI();
  }

  function cycleLoop() {
    if (state.playback.loopMode === 'all') state.playback.loopMode = 'one';
    else if (state.playback.loopMode === 'one') state.playback.loopMode = 'none';
    else state.playback.loopMode = 'all';
    updatePlayUI();
  }

  function updatePlayUI() {
    var p = state.playback;
    els.btnPlay.innerHTML = svgIcon(p.isPlaying ? 'pause' : 'play');
    els.btnPlay.title = p.isPlaying ? '暂停' : '播放';
    els.vinylDisc.classList.toggle('spinning', p.isPlaying);
    els.btnShuffle.classList.toggle('on', p.isShuffle);
    els.btnLoop.classList.toggle('on', p.loopMode !== 'none');
    els.btnLoop.title = p.loopMode === 'one' ? '单曲循环' : p.loopMode === 'all' ? '列表循环' : '顺序播放';
    els.btnMute.innerHTML = svgIcon((p.isMuted || p.volume === 0) ? 'mute' : 'volume');
    els.volumeFill.style.width = (p.isMuted ? 0 : p.volume * 100) + '%';
    // 全屏播放器
    els.fsPlay.innerHTML = svgIcon(p.isPlaying ? 'pause' : 'play');
    els.fsPlay.title = p.isPlaying ? '暂停' : '播放';
    els.fsPlay.classList.toggle('playing', p.isPlaying);
    els.fsShuffle.classList.toggle('on', p.isShuffle);
    els.fsLoop.classList.toggle('on', p.loopMode !== 'none');
    els.fsLoop.title = p.loopMode === 'one' ? '单曲循环' : p.loopMode === 'all' ? '列表循环' : '顺序播放';
    updateProgress();
  }

  /* ---------- 进度条 ---------- */
  function updateProgress() {
    var p = state.playback;
    var dur = p.duration || 0;
    var pct = dur > 0 ? (p.currentTime / dur) * 100 : 0;
    els.progressFill.style.width = pct + '%';
    els.progressThumb.style.left = pct + '%';
    els.timeCur.textContent = fmtTime(p.currentTime);
    els.timeDur.textContent = fmtTime(dur);
    if (fsState && fsState.open) updateFullscreenProgress();
  }

  function seekFromEvent(e, bar) {
    var rect = bar.getBoundingClientRect();
    var pct = (e.clientX - rect.left) / rect.width;
    if (pct < 0) pct = 0;
    if (pct > 1) pct = 1;
    var dur = state.playback.duration || 0;
    els.audio.currentTime = pct * dur;
    state.playback.currentTime = pct * dur;
    updateProgress();
  }

  /* ---------- 歌词 ---------- */
  function parseLRC(text) {
    var lines = [];
    var re = /\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g;
    var raw = text.split(/\r?\n/);
    raw.forEach(function (line) {
      re.lastIndex = 0;
      var m;
      var times = [];
      while ((m = re.exec(line)) !== null) {
        var min = parseInt(m[1], 10);
        var sec = parseInt(m[2], 10);
        var frac = m[3] ? parseInt(m[3], 10) / Math.pow(10, m[3].length) : 0;
        times.push(min * 60 + sec + frac);
      }
      if (times.length) {
        var content = line.replace(re, '').trim();
        if (content) {
          times.forEach(function (t) { lines.push({ time: t, text: content }); });
        }
      }
    });
    lines.sort(function (a, b) { return a.time - b.time; });
    return lines;
  }

  function parseTTML(xml) {
    // 完整解析：行级 begin/end + 字级 timed words + translation（与安卓 LyricParser 对齐）
    var lines = [];
    var re = /<p\b([^>]*)>([\s\S]*?)<\/p>/gi;
    var m;
    while ((m = re.exec(xml)) !== null) {
      var attrs = m[1] || '';
      var body = m[2] || '';
      var begin = ttmlAttrTime(attrs, 'begin');
      if (begin < 0) continue;
      var end = ttmlAttrTime(attrs, 'end');
      // 提取翻译（ttm:role/class 含 translation 的 span）
      var translation = '';
      var transRe = /<span\b[^>]*(?:ttm:role|role|class)=["']([^"']*translation[^"']*)["'][^>]*>([\s\S]*?)<\/span>/gi;
      var tr;
      while ((tr = transRe.exec(body)) !== null) {
        translation += cleanupTtml(tr[2]) + '\n';
      }
      translation = translation.trim();
      body = body.replace(transRe, '');
      // 提取 timed words
      var words = [];
      var spanRe = /<span\b([^>]*)>([\s\S]*?)<\/span>/gi;
      var sp;
      while ((sp = spanRe.exec(body)) !== null) {
        var ws = ttmlAttrTime(sp[1], 'begin');
        var we = ttmlAttrTime(sp[1], 'end');
        var wt = cleanupTtml(sp[2]);
        if (!wt || ws < 0) continue;
        if (we < ws) we = ws + 0.22;
        words.push({ text: wt, start: ws, end: we });
      }
      var mainText = words.map(function (w) { return w.text; }).join(' ');
      if (!mainText) mainText = cleanupTtml(body);
      if (translation && mainText && translation !== mainText) {
        mainText += '\n' + translation;
      } else if (!mainText) {
        mainText = translation;
      }
      if (!mainText) continue;
      if (end < begin && words.length) end = words[words.length - 1].end;
      if (end < begin) end = begin;
      lines.push({ time: begin, endTime: end, text: mainText, words: words });
    }
    lines.sort(function (a, b) { return a.time - b.time; });
    return lines;
  }

  function ttmlAttrTime(attrs, key) {
    var re = new RegExp('(?:^|\\s)' + key + '="([^"]+)"', 'i');
    var m = re.exec(attrs || '');
    if (!m) return -1;
    var v = m[1].trim();
    if (v.indexOf(':') >= 0) {
      var parts = v.split(':');
      var sec = 0;
      for (var i = 0; i < parts.length; i++) {
        sec = sec * 60 + parseFloat(parts[i]);
      }
      return sec;
    }
    return parseFloat(v) || 0;
  }

  function cleanupTtml(text) {
    return String(text == null ? '' : text)
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<[^>]+>/g, '')
      .replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&#10;/g, '\n')
      .trim();
  }

  function fetchLyrics(song) {
    if (!song || !song.lyrics_url) return Promise.resolve(null);
    if (state.lyricsCache[song.id]) return Promise.resolve(state.lyricsCache[song.id]);
    // 歌词也用多源回源：依次尝试候选线路
    var cands = buildUploadCandidates(song.lyrics_url);
    function tryFetch(idx) {
      if (idx >= cands.length) {
        state.lyricsCache[song.id] = null;
        return Promise.resolve(null);
      }
      var url = cands[idx];
      return fetch(url).then(function (resp) {
        if (!resp.ok) throw new Error('lyrics fetch fail');
        reportLineSuccess(url);
        return resp.text();
      }).then(function (text) {
        var lines;
        if (/<tt\b|<p\b/i.test(text)) lines = parseTTML(text);
        else lines = parseLRC(text);
        if (!lines.length) {
          lines = [{ time: 0, text: text.trim().slice(0, 200) }];
        }
        state.lyricsCache[song.id] = lines;
        return lines;
      }).catch(function () {
        reportLineFailure(url);
        return tryFetch(idx + 1);
      });
    }
    return tryFetch(0);
  }

  /* ---------- 歌词动画引擎（复刻安卓 LyricCascadeView） ----------
     核心：每一行歌词是独立的动画单元，各行启动时间不同（错开 delay），
     像波浪一样依次完成移动/缩放/渐显。入场飞入 + 行切换弹簧位移都按行错开。 */
  var LYRIC_LINE_H = 72;         // 迷你容器行高 px
  var LYRIC_ANIM_VISIBLE = 5;    // 动画可见范围（±5 行）
  var INTRO_STEP_DELAY = 0.052;  // 入场每行步进延迟 52ms
  var INTRO_LINE_DUR = 0.36;     // 每行入场动画 360ms
  var lyricEngine = {
    lines: [], nodes: [], st: [],   // st[i] = 每行独立状态
    activeIndex: -1, introStart: 0, introDone: false,
    raf: 0, lastTs: 0, playTime: 0, // 自驱动平滑播放时间（卡拉OK扫描线用）
    container: null, width: 0, height: 0, running: false,
    isDragging: false, touchStartY: 0, previewOffset: 0, lastTouchY: 0,
    mode: 'mini'
  };

  function lyricTargetContainer() {
    return lyricEngine.mode === 'full' ? els.fsLyricView : els.lyricsMini;
  }

  function lyricLineHeight() {
    return lyricEngine.mode === 'full' ? 84 : LYRIC_LINE_H;
  }

  function lyricSpringTarget(i, anchor) {
    return (i - anchor) * lyricLineHeight();
  }

  function lyricScaleFor(dist) {
    return dist === 0 ? 1.15 : (dist === 1 ? 1.06 : 1);
  }

  function lyricAlphaFor(dist) {
    return Math.max(0.16, 1 - dist * 0.3);
  }

  function easeInOutCubic(t) {
    return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
  }

  /* 三次贝塞尔求值（用于卡拉OK扫描线速度曲线）。
     控制点 (0.35, 0) / (0.45, 1)：先快后慢的唱词扫描，视觉更顺滑。
     预计算 0~100 查表，避免每帧每词做二分迭代（性能关键）。 */
  var bezierLUT = null;
  function initBezierLUT() {
    bezierLUT = [];
    var x1 = 0.35, y1 = 0, x2 = 0.45, y2 = 1;
    var cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
    var cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
    for (var i = 0; i <= 100; i++) {
      var p = i / 100;
      var lo = 0, hi = 1;
      for (var j = 0; j < 10; j++) {
        var mid = (lo + hi) / 2;
        var x = ((ax * mid + bx) * mid + cx) * mid;
        if (x < p) lo = mid; else hi = mid;
      }
      var t = (lo + hi) / 2;
      bezierLUT[i] = ((ay * t + by) * t + cy) * t;
    }
  }

  function bezierEase(p) {
    if (!bezierLUT) initBezierLUT();
    if (p <= 0) return 0;
    if (p >= 1) return 1;
    var idx = Math.round(p * 100);
    return bezierLUT[idx];
  }

  function lyricStopEngine() {
    lyricEngine.running = false;
    if (lyricEngine.raf) cancelAnimationFrame(lyricEngine.raf);
    lyricEngine.raf = 0;
  }

  function lyricStartEngine() {
    var eng = lyricEngine;
    if (eng.running) return;
    eng.running = true;
    eng.lastTs = 0;
    eng.raf = requestAnimationFrame(lyricFrame);
  }

  /** 行切换：每行独立目标 + 独立延迟启动（安卓 animateToIndex 的波浪效果）。 */
  function lyricAnimateToIndex(newIndex) {
    var eng = lyricEngine;
    if (!eng.st.length) return;
    eng.activeIndex = newIndex;
    var now = performance.now() / 1000;
    var lh = lyricLineHeight();
    var visibleStart = Math.max(0, newIndex - LYRIC_ANIM_VISIBLE);
    var visibleEnd = Math.min(eng.lines.length - 1, newIndex + LYRIC_ANIM_VISIBLE);
    var delay = 0, base = 0.035;
    var topY = 0; // 相对中线以上开始累积延迟（对齐安卓 targetY >= topY 判断）
    for (var i = 0; i < eng.lines.length; i++) {
      var st = eng.st[i];
      if (!st) continue;
      var dist = Math.abs(i - newIndex);
      st.targetY = (i - newIndex) * lh;
      st.targetScale = lyricScaleFor(dist);
      st.targetAlpha = lyricAlphaFor(dist);
      st.delayUntil = now;          // 默认立即启动（远行）
      if (i >= visibleStart && i <= visibleEnd && (i - newIndex) * lh >= topY) {
        st.delayUntil = now + delay;
        delay += base;
        if (i >= newIndex) base /= 1.05;
      }
      // 复位弹簧速度与趋近状态，保证错开时每行从当前值独立过渡
      st.scaleDelayUntil = now;
    }
  }

  function lyricFrame() {
    var eng = lyricEngine;
    if (!eng.running) return;
    eng.raf = requestAnimationFrame(lyricFrame);
    var now = performance.now() / 1000;
    var dt = eng.lastTs > 0 ? Math.min(0.05, Math.max(0.001, now - eng.lastTs)) : 0.016;
    eng.lastTs = now;

    // 平滑推进时间：播放中每帧自增，不依赖低频 timeupdate 事件
    if (state.playback.isPlaying) {
      eng.playTime += dt;
    } else {
      eng.playTime = state.playback.currentTime;
    }
    // 与 audio 实际进度校准（timeupdate 低频到达时修正漂移）
    if (state.playback.currentTime > 0 && Math.abs(eng.playTime - state.playback.currentTime) > 1.0) {
      eng.playTime = state.playback.currentTime;
    }
    var t = eng.playTime;
    var anchor = findCurrentLineByTime(eng.lines, t);
    if (anchor < 0) anchor = 0;
    if (anchor !== eng.activeIndex && !eng.isDragging) {
      lyricAnimateToIndex(anchor);
    }
    var anchorIndex = eng.activeIndex;
    var mid = eng.container ? eng.container.clientHeight / 2 : 120;
    var lh = lyricLineHeight();
    var previewShift = eng.isDragging ? eng.previewOffset : 0;

    // 入场动画全局进度（每行各自 delay）
    var introElapsed = now - eng.introStart;
    var visibleStart = Math.max(0, anchorIndex - LYRIC_ANIM_VISIBLE);
    var visibleEnd = Math.min(eng.lines.length - 1, anchorIndex + LYRIC_ANIM_VISIBLE);
    var maxDelay = Math.max(0, visibleEnd - visibleStart) * INTRO_STEP_DELAY;
    var introDone = introElapsed >= (INTRO_LINE_DUR + maxDelay + 0.05);
    if (introDone && !eng.introDone) {
      // 入场完成：触发一次逐行错开的波浪归位（每行独立 delay 启动）
      eng.introDone = true;
      eng._introArranged = true;
      lyricAnimateToIndex(anchorIndex);
    }

    for (var i = 0; i < eng.lines.length; i++) {
      var node = eng.nodes[i];
      var st = eng.st[i];
      if (!node || !st) continue;
      var dist = Math.abs(i - anchorIndex);

      if (eng.isDragging) {
        // 拖拽预览：按偏移直接布局，不做弹簧
        st.y = st.targetY + previewShift;
      } else if (!introDone) {
        // ---- 入场：每行独立 delay 从右飞入 + 渐现（初始位置在 renderLyricsInto 已设好） ----
        var clamped = Math.max(visibleStart, Math.min(i, visibleEnd));
        var lineDelay = Math.max(0, clamped - visibleStart) * INTRO_STEP_DELAY;
        var local = (introElapsed - lineDelay) / INTRO_LINE_DUR;
        local = Math.max(0, Math.min(1, local));
        var eased = easeInOutCubic(local);
        st.x = (eng.width + 48) * (1 - eased);
        st.alpha = lyricAlphaFor(dist) * eased;
      } else if (now >= st.delayUntil) {
        // ---- 行切换波浪：弹簧位移（独立 per-row，各行 delay 错开） ----
        var k = 90, c = 16;
        var acc = ((st.targetY + previewShift) - st.y) * k;
        st.vy = (st.vy + acc * dt) * Math.exp(-c * dt);
        st.y += st.vy * dt;
        // 缩放/透明度（独立趋近）
        var ease = 1 - Math.exp(-16 * dt);
        st.scale += (st.targetScale - st.scale) * ease;
        st.alpha += (st.targetAlpha - st.alpha) * ease;
        // 入场 X 彻底归零
        st.x += (0 - st.x) * ease;
      }

      var y = mid + st.y;
      node.style.transform = 'translate(-50%, ' + y + 'px) scale(' + st.scale + ') translateX(' + st.x + 'px)';
      node.style.opacity = String(st.alpha);
      node.style.zIndex = String(dist === 0 ? 3 : (dist === 1 ? 2 : 1));
      node.classList.toggle('active', i === anchorIndex);
      // 词级卡拉OK（对齐安卓 drawTimedPrimaryLine）：
      // 所有有 timed words 的行都逐词高亮（不限于当前行），已唱完的词保持高亮白（done），
      // 正在唱的词 mask 从左往右填充（singing），未唱的半透明。距离远的行整行已变淡（st.alpha）。
      if (eng.lines[i].words && eng.lines[i].words.length) {
        var words = eng.lines[i].words;
        for (var w = 0; w < words.length; w++) {
          var wrap = node.querySelector('[data-w="' + w + '"]');
          if (!wrap) continue;
          var word = words[w];
          wrap.classList.remove('done', 'singing');
          if (t >= word.end) {
            wrap.classList.add('done'); // 已唱完：保持高亮
          } else if (t >= word.start) {
            wrap.classList.add('singing'); // 正在唱：填充
            var dur = Math.max(0.001, word.end - word.start);
            var linear = Math.max(0, Math.min(1, (t - word.start) / dur));
            // 贝塞尔速度曲线：扫描线先快后慢，唱词过程更顺滑（避免 linear 生硬）
            var prog = bezierEase(linear);
            var mask = wrap.querySelector('.lw-mask');
            if (mask) mask.style.width = (prog * 100) + '%';
          } else {
            wrap.classList.remove('done', 'singing'); // 未唱
          }
        }
      }
    }
    if (introDone) eng.introDone = true;
    // 入场播完且未在播放音乐 → 停帧（省资源）；播放时由 audio.play 事件重启
    if (eng.introDone && !state.playback.isPlaying && !eng.isDragging) {
      if (!eng._idleStop) {
        eng._idleStop = true;
        return;
      }
      lyricStopEngine();
    }
  }

  var lyricRenderSeq = 0; // 渲染序号，防止切歌时旧 fetch 回调覆盖新歌词

  /** 渲染歌词到指定容器（mode: 'mini' | 'full'），带入场动画。 */
  function renderLyricsInto(mode) {
    lyricEngine.mode = mode;
    var container = lyricTargetContainer();
    var mySeq = ++lyricRenderSeq; // 本次渲染的序号
    lyricStopEngine();
    container.innerHTML = '';
    lyricEngine.lines = [];
    lyricEngine.nodes = [];
    lyricEngine.st = [];
    lyricEngine.activeIndex = -1;
    lyricEngine.introDone = false;
    lyricEngine._idleStop = false;
    lyricEngine.isDragging = false;
    lyricEngine.playTime = state.playback.currentTime || 0;
    lyricEngine.previewOffset = 0;
    lyricEngine.container = container;
    lyricEngine.width = container.clientWidth || 320;
    lyricEngine.height = container.clientHeight || 200;

    if (!state.currentSong) {
      var empty = document.createElement('p');
      empty.className = 'lyrics-empty';
      empty.textContent = 'NO RECORDING LOADED';
      container.appendChild(empty);
      return;
    }
    fetchLyrics(state.currentSong).then(function (lines) {
      // 若期间又切换了歌曲/渲染，丢弃本次结果
      if (mySeq !== lyricRenderSeq) return;
      container.innerHTML = '';
      if (!lines || !lines.length) {
        var e2 = document.createElement('p');
        e2.className = 'lyrics-empty';
        e2.textContent = '暂无歌词';
        container.appendChild(e2);
        return;
      }
      var limit = Math.min(lines.length, 200);
      for (var i = 0; i < limit; i++) {
        var line = lines[i];
        var p = document.createElement('p');
        p.className = 'lyric-anim-line';
        p.dataset.index = i;
        if (line.words && line.words.length) {
          for (var w = 0; w < line.words.length; w++) {
            var wt = line.words[w].text + (w < line.words.length - 1 ? ' ' : '');
            var wrap = document.createElement('span');
            wrap.className = 'lyric-word';
            wrap.dataset.w = w;
            var txt = document.createElement('span');
            txt.className = 'lw-text';
            txt.textContent = wt;
            var mask = document.createElement('span');
            mask.className = 'lw-mask';
            mask.textContent = wt;
            wrap.appendChild(txt);
            wrap.appendChild(mask);
            p.appendChild(wrap);
          }
        } else {
          p.textContent = line.text;
        }
        (function (idx, time) {
          p.addEventListener('click', function () {
            if (state.currentSong) els.audio.currentTime = time;
          });
        })(i, line.time);
        container.appendChild(p);
        lyricEngine.lines.push(line);
        lyricEngine.nodes.push(p);
        // 每行独立动画状态（对齐安卓 LineState）：入场初值在右侧 + 隐藏
        var dist0 = Math.abs(i - 0);
        lyricEngine.st.push({
          y: lyricSpringTarget(i, 0),
          vy: 0,
          scale: dist0 === 0 ? 1.08 : lyricScaleFor(dist0),
          alpha: 0,
          x: lyricEngine.width + 48,
          targetY: lyricSpringTarget(i, 0),
          targetScale: lyricScaleFor(dist0),
          targetAlpha: lyricAlphaFor(dist0),
          delayUntil: 0,
          scaleDelayUntil: 0
        });
      }
      lyricEngine.activeIndex = 0;
      lyricEngine.introStart = performance.now() / 1000;
      lyricEngine.introDone = false;
      lyricStartEngine();
    });
  }

  /** 迷你歌词（探索页右侧）。 */
  function renderLyricsMini() {
    renderLyricsInto('mini');
  }

  /** 全屏歌词。 */
  function renderLyricsFull() {
    renderLyricsInto('full');
  }

  function findCurrentLineByTime(lines, t) {
    var lo = 0, hi = lines.length - 1, ans = -1;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (lines[mid].time <= t) { ans = mid; lo = mid + 1; }
      else hi = mid - 1;
    }
    return ans;
  }

  /* ---------- 全屏播放器（Apple Music 风格：封面页 + 歌词页） ---------- */
  var fsState = { open: false, page: 'cover' };

  function openFullscreen() {
    if (fsState.open) return;
    if (!state.currentSong) return;
    fsState.open = true;
    els.fsPlayer.classList.remove('hidden');
    document.body.style.overflow = 'hidden';
    syncFullscreenUI();
    // 歌词页切换为全屏模式
    renderLyricsFull();
    // 用封面渐入过渡
    els.fsPlayer.style.opacity = '0';
    requestAnimationFrame(function () {
      els.fsPlayer.style.opacity = '1';
    });
  }

  function closeFullscreen() {
    if (!fsState.open) return;
    fsState.open = false;
    els.fsPlayer.classList.add('hidden');
    document.body.style.overflow = '';
    // 切回迷你歌词
    renderLyricsMini();
  }

  function syncFullscreenUI() {
    var s = state.currentSong;
    if (!s) return;
    els.fsTopTitle.textContent = s.name || '未知歌曲';
    els.fsTopArtist.textContent = s.owner_name || s.owner_uid || '';
    els.fsSongName.textContent = s.name || '未知歌曲';
    els.fsOwner.textContent = s.owner_name || s.owner_uid || '—';
    els.fsLyricSong.textContent = s.name || '未知歌曲';
    els.fsLyricArtist.textContent = s.owner_name || s.owner_uid || '';
    els.fsStats.textContent = '♥ ' + fmtCount(s.likes) + '   ▶ ' + fmtCount(s.play_count) +
      (s.duration_ms ? '   ⏱ ' + fmtTime(Math.round(s.duration_ms / 1000)) : '');
    var cover = s.cover_url || '';
    setCoverImage(els.fsCover, cover);
    var fsBgUrl = resolveCoverUrl(cover);
    if (cover && fsBgUrl !== DEFAULT_COVER_SVG) {
      els.fsBg.style.backgroundImage = 'url(' + fsBgUrl + ')';
    } else {
      els.fsBg.style.backgroundImage = '';
    }
    applyMonetBackground(cover);
    els.fsFav.classList.toggle('faved', !!state.favIds[s.id]);
    els.fsLike.classList.toggle('faved', !!s.liked);
    updateFullscreenProgress();
  }

  /* ---------- 全屏换歌过渡：封面下翻 + 背景缓慢渐变 ---------- */
  function animateFullscreenSwitch(song) {
    var wrap = document.querySelector('#fsLeft .fs-cover-wrap');
    if (!wrap) return;
    var cover = els.fsCover;
    // 封面下翻：绕 X 轴向下翻 72 度（像翻页收进去），换图后翻回来
    wrap.style.transition = 'transform 0.3s ease';
    wrap.style.transform = 'perspective(900px) rotateX(72deg)';
    cover.style.opacity = '0.3';
    setTimeout(function () {
      setCoverImage(cover, song.cover_url);
      wrap.style.transition = 'transform 0.5s cubic-bezier(0.25, 0.9, 0.3, 1.15)';
      wrap.style.transform = 'perspective(900px) rotateX(0deg)';
      cover.style.transition = 'opacity 0.5s ease';
      cover.style.opacity = '1';
      setTimeout(function () {
        wrap.style.transition = '';
        cover.style.transition = '';
      }, 550);
    }, 300);
    // 背景缓慢渐变：莫奈渐变 1.2s 淡入过渡
    var fsBgUrl = resolveCoverUrl(song.cover_url);
    if (song.cover_url && fsBgUrl !== DEFAULT_COVER_SVG) {
      els.fsBg.style.transition = 'background-image 1.2s ease, opacity 1s ease';
      els.fsBg.style.opacity = '0.3';
      els.fsBg.style.backgroundImage = 'url(' + fsBgUrl + ')';
      setTimeout(function () {
        els.fsBg.style.opacity = '0.5';
      }, 60);
    }
  }

  /* ---------- 莫奈取色：从封面提取主色生成动态渐变背景 ---------- */
  function applyMonetBackground(coverUrl) {
    var realUrl = resolveCoverUrl(coverUrl);
    if (!coverUrl || realUrl === DEFAULT_COVER_SVG) {
      els.fsBgGradient.style.background = '';
      return;
    }
    var img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = function () {
      try {
        var canvas = document.createElement('canvas');
        canvas.width = 32;
        canvas.height = 32;
        var ctx = canvas.getContext('2d');
        ctx.drawImage(img, 0, 0, 32, 32);
        var data = ctx.getImageData(0, 0, 32, 32).data;
        // 聚类取主色：简单降采样 + 统计
        var buckets = {};
        for (var i = 0; i < data.length; i += 16) {
          var r = data[i], g = data[i + 1], b = data[i + 2];
          if (r + g + b < 60) continue; // 跳过过暗像素
          var key = (r >> 5) + ',' + (g >> 5) + ',' + (b >> 5);
          if (!buckets[key]) buckets[key] = { r: 0, g: 0, b: 0, n: 0 };
          buckets[key].r += r; buckets[key].g += g; buckets[key].b += b; buckets[key].n++;
        }
        var top = Object.keys(buckets).sort(function (a, b2) {
          return buckets[b2].n - buckets[a].n;
        }).slice(0, 4);
        var colors = top.map(function (k) {
          var c = buckets[k];
          return 'rgb(' + Math.round(c.r / c.n) + ',' + Math.round(c.g / c.n) + ',' + Math.round(c.b / c.n) + ')';
        });
        if (colors.length >= 2) {
          els.fsBgGradient.style.background =
            'linear-gradient(160deg, ' + colors[0] + ' 0%, ' + colors[1] + ' 45%, ' + (colors[2] || colors[1]) + ' 100%)';
        }
      } catch (e) {
        // 跨域/取色失败则忽略，保持原背景
      }
    };
    img.src = realUrl;
  }

  function updateFullscreenProgress() {
    var p = state.playback;
    var dur = p.duration || 0;
    var pct = dur > 0 ? (p.currentTime / dur) * 100 : 0;
    els.fsProgressFill.style.width = pct + '%';
    els.fsProgressThumb.style.left = pct + '%';
    els.fsTimeCur.textContent = fmtTime(p.currentTime);
    els.fsTimeDur.textContent = fmtTime(dur);
  }

  function fsSeekFromEvent(e) {
    var rect = els.fsProgressBar.getBoundingClientRect();
    var pct = (e.clientX - rect.left) / rect.width;
    if (pct < 0) pct = 0;
    if (pct > 1) pct = 1;
    var dur = state.playback.duration || 0;
    els.audio.currentTime = pct * dur;
    state.playback.currentTime = pct * dur;
    updateFullscreenProgress();
  }

  /** 歌词页拖拽预览（安卓 LyricCascadeView 手势）。 */
  function bindLyricDrag() {
    var view = els.fsLyricView;
    view.addEventListener('touchstart', function (e) {
      if (!lyricEngine.lines.length) return;
      lyricEngine.isDragging = true;
      lyricEngine.touchStartY = e.touches[0].clientY;
      lyricEngine.lastTouchY = e.touches[0].clientY;
      lyricEngine.previewOffset = 0;
      view.classList.add('dragging');
      view.setPointerCapture && view.setPointerCapture(e.touches[0].identifier);
      e.preventDefault();
    }, { passive: false });
    view.addEventListener('touchmove', function (e) {
      if (!lyricEngine.isDragging) return;
      var y = e.touches[0].clientY;
      var delta = y - lyricEngine.lastTouchY;
      lyricEngine.lastTouchY = y;
      lyricEngine.previewOffset += delta;
      e.preventDefault();
    }, { passive: false });
    function endDrag(e) {
      if (!lyricEngine.isDragging) return;
      lyricEngine.isDragging = false;
      view.classList.remove('dragging');
      // 松手回弹：重置偏移
      lyricEngine.previewOffset = 0;
      // 若是点击（无位移），按点击位置 seek
      var moved = Math.abs(e.clientY - lyricEngine.touchStartY);
      if (moved < 8 && lyricEngine.nodes.length) {
        var rect = view.getBoundingClientRect();
        var targetY = e.clientY - rect.top - rect.height / 2;
        var idx = Math.round(targetY / lyricLineHeight());
        idx = Math.max(0, Math.min(lyricEngine.lines.length - 1, idx));
        if (state.currentSong && lyricEngine.lines[idx]) {
          els.audio.currentTime = lyricEngine.lines[idx].time;
        }
      }
    }
    view.addEventListener('touchend', endDrag);
    view.addEventListener('touchcancel', function () {
      lyricEngine.isDragging = false;
      lyricEngine.previewOffset = 0;
      view.classList.remove('dragging');
    });
    // 鼠标拖拽（桌面）
    view.addEventListener('mousedown', function (e) {
      if (!lyricEngine.lines.length) return;
      lyricEngine.isDragging = true;
      lyricEngine.touchStartY = e.clientY;
      lyricEngine.lastTouchY = e.clientY;
      lyricEngine.previewOffset = 0;
      view.classList.add('dragging');
      e.preventDefault();
    });
    window.addEventListener('mousemove', function (e) {
      if (!lyricEngine.isDragging) return;
      var delta = e.clientY - lyricEngine.lastTouchY;
      lyricEngine.lastTouchY = e.clientY;
      lyricEngine.previewOffset += delta;
    });
    window.addEventListener('mouseup', function (e) {
      if (!lyricEngine.isDragging) return;
      lyricEngine.isDragging = false;
      view.classList.remove('dragging');
      lyricEngine.previewOffset = 0;
      var moved = Math.abs(e.clientY - lyricEngine.touchStartY);
      if (moved < 8 && lyricEngine.nodes.length) {
        var rect = view.getBoundingClientRect();
        var targetY = e.clientY - rect.top - rect.height / 2;
        var idx = Math.round(targetY / lyricLineHeight());
        idx = Math.max(0, Math.min(lyricEngine.lines.length - 1, idx));
        if (state.currentSong && lyricEngine.lines[idx]) {
          els.audio.currentTime = lyricEngine.lines[idx].time;
        }
      }
    });
  }

  function bindFullscreenEvents() {
    // 打开：点播放条左侧（封面/标题区域）
    els.playerLeftArea.addEventListener('click', openFullscreen);
    // 返回
    els.fsBack.addEventListener('click', closeFullscreen);
    // 收藏
    els.fsFav.addEventListener('click', function () {
      if (state.currentSong) toggleFavorite(state.currentSong);
    });
    // 点赞
    els.fsLike.addEventListener('click', function () {
      if (state.currentSong) {
        toggleLike(state.currentSong, els.fsLike);
        syncFullscreenUI();
      }
    });
    // 控制
    els.fsPlay.addEventListener('click', togglePlay);
    els.fsNext.addEventListener('click', nextSong);
    els.fsPrev.addEventListener('click', prevSong);
    els.fsShuffle.addEventListener('click', function () {
      state.playback.isShuffle = !state.playback.isShuffle;
      updatePlayUI();
    });
    els.fsLoop.addEventListener('click', cycleLoop);
    // 进度
    els.fsProgressBar.addEventListener('click', fsSeekFromEvent);
    // 歌词拖拽
    bindLyricDrag();
    // Esc 关闭
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && fsState.open) closeFullscreen();
    });
  }

  function getActiveLyricIndex() {
    return lyricEngine.activeIndex;
  }

  function scrollToActiveLyric() {
    // 动画引擎自动居中，无需手动滚动
  }

  function updateLyricHighlight() {
    // 动画引擎在帧循环中处理，无需外部高亮
  }

  /* ---------- 我的歌单（云端同步，≤5 歌单 / 每歌单 ≤200 首） ---------- */
  var playlistsCache = null; // 云端歌单列表

  function loadPlaylists() {
    return apiRequest('/music/playlists').then(function (res) {
      if (res.code !== 200) {
        if (res.code === 401) { redirectLogin(); return; }
        toast('加载歌单失败 (' + res.code + ')', true);
        return;
      }
      playlistsCache = res.data.items || [];
      updatePlaylistBadge();
      if (state.activeTab === 'playlists') renderPlaylists();
    });
  }

  function updatePlaylistBadge() {
    var n = playlistsCache ? playlistsCache.length : 0;
    els.playlistBadge.textContent = String(n);
    els.playlistBadge.classList.toggle('hidden', n === 0);
  }

  function renderPlaylists() {
    var container = els.playlistList;
    container.innerHTML = '';
    var list = playlistsCache || [];
    if (!list.length) {
      var empty = document.createElement('div');
      empty.className = 'fav-empty';
      empty.innerHTML = '<div class="heart-big">▤</div><h3>No Playlists Yet</h3>' +
        '<p>点击「保存当前列表为歌单」可将发现页当前列表存为云端歌单，或在安卓客户端同步。</p>';
      container.appendChild(empty);
      return;
    }
    list.forEach(function (pl) {
      container.appendChild(renderPlaylistItem(pl));
    });
  }

  function renderPlaylistItem(pl) {
    var card = document.createElement('div');
    card.className = 'playlist-card';
    var songs = pl.songs || [];

    var head = document.createElement('div');
    head.className = 'playlist-head';
    var titleWrap = document.createElement('div');
    titleWrap.style.minWidth = '0';
    var name = document.createElement('h3');
    name.className = 'playlist-name';
    name.textContent = pl.name || '未命名歌单';
    var meta = document.createElement('p');
    meta.className = 'playlist-meta';
    meta.textContent = songs.length + ' 首 · 更新于 ' + fmtDate(pl.updated_at);
    titleWrap.appendChild(name);
    titleWrap.appendChild(meta);
    var playBtn = document.createElement('button');
    playBtn.className = 'load-more-btn accent';
    playBtn.textContent = '全部播放';
    playBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      playPlaylistSongs(songs);
    });
    head.appendChild(titleWrap);
    head.appendChild(playBtn);
    card.appendChild(head);

    var songList = document.createElement('div');
    songList.className = 'playlist-songs';
    if (songs.length) {
      songs.forEach(function (s, i) {
        var row = document.createElement('div');
        row.className = 'playlist-song-row' + (state.currentSong && state.currentSong.id === s.id ? ' current' : '');
        var idx = document.createElement('span');
        idx.className = 'fav-index';
        idx.textContent = (i + 1 < 10 ? '0' + (i + 1) : i + 1);
        var info = document.createElement('div');
        info.style.minWidth = '0';
        info.style.flex = '1';
        var t = document.createElement('p');
        t.className = 'playlist-song-name';
        t.textContent = s.name || '未知歌曲';
        var sub = document.createElement('p');
        sub.className = 'playlist-song-sub';
        sub.textContent = (s.owner_name || s.owner_uid || '') + (s.duration_ms ? ' · ' + fmtTime(Math.round(s.duration_ms / 1000)) : '');
        info.appendChild(t);
        info.appendChild(sub);
        row.appendChild(idx);
        row.appendChild(info);
        row.addEventListener('click', function () {
          selectSong(toPlayableSong(s));
        });
        songList.appendChild(row);
      });
    } else {
      var emptyTip = document.createElement('p');
      emptyTip.className = 'lyrics-empty';
      emptyTip.textContent = '空歌单';
      songList.appendChild(emptyTip);
    }
    card.appendChild(songList);
    return card;
  }

  /** 歌单接口返回的 song 结构与广场一致，直接可播（含 song_url / lyrics_url）。 */
  function toPlayableSong(s) {
    return s;
  }

  function playPlaylistSongs(songs) {
    if (!songs || !songs.length) { toast('歌单为空', true); return; }
    // 设置播放队列（歌单全部歌曲），播完自动下一首
    state.queue = songs.slice();
    selectSong(songs[0]);
    state.playback.isPlaying = true;
    els.audio.play().catch(function () {});
    updatePlayUI();
    toast('播放歌单，共 ' + songs.length + ' 首');
  }

  function fmtDate(ts) {
    if (!ts) return '';
    var d = new Date(ts * 1000);
    return d.getFullYear() + '-' + (d.getMonth() + 1 < 10 ? '0' + (d.getMonth() + 1) : d.getMonth() + 1) + '-' + (d.getDate() < 10 ? '0' + d.getDate() : d.getDate());
  }

  /** 把发现页当前列表保存为云端歌单（新建一个歌单并整体 sync 上传）。 */
  function saveCurrentAsPlaylist() {
    var pool = state.songs;
    if (!pool.length) { toast('当前列表为空，无法保存', true); return; }
    var songs = pool.map(function (s) {
      return { song_id: s.id, song: s };
    });
    var name = '网页歌单 ' + new Date().toISOString().slice(5, 10);
    var payload = { playlists: [{ id: 'web-pl-' + Date.now(), name: name, cover_url: '', songs: songs }] };
    // 若已有云端歌单，合并（保留已有，只追加新建的），避免覆盖
    var existing = playlistsCache || [];
    var merged = existing.map(function (pl) {
      return {
        id: pl.id,
        name: pl.name,
        cover_url: pl.cover_url || '',
        songs: (pl.songs || []).map(function (s) { return { song_id: s.id, song: s }; })
      };
    });
    if (merged.length >= 5) { toast('已达 5 个歌单上限，请先在安卓端删除', true); return; }
    merged.push(payload.playlists[0]);
    apiRequest('/music/playlists/sync', { method: 'POST', body: { playlists: merged } }).then(function (res) {
      if (res.code === 200) {
        toast('已保存为「' + name + '」');
        loadPlaylists();
      } else {
        toast('保存失败 (' + res.code + ')', true);
      }
    });
  }

  /* ---------- 评论 ---------- */
  function loadComments(itemId) {
    apiRequest('/music/plaza/comments?item_id=' + encodeURIComponent(itemId)).then(function (res) {
      if (res.code !== 200) return;
      var comments = res.data.comments || [];
      state.commentsCache[itemId] = comments;
      renderComments(itemId);
    });
  }

  function renderComments(itemId) {
    var comments = state.commentsCache[itemId] || [];
    els.commentsCount.textContent = String(comments.length);
    els.commentsList.innerHTML = '';
    if (!comments.length) {
      var empty = document.createElement('p');
      empty.className = 'comments-empty';
      empty.textContent = '还没有评论，来抢沙发';
      els.commentsList.appendChild(empty);
      return;
    }
    comments.forEach(function (c) {
      var row = document.createElement('div');
      row.className = 'comment-item';
      var avatar = document.createElement('div');
      avatar.className = 'c-avatar';
      if (c.from_avatar) {
        var img = document.createElement('img');
        img.src = c.from_avatar;
        img.onerror = function () { this.style.display = 'none'; this.parentNode.textContent = (c.from_name || '?').slice(0, 1); };
        avatar.appendChild(img);
      } else {
        avatar.textContent = (c.from_name || '?').slice(0, 1);
      }
      var body = document.createElement('div');
      body.className = 'c-body';
      var nameRow = document.createElement('div');
      nameRow.style.display = 'flex';
      nameRow.style.alignItems = 'center';
      nameRow.style.gap = '8px';
      var name = document.createElement('p');
      name.className = 'c-name';
      name.textContent = c.from_name || c.from_uid || '';
      var ctime = document.createElement('span');
      ctime.className = 'c-name';
      ctime.style.color = 'var(--zinc-700)';
      ctime.style.fontSize = '9px';
      ctime.textContent = fmtTimeAgo(c.created_at);
      nameRow.appendChild(name);
      nameRow.appendChild(ctime);
      var text = document.createElement('p');
      text.className = 'c-text';
      text.textContent = c.body || '';
      body.appendChild(nameRow);
      body.appendChild(text);
      row.appendChild(avatar);
      row.appendChild(body);
      els.commentsList.appendChild(row);
    });
  }

  function submitComment() {
    var song = state.currentSong;
    var text = els.commentInput.value.trim();
    if (!song) { toast('请先选择歌曲', true); return; }
    if (!text) { toast('评论不能为空', true); return; }
    apiRequest('/music/plaza/comment', { method: 'POST', body: { item_id: song.id, body: text } }).then(function (res) {
      if (res.code === 200 || res.code === 201) {
        els.commentInput.value = '';
        loadComments(song.id);
        toast('评论成功');
        // 更新评论数
        var found = state.songs.find(function (s) { return s.id === song.id; });
        if (found) { found.comments = (found.comments || 0) + 1; renderSongs(); }
      } else {
        toast('评论失败 (' + res.code + ')', true);
      }
    });
  }

  /* ---------- 上传 ---------- */
  function handleUpload(e) {
    e.preventDefault();
    var name = els.upName.value.trim();
    var songFile = els.upSong.files[0];
    if (!name) { els.uploadMsg.textContent = '请填写歌曲名'; els.uploadMsg.className = 'upload-msg err'; return; }
    if (!songFile) { els.uploadMsg.textContent = '请选择音频文件'; els.uploadMsg.className = 'upload-msg err'; return; }
    if (songFile.size > 20 * 1024 * 1024) { els.uploadMsg.textContent = '音频不能超过 20MB'; els.uploadMsg.className = 'upload-msg err'; return; }
    var fd = new FormData();
    fd.append('name', name);
    fd.append('song', songFile);
    if (els.upCover.files[0]) {
      if (els.upCover.files[0].size > 1024 * 1024) { els.uploadMsg.textContent = '封面不能超过 1MB'; els.uploadMsg.className = 'upload-msg err'; return; }
      fd.append('cover', els.upCover.files[0]);
    }
    if (els.upLyrics.files[0]) {
      if (els.upLyrics.files[0].size > 1024 * 1024) { els.uploadMsg.textContent = '歌词不能超过 1MB'; els.uploadMsg.className = 'upload-msg err'; return; }
      fd.append('lyrics', els.upLyrics.files[0]);
    }
    els.btnUpload.disabled = true;
    els.uploadMsg.textContent = '上传中…';
    els.uploadMsg.className = 'upload-msg';
    apiRequest('/music/plaza/upload', { method: 'POST', body: fd }).then(function (res) {
      if (res.code === 201) {
        els.uploadMsg.textContent = '上传成功！';
        els.uploadMsg.className = 'upload-msg ok';
        els.uploadForm.reset();
        setTimeout(function () { els.uploadMsg.textContent = ''; }, 3000);
        // 刷新列表
        loadSongs(true);
      } else {
        var msg = res.data.message || res.data.error || ('上传失败 (' + res.code + ')');
        els.uploadMsg.textContent = msg;
        els.uploadMsg.className = 'upload-msg err';
      }
    }).finally(function () {
      els.btnUpload.disabled = false;
    });
  }

  /* ---------- 登录/退出与用户状态 ---------- */
  function updateUserUI() {
    var auth = loadAuth();
    if (auth && auth.access_token) {
      if (els.userLoggedOut) els.userLoggedOut.classList.add('hidden');
      if (els.userLoggedIn) els.userLoggedIn.classList.remove('hidden');
      var name = (auth.user && (auth.user.display_name || auth.user.username)) || auth.username || auth.uid || '已登录';
      if (els.userNameDisplay) els.userNameDisplay.textContent = '@' + name;
    } else {
      if (els.userLoggedOut) els.userLoggedOut.classList.remove('hidden');
      if (els.userLoggedIn) els.userLoggedIn.classList.add('hidden');
    }
  }

  function openLoginModal() {
    if (els.loginModal) {
      els.loginModal.classList.remove('hidden');
      if (els.loginModalError) els.loginModalError.textContent = '';
      if (els.loginIdentifier) els.loginIdentifier.focus();
    }
  }

  function closeLoginModal() {
    if (els.loginModal) {
      els.loginModal.classList.add('hidden');
    }
  }

  function redirectLogin() {
    toast('请先登录旧聊账号', true);
    openLoginModal();
  }

  /* ---------- 事件绑定 ---------- */
  function bindEvents() {
    $$('.menu-item').forEach(function (btn) {
      btn.addEventListener('click', function () { setActiveTab(btn.dataset.tab); });
    });
    $$('.mobile-tabs button').forEach(function (btn) {
      btn.addEventListener('click', function () { setActiveTab(btn.dataset.tab); });
    });

    var debounceTimer = null;
    els.searchInput.addEventListener('input', function () {
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(function () {
        state.search = els.searchInput.value.trim();
        loadSongs(true);
      }, 400);
    });

    els.btnLoadMore.addEventListener('click', function () {
      loadSongs(false);
    });

    els.btnPlay.addEventListener('click', togglePlay);
    els.btnNext.addEventListener('click', nextSong);
    els.btnPrev.addEventListener('click', prevSong);
    els.btnShuffle.addEventListener('click', function () {
      state.playback.isShuffle = !state.playback.isShuffle;
      updatePlayUI();
    });
    els.btnLoop.addEventListener('click', cycleLoop);

    els.progressBar.addEventListener('click', function (e) { seekFromEvent(e, els.progressBar); });
    els.volumeBar.addEventListener('click', function (e) {
      var rect = els.volumeBar.getBoundingClientRect();
      var pct = (e.clientX - rect.left) / rect.width;
      if (pct < 0) pct = 0;
      if (pct > 1) pct = 1;
      state.playback.volume = pct;
      state.playback.isMuted = pct === 0;
      els.audio.volume = pct;
      els.audio.muted = state.playback.isMuted;
      updatePlayUI();
    });
    els.btnMute.addEventListener('click', function () {
      state.playback.isMuted = !state.playback.isMuted;
      els.audio.muted = state.playback.isMuted;
      updatePlayUI();
    });
    // 全屏播放按钮
    els.btnExpand.addEventListener('click', function () {
      if (!state.currentSong) { toast('请先选择一首歌', true); return; }
      openFullscreen();
    });

    els.btnPlayerFav.addEventListener('click', function () {
      if (state.currentSong) toggleFavorite(state.currentSong);
    });

    els.btnComment.addEventListener('click', submitComment);
    els.commentInput.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') submitComment();
    });

    els.uploadForm.addEventListener('submit', handleUpload);

    // 登录弹窗与用户状态
    if (els.btnLogin) {
      els.btnLogin.addEventListener('click', openLoginModal);
    }
    if (els.btnCloseLoginModal) {
      els.btnCloseLoginModal.addEventListener('click', closeLoginModal);
    }
    if (els.loginModal) {
      els.loginModal.addEventListener('click', function (e) {
        if (e.target === els.loginModal) closeLoginModal();
      });
    }
    if (els.loginForm) {
      els.loginForm.addEventListener('submit', function (e) {
        e.preventDefault();
        var id = els.loginIdentifier ? els.loginIdentifier.value.trim() : '';
        var pwd = els.loginPassword ? els.loginPassword.value : '';
        if (!id || !pwd) return;

        if (els.btnSubmitLogin) els.btnSubmitLogin.disabled = true;
        if (els.loginModalError) els.loginModalError.textContent = '正在登录...';

        apiRequest('/auth/login', {
          method: 'POST',
          body: {
            identifier: id,
            password: pwd,
            device_id: 'web_music_plaza',
            device_name: 'Web Browser',
            platform: 'web',
            app_version: '1.3.62'
          }
        }).then(function (res) {
          var data = res.data && res.data.access_token ? res.data : (res.data && res.data.data && res.data.data.access_token ? res.data.data : null);
          if (res.code === 200 && data) {
            try {
              localStorage.setItem('oldchat_web_auth', JSON.stringify(data));
              var expires = new Date(Date.now() + 30 * 86400 * 1000).toUTCString();
              document.cookie = 'oldchat_auth=' + encodeURIComponent(JSON.stringify(data)) + '; expires=' + expires + '; path=/; SameSite=Lax; Secure';
            } catch (e2) {}

            toast('登录成功，欢迎回来！');
            closeLoginModal();
            updateUserUI();
            loadFavorites();
            loadPlaylists();
            loadMine();
          } else {
            var err = (res.data && (res.data.message || res.data.error)) || '登录失败，请检查账号密码';
            if (els.loginModalError) els.loginModalError.textContent = err;
          }
        }).catch(function () {
          if (els.loginModalError) els.loginModalError.textContent = '网络错误，请稍后再试';
        }).finally(function () {
          if (els.btnSubmitLogin) els.btnSubmitLogin.disabled = false;
        });
      });
    }

    if (els.btnLogout) {
      els.btnLogout.addEventListener('click', function () {
        if (confirm('确定要退出登录吗？')) {
          try { localStorage.removeItem('oldchat_web_auth'); } catch (e) {}
          document.cookie = 'oldchat_auth=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
          updateUserUI();
          state.favorites = [];
          state.playlists = [];
          state.mineSongs = [];
          toast('已退出登录');
        }
      });
    }

    // 我的歌单
    els.btnPlaylistRefresh.addEventListener('click', function () {
      toast('刷新中…');
      loadPlaylists();
    });
    els.btnPlaylistSave.addEventListener('click', function () {
      saveCurrentAsPlaylist();
    });

    // 我的发布
    els.btnMineRefresh.addEventListener('click', function () {
      toast('刷新中…');
      loadMine();
    });

    // 音频事件
    var audio = els.audio;
    audio.addEventListener('loadedmetadata', function () {
      state.playback.duration = audio.duration || 0;
      updateProgress();
    });
    audio.addEventListener('timeupdate', function () {
      state.playback.currentTime = audio.currentTime;
      if (state.playback.duration === 0 && audio.duration && isFinite(audio.duration)) {
        state.playback.duration = audio.duration;
      }
      updateProgress();
    });
    audio.addEventListener('pause', function () {
      state.playback.isPlaying = false;
      updatePlayUI();
      lyricStopEngine();
    });
    audio.addEventListener('play', function () {
      state.playback.isPlaying = true;
      updatePlayUI();
      lyricEngine.playTime = audio.currentTime || lyricEngine.playTime;
      if (lyricEngine.nodes.length) lyricStartEngine();
    });
    audio.addEventListener('ended', function () {
      if (state.playback.loopMode === 'one') {
        audio.currentTime = 0;
        audio.play().catch(function () {});
      } else if (state.playback.loopMode === 'all') {
        nextSong();
      } else {
        var idx = state.currentSong ? state.songs.findIndex(function (s) { return s.id === state.currentSong.id; }) : -1;
        if (idx < state.songs.length - 1) nextSong();
        else state.playback.isPlaying = false;
      }
    });

    // 全屏播放器事件
    bindFullscreenEvents();
  }

  /* ---------- 启动 ---------- */
  function init() {
    bindEvents();
    renderIcons();
    updateUserUI();
    setCoverImage(els.vinylCover, '');
    setCoverImage(els.playerCover, '');
    setCoverImage(els.fsCover, '');
    els.audio.volume = state.playback.volume;
    els.volumeFill.style.width = '80%';
    updatePlayUI();
    loadSongs(true);
    loadRanking();
    if (getToken()) {
      loadFavorites();
      loadPlaylists();
      loadMine();
    }
  }

  init();
})();


