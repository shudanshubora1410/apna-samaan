/**
 * Apna Samaan — Auth Page Controllers (`auth-pages.js`)
 */

import { API_BASE_URL, api, getAuthSession, saveAuthSession } from './api.js';
import { createFleetPhotoNode } from './fleet-art.js';
import {
  applyApiFormErrors,
  clearFormErrors,
  clearNode,
  el,
  enhanceFileDropzone,
  portalPathForRole,
  renderFooter,
  renderNavbar,
  showToast
} from './ui.js';

const page = document.body.dataset.page || '';
renderNavbar(page);
renderFooter();

const OAUTH_ERROR_MESSAGES = {
  access_denied: 'Google sign-in was cancelled before completion.',
  role_not_customer:
    'Google Sign-In is available only for Shipper (Customer) accounts. Driver Partners and Operations Admins must sign in with email and password.',
  account_disabled: 'Your account has been deactivated. Contact teamapnasamaan@gmail.com for assistance.',
  email_unverified: 'Your Google account email must be verified before signing in to Apna Samaan.',
  state_invalid: 'Your Google sign-in session expired or failed security verification. Please try again.',
  account_conflict: 'This email address is already linked to a different Google account.',
  not_configured:
    'Google OAuth2 credentials are not configured yet. Add GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET in .env to enable live Google Sign-In.',
  provider_error: 'Unable to verify your Google account right now. Please try again or sign in with email.'
};

const initialParams = new URLSearchParams(window.location.search);
const pendingOAuthTicket = initialParams.get('oauth_ticket');
const pendingOAuthError = initialParams.get('oauth_error');
const requestedNextPage = initialParams.get('next');

if (pendingOAuthTicket || pendingOAuthError) {
  const cleanParams = new URLSearchParams(window.location.search);
  cleanParams.delete('oauth_ticket');
  cleanParams.delete('oauth_error');
  const query = cleanParams.toString();
  const cleanUrl = `${window.location.pathname}${query ? `?${query}` : ''}`;
  window.history.replaceState({}, '', cleanUrl);
}

function redirectAfterAuth(authResponse, explicitNext = null) {
  saveAuthSession(authResponse);
  const nextCandidate = explicitNext || new URLSearchParams(window.location.search).get('next');
  if (nextCandidate && /^[a-z0-9\-]+\.html$/i.test(nextCandidate)) {
    window.location.replace(nextCandidate);
    return;
  }
  window.location.replace(portalPathForRole(authResponse.user.role));
}

function showPageAuthError(message) {
  const errBox = document.querySelector('[data-form-error]');
  if (errBox) {
    errBox.textContent = message;
    errBox.classList.remove('d-none');
  } else {
    showToast(message, 'danger');
  }
}

if (pendingOAuthError) {
  showPageAuthError(OAUTH_ERROR_MESSAGES[pendingOAuthError] || OAUTH_ERROR_MESSAGES.provider_error);
}

if (pendingOAuthTicket) {
  (async () => {
    const googleBtn = document.querySelector('[data-google-auth-btn]');
    const googleLabel = document.querySelector('[data-google-auth-label]');
    if (googleBtn) googleBtn.disabled = true;
    if (googleLabel) googleLabel.textContent = 'Completing Google sign-in...';
    try {
      const res = await api.post('/auth/google/exchange', { ticket: pendingOAuthTicket }, false);
      redirectAfterAuth(res, requestedNextPage);
    } catch (err) {
      showPageAuthError(err?.message || 'Google sign-in ticket expired. Please try again.');
      if (googleBtn) googleBtn.disabled = false;
      if (googleLabel) googleLabel.textContent = 'Continue with Google';
    }
  })();
} else if (['login', 'register-customer', 'register-driver'].includes(page)) {
  const existing = getAuthSession();
  if (existing?.accessToken && existing?.user?.role) {
    window.location.replace(portalPathForRole(existing.user.role));
  }
}

/* 0. Customer Google OAuth2 Button (`login.html` & `register-customer.html`) */
const googleAuthBtn = document.querySelector('[data-google-auth-btn]');
if (googleAuthBtn) {
  let googleEnabled = false;
  const hintNode = document.querySelector('[data-google-auth-hint]');

  (async () => {
    try {
      const status = await api.get('/auth/google/status', false);
      googleEnabled = Boolean(status?.enabled);
      if (!googleEnabled && hintNode) {
        hintNode.textContent =
          'Local setup note: set GOOGLE_CLIENT_ID & GOOGLE_CLIENT_SECRET in .env to enable live Google Sign-In.';
        hintNode.classList.remove('d-none');
      }
    } catch {
      googleEnabled = false;
    }
  })();

  googleAuthBtn.addEventListener('click', () => {
    if (!googleEnabled) {
      showPageAuthError(OAUTH_ERROR_MESSAGES.not_configured);
      showToast('Configure GOOGLE_CLIENT_ID & GOOGLE_CLIENT_SECRET in .env first.', 'warning');
      return;
    }
    googleAuthBtn.disabled = true;
    const label = googleAuthBtn.querySelector('[data-google-auth-label]');
    if (label) {
      label.textContent = 'Redirecting to Google...';
    }
    const target = new URL(`${API_BASE_URL}/auth/google/authorize`, window.location.origin);
    if (requestedNextPage && /^[a-z0-9\-]+\.html$/i.test(requestedNextPage)) {
      target.searchParams.set('next', requestedNextPage);
    }
    window.location.assign(target.toString());
  });
}

/* 1. Login */
const loginForm = document.getElementById('login-form');
if (loginForm) {
  const forgotLink = document.getElementById('link-forgot-password');
  if (forgotLink) {
    forgotLink.addEventListener('click', (evt) => {
      const typedEmail = loginForm.email?.value?.trim();
      if (typedEmail) {
        evt.preventDefault();
        window.location.href = `forgot-password.html?email=${encodeURIComponent(typedEmail)}`;
      }
    });
  }

  loginForm.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    clearFormErrors(loginForm);
    const btn = document.getElementById('btn-login');
    btn.disabled = true;
    btn.textContent = 'Signing in...';
    try {
      const res = await api.post(
        '/auth/login',
        {
          email: loginForm.email.value.trim(),
          password: loginForm.password.value
        },
        false
      );
      redirectAfterAuth(res);
    } catch (err) {
      applyApiFormErrors(loginForm, err);
    } finally {
      btn.disabled = false;
      btn.textContent = 'Sign In to Workspace →';
    }
  });
}

/* 2. Customer Registration */
const regCustomerForm = document.getElementById('register-customer-form');
if (regCustomerForm) {
  regCustomerForm.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    clearFormErrors(regCustomerForm);
    const btn = document.getElementById('btn-reg-customer');
    btn.disabled = true;
    btn.textContent = 'Creating account...';
    try {
      const payload = {
        name: regCustomerForm.name.value.trim(),
        email: regCustomerForm.email.value.trim(),
        phone: regCustomerForm.phone.value.trim(),
        password: regCustomerForm.password.value
      };
      const res = await api.post('/auth/register/customer', payload, false);
      redirectAfterAuth(res);
    } catch (err) {
      applyApiFormErrors(regCustomerForm, err);
    } finally {
      btn.disabled = false;
      btn.textContent = 'Create Shipper Account & Enter Workbench →';
    }
  });
}

/* 3. Driver Registration with Live Fleet Silhouette Preview */
const regDriverForm = document.getElementById('register-driver-form');
if (regDriverForm) {
  let vehicles = [];
  const select = document.getElementById('d-vehicle-type');
  const previewArt = document.getElementById('d-preview-art');
  const previewTitle = document.getElementById('d-preview-title');
  const previewSpec = document.getElementById('d-preview-spec');

  function updateDriverSilhouette(code) {
    const found = vehicles.find((v) => v.code === code) || vehicles[0];
    const effectiveCode = found?.code || code || 'CARGO_AUTO';
    if (previewArt) {
      clearNode(previewArt);
      const photoNode = createFleetPhotoNode(effectiveCode, found?.name || 'Commercial vehicle');
      photoNode.style.height = '118px';
      previewArt.appendChild(photoNode);
    }
    if (found && previewTitle && previewSpec) {
      previewTitle.textContent = found.name;
      previewSpec.textContent = `Max ${found.maxWeightKg} kg · ${found.maxLengthCm}×${found.maxWidthCm}×${found.maxHeightCm} cm`;
    }
  }

  (async () => {
    try {
      vehicles = await api.get('/vehicle-types', false);
      select.textContent = '';
      vehicles.forEach((v) => {
        select.appendChild(el('option', { value: v.code, text: `${v.name} (up to ${v.maxWeightKg} kg)` }));
      });
      if (vehicles.length > 0) {
        select.value = 'CARGO_AUTO';
        if (!select.value) select.value = vehicles[0].code;
        updateDriverSilhouette(select.value);
      }
    } catch {
      select.textContent = '';
      ['BIKE', 'CARGO_AUTO', 'MINI_TRUCK', 'TRUCK'].forEach((code) => {
        select.appendChild(el('option', { value: code, text: code }));
      });
      updateDriverSilhouette('CARGO_AUTO');
    }
  })();

  select.addEventListener('change', () => updateDriverSilhouette(select.value));

  enhanceFileDropzone(document.getElementById('d-dl-file'), {
    title: 'Drop Driving Licence scan or click to browse',
    hint: 'JPEG, PNG, or PDF · Max 5 MB'
  });
  enhanceFileDropzone(document.getElementById('d-rc-file'), {
    title: 'Drop Vehicle RC scan or click to browse',
    hint: 'JPEG, PNG, or PDF · Max 5 MB'
  });

  regDriverForm.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    clearFormErrors(regDriverForm);

    const dlFile = document.getElementById('d-dl-file').files[0];
    const rcFile = document.getElementById('d-rc-file').files[0];
    if (!dlFile || !rcFile) {
      showToast('Please attach both Driving Licence and Vehicle RC files.', 'error');
      return;
    }

    const formData = new FormData();
    formData.append('name', regDriverForm.name.value.trim());
    formData.append('email', regDriverForm.email.value.trim());
    formData.append('phone', regDriverForm.phone.value.trim());
    formData.append('password', regDriverForm.password.value);
    formData.append('vehicleTypeCode', regDriverForm.vehicleTypeCode.value);
    formData.append('city', regDriverForm.city.value.trim());
    formData.append('vehicleNumber', regDriverForm.vehicleNumber.value.trim().toUpperCase());
    formData.append('drivingLicence', dlFile);
    formData.append('vehicleRc', rcFile);

    const btn = document.getElementById('btn-reg-driver');
    btn.disabled = true;
    btn.textContent = 'Uploading KYC & Creating Terminal...';
    try {
      const res = await api.postForm('/auth/register/driver', formData, false);
      redirectAfterAuth(res.auth || res);
    } catch (err) {
      applyApiFormErrors(regDriverForm, err);
    } finally {
      btn.disabled = false;
      btn.textContent = 'Submit KYC Application & Open Driver Terminal →';
    }
  });
}

/* 4. Forgot Password */
const forgotForm = document.getElementById('forgot-form');
if (forgotForm) {
  const prefillEmail = new URLSearchParams(window.location.search).get('email');
  if (prefillEmail && forgotForm.email) {
    forgotForm.email.value = prefillEmail.trim();
  }

  forgotForm.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    clearFormErrors(forgotForm);
    const successBox = document.getElementById('forgot-success');
    successBox.classList.add('d-none');

    const submittedEmail = forgotForm.email.value.trim();
    if (!submittedEmail) {
      showPageAuthError('Please enter your registered email address.');
      return;
    }

    const btn = document.getElementById('btn-forgot');
    btn.disabled = true;
    btn.textContent = 'Sending Reset Link...';
    try {
      const res = await api.post('/auth/forgot-password', { email: submittedEmail }, false);
      successBox.textContent =
        res?.message || 'If an account exists for that email, a 15-minute password reset link has been sent.';
      successBox.classList.remove('d-none');
      showToast('Check your inbox (and Spam/Promotions tab) for the reset link.', 'success');
      forgotForm.reset();
    } catch (err) {
      applyApiFormErrors(forgotForm, err);
    } finally {
      btn.disabled = false;
      btn.textContent = 'Send 15-Minute Reset Link →';
    }
  });
}

/* 5. Reset Password */
const resetForm = document.getElementById('reset-form');
if (resetForm) {
  const params = new URLSearchParams(window.location.search);
  const urlToken = params.get('token');
  if (urlToken) {
    document.getElementById('rp-token').value = urlToken.trim();
    document.getElementById('rp-token-group')?.classList.add('d-none');
    document.getElementById('rp-link-verified')?.classList.remove('d-none');

    // Remove single-use reset token from browser URL bar & history immediately
    params.delete('token');
    const remaining = params.toString();
    window.history.replaceState({}, '', `${window.location.pathname}${remaining ? `?${remaining}` : ''}`);
  }

  document.querySelectorAll('[data-toggle-pw]').forEach((toggleBtn) => {
    toggleBtn.addEventListener('click', () => {
      const input = document.getElementById(toggleBtn.dataset.togglePw);
      if (!input) return;
      const makeVisible = input.type === 'password';
      input.type = makeVisible ? 'text' : 'password';
      const icon = toggleBtn.querySelector('i');
      if (icon) {
        icon.className = makeVisible ? 'bi bi-eye-slash' : 'bi bi-eye';
      }
    });
  });

  resetForm.addEventListener('submit', async (evt) => {
    evt.preventDefault();
    clearFormErrors(resetForm);

    const tokenVal = resetForm.token.value.trim();
    const newPassword = resetForm.newPassword.value;
    const confirmInput = document.getElementById('rp-confirm-password');
    const confirmPassword = confirmInput ? confirmInput.value : newPassword;

    if (!tokenVal) {
      showPageAuthError('Reset token is missing. Please open the link from your email again.');
      document.getElementById('rp-token-group')?.classList.remove('d-none');
      return;
    }
    if (newPassword.length < 8) {
      showPageAuthError('New password must be at least 8 characters long.');
      return;
    }
    if (newPassword !== confirmPassword) {
      showPageAuthError('New password and confirmation password do not match.');
      return;
    }

    const btn = document.getElementById('btn-reset');
    btn.disabled = true;
    btn.textContent = 'Updating Password...';
    try {
      const res = await api.post(
        '/auth/reset-password',
        {
          token: tokenVal,
          newPassword
        },
        false
      );
      const box = document.getElementById('reset-success');
      box.textContent = res?.message || 'Password updated! Redirecting to Sign In...';
      box.classList.remove('d-none');
      showToast('Password updated. Sign in with your new password.', 'success');
      resetForm.classList.add('d-none');
      window.setTimeout(() => {
        window.location.href = 'login.html';
      }, 1500);
    } catch (err) {
      applyApiFormErrors(resetForm, err);
    } finally {
      btn.disabled = false;
      btn.textContent = 'Save New Password →';
    }
  });
}
