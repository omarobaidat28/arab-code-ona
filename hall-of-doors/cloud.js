/* ===================== ACCOUNTS · CLOUD SAVE · UPDATES · LANDSCAPE ===================== */
(function () {
  'use strict';
  const C = window.CLOUD = { enabled: false, user: null, ready: false, listeners: [], platform: window.GAME_PLATFORM || 'web' };
  C.inApp = C.platform !== 'web';
  const $ = s => document.querySelector(s);
  const esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const cfg = window.FIREBASE_CONFIG;
  C.on = f => C.listeners.push(f);
  const emit = () => C.listeners.forEach(f => { try { f(C); } catch (e) { console.warn(e); } });
  C.displayName = () => C.user ? (C.user.displayName || (C.user.email || '').split('@')[0] || 'لاعب') : 'ضيف';

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
        if (!local || (remote.xp || 0) >= (local.xp || 0)) { window.__game && window.__game.adoptSave(remote); toastMsg('☁️ تم تحميل تقدّمك من حسابك'); if (window.hodStats && d.data().points !== window.hodStats(remote).points) C.push(remote); }
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
  const errAr = e => ({ 'auth/invalid-email': 'البريد الإلكتروني غير صحيح', 'auth/user-not-found': 'لا يوجد حساب بهذا البريد', 'auth/wrong-password': 'كلمة المرور غير صحيحة', 'auth/invalid-credential': 'البريد أو كلمة المرور غير صحيحة', 'auth/email-already-in-use': 'هذا البريد مسجّل مسبقاً — سجّل الدخول', 'auth/weak-password': 'كلمة المرور قصيرة (6 أحرف على الأقل)', 'auth/network-request-failed': 'لا يوجد اتصال بالإنترنت', 'auth/popup-closed-by-user': 'أُغلقت نافذة تسجيل الدخول', 'auth/too-many-requests': 'محاولات كثيرة، انتظر قليلاً', 'auth/account-exists-with-different-credential': 'بريد حساب GitHub هذا مسجّل مسبقاً بكلمة مرور — سجّل الدخول بالإيميل وكلمة المرور', 'auth/operation-not-allowed': 'الدخول بـ GitHub غير مفعّل بعد في إعدادات اللعبة', 'auth/unauthorized-domain': 'هذا الموقع غير مضاف في إعدادات Firebase (Authorized domains)', 'auth/popup-blocked': 'المتصفح منع نافذة تسجيل الدخول — اسمح بالنوافذ المنبثقة وحاول مجدداً', 'auth/cancelled-popup-request': 'أُغلقت نافذة تسجيل الدخول' }[e && e.code] || (e && e.message) || 'حدث خطأ');

  C.openLogin = (onDone, allowGuest = true) => {
    if (!C.enabled) {
      overlay(`<h2>🔐 الحسابات غير مفعّلة بعد</h2><p class="muted">صاحب اللعبة لم يربط قاعدة البيانات (Firebase) بعد، لذلك يُحفظ تقدّمك على هذا الجهاز فقط.</p><div class="row center mt"><button class="btn" id="ac-ok">العب الآن ▶</button></div>`);
      $('#ac-ok').onclick = () => { C.close(); onDone && onDone(); }; return;
    }
    let mode = 'login';
    const draw = (msg = '') => {
      overlay(`<h2>${mode === 'login' ? '🔐 تسجيل الدخول' : '✨ إنشاء حساب جديد'}</h2>
        <p class="muted">سجّل دخولك ليُحفظ تقدّمك في السحابة وتكمل من أي جهاز (آيفون، أندرويد، كمبيوتر) 🌐</p>
        <form id="ac-f" class="acct-form" autocomplete="on">
          ${mode === 'signup' ? '<label>الاسم<input id="ac-name" required maxlength="24" placeholder="اسمك في اللعبة"></label>' : ''}
          <label>البريد الإلكتروني<input id="ac-mail" type="email" required dir="ltr" autocomplete="email" placeholder="you@example.com"></label>
          <label>كلمة المرور<input id="ac-pass" type="password" required minlength="6" dir="ltr" autocomplete="${mode === 'login' ? 'current-password' : 'new-password'}"></label>
          ${msg ? `<p class="acct-err">${msg}</p>` : ''}
          <button class="btn" type="submit" id="ac-go">${mode === 'login' ? 'دخول ▶' : 'إنشاء الحساب ✨'}</button>
        </form>
        ${C.inApp ? '' : '<div class="acct-or">أو</div><button class="btn ghost" id="ac-github" style="display:flex;align-items:center;justify-content:center;gap:8px;width:100%"><svg width="18" height="18" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true"><path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z"/></svg> الدخول بحساب GitHub</button>'}
        <div class="row center mt" style="gap:14px">
          <a href="#" id="ac-sw">${mode === 'login' ? 'ما عندك حساب؟ أنشئ حساباً' : 'عندك حساب؟ سجّل الدخول'}</a>
          ${mode === 'login' ? '<a href="#" id="ac-reset">نسيت كلمة المرور؟</a>' : ''}
        </div>
        ${allowGuest ? '<div class="row center mt"><button class="btn ghost small" id="ac-guest">العب كضيف (حفظ على هذا الجهاز فقط)</button></div>' : '<div class="row center mt"><button class="btn ghost small" id="ac-x">إغلاق</button></div>'}`);
      $('#ac-f').onsubmit = async ev => {
        ev.preventDefault(); const mail = $('#ac-mail').value.trim(), pass = $('#ac-pass').value; $('#ac-go').disabled = true;
        try {
          if (mode === 'login') await C.auth.signInWithEmailAndPassword(mail, pass);
          else { const r = await C.auth.createUserWithEmailAndPassword(mail, pass); await r.user.updateProfile({ displayName: $('#ac-name').value.trim() }); C.user = C.auth.currentUser; emit(); }
          C.close(); toastMsg(`👋 أهلاً ${esc(C.displayName())}!`); onDone && onDone();
        } catch (e) { draw(errAr(e)); }
      };
      $('#ac-sw').onclick = e => { e.preventDefault(); mode = mode === 'login' ? 'signup' : 'login'; draw(); };
      const rs = $('#ac-reset'); if (rs) rs.onclick = async e => { e.preventDefault(); const mail = $('#ac-mail').value.trim(); if (!mail) return draw('اكتب بريدك أولاً ثم اضغط «نسيت كلمة المرور»'); try { await C.auth.sendPasswordResetEmail(mail); draw('📧 أرسلنا رابط إعادة التعيين إلى بريدك'); } catch (er) { draw(errAr(er)); } };
      const gh = $('#ac-github'); if (gh) gh.onclick = async () => {
        gh.disabled = true;
        try {
          const r = await C.auth.signInWithPopup(new firebase.auth.GithubAuthProvider());
          const u = r.user, nick = r.additionalUserInfo && r.additionalUserInfo.username;
          if (u && !u.displayName && nick) { await u.updateProfile({ displayName: nick }); C.user = C.auth.currentUser; emit(); }
          C.close(); toastMsg(`👋 أهلاً ${esc(C.displayName())}!`); onDone && onDone();
        } catch (e) { draw(errAr(e)); }
      };
      const gu = $('#ac-guest'); if (gu) gu.onclick = () => { C.guest = true; try { localStorage.setItem('hod-guest', '1'); } catch (e) { } C.close(); onDone && onDone(); };
      const x = $('#ac-x'); if (x) x.onclick = C.close;
    };
    draw();
  };
  C.signOut = async () => { if (C.enabled && C.user) { await C.auth.signOut(); toastMsg('👋 تم تسجيل الخروج'); } };
  C.isGuest = () => { if (C.guest) return true; try { return localStorage.getItem('hod-guest') === '1'; } catch (e) { return false; } };
  C.openLeaderboard = async () => {
    if (!C.enabled) return;
    overlay('<h2>🏆 لوحة الشرف</h2><p class="muted">⏳ جاري التحميل…</p>');
    try {
      const all = await C.top(50), me = C.user ? all.findIndex(r => r.uid === C.user.uid) : -1, rows = all.slice(0, 20);
      const mine = window.hodStats && window.__game ? window.hodStats(window.__game.save) : null;
      const row = (r, i) => `<tr class="${C.user && r.uid === C.user.uid ? 'me' : ''}"><td>${['🥇', '🥈', '🥉'][i] || i + 1}</td><td>${esc(r.name || 'لاعب')}</td><td dir="ltr"><b>${r.points || 0}</b></td><td>${r.doors || 0}/8</td><td>${r.quizzes || 0}/8</td><td>${r.mid ? '🎓' : '—'}</td></tr>`;
      overlay(`<h2>🏆 لوحة الشرف</h2>
        <p class="muted" style="margin:0 0 6px">النقاط 🏅 = نجوم المحاضرات ×100 + أفضل علامة كويز ×10 + الامتحان النصفي (500 + نجومه ×250)</p>
        ${C.user ? `<p class="lb-me">👤 <b>${esc(C.displayName())}</b> · 🏅 <b dir="ltr">${mine ? mine.points : '—'}</b> · الترتيب: <b>${me >= 0 ? '#' + (me + 1) : 'خارج أفضل 50'}</b></p>` : '<p class="muted">🔐 سجّل الدخول لتظهر في لوحة الشرف</p>'}
        <div style="max-height:46vh;overflow:auto"><table class="lb"><tr><th>#</th><th>اللاعب</th><th>🏅 نقاط</th><th>🚪</th><th>📝</th><th>🎓</th></tr>${rows.length ? rows.map(row).join('') : '<tr><td colspan="6">لا يوجد لاعبون بعد — كن الأول! 🌟</td></tr>'}</table></div>
        <div class="row center mt"><button class="btn" id="lb-x">إغلاق</button></div>`);
    } catch (e) { overlay(`<h2>🏆 لوحة الشرف</h2><p class="acct-err">${esc(errAr(e))}</p><div class="row center mt"><button class="btn" id="lb-x">إغلاق</button></div>`); }
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
        overlay(`<h2>🆕 تحديث جديد متوفّر!</h2><p>الإصدار <b dir="ltr">${esc(v.version)}</b> (عندك <span dir="ltr">${esc(mine)}</span>)</p>${v.notes ? `<ul class="notes">${[].concat(v.notes).map(n => `<li>${esc(n)}</li>`).join('')}</ul>` : ''}
          <div class="row center mt">${link ? '<button class="btn" id="up-dl">⬇️ تنزيل التحديث</button>' : '<button class="btn" id="up-rl">🔄 حدّث الصفحة</button>'}${C.inApp && v.web ? '<button class="btn ghost" id="up-web">🌐 العب النسخة الأونلاين</button>' : ''}<button class="btn ghost" id="up-later">لاحقاً</button></div>
          ${C.platform === 'android' ? '<p class="muted mt">بعد التنزيل افتح الملف لتثبيت التحديث فوق النسخة الحالية (تقدّمك المحفوظ بحسابك لن يضيع).</p>' : ''}`);
        const dl = $('#up-dl'); if (dl) dl.onclick = () => C.openExternal(link);
        const rl = $('#up-rl'); if (rl) rl.onclick = () => location.reload();
        const wb = $('#up-web'); if (wb) wb.onclick = () => C.openExternal(v.web);
        $('#up-later').onclick = C.close;
      } else if (manual) toastMsg('✅ عندك آخر إصدار (' + esc(mine) + ')');
    } catch (e) { if (manual) toastMsg('⚠️ تعذّر التحقق من التحديثات'); }
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
