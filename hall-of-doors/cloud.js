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
        doors: Object.keys(save.stars || {}).length, name: C.displayName(), updatedAt: firebase.firestore.FieldValue.serverTimestamp()
      }, { merge: true }).then(() => { C.lastSync = Date.now(); emit(); }).catch(e => console.warn('push failed', e));
    }, 1200);
  };
  C.pull = async () => {
    try {
      const d = await C.db.collection('hod_users').doc(C.user.uid).get();
      const local = window.__game && window.__game.save;
      if (d.exists && d.data().save) {
        const remote = JSON.parse(d.data().save);
        if (!local || (remote.xp || 0) >= (local.xp || 0)) { window.__game && window.__game.adoptSave(remote); toastMsg('☁️ تم تحميل تقدّمك من حسابك'); }
        else C.push(local);
      } else if (local) C.push(local);
      C.lastSync = Date.now(); emit();
    } catch (e) { console.warn('pull failed', e); }
  };
  C.top = async () => { const q = await C.db.collection('hod_users').orderBy('xp', 'desc').limit(10).get(); return q.docs.map(d => d.data()); };

  /* ---------- small UI helpers (own overlay, above the game) ---------- */
  function overlay(html) {
    let o = $('#acct'); if (!o) { o = document.createElement('div'); o.id = 'acct'; o.className = 'acct'; document.body.appendChild(o); }
    o.innerHTML = `<div class="acct-in">${html}</div>`; o.hidden = false; return o;
  }
  C.close = () => { const o = $('#acct'); if (o) o.hidden = true; };
  function toastMsg(t) { const d = document.createElement('div'); d.className = 'qtoast good'; d.style.zIndex = 90; d.innerHTML = t; document.body.appendChild(d); setTimeout(() => d.remove(), 3000); }
  const errAr = e => ({ 'auth/invalid-email': 'البريد الإلكتروني غير صحيح', 'auth/user-not-found': 'لا يوجد حساب بهذا البريد', 'auth/wrong-password': 'كلمة المرور غير صحيحة', 'auth/invalid-credential': 'البريد أو كلمة المرور غير صحيحة', 'auth/email-already-in-use': 'هذا البريد مسجّل مسبقاً — سجّل الدخول', 'auth/weak-password': 'كلمة المرور قصيرة (6 أحرف على الأقل)', 'auth/network-request-failed': 'لا يوجد اتصال بالإنترنت', 'auth/popup-closed-by-user': 'أُغلقت نافذة تسجيل الدخول', 'auth/too-many-requests': 'محاولات كثيرة، انتظر قليلاً' }[e && e.code] || (e && e.message) || 'حدث خطأ');

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
        ${C.inApp ? '' : '<div class="acct-or">أو</div><button class="btn ghost" id="ac-google"><b style="color:#4285f4">G</b> الدخول بحساب Google</button>'}
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
      const gg = $('#ac-google'); if (gg) gg.onclick = async () => { try { await C.auth.signInWithPopup(new firebase.auth.GoogleAuthProvider()); C.close(); toastMsg(`👋 أهلاً ${esc(C.displayName())}!`); onDone && onDone(); } catch (e) { draw(errAr(e)); } };
      const gu = $('#ac-guest'); if (gu) gu.onclick = () => { C.guest = true; try { localStorage.setItem('hod-guest', '1'); } catch (e) { } C.close(); onDone && onDone(); };
      const x = $('#ac-x'); if (x) x.onclick = C.close;
    };
    draw();
  };
  C.signOut = async () => { if (C.enabled && C.user) { await C.auth.signOut(); toastMsg('👋 تم تسجيل الخروج'); } };
  C.isGuest = () => { if (C.guest) return true; try { return localStorage.getItem('hod-guest') === '1'; } catch (e) { return false; } };
  C.openLeaderboard = async () => {
    if (!C.enabled) return;
    overlay('<h2>🏆 المتصدّرون</h2><p class="muted">⏳ جاري التحميل…</p>');
    try {
      const rows = await C.top();
      overlay(`<h2>🏆 المتصدّرون</h2><table class="lb"><tr><th>#</th><th>اللاعب</th><th>XP</th><th>🚪</th><th>⭐</th></tr>${rows.map((r, i) => `<tr class="${C.user && r.name === C.displayName() ? 'me' : ''}"><td>${['🥇', '🥈', '🥉'][i] || i + 1}</td><td>${esc(r.name || 'لاعب')}</td><td dir="ltr">${r.xp || 0}</td><td>${r.doors || 0}/8</td><td>${r.stars || 0}</td></tr>`).join('')}</table><div class="row center mt"><button class="btn" id="lb-x">إغلاق</button></div>`);
    } catch (e) { overlay(`<h2>🏆 المتصدّرون</h2><p class="acct-err">${esc(errAr(e))}</p><div class="row center mt"><button class="btn" id="lb-x">إغلاق</button></div>`); }
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
