document.addEventListener('click', async event => {
  const button = event.target.closest('[data-copy-text]');
  if (!button) return;
  const feedback = button.querySelector('.copy-feedback');
  try {
    await navigator.clipboard.writeText(button.dataset.copyText);
    feedback.textContent = '복사됨';
  } catch {
    feedback.textContent = '복사 실패';
  }
  feedback.classList.add('show');
  setTimeout(() => {
    feedback.classList.remove('show');
    feedback.textContent = '';
  }, 1200);
});
