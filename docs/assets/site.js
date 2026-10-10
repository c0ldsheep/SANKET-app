// site.js: the scroll moves one piece of liquid glass through the story (glass.js follows it on a spring), and each
// scene's words come on screen while its object rests. Without WebGL2, or with reduced motion, the story stays a row
// of still pictures and nothing here runs.
const root = document.documentElement;
const story = document.getElementById('story');
const stage = story.querySelector('.stage');
const canvas = story.querySelector('.stage-canvas');
const hint = story.querySelector('.scrollhint');
const tags = { warn: story.querySelector('[data-tag="warn"]'), gone: story.querySelector('[data-tag="gone"]') };
const beats = [...story.querySelectorAll('.beat')].map((el) => ({
  el, scene: +el.dataset.scene, links: [...el.querySelectorAll('a')], on: null,
}));

const reduced = matchMedia('(prefers-reduced-motion: reduce)').matches;
const webgl2 = (() => {
  try { return !!document.createElement('canvas').getContext('webgl2'); } catch { return false; }
})();

function progress() {
  const r = story.getBoundingClientRect();
  const total = r.height - innerHeight;
  return total > 0 ? Math.min(1, Math.max(0, -r.top / total)) : 0;
}

/** Whose words are on screen at story position P: a scene's, while its object rests (a little either side). */
function beatFor(P, rest, current) {
  const k = Math.min(rest.length - 1, Math.floor(P));
  const u = P - k;
  const slack = current === k ? 0.14 : 0.08;   // a little stickier once shown, so nothing flickers
  if (u < rest[k] + slack) return k;
  if (k + 1 < rest.length && u > 1 - slack) return k + 1;
  return -1;
}

/** The scroll position halfway through scene k's rest, so links to a scene land on its words. */
function scrollForScene(k, count, rest) {
  const top = story.getBoundingClientRect().top + scrollY;
  return top + (story.offsetHeight - innerHeight) * ((k + rest[k] * 0.45) / count);
}

async function startMotion() {
  root.classList.add('motion');
  let mod;
  try {
    mod = await import('./glass.js');
  } catch (e) {
    console.error('SANKET: the glass could not load', e);
    root.classList.remove('motion');
    return;
  }
  const { createGlass, SCENE_COUNT, REST } = mod;
  const q = new URLSearchParams(location.search);
  const scale = +q.get('scale') || undefined;
  const level = q.has('level') ? +q.get('level') : undefined;
  let beat = -2, visible = true;
  const glass = createGlass(canvas, {
    host: stage, scale, level,
    afterFrame: ({ P, state }) => { paint(beatFor(P, REST, beat)); labels(state); },
    onLost: () => root.classList.remove('motion'),
  });
  if (!glass) {
    root.classList.remove('motion');
    return;
  }
  glass.ready.then((ok) => { if (!ok) root.classList.remove('motion'); else canvas.classList.add('ready'); });

  function paint(k) {
    if (k === beat) return;
    beat = k;
    for (const b of beats) {
      const on = b.scene === k;
      if (on === b.on) continue;
      b.on = on;
      b.el.classList.toggle('on', on);
      // Every scene stays readable to screen readers; only the one on screen takes keyboard focus.
      for (const a of b.links) a.tabIndex = on ? 0 : -1;
    }
  }

  function update() {
    const p = progress();
    glass.setTarget(p * SCENE_COUNT);
    if (hint) hint.classList.toggle('gone', p > 0.01);
  }

  /** Puts a label above a point on the object, kept inside the screen. */
  function pin(tag, at, ax, ay) {
    if (!at) return;
    const w = tag.offsetWidth, h = tag.offsetHeight;
    const x = Math.min(Math.max(8, at.x - w * ax), innerWidth - w - 8);
    const y = Math.max(8, at.y - h * ay);
    tag.style.transform = `translate(${x.toFixed(1)}px, ${y.toFixed(1)}px)`;
  }

  // The ride's two labels appear while its columns stand, and follow them as the view leans.
  let tagsOn = false;
  function labels(state) {
    const show = !!(state && state.labels && beat === 3);
    if (show !== tagsOn) {
      tagsOn = show;
      tags.warn.classList.toggle('on', show);
      tags.gone.classList.toggle('on', show);
    }
    if (!show) return;
    pin(tags.warn, glass.project(state.labels.warn), 0.5, 1.4);
    pin(tags.gone, glass.project(state.labels.gone), 0.2, 1.6);
  }

  update();
  addEventListener('scroll', update, { passive: true });

  // Links to a scene scroll to where that scene is.
  const sceneOf = new Map(beats.map((b) => [b.el.id, b.scene]));
  document.addEventListener('click', (e) => {
    const a = e.target.closest('a[href^="#"]');
    if (!a) return;
    const k = sceneOf.get(a.getAttribute('href').slice(1));
    if (k === undefined) return;
    e.preventDefault();
    scrollTo({ top: scrollForScene(k, SCENE_COUNT, REST), behavior: 'smooth' });
    history.replaceState(null, '', a.getAttribute('href'));
  });
  const first = sceneOf.get(location.hash.slice(1));
  if (first !== undefined) scrollTo({ top: scrollForScene(first, SCENE_COUNT, REST) });

  // Draw only while the story is on screen and the tab is visible.
  const sync = () => {
    const on = visible && !document.hidden;
    if (on) glass.start();
    else glass.stop();
  };
  new IntersectionObserver((entries) => { visible = entries[0].isIntersecting; sync(); }, { rootMargin: '120px' }).observe(story);
  document.addEventListener('visibilitychange', sync);
  new ResizeObserver(() => { glass.resize(); update(); }).observe(stage);
  sync();
}

if (!reduced && webgl2) startMotion();
