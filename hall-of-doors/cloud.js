/* ===================== ACCOUNTS · CLOUD SAVE · UPDATES · LANDSCAPE ===================== */
(function () {
  'use strict';
  const EN = () => { try { return (window.hodLang ? window.hodLang() : localStorage.getItem('hod-lang')) === 'en'; } catch (e) { return false; } };
  const tx = (ar, en) => EN() ? en : ar;
  const C = window.CLOUD = { enabled: false, user: null, ready: false, listeners: [], platform: window.GAME_PLATFORM || 'web' };
  C.inApp = C.platform !== 'web';
  const $ = s => document.querySelector(s);
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const cfg = window.FIREBASE_CONFIG;
  C.on = f => C.listeners.push(f);
  const emit = () => C.listeners.forEach(f => { try { f(C); } catch (e) { console.warn(e); } });
  C.displayName = () => C.user ? (C.user.displayName || (C.user.email || '').split('@')[0] || tx('لاعب', 'Player')) : tx('ضيف', 'Guest');

  if (cfg && cfg.apiKey && !/PUT_|YOUR/i.test(cfg.apiKey) && window.firebase) {
    try {
      firebase.initializeApp(cfg); C.auth = firebase.auth(); C.db = firebase.firestore(); C.enabled = true;
      C.auth.onAuthStateChanged(u => { C.user = u; C.ready = true; if (u) C.pull(); emit(); });
    } catch (e) { console.warn('firebase init failed', e); C.ready = true; }
  } else C.ready = true;

  /* ---------- cloud save ---------- */
  let pushT = 0;
  C.push = save => {
    if (!C.enabled || !C.user || !save) return;
    clearTimeout(pushT);
    pushT = setTimeout(() => {
      C.db.collection('hod_users').doc(C.user.uid).set({
        save: JSON.stringify(save), xp: save.xp || 0, stars: Object.values(save.stars || {}).reduce((a, b) => a + b, 0),
        doors: Object.keys(save.stars || {}).length, name: C.displayName(), ...(window.hodStats ? window.hodStats(save) : {}), updatedAt: firebase.firestore.FieldValue.serverTimestamp()
      }, { merge: true }).then(() => { C.lastSync = Date.now(); emit(); }).catch(e => console.warn('push failed', e));
    }, 1200);
  };
  C.pull = async () => {
    try {
      const d = await C.db.collection('hod_users').doc(C.user.uid).get();
      const local = window.__game && window.__game.save;
      if (d.exists && d.data().save) {
        const remote = JSON.parse(d.data().save);
        if (!local || (remote.xp || 0) >= (local.xp || 0)) { window.__game && window.__game.adoptSave(remote); toastMsg(tx('☁️ تم تحميل تقدّمك من حسابك', '☁️ Progress loaded from your account')); if (window.hodStats && d.data().points !== window.hodStats(remote).points) C.push(remote); }
        else C.push(local);
      } else if (local) C.push(local);
      C.lastSync = Date.now(); emit();
    } catch (e) { console.warn('pull failed', e); }
  };
  C.top = async (n = 50) => { const q = await C.db.collection('hod_users').orderBy('points', 'desc').limit(n).get(); return q.docs.map(d => Object.assign({ uid: d.id }, d.data())); };

  /* ---------- small UI helpers (own overlay, above the game) ---------- */
  function overlay(html) {
    let o = $('#acct'); if (!o) { o = document.createElement('div'); o.id = 'acct'; o.className = 'acct'; document.body.appendChild(o); }
    o.innerHTML = `<div class="acct-in">${html}</div>`; o.hidden = false; return o;
  }
  C.close = () => { const o = $('#acct'); if (o) o.hidden = true; };
  function toastMsg(t) { const d = document.createElement('div'); d.className = 'qtoast good'; d.style.zIndex = 90; d.innerHTML = t; document.body.appendChild(d); setTimeout(() => d.remove(), 3000); }
  const errAr = e => errMsg(e);
  const errAr0 = e => ({ 'auth/invalid-email': 'البريد الإلكتروني غير صحيح', 'auth/user-not-found': 'لا يوجد حساب بهذا البريد', 'auth/wrong-password': 'كلمة المرور غير صحيحة', 'auth/invalid-credential': 'البريد أو كلمة المرور غير صحيحة', 'auth/email-already-in-use': 'هذا البريد مسجّل مسبقاً — سجّل الدخول', 'auth/weak-password': 'كلمة المرور قصيرة (6 أحرف على الأقل)', 'auth/network-request-failed': 'لا يوجد اتصال بالإنترنت', 'auth/popup-closed-by-user': 'أُغلقت نافذة تسجيل الدخول', 'auth/too-many-requests': 'محاولات كثيرة، انتظر قليلاً', 'auth/account-exists-with-different-credential': 'بريد حساب GitHub هذا مسجّل مسبقاً بكلمة مرور — سجّل الدخول بالإيميل وكلمة المرور', 'auth/operation-not-allowed': 'الدخول بـ GitHub غير مفعّل بعد في إعدادات اللعبة', 'auth/unauthorized-domain': 'هذا الموقع غير مضاف في إعدادات Firebase (Authorized domains)', 'auth/popup-blocked': 'المتصفح منع نافذة تسجيل الدخول — اسمح بالنوافذ المنبثقة وحاول مجدداً', 'auth/cancelled-popup-request': 'أُغلقت نافذة تسجيل الدخول' }[e && e.code]); const errEn = e => ({ 'auth/invalid-email': 'Invalid email address', 'auth/user-not-found': 'No account with this email', 'auth/wrong-password': 'Wrong password', 'auth/invalid-credential': 'Wrong email or password', 'auth/email-already-in-use': 'This email is already registered — sign in', 'auth/weak-password': 'Password too short (at least 6 characters)', 'auth/network-request-failed': 'No internet connection', 'auth/popup-closed-by-user': 'The sign-in window was closed', 'auth/too-many-requests': 'Too many attempts, wait a little', 'auth/account-exists-with-different-credential': 'This GitHub email is already registered with a password — sign in with email and password', 'auth/operation-not-allowed': 'GitHub sign-in is not enabled yet in the game settings', 'auth/unauthorized-domain': 'This site is not added in Firebase (Authorized domains)', 'auth/popup-blocked': 'The browser blocked the sign-in window — allow pop-ups and try again', 'auth/cancelled-popup-request': 'The sign-in window was closed' })[e && e.code];
  const errMsg = e => (EN() ? errEn(e) : errAr0(e)) || (e && e.message) || tx('حدث خطأ', 'Something went wrong');

  C.openLogin = (onDone, allowGuest = true) => {
    if (!C.enabled) {
      overlay(tx(`<h2>🔐 الحسابات غير مفعّلة بعد</h2><p class="muted">صاحب اللعبة لم يربط قاعدة البيانات (Firebase) بعد، لذلك يُحفظ تقدّمك على هذا الجهاز فقط.</p><div class="row center mt"><button class="btn" id="ac-ok">العب الآن ▶</button></div>`, `<h2>🔐 Accounts are not enabled yet</h2><p class="muted">The game owner hasn’t connected the database (Firebase) yet, so your progress is saved on this device only.</p><div class="row center mt"><button class="btn" id="ac-ok">Play now ▶</button></div>`));
      $('#ac-ok').onclick = () => { C.close(); onDone && onDone(); }; return;
    }
    let mode = 'login';
    const draw = (msg = '') => {
      overlay(`<h2>${mode === 'login' ? tx('🔐 تسجيل الدخول', '🔐 Sign in') : tx('✨ إنشاء حساب جديد', '✨ Create a new account')}</h2>
        <p class="muted">${tx('سجّل دخولك ليُحفظ تقدّمك في السحابة وتكمل من أي جهاز (آيفون، أندرويد، كمبيوتر) 🌐', 'Sign in to save your progress in the cloud and continue on any device (iPhone, Android, computer) 🌐')}</p>
        <form id="ac-f" class="acct-form" autocomplete="on">
          ${mode === 'signup' ? `<label>${tx('الاسم', 'Name')}<input id="ac-name" required maxlength="24" placeholder="${tx('اسمك في اللعبة', 'Your in-game name')}"></label>` : ''}
          <label>${tx('البريد الإلكتروني', 'Email')}<input id="ac-mail" type="email" required dir="ltr" autocomplete="email" placeholder="you@example.com"></label>
          <label>${tx('كلمة المرور', 'Password')}<input id="ac-pass" type="password" required minlength="6" dir="ltr" autocomplete="${mode === 'login' ? 'current-password' : 'new-password'}"></label>
          ${msg ? `<p class="acct-err">${msg}</p>` : ''}
          <button class="btn" type="submit" id="ac-go">${mode === 'login' ? tx('دخول ▶', 'Sign in ▶') : tx('إنشاء الحساب ✨', 'Create account ✨')}</button>
        </form>
        ${C.inApp ? '' : `<div class="acct-or">${tx('أو', 'or')}</div><button class="btn ghost" id="ac-github" style="display:flex;align-items:center;justify-content:center;gap:8px;width:100%"><svg width="18" height="18" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg> ${tx('الدخول بحساب GitHub', 'Sign in with GitHub')}</button>`}
        <div class="row center mt" style="gap:14px">
          <a href="#" id="ac-sw">${mode === 'login' ? tx('ما عندك حساب؟ أنشئ حساباً', 'No account? Create one') : tx('عندك حساب؟ سجّل الدخول', 'Have an account? Sign in')}</a>
          ${mode === 'login' ? `<a href="#" id="ac-reset">${tx('نسيت كلمة المرور؟', 'Forgot password?')}</a>` : ''}
        </div>
        ${allowGuest ? `<div class="row center mt"><button class="btn ghost small" id="ac-guest">${tx('العب كضيف (حفظ على هذا الجهاز فقط)', 'Play as guest (saved on this device only)')}</button></div>` : `<div class="row center mt"><button class="btn ghost small" id="ac-x">${tx('إغلاق', 'Close')}</button></div>`}`);
      $('#ac-f').onsubmit = async ev => {
        ev.preventDefault(); const mail = $('#ac-mail').value.trim(), pass = $('#ac-pass').value; $('#ac-go').disabled = true;
        try {
          if (mode === 'login') await C.auth.signInWithEmailAndPassword(mail, pass);
          else { const r = await C.auth.createUserWithEmailAndPassword(mail, pass); await r.user.updateProfile({ displayName: $('#ac-name').value.trim() }); C.user = C.auth.currentUser; emit(); }
          C.close(); toastMsg(`👋 ${tx('أهلاً', 'Welcome,')} ${esc(C.displayName())}!`); onDone && onDone();
        } catch (e) { draw(errAr(e)); }
      };
      $('#ac-sw').onclick = e => { e.preventDefault(); mode = mode === 'login' ? 'signup' : 'login'; draw(); };
      const rs = $('#ac-reset'); if (rs) rs.onclick = async e => { e.preventDefault(); const mail = $('#ac-mail').value.trim(); if (!mail) return draw(tx('اكتب بريدك أولاً ثم اضغط «نسيت كلمة المرور»', 'Type your email first, then tap “Forgot password?”')); try { await C.auth.sendPasswordResetEmail(mail); draw(tx('📧 أرسلنا رابط إعادة التعيين إلى بريدك', '📧 We sent a reset link to your email')); } catch (er) { draw(errAr(er)); } };
      const gh = $('#ac-github'); if (gh) gh.onclick = async () => {
        gh.disabled = true;
        try {
          const r = await C.auth.signInWithPopup(new firebase.auth.GithubAuthProvider());
          const u = r.user, nick = r.additionalUserInfo && r.additionalUserInfo.username;
          if (u && !u.displayName && nick) { await u.updateProfile({ displayName: nick }); C.user = C.auth.currentUser; emit(); }
          C.close(); toastMsg(`👋 ${tx('أهلاً', 'Welcome,')} ${esc(C.displayName())}!`); onDone && onDone();
        } catch (e) { draw(errAr(e)); }
      };
      const gu = $('#ac-guest'); if (gu) gu.onclick = () => { C.guest = true; try { localStorage.setItem('hod-guest', '1'); } catch (e) { } C.close(); onDone && onDone(); };
      const x = $('#ac-x'); if (x) x.onclick = C.close;
    };
    draw();
  };
  C.signOut = async () => { if (C.enabled && C.user) { await C.auth.signOut(); toastMsg(tx('👋 تم تسجيل الخروج', '👋 Signed out')); } };
  C.isGuest = () => { if (C.guest) return true; try { return localStorage.getItem('hod-guest') === '1'; } catch (e) { return false; } };
  C.openLeaderboard = async () => {
    if (!C.enabled) return;
    overlay(tx('<h2>🏆 لوحة الشرف</h2><p class="muted">⏳ جاري التحميل…</p>', '<h2>🏆 Hall of Fame</h2><p class="muted">⏳ Loading…</p>'));
    try {
      const all = await C.top(50), me = C.user ? all.findIndex(r => r.uid === C.user.uid) : -1, rows = all.slice(0, 20);
      const mine = window.hodStats && window.__game ? window.hodStats(window.__game.save) : null;
      const row = (r, i) => `<tr class="${C.user && r.uid === C.user.uid ? 'me' : ''}"><td>${['🥇', '🥈', '🥉'][i] || i + 1}</td><td>${esc(r.name || tx('لاعب', 'Player'))}</td><td dir="ltr"><b>${r.points || 0}</b></td><td>${r.doors || 0}/8</td><td>${r.quizzes || 0}/8</td><td>${r.mid ? '🎓' : '—'}</td></tr>`;
      overlay(`<h2>${tx('🏆 لوحة الشرف', '🏆 Hall of Fame')}</h2>
        <p class="muted" style="margin:0 0 6px">${tx('النقاط 🏅 = نجوم المحاضرات ×100 + أفضل علامة كويز ×10 + الامتحان النصفي (500 + نجومه ×250)', 'Points 🏅 = lecture stars ×100 + best quiz score ×10 + MID exam (500 + its stars ×250)')}</p>
        ${C.user ? `<p class="lb-me">👤 <b>${esc(C.displayName())}</b> · 🏅 <b dir="ltr">${mine ? mine.points : '—'}</b> · ${tx('الترتيب:', 'Rank:')} <b>${me >= 0 ? '#' + (me + 1) : tx('خارج أفضل 50', 'outside the top 50')}</b></p>` : `<p class="muted">${tx('🔐 سجّل الدخول لتظهر في لوحة الشرف', '🔐 Sign in to appear in the Hall of Fame')}</p>`}
        <div style="max-height:46vh;overflow:auto"><table class="lb"><tr><th>#</th><th>${tx('اللاعب', 'Player')}</th><th>🏅 ${tx('نقاط', 'Points')}</th><th>🚪</th><th>📝</th><th>🎓</th></tr>${rows.length ? rows.map(row).join('') : `<tr><td colspan="6">${tx('لا يوجد لاعبون بعد — كن الأول! 🌟', 'No players yet — be the first! 🌟')}</td></tr>`}</table></div>
        <div class="row center mt"><button class="btn" id="lb-x">${tx('إغلاق', 'Close')}</button></div>`);
    } catch (e) { overlay(`<h2>${tx('🏆 لوحة الشرف', '🏆 Hall of Fame')}</h2><p class="acct-err">${esc(errAr(e))}</p><div class="row center mt"><button class="btn" id="lb-x">${tx('إغلاق', 'Close')}</button></div>`); }
    $('#lb-x').onclick = C.close;
  };

  /* ---------- opening links (web / android / windows) ---------- */
  C.openExternal = url => { if (C.platform === 'android') location.href = url; else window.open(url, '_blank'); };

  /* ---------- update check ---------- */
  const cmp = (a, b) => { const x = String(a).split('.').map(Number), y = String(b).split('.').map(Number); for (let i = 0; i < 3; i++) { if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) - (y[i] || 0); } return 0; };
  C.checkUpdate = async (manual) => {
    const base = window.SITE_URL || ''; if (!base && C.inApp) return;
    try {
      const r = await fetch((C.inApp ? base : '') + 'version.json?t=' + Date.now(), { cache: 'no-store' }); if (!r.ok) throw 0;
      const v = await r.json(), mine = window.APP_VERSION || '0.0.0';
      if (cmp(v.version, mine) > 0) {
        const link = C.platform === 'android' ? v.apk : C.platform === 'windows' ? v.exe : null;
        const notes = EN() && v.notes_en ? v.notes_en : v.notes;
        overlay(`<h2>${tx('🆕 تحديث جديد متوفّر!', '🆕 A new update is available!')}</h2><p>${tx('الإصدار', 'Version')} <b dir="ltr">${esc(v.version)}</b> (${tx('عندك', 'you have')} <span dir="ltr">${esc(mine)}</span>)</p>${notes ? `<ul class="notes">${[].concat(notes).map(n => `<li>${esc(n)}</li>`).join('')}</ul>` : ''}
          <div class="row center mt">${link ? `<button class="btn" id="up-dl">${tx('⬇️ تنزيل التحديث', '⬇️ Download the update')}</button>` : `<button class="btn" id="up-rl">${tx('🔄 حدّث الصفحة', '🔄 Reload the page')}</button>`}${C.inApp && v.web ? `<button class="btn ghost" id="up-web">${tx('🌐 العب النسخة الأونلاين', '🌐 Play the online version')}</button>` : ''}<button class="btn ghost" id="up-later">${tx('لاحقاً', 'Later')}</button></div>
          ${C.platform === 'android' ? `<p class="muted mt">${tx('بعد التنزيل افتح الملف لتثبيت التحديث فوق النسخة الحالية (تقدّمك المحفوظ بحسابك لن يضيع).', 'After downloading, open the file to install the update over the current version (progress saved to your account won’t be lost).')}</p>` : ''}`);
        const dl = $('#up-dl'); if (dl) dl.onclick = () => C.openExternal(link);
        const rl = $('#up-rl'); if (rl) rl.onclick = () => location.reload();
        const wb = $('#up-web'); if (wb) wb.onclick = () => C.openExternal(v.web);
        $('#up-later').onclick = C.close;
      } else if (manual) toastMsg(tx('✅ عندك آخر إصدار (', '✅ You have the latest version (') + esc(mine) + ')');
    } catch (e) { if (manual) toastMsg(tx('⚠️ تعذّر التحقق من التحديثات', '⚠️ Couldn’t check for updates')); }
  };

  /* ---------- landscape ---------- */
  C.landscape = async () => {
    try {
      if (C.platform !== 'web') return;
      const el = document.documentElement;
      if (matchMedia('(pointer: coarse)').matches && !document.fullscreenElement && el.requestFullscreen) await el.requestFullscreen({ navigationUI: 'hide' });
      if (screen.orientation && screen.orientation.lock) await screen.orientation.lock('landscape');
    } catch (e) { /* iOS Safari cannot lock — the rotate overlay handles it */ }
  };
  addEventListener('load', () => { setTimeout(() => C.checkUpdate(false), 4000); });
})();
