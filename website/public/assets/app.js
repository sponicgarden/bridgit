const track = document.querySelector('#use-case-track');
if (track) {
  const previous = document.querySelector('[data-carousel-prev]');
  const next = document.querySelector('[data-carousel-next]');
  const status = document.querySelector('#carousel-status');
  const slides = [...track.querySelectorAll('.use-slide')];
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
  let frame = 0;
  function updateCarousel() {
    const step = slides.length > 1 ? slides[1].offsetLeft - slides[0].offsetLeft : track.clientWidth;
    const index = Math.min(slides.length - 1, Math.max(0, Math.round(track.scrollLeft / step)));
    previous.disabled = track.scrollLeft <= 2;
    next.disabled = track.scrollLeft >= track.scrollWidth - track.clientWidth - 2;
    status.textContent = `${index + 1} of ${slides.length}`;
    frame = 0;
  }
  function move(direction) {
    const step = slides.length > 1 ? slides[1].offsetLeft - slides[0].offsetLeft : track.clientWidth;
    track.scrollBy({left:direction * step,behavior:reducedMotion.matches ? 'instant' : 'smooth'});
  }
  previous.addEventListener('click',()=>move(-1));
  next.addEventListener('click',()=>move(1));
  track.addEventListener('keydown',event=>{
    if (!['ArrowRight','ArrowLeft','Home','End'].includes(event.key)) return;
    event.preventDefault(); if (event.key === 'Home' || event.key === 'End') {
      track.scrollTo({left:event.key === 'Home' ? 0 : track.scrollWidth,behavior:reducedMotion.matches ? 'instant' : 'smooth'});
    } else move(event.key === 'ArrowRight' ? 1 : -1);
  });
  track.addEventListener('scroll',()=>{if (!frame) frame=requestAnimationFrame(updateCarousel);},{passive:true});
  new ResizeObserver(updateCarousel).observe(track);
  updateCarousel();
}

const featureCards = [...document.querySelectorAll('[data-feature]')];
if (featureCards.length) {
  const tools = document.querySelector('[data-feature-tools]');
  const search = document.querySelector('[data-feature-search]');
  const count = document.querySelector('[data-feature-count]');
  let availability = 'all';
  function filterFeatures() {
    const terms = search.value.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean);
    let visible = 0;
    for (const card of featureCards) {
      const text = (card.dataset.search || card.textContent).toLocaleLowerCase();
      card.hidden = !(availability === 'all' || card.dataset.stage === availability) || !terms.every(term => text.includes(term));
      if (!card.hidden) visible++;
    }
    count.textContent = `${visible} of ${featureCards.length} features and controls`;
    document.querySelector('[data-feature-empty]').hidden = visible !== 0;
  }
  tools.hidden = false;
  search.addEventListener('input', filterFeatures);
  document.querySelectorAll('[data-feature-filter]').forEach(button => button.addEventListener('click', () => {
    availability = button.dataset.featureFilter;
    document.querySelectorAll('[data-feature-filter]').forEach(b => b.setAttribute('aria-pressed', String(b === button)));
    filterFeatures();
  }));
}

const copyAgentPrompt = document.querySelector('#copy-agent-prompt');
if (copyAgentPrompt) {
  const prompt = document.querySelector('#agent-intro-prompt');
  const status = document.querySelector('#agent-copy-status');
  let notificationTimer;
  copyAgentPrompt.addEventListener('click', async () => {
    document.querySelector('#agent-prompt-details').open = true;
    clearTimeout(notificationTimer);
    status.textContent = '';
    try {
      await navigator.clipboard.writeText(prompt.value);
      status.textContent = '✓ Prompt copied';
      notificationTimer = setTimeout(() => { status.textContent = ''; }, 3500);
    } catch {
      document.querySelector('#agent-prompt-details').open = true;
      prompt.focus();
      prompt.select();
      status.textContent = 'Select and copy the prompt below, then paste it into your agent.';
    }
  });
}

const remoteCommands = {
  usage: {
    request: '“How many tokens has this session used?”',
    response: 'This example session has used 24,800 input tokens and 6,200 output tokens. I’ll show updated usage when you ask again.'
  },
  progress: {
    request: '“Where are you up to, and what’s next?”',
    response: 'The settings screen is updated. Next I’ll check the mobile layout and run the tests. There are no blockers in this example.'
  },
  tests: {
    request: '“Run the relevant tests and tell me what fails.”',
    response: 'Example result: 18 tests passed, 1 failed. The failing test expects the old button label. I can show you the failure details next.'
  },
  summary: {
    request: '“Summarize what you changed.”',
    response: 'I updated the settings labels and added a connection status display. Three files changed. Nothing has been committed or deployed in this example.'
  }
};
for (const button of document.querySelectorAll('[data-remote-command]')) {
  button.addEventListener('click', () => {
    const command = remoteCommands[button.dataset.remoteCommand];
    for (const control of document.querySelectorAll('[data-remote-command]')) {
      control.setAttribute('aria-pressed', String(control === button));
    }
    document.querySelector('#remote-request').textContent = command.request;
    document.querySelector('#remote-response').textContent = command.response;
  });
}
