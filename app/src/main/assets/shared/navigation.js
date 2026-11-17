/* Relevé Immo — navigation ciblée, sans modification des moteurs métier. */
(function () {
  'use strict';
  const routes = {
    parametres: { stage: 1, target: 'geminikey' },
    geminikey: { stage: 1, target: 'geminikey' },
    importxml: { stage: 3, target: 'importxml', action: 'click' },
    xml: { stage: 2, target: 'xml' },
    csv: { stage: 2, target: 'csv' },
    json: { stage: 2, target: 'json' },
    txt: { stage: 2, target: 'txt' }
  };
  function navigate() {
    const key = decodeURIComponent(location.hash.slice(1)).toLowerCase();
    const route = routes[key];
    if (!route) return;
    let attempts = 0;
    const waitForGuided = () => {
      const tabs = document.querySelectorAll('#ri-steps button');
      if (tabs.length < 4 && attempts++ < 25) {
        setTimeout(waitForGuided, 40);
        return;
      }
      if (tabs[route.stage]) tabs[route.stage].click();
      const target = document.getElementById(route.target);
      if (!target) return;
      setTimeout(() => {
        target.scrollIntoView({ behavior: 'smooth', block: 'center' });
        if (route.action === 'click' && !target.disabled) target.click();
        else if (route.target === 'geminikey') target.focus({ preventScroll: true });
      }, 80);
    };
    waitForGuided();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', navigate, { once: true });
  else navigate();
  window.addEventListener('hashchange', navigate);
})();
