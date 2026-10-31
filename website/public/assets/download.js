const form = document.querySelector('#download-form');
const status = document.querySelector('#download-status');
const button = form.querySelector('button[type=submit]');
button.disabled = false;
if (new URL(location.href).searchParams.has('confirmed')) status.textContent = 'Your email is confirmed. The current APK download and install steps are below.';
form.addEventListener('submit', async event => {
 event.preventDefault();
 if (!form.reportValidity()) return;
 const turnstileToken = window.turnstile?.getResponse();
 if (!turnstileToken) { status.textContent = 'Please complete the security check, then try again.'; return; }
 button.disabled = true; status.textContent = 'Sending your download email…';
 try {
  const response = await fetch('/api/download-request', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({email:form.elements.email.value, updates:form.elements.updates.checked, website:form.elements.website.value, turnstileToken}) });
  const result = await response.json();
  status.textContent = response.ok ? result.message : (result.error || 'We could not send the email. Please try again later.');
  if (response.ok) form.reset();
 } catch { status.textContent = 'The connection was interrupted. Please try again later, or use the direct APK download below.'; }
 finally { button.disabled = false; window.turnstile?.reset(); }
});
