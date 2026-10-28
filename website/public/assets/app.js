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
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return;
    event.preventDefault(); move(event.key === 'ArrowRight' ? 1 : -1);
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
