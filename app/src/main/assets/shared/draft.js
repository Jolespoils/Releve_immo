/* Relevé Immo — sauvegarde locale, sans toucher aux moteurs IA/Liciel. */
(function () {
  'use strict';
  const KEY = 'releve_immo_draft_v2';
  const ARCHIVES = 'releve_immo_archives_v1';
  const STEP2 = 'releve_immo_etape2_pieces_v1';
  const $ = id => document.getElementById(id);
  let restoring = new URLSearchParams(location.search).get('new') === '1';
  let saveTimer = null;

  function snapshot() {
    return {
      version: 2, updated: Date.now(),
      speech: $('speech').value,
      rooms: rooms.map(r => ({ ...r })),
      keys: [...keys], step1Saved: !!step1Saved
    };
  }
  function save() {
    if (restoring) return;
    try { localStorage.setItem(KEY, JSON.stringify(snapshot())); }
    catch (e) { console.warn('Sauvegarde locale impossible', e); }
  }
  function scheduleSave() {
    if (restoring) return;
    clearTimeout(saveTimer);
    saveTimer = setTimeout(save, 150);
  }
  function archiveExisting() {
    const current = localStorage.getItem(KEY);
    const step2 = localStorage.getItem(STEP2);
    if (!current && !step2) return true;
    try {
      const archives = JSON.parse(localStorage.getItem(ARCHIVES) || '[]');
      if (!Array.isArray(archives)) throw Error('Archives invalides');
      archives.push({ id: Date.now() + '-' + Math.random().toString(36).slice(2),
        savedAt: Date.now(), draft: current, step2 });
      // Ne supprimer aucun ancien relevé : si le stockage est plein, refuser le nouveau.
      localStorage.setItem(ARCHIVES, JSON.stringify(archives));
      return true;
    } catch (e) {
      alert('Impossible de sauvegarder le relevé précédent. Aucun relevé supprimé.');
      return false;
    }
  }
  function hasMeaningfulDraft(raw, step2) {
    if (step2) { try { const v=JSON.parse(step2); if (Array.isArray(v)?v.length:Object.keys(v||{}).length) return true; } catch(e) { return true; } }
    if (!raw) return false;
    try { const d=JSON.parse(raw); return !!(String(d.speech||'').trim() || (Array.isArray(d.rooms)&&d.rooms.length)); }
    catch(e) { return true; }
  }
  function askNewDraft() {
    return new Promise(resolve => {
      const overlay=document.createElement('div');
      overlay.style.cssText='position:fixed;inset:0;z-index:9999;background:#061b30b8;display:grid;place-items:center;padding:22px';
      overlay.innerHTML='<div role="dialog" aria-modal="true" aria-labelledby="newdraft-title" style="width:100%;max-width:390px;background:#102b45;color:#fff;border:1px solid #4e7798;border-radius:22px;padding:26px 22px;box-shadow:0 24px 70px #0008;text-align:center"><div aria-hidden="true" style="font-size:36px;color:#8bc9ff;margin-bottom:12px">▢</div><h2 id="newdraft-title" style="font-size:21px;margin:0 0 14px">Créer un nouveau relevé ?</h2><p style="color:#d3e2f0;line-height:1.55;margin:0 0 24px">Le relevé actuel sera archivé et restera accessible dans « Mes relevés ».</p><div style="display:flex;gap:10px"><button id="ri-no" style="flex:1;border:0;border-radius:12px;background:#30465b;color:white;padding:14px 8px;font-weight:700">Annuler</button><button id="ri-yes" style="flex:1;border:0;border-radius:12px;background:#0875dc;color:white;padding:14px 8px;font-weight:700">Créer le relevé</button></div></div>';
      document.body.appendChild(overlay);
      const done=value=>{overlay.remove();resolve(value)};
      overlay.querySelector('#ri-no').onclick=()=>done(false);
      overlay.querySelector('#ri-yes').onclick=()=>done(true);
    });
  }
  async function restore() {
    const newRequest = new URLSearchParams(location.search).get('new') === '1';
    if (newRequest) {
      const previous = localStorage.getItem(KEY);
      const previousStep2 = localStorage.getItem(STEP2);
      if (hasMeaningfulDraft(previous, previousStep2)) {
        const ok = await askNewDraft();
        if (!ok) {
          location.replace('../accueil/index.html');
          return;
        }
        if (!archiveExisting()) {
          location.replace('../accueil/index.html');
          return;
        }
      }
      localStorage.removeItem(KEY);
      localStorage.removeItem(STEP2);
      history.replaceState(null, '', location.pathname);
      restoring = false;
      return;
    }
    try {
      const d = JSON.parse(localStorage.getItem(KEY) || 'null');
      if (!d || d.version !== 2) return;
      restoring = true;
      $('speech').value = String(d.speech || '');
      rooms = Array.isArray(d.rooms) ? d.rooms : [];
      keys = Array.isArray(d.keys) ? d.keys : [];
      render();
      if (d.step1Saved) {
        step1Saved = true;
        $('nextstep').disabled = false;
        $('exportguard').textContent = 'XML déjà enregistré — vérifie les pièces avant de poursuivre.';
      }
    } catch (e) { console.warn('Restauration impossible', e); }
    finally { restoring = false; }
  }
  restore();
  document.addEventListener('input', scheduleSave, true);
  document.addEventListener('change', scheduleSave, true);
  document.addEventListener('click', scheduleSave, true);
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') save(); });
  window.addEventListener('pagehide', save);
  const list = $('rooms');
  if (list) new MutationObserver(scheduleSave).observe(list, { childList: true, subtree: true });
  window.releveSaveDraft = save;
})();
