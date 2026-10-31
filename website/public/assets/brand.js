// Size the square icon from the complete wordmark/subtitle block on every page.
for (const brand of document.querySelectorAll('.wordmark')) {
  const icon = brand.querySelector('.brand-icon');
  const text = brand.querySelector(':scope > span');
  if (!icon || !text) continue;
  let lastSize = 0;
  const syncSize = () => {
    const size = text.getBoundingClientRect().height;
    if (size <= 0 || Math.abs(size - lastSize) < 0.1) return;
    lastSize = size;
    icon.style.width = `${size}px`;
    icon.style.height = `${size}px`;
  };
  if ('ResizeObserver' in window) new ResizeObserver(syncSize).observe(text);
  text.querySelector('.brand-name')?.addEventListener('load', syncSize);
  syncSize();
}
