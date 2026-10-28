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
