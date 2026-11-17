/* Relevé Immo — sauvegarde locale, sans toucher aux moteurs IA/Liciel. */
(function () {
  'use strict';
  const KEY = 'releve_immo_draft_v2';
  const ARCHIVES = 'releve_immo_archives_v1';
  const STEP2 = 'releve_immo_etape2_pieces_v1';
  const $ = id => document.getElementById(id);
  let restoring = false;
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
  function restore() {
    const newRequest = new URLSearchParams(location.search).get('new') === '1';
    if (newRequest) {
      const previous = localStorage.getItem(KEY);
      const previousStep2 = localStorage.getItem(STEP2);
      if (previous || previousStep2) {
        const ok = confirm('Créer un nouveau relevé ? Le relevé en cours sera archivé et pourra être retrouvé dans Mes relevés.');
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
