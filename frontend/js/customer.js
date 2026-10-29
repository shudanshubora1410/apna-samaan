/**
 * Apna Samaan — Shipper Dashboard (`customer.js`)
 * Non-overlapping Sidebar + Map Workspace, smart context-aware CTAs, human-readable status history, and 5s live GPS.
 */

import { api, updateSessionUser } from './api.js';
import { createFleetSvgNode } from './fleet-art.js';
import {
  applyApiFormErrors,
  buildDeliveryStepper,
  clearFormErrors,
  clearNode,
  createStatusBadge,
  el,
  formatDateTime,
  formatInr,
  humanStatusLabel,
  openReasonModal,
  renderAppSidebar,
  requireRole,
  showToast
} from './ui.js';
import {
  bindIndiaPlaceAutocomplete,
  extractCityFromAddress,
  INDIA_ROUTE_PRESETS,
  LogisticsMap
} from './map-service.js';

const session = requireRole(['CUSTOMER']);
if (!session) {
  throw new Error('Redirecting to login');
}

document.getElementById('btn-mobile-logout').addEventListener('click', async () => {
  await api.logout();
  window.location.href = 'login.html';
});

const NAV_ITEMS = [
  { key: 'book', label: 'Book Shipment', icon: 'bi-plus-square' },
  { key: 'shipments', label: 'My Shipments', icon: 'bi-box-seam' },
  { key: 'profile', label: 'Account Settings', icon: 'bi-person-gear' }
];

let activeTabKey = 'book';
let vehicles = [];
let selectedVehicleCode = '';
let latestQuote = null;
let bookingMap = null;
let trackingMap = null;
let pinTarget = 'pickup';
let activeShipmentId = null;
let trackingPollTimer = null;
let activeCheckoutOrder = null;

function switchTab(tabKey) {
  activeTabKey = tabKey;
  renderAppSidebar('customer-rail', {
    roleLabel: 'SHIPPER',
    navItems: NAV_ITEMS,
    activeKey: activeTabKey,
    onSelect: switchTab
  });

  const titles = {
    book: 'Book a Shipment',
    shipments: 'My Shipments & Live Tracking',
    profile: 'Account Settings'
  };
  document.getElementById('cust-view-title').textContent = titles[tabKey] || 'Shipper Dashboard';

  ['book', 'shipments', 'profile'].forEach((k) => {
    document.getElementById(`tab-${k}`).classList.toggle('d-none', k !== tabKey);
  });

  if (tabKey === 'book' && bookingMap) {
    bookingMap.invalidateSize();
  }
  if (tabKey === 'shipments') {
    if (!trackingMap) {
      trackingMap = new LogisticsMap('tracking-map');
    }
    trackingMap.invalidateSize();
    loadMyShipments();
  }
  if (tabKey === 'profile') {
    loadInvoiceHistory();
  }
}

renderAppSidebar('customer-rail', {
  roleLabel: 'SHIPPER',
  navItems: NAV_ITEMS,
  activeKey: activeTabKey,
  onSelect: switchTab
});

/* 1. Customer Profile (`GET /customer/me`, `PUT /customer/me`) */
function syncProfileIndicators(profile) {
  const phoneBanner = document.getElementById('google-phone-banner');
  if (phoneBanner) {
    phoneBanner.classList.toggle('d-none', Boolean(profile?.phone));
  }

  const authBadge = document.getElementById('profile-auth-badge');
  if (authBadge) {
    const isGoogle = Boolean(profile?.googleLinked || profile?.authProvider === 'GOOGLE');
    authBadge.textContent = isGoogle ? 'Google Account Linked' : 'Email & Password';
    authBadge.className = isGoogle ? 'as-badge as-badge-emerald' : 'as-badge as-badge-slate';
  }
}

document.getElementById('btn-go-add-phone')?.addEventListener('click', () => {
  switchTab('profile');
  window.setTimeout(() => {
    document.getElementById('p-phone')?.focus();
  }, 60);
});

async function loadProfile() {
  try {
    const profile = await api.get('/customer/me');
    document.getElementById('p-name').value = profile.name || '';
    document.getElementById('p-phone').value = profile.phone || '';
    document.getElementById('p-email').value = profile.email || '';

    document.getElementById('b-pickup-name').value = profile.name || '';
    if (profile.phone) {
      document.getElementById('b-pickup-phone').value = profile.phone;
    }
    syncProfileIndicators(profile);
  } catch {
    // Non-fatal
  }
}

document.getElementById('profile-form').addEventListener('submit', async (evt) => {
  evt.preventDefault();
  const form = evt.currentTarget;
  clearFormErrors(form);
  const btn = document.getElementById('btn-save-profile');
  btn.disabled = true;
  try {
    const updated = await api.put('/customer/me', {
      name: document.getElementById('p-name').value.trim(),
      phone: document.getElementById('p-phone').value.trim()
    });
    updateSessionUser({ name: updated.name, phone: updated.phone });
    if (updated.phone && !document.getElementById('b-pickup-phone').value.trim()) {
      document.getElementById('b-pickup-phone').value = updated.phone;
    }
    syncProfileIndicators(updated);
    renderAppSidebar('customer-rail', {
      roleLabel: 'SHIPPER',
      navItems: NAV_ITEMS,
      activeKey: activeTabKey,
      onSelect: switchTab
    });
    showToast('Account details saved.', 'success');
  } catch (err) {
    applyApiFormErrors(form, err);
  } finally {
    btn.disabled = false;
  }
});

/* 2. Booking Map, Presets & Vehicle Cards */
function syncBookingMap() {
  if (!bookingMap) return;
  const pLat = Number(document.getElementById('b-pickup-lat').value);
  const pLng = Number(document.getElementById('b-pickup-lng').value);
  const dLat = Number(document.getElementById('b-drop-lat').value);
  const dLng = Number(document.getElementById('b-drop-lng').value);

  document.getElementById('lbl-pickup-coords').textContent =
    document.getElementById('b-pickup-city').value || 'Pickup pinned';
  document.getElementById('lbl-drop-coords').textContent =
    document.getElementById('b-drop-city').value || 'Drop-off pinned';

  bookingMap.setRoutePoints(
    { lat: pLat, lng: pLng, label: 'Pickup Location' },
    { lat: dLat, lng: dLng, label: 'Drop-off Location' },
    true
  );
}

function applyBookingPreset(preset, activeChip = null) {
  document.getElementById('b-pickup-address').value = preset.pickup.address;
  document.getElementById('b-pickup-city').value = preset.pickup.city;
  document.getElementById('b-pickup-lat').value = String(preset.pickup.lat);
  document.getElementById('b-pickup-lng').value = String(preset.pickup.lng);

  document.getElementById('b-drop-address').value = preset.drop.address;
  document.getElementById('b-drop-city').value = preset.drop.city;
  document.getElementById('b-drop-lat').value = String(preset.drop.lat);
  document.getElementById('b-drop-lng').value = String(preset.drop.lng);

  if (!document.getElementById('b-drop-name').value) {
    document.getElementById('b-drop-name').value = 'Warehouse Desk';
  }
  if (!document.getElementById('b-drop-phone').value) {
    document.getElementById('b-drop-phone').value = '9811001122';
  }

  if (activeChip) {
    document.querySelectorAll('#cust-presets .corridor-chip').forEach((c) => c.classList.remove('active'));
    activeChip.classList.add('active');
  }

  syncBookingMap();
  refreshLiveQuote();
}

function renderVehicleCards(quote) {
  const grid = document.getElementById('vehicle-cards-grid');
  clearNode(grid);

  const eligibleMap = new Map((quote?.eligibleVehicles || []).map((ev) => [ev.vehicleTypeCode, ev]));
  const activeCode = quote?.selectedVehicleCode || selectedVehicleCode;
  const recCode = quote?.recommendedVehicleCode || '';

  vehicles.forEach((v) => {
    const eligible = eligibleMap.get(v.code);
    const fits = !quote || Boolean(eligible);
    const isSelected = activeCode === v.code;
    const isRecommended = recCode === v.code;

    const card = el(
      'div',
      {
        className: `vehicle-option-card ${isSelected ? 'selected' : ''} ${!fits ? 'opacity-50' : ''}`,
        role: 'button',
        tabindex: '0',
        onClick: () => {
          if (!fits) {
            showToast(`${v.name} max capacity is ${v.maxWeightKg} kg.`, 'info', 2200);
            return;
          }
          selectedVehicleCode = v.code;
          refreshLiveQuote();
        }
      },
      [
        createFleetSvgNode(v.code, 'vehicle-mini-art flex-shrink-0'),
        el('div', { className: 'flex-grow-1 min-w-0' }, [
          el('div', { className: 'd-flex align-items-center gap-2' }, [
            el('span', { className: 'fw-bold small', text: v.name }),
            isRecommended ? el('span', { className: 'as-badge as-badge-emerald', text: 'Best Fit' }) : null
          ]),
          el('div', {
            className: 'text-secondary tabular-nums',
            style: 'font-size:0.74rem;',
            text: `Up to ${v.maxWeightKg} kg · ${v.maxLengthCm}×${v.maxWidthCm}×${v.maxHeightCm} cm`
          })
        ]),
        el('div', { className: 'text-end flex-shrink-0' }, [
          el('div', {
            className: 'font-mono fw-bold small',
            text: eligible ? formatInr(eligible.totalFare) : formatInr(v.baseFare)
          }),
          el('div', {
            className: 'text-secondary tabular-nums',
            style: 'font-size:0.68rem;',
            text: eligible ? 'Est. total' : `+₹${v.perKmRate}/km`
          })
        ])
      ]
    );
    grid.appendChild(card);
  });
}

async function refreshLiveQuote() {
  const payload = {
    pickupLat: Number(document.getElementById('b-pickup-lat').value),
    pickupLng: Number(document.getElementById('b-pickup-lng').value),
    dropLat: Number(document.getElementById('b-drop-lat').value),
    dropLng: Number(document.getElementById('b-drop-lng').value),
    weightKg: Number(document.getElementById('b-weight').value || 1),
    lengthCm: Number(document.getElementById('b-length').value || 10),
    widthCm: Number(document.getElementById('b-width').value || 10),
    heightCm: Number(document.getElementById('b-height').value || 10),
    packageCategory: document.getElementById('b-category').value,
    packageDescription: document.getElementById('b-desc')?.value?.trim() || 'Packed commercial cargo',
    fragile: document.getElementById('b-fragile').checked,
    gstApplicable: document.getElementById('b-gst').checked
  };
  if (selectedVehicleCode) payload.vehicleTypeCode = selectedVehicleCode;

  try {
    const q = await api.post('/shipments/quote', payload, false);
    latestQuote = q;
    renderVehicleCards(q);

    document.getElementById('b-sum-dist').textContent =
      `${Number(q.distanceKm).toFixed(1)} km · ~${q.estimatedDurationMinutes} min`;
    document.getElementById('b-sum-core').textContent = formatInr(q.subtotalFare);
    document.getElementById('b-sum-extras').textContent = formatInr(q.gstAmount);
    document.getElementById('b-sum-total').textContent = formatInr(q.totalFare);

    const isGroq = q.suggestionSource === 'GROQ_AI';
    document.getElementById('b-ai-badge').textContent = isGroq ? 'Groq AI Verified' : 'Recommended Fit';
    document.getElementById('b-ai-advisor-card')?.classList.toggle('is-groq-live', isGroq);
    const aiIcon = document.getElementById('b-ai-icon');
    if (aiIcon) {
      aiIcon.className = isGroq ? 'bi bi-stars' : 'bi bi-shield-check';
    }
    const aiHeading = document.getElementById('b-ai-heading');
    if (aiHeading) {
      aiHeading.textContent = isGroq
        ? `Groq AI Vehicle Advisor · ${q.selectedVehicleName || q.recommendedVehicleCode}`
        : `Smart Capacity Advisor · ${q.selectedVehicleName || q.recommendedVehicleCode}`;
    }
    document.getElementById('b-ai-note').textContent = q.suggestionNote || '';
    document.getElementById('btn-book-submit').textContent =
      `Continue to Pay ${formatInr(q.totalFare)} →`;
  } catch (err) {
    showToast(err.message || 'Cargo exceeds vehicle dimensions.', 'error');
  }
}

async function initBookingWorkspace() {
  vehicles = await api.get('/vehicle-types', false);

  const presetsBox = document.getElementById('cust-presets');
  INDIA_ROUTE_PRESETS.forEach((p, idx) => {
    const chip = el('button', {
      type: 'button',
      className: `corridor-chip ${idx === 0 ? 'active' : ''}`,
      text: p.label.split('·')[0].trim(),
      title: p.label,
      onClick: () => applyBookingPreset(p, chip)
    });
    presetsBox.appendChild(chip);
  });

  bookingMap = new LogisticsMap('booking-map', {
    onMapPick: ({ lat, lng }) => {
      if (pinTarget === 'pickup') {
        document.getElementById('b-pickup-lat').value = String(lat);
        document.getElementById('b-pickup-lng').value = String(lng);
      } else {
        document.getElementById('b-drop-lat').value = String(lat);
        document.getElementById('b-drop-lng').value = String(lng);
      }
      syncBookingMap();
      refreshLiveQuote();
    }
  });

  const btnPinP = document.getElementById('b-pin-pickup');
  const btnPinD = document.getElementById('b-pin-drop');
  const lblPinP = document.getElementById('lbl-b-pin-pickup');
  const lblPinD = document.getElementById('lbl-b-pin-drop');
  const setBookingPinMode = (mode) => {
    pinTarget = mode;
    const isPickup = mode === 'pickup';
    btnPinP.classList.toggle('active', isPickup);
    btnPinD.classList.toggle('active', !isPickup);
    btnPinP.setAttribute('aria-pressed', String(isPickup));
    btnPinD.setAttribute('aria-pressed', String(!isPickup));
    if (lblPinP) lblPinP.textContent = isPickup ? 'Pickup · Active' : 'Pickup';
    if (lblPinD) lblPinD.textContent = !isPickup ? 'Drop-off · Active' : 'Drop-off';
  };
  btnPinP.addEventListener('click', () => setBookingPinMode('pickup'));
  btnPinD.addEventListener('click', () => setBookingPinMode('drop'));

  bindIndiaPlaceAutocomplete(document.getElementById('b-pickup-address'), (place) => {
    document.getElementById('b-pickup-lat').value = String(place.lat);
    document.getElementById('b-pickup-lng').value = String(place.lng);
    document.getElementById('b-pickup-city').value =
      place.city || extractCityFromAddress(place.displayName, 'Delhi');
    syncBookingMap();
    refreshLiveQuote();
  });

  bindIndiaPlaceAutocomplete(document.getElementById('b-drop-address'), (place) => {
    document.getElementById('b-drop-lat').value = String(place.lat);
    document.getElementById('b-drop-lng').value = String(place.lng);
    document.getElementById('b-drop-city').value =
      place.city || extractCityFromAddress(place.displayName, 'Gurugram');
    syncBookingMap();
    refreshLiveQuote();
  });

  document.getElementById('b-pickup-address').addEventListener('blur', (e) => {
    const derived = extractCityFromAddress(e.target.value, document.getElementById('b-pickup-city').value || 'Delhi');
    document.getElementById('b-pickup-city').value = derived;
    document.getElementById('lbl-pickup-coords').textContent = derived;
  });
  document.getElementById('b-drop-address').addEventListener('blur', (e) => {
    const derived = extractCityFromAddress(e.target.value, document.getElementById('b-drop-city').value || 'Gurugram');
    document.getElementById('b-drop-city').value = derived;
    document.getElementById('lbl-drop-coords').textContent = derived;
  });

  ['b-weight', 'b-length', 'b-width', 'b-height', 'b-category', 'b-desc', 'b-fragile', 'b-gst'].forEach((id) => {
    document.getElementById(id)?.addEventListener('change', () => {
      selectedVehicleCode = '';
      refreshLiveQuote();
    });
  });

  let descDebounceTimer = null;
  document.getElementById('b-desc')?.addEventListener('input', () => {
    window.clearTimeout(descDebounceTimer);
    descDebounceTimer = window.setTimeout(() => {
      selectedVehicleCode = '';
      refreshLiveQuote();
    }, 650);
  });

  document.getElementById('btn-refresh-quote').addEventListener('click', refreshLiveQuote);

  applyBookingPreset(INDIA_ROUTE_PRESETS[0]);
}

document.getElementById('booking-form').addEventListener('submit', async (evt) => {
  evt.preventDefault();
  const form = evt.currentTarget;
  clearFormErrors(form);

  const chosenCode =
    selectedVehicleCode || latestQuote?.selectedVehicleCode || latestQuote?.recommendedVehicleCode || 'CARGO_AUTO';

  const pickupAddrVal = document.getElementById('b-pickup-address').value.trim();
  const dropAddrVal = document.getElementById('b-drop-address').value.trim();
  const pickupCityVal =
    document.getElementById('b-pickup-city').value.trim() || extractCityFromAddress(pickupAddrVal, 'Delhi');
  const dropCityVal =
    document.getElementById('b-drop-city').value.trim() || extractCityFromAddress(dropAddrVal, 'Gurugram');

  const payload = {
    pickupAddress: pickupAddrVal,
    pickupCity: pickupCityVal,
    pickupLat: Number(document.getElementById('b-pickup-lat').value),
    pickupLng: Number(document.getElementById('b-pickup-lng').value),
    pickupContactName: document.getElementById('b-pickup-name').value.trim(),
    pickupContactPhone: document.getElementById('b-pickup-phone').value.trim(),
    dropAddress: dropAddrVal,
    dropCity: dropCityVal,
    dropLat: Number(document.getElementById('b-drop-lat').value),
    dropLng: Number(document.getElementById('b-drop-lng').value),
    dropContactName: document.getElementById('b-drop-name').value.trim(),
    dropContactPhone: document.getElementById('b-drop-phone').value.trim(),
    packageDescription: document.getElementById('b-desc').value.trim() || 'Packed commercial cartons',
    packageCategory: document.getElementById('b-category').value,
    weightKg: Number(document.getElementById('b-weight').value),
    lengthCm: Number(document.getElementById('b-length').value),
    widthCm: Number(document.getElementById('b-width').value),
    heightCm: Number(document.getElementById('b-height').value),
    fragile: document.getElementById('b-fragile').checked,
    gstApplicable: document.getElementById('b-gst').checked,
    vehicleTypeCode: chosenCode
  };

  const btn = document.getElementById('btn-book-submit');
  btn.disabled = true;
  btn.textContent = 'Creating Shipment…';
  try {
    const shipment = await api.post('/shipments', payload);
    showToast(`Shipment ${shipment.trackingCode} created! Complete payment to dispatch.`, 'success');
    await openCheckoutForShipment(shipment);
  } catch (err) {
    applyApiFormErrors(form, err);
  } finally {
    btn.disabled = false;
    btn.textContent = latestQuote ? `Continue to Pay ${formatInr(latestQuote.totalFare)} →` : 'Continue to Payment →';
  }
});

/* 3. Checkout Modal */
const checkoutModal = document.getElementById('checkout-modal-backdrop');

function closeCheckoutModal() {
  checkoutModal.classList.add('d-none');
  checkoutModal.classList.remove('d-flex');
}
document.getElementById('btn-close-checkout').addEventListener('click', closeCheckoutModal);

async function openCheckoutForShipment(shipment) {
  try {
    const order = await api.post('/payments/orders', { shipmentId: shipment.id });
    activeCheckoutOrder = order;

    const formattedAmt = formatInr(order.amountInr);
    document.getElementById('chk-ref').textContent = order.trackingCode || `Shipment #${shipment.id}`;
    document.getElementById('chk-order-id').textContent = order.razorpayOrderId;
    document.getElementById('chk-amount').textContent = formattedAmt;

    const btnLive = document.getElementById('btn-razorpay-live');
    const btnSim = document.getElementById('btn-razorpay-sim');
    const note = document.getElementById('chk-mode-note');

    btnLive.textContent = `Pay ${formattedAmt} →`;
    btnSim.textContent = `Pay ${formattedAmt} & Dispatch Driver →`;

    const isSimOrder =
      String(order.razorpayOrderId || '').startsWith('order_sim_') ||
      !order.razorpayKeyId ||
      String(order.razorpayKeyId).includes('placeholder');

    if (isSimOrder) {
      btnLive.classList.add('d-none');
      btnSim.classList.remove('d-none');
      note.textContent =
        'Confirm below to complete instant payment and notify the nearest verified driver in your pickup city.';
    } else {
      btnSim.classList.add('d-none');
      btnLive.classList.remove('d-none');
      note.textContent =
        'Pay securely via UPI, corporate debit/credit card, or netbanking.';
    }

    checkoutModal.classList.remove('d-none');
    checkoutModal.classList.add('d-flex');
  } catch (err) {
    showToast(err.message || 'Unable to start payment.', 'error');
  }
}

document.getElementById('btn-razorpay-sim').addEventListener('click', async (evt) => {
  if (!activeCheckoutOrder) return;
  const btn = evt.currentTarget;
  btn.disabled = true;
  btn.textContent = 'Confirming Payment…';
  try {
    await api.post('/payments/simulate-checkout', {
      shipmentId: activeCheckoutOrder.shipmentId,
      razorpayOrderId: activeCheckoutOrder.razorpayOrderId
    });
    closeCheckoutModal();
    showToast('Payment confirmed! Matching nearest verified driver.', 'success');
    activeShipmentId = activeCheckoutOrder.shipmentId;
    switchTab('shipments');
    await inspectShipment(activeShipmentId);
  } catch (err) {
    showToast(err.message || 'Payment confirmation failed.', 'error');
  } finally {
    btn.disabled = false;
  }
});

document.getElementById('btn-razorpay-live').addEventListener('click', () => {
  if (!activeCheckoutOrder || typeof window.Razorpay === 'undefined') {
    showToast('Payment checkout SDK could not be loaded.', 'error');
    return;
  }
  const rzp = new window.Razorpay({
    key: activeCheckoutOrder.razorpayKeyId,
    amount: activeCheckoutOrder.amountPaise,
    currency: activeCheckoutOrder.currency || 'INR',
    name: 'Apna Samaan',
    description: activeCheckoutOrder.trackingCode || `Shipment #${activeCheckoutOrder.shipmentId}`,
    order_id: activeCheckoutOrder.razorpayOrderId,
    prefill: {
      name: activeCheckoutOrder.customerName,
      email: activeCheckoutOrder.customerEmail,
      contact: activeCheckoutOrder.customerPhone
    },
    theme: { color: '#D9381E' },
    handler: async (response) => {
      try {
        await api.post('/payments/verify', {
          shipmentId: activeCheckoutOrder.shipmentId,
          razorpayOrderId: response.razorpay_order_id,
          razorpayPaymentId: response.razorpay_payment_id,
          razorpaySignature: response.razorpay_signature
        });
        closeCheckoutModal();
        showToast('Payment confirmed! Finding nearest verified driver.', 'success');
        activeShipmentId = activeCheckoutOrder.shipmentId;
        switchTab('shipments');
        await inspectShipment(activeShipmentId);
      } catch (err) {
        showToast(err.message || 'Payment verification failed.', 'error');
      }
    }
  });
  rzp.open();
});

/* 4. My Shipments List & 5s Live GPS Tracking */
async function loadMyShipments() {
  const listBox = document.getElementById('shipments-list');
  const countLabel = document.getElementById('shipments-count-label');
  try {
    const page = await api.get('/shipments/my?page=0&size=25');
    const items = page.items || [];
    clearNode(listBox);

    if (countLabel) {
      countLabel.textContent = items.length === 1 ? '1 Shipment' : `${items.length} Shipments`;
    }

    if (items.length === 0) {
      listBox.appendChild(
        el('div', {
          className: 'as-panel p-3 text-center text-secondary small',
          text: 'No shipments booked yet.'
        })
      );
      return;
    }

    items.forEach((s) => {
      const isSelected = activeShipmentId === s.id;
      const card = el(
        'div',
        {
          className: `vehicle-option-card ${isSelected ? 'selected' : ''}`,
          role: 'button',
          tabindex: '0',
          onClick: () => inspectShipment(s.id)
        },
        [
          createFleetSvgNode(s.vehicleTypeCode, 'vehicle-mini-art flex-shrink-0'),
          el('div', { className: 'flex-grow-1 min-w-0' }, [
            el('div', { className: 'd-flex align-items-center justify-content-between gap-2 mb-1' }, [
              el('span', {
                className: 'tracking-id',
                title: s.trackingCode || `#${s.id}`,
                text: s.trackingCode || `#${s.id}`
              }),
              createStatusBadge(s.status)
            ]),
            el('div', { className: 'd-flex align-items-center justify-content-between gap-2' }, [
              el('span', { className: 'small text-secondary text-truncate', text: `${s.pickupCity} → ${s.dropCity}` }),
              el('span', { className: 'font-mono fw-bold small flex-shrink-0', text: formatInr(s.totalFare) })
            ]),
            el('div', {
              className: 'text-secondary tabular-nums',
              style: 'font-size:0.7rem;',
              text: formatDateTime(s.createdAt)
            })
          ])
        ]
      );
      listBox.appendChild(card);
    });

    if (!activeShipmentId && items[0]) {
      inspectShipment(items[0].id);
    }
  } catch (err) {
    showToast(err.message || 'Could not load shipments.', 'error');
  }
}

document.getElementById('btn-reload-shipments').addEventListener('click', loadMyShipments);

async function inspectShipment(shipmentId) {
  const prevId = activeShipmentId;
  activeShipmentId = shipmentId;
  window.clearInterval(trackingPollTimer);

  // Highlight active row immediately
  document.querySelectorAll('#shipments-list .vehicle-option-card').forEach((card, idx) => {
    card.classList.toggle('selected', false);
  });

  document.getElementById('tracking-empty').classList.add('d-none');
  document.getElementById('tracking-panel').classList.remove('d-none');

  if (!trackingMap) {
    trackingMap = new LogisticsMap('tracking-map');
  }
  trackingMap.invalidateSize();

  await renderShipmentInspection(shipmentId, prevId !== shipmentId);
  await loadMyShipments();

  trackingPollTimer = window.setInterval(() => {
    if (activeTabKey === 'shipments' && activeShipmentId === shipmentId) {
      renderShipmentInspection(shipmentId, false);
    }
  }, 5000);
}

function humanizeMapStatus(shipmentStatus, tracking) {
  if (tracking.driverLat !== null && tracking.driverLng !== null) {
    return `Live Driver GPS · ${tracking.distanceToTargetKm ?? '—'} km away`;
  }
  switch (shipmentStatus) {
    case 'PENDING_PAYMENT':
      return 'Complete payment to dispatch driver';
    case 'SEARCHING_DRIVER':
      return 'Finding nearest verified driver…';
    case 'ASSIGNED':
    case 'PICKED_UP':
    case 'IN_TRANSIT':
      return 'Connecting to vehicle GPS…';
    case 'DELIVERED':
      return 'Delivered at destination';
    case 'CANCELLED':
      return 'Shipment cancelled';
    case 'FAILED':
      return 'No nearby driver available';
    default:
      return 'Waiting for GPS update';
  }
}

async function renderShipmentInspection(shipmentId, fitBounds = false) {
  try {
    const [shipment, tracking, history] = await Promise.all([
      api.get(`/shipments/${shipmentId}`),
      api.get(`/shipments/${shipmentId}/tracking`),
      api.get(`/shipments/${shipmentId}/history`)
    ]);

    let paymentInfo = null;
    try {
      paymentInfo = await api.get(`/payments/shipment/${shipmentId}`);
    } catch {
      paymentInfo = null;
    }

    document.getElementById('trk-ref').textContent = `${shipment.trackingCode} · ${shipment.vehicleTypeName}`;
    document.getElementById('trk-route').textContent = `${shipment.pickupCity} → ${shipment.dropCity} · ${Number(shipment.distanceKm).toFixed(1)} km`;

    const badgeHost = document.getElementById('trk-badge');
    clearNode(badgeHost);
    badgeHost.appendChild(createStatusBadge(shipment.status));

    const stepperHost = document.getElementById('trk-stepper');
    clearNode(stepperHost);
    stepperHost.appendChild(buildDeliveryStepper(shipment.status));

    const btnPay = document.getElementById('btn-trk-pay');
    const btnCancel = document.getElementById('btn-trk-cancel');
    const isPaid = paymentInfo?.status === 'CAPTURED';

    btnPay.classList.toggle('d-none', shipment.status !== 'PENDING_PAYMENT');
    btnPay.textContent = `Pay ${formatInr(shipment.totalFare)} →`;
    btnPay.onclick = () => openCheckoutForShipment(shipment);

    btnCancel.classList.toggle('d-none', !shipment.cancellableByCustomer);
    btnCancel.textContent = isPaid ? 'Cancel & Refund' : 'Cancel Shipment';
    btnCancel.onclick = async () => {
      const reason = await openReasonModal({
        eyebrow: isPaid ? '100% INSTANT REFUND PROTECTION' : 'CANCEL BOOKING',
        title: isPaid ? 'Cancel Shipment & Request Refund' : 'Cancel Unpaid Shipment',
        subtitle: isPaid
          ? 'Cancelling before cargo pickup triggers an automatic 100% refund to your original payment method.'
          : 'This shipment has not been paid yet and will be cancelled immediately at zero charge.',
        summaryRows: [
          { label: 'Tracking ID', value: shipment.trackingCode, mono: true },
          { label: 'Route', value: `${shipment.pickupCity} → ${shipment.dropCity}` },
          {
            label: isPaid ? 'Refund Amount' : 'Booking Amount',
            value: isPaid ? `${formatInr(shipment.totalFare)} (100% Refund)` : `${formatInr(shipment.totalFare)} (Unpaid)`,
            mono: true
          }
        ],
        reasons: [
          'Pickup schedule changed',
          'Found alternative transport',
          'Cargo weight or vehicle size changed',
          'Booked by mistake'
        ],
        cancelText: 'Keep Shipment',
        confirmText: isPaid
          ? `Cancel & Refund ${formatInr(shipment.totalFare)}`
          : 'Cancel Shipment',
        danger: true
      });
      if (!reason) return;
      try {
        await api.post(`/shipments/${shipment.id}/cancel`, { reason });
        showToast(
          isPaid ? `Shipment ${shipment.trackingCode} cancelled — 100% refund initiated.` : 'Shipment cancelled.',
          'success'
        );
        await loadMyShipments();
        await renderShipmentInspection(shipment.id, false);
      } catch (err) {
        showToast(err.message || 'Unable to cancel shipment.', 'error');
      }
    };

    trackingMap.setRoutePoints(
      { lat: tracking.pickupLat, lng: tracking.pickupLng, label: 'Pickup Location' },
      { lat: tracking.dropLat, lng: tracking.dropLng, label: 'Drop-off Location' },
      fitBounds
    );

    if (tracking.driverLat !== null && tracking.driverLng !== null) {
      const target =
        shipment.status === 'ASSIGNED'
          ? [tracking.pickupLat, tracking.pickupLng]
          : [tracking.dropLat, tracking.dropLng];
      trackingMap.setDriverPosition(tracking.driverLat, tracking.driverLng, target, false);
    } else {
      trackingMap.setDriverPosition(null, null);
    }

    document.getElementById('trk-gps-meta').textContent = humanizeMapStatus(shipment.status, tracking);

    document.getElementById('trk-driver').textContent = tracking.assignedDriverName
      ? `${tracking.assignedDriverName} · ${tracking.assignedDriverVehicleNumber || 'Verified'}`
      : shipment.status === 'SEARCHING_DRIVER'
        ? 'Matching nearby driver…'
        : 'Assigned after payment';

    document.getElementById('trk-payment').textContent = paymentInfo
      ? `${humanStatusLabel(paymentInfo.status)} · ${formatInr(paymentInfo.amountInr)}`
      : `Due · ${formatInr(shipment.totalFare)}`;

    const timeline = document.getElementById('trk-timeline');
    clearNode(timeline);
    (history || []).forEach((item) => {
      const friendlyStatus = humanStatusLabel(item.toStatus);
      const detail = item.note ? ` — ${item.note}` : '';
      timeline.appendChild(
        el('div', { className: 'as-timeline-item' }, [
          el('div', { className: 'fw-semibold text-dark', text: `${friendlyStatus}${detail}` }),
          el('div', {
            className: 'text-secondary tabular-nums',
            style: 'font-size:0.72rem;',
            text: formatDateTime(item.createdAt)
          })
        ])
      );
    });
  } catch (err) {
    showToast(err.message || 'Could not refresh shipment details.', 'error');
  }
}

/* 5. B2B GST Profile, Saved Pickup Hubs & Tax Invoice Downloads */
const userStorageScope = session?.user?.id || session?.user?.email || 'default';
const B2B_KEY = `apna_samaan_b2b_${userStorageScope}`;
const HUBS_KEY = `apna_samaan_hubs_${userStorageScope}`;

const DEFAULT_HUBS = [
  {
    id: 'hub-cp',
    label: 'Primary Dispatch Bay · Connaught Place',
    address: 'Block A, Connaught Place, New Delhi 110001',
    city: 'Delhi',
    lat: 28.6315,
    lng: 77.2167
  },
  {
    id: 'hub-okhla',
    label: 'Okhla Phase II Warehouse',
    address: 'Okhla Industrial Estate Phase II, New Delhi 110020',
    city: 'Delhi',
    lat: 28.5355,
    lng: 77.2732
  }
];

function getB2bSettings() {
  try {
    return JSON.parse(localStorage.getItem(B2B_KEY) || 'null') || {
      companyName: '',
      gstin: '',
      state: '07 - Delhi',
      defaultGst: true
    };
  } catch {
    return { companyName: '', gstin: '', state: '07 - Delhi', defaultGst: true };
  }
}

function getSavedHubs() {
  try {
    const raw = JSON.parse(localStorage.getItem(HUBS_KEY) || 'null');
    return Array.isArray(raw) && raw.length > 0 ? raw : DEFAULT_HUBS;
  } catch {
    return DEFAULT_HUBS;
  }
}

function renderSavedHubs() {
  const host = document.getElementById('saved-hubs-list');
  if (!host) return;
  clearNode(host);

  const hubs = getSavedHubs();
  hubs.forEach((hub) => {
    host.appendChild(
      el('div', { className: 'vehicle-option-card justify-content-between py-2' }, [
        el('div', { className: 'min-w-0' }, [
          el('div', { className: 'fw-bold small text-dark text-truncate', text: hub.label }),
          el('div', { className: 'text-secondary text-truncate', style: 'font-size:0.75rem;', text: hub.address })
        ]),
        el('div', { className: 'd-flex align-items-center gap-1 flex-shrink-0' }, [
          el('button', {
            type: 'button',
            className: 'btn btn-sm btn-as-navy py-1 px-2 text-nowrap',
            text: 'Use Pickup →',
            onClick: () => {
              document.getElementById('b-pickup-address').value = hub.address;
              document.getElementById('b-pickup-city').value = hub.city || 'Delhi';
              document.getElementById('b-pickup-lat').value = String(hub.lat);
              document.getElementById('b-pickup-lng').value = String(hub.lng);
              switchTab('book');
              syncBookingMap();
              refreshLiveQuote();
              showToast(`Loaded pickup hub: ${hub.label}`, 'success');
            }
          }),
          hubs.length > 1
            ? el('button', {
                type: 'button',
                className: 'btn btn-sm btn-as-ghost py-1 px-2 text-secondary',
                title: 'Remove saved hub',
                'aria-label': `Remove ${hub.label}`,
                onClick: () => {
                  const next = getSavedHubs().filter((h) => h.id !== hub.id);
                  localStorage.setItem(HUBS_KEY, JSON.stringify(next));
                  renderSavedHubs();
                  showToast('Removed saved pickup address.', 'info');
                }
              }, [el('i', { className: 'bi bi-trash3', 'aria-hidden': 'true' })])
            : null
        ])
      ])
    );
  });
}

function initB2bWorkspace() {
  const b2b = getB2bSettings();
  document.getElementById('b2b-company').value = b2b.companyName || '';
  document.getElementById('b2b-gstin').value = b2b.gstin || '';
  document.getElementById('b2b-state').value = b2b.state || '07 - Delhi';
  document.getElementById('b2b-default-gst').checked = Boolean(b2b.defaultGst);
  document.getElementById('b-gst').checked = Boolean(b2b.defaultGst);

  document.getElementById('b2b-tax-form').addEventListener('submit', (evt) => {
    evt.preventDefault();
    const gstin = document.getElementById('b2b-gstin').value.trim().toUpperCase();
    if (gstin && !/^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/.test(gstin)) {
      showToast('Enter a valid 15-character Indian GSTIN (e.g. 07AABCU9603R1ZM) or leave blank.', 'error');
      return;
    }
    const next = {
      companyName: document.getElementById('b2b-company').value.trim(),
      gstin,
      state: document.getElementById('b2b-state').value,
      defaultGst: document.getElementById('b2b-default-gst').checked
    };
    localStorage.setItem(B2B_KEY, JSON.stringify(next));
    document.getElementById('b-gst').checked = next.defaultGst;
    showToast('Business & GST billing profile saved.', 'success');
  });

  bindIndiaPlaceAutocomplete(document.getElementById('hub-address'), (place) => {
    document.getElementById('hub-lat').value = String(place.lat);
    document.getElementById('hub-lng').value = String(place.lng);
    document.getElementById('hub-city').value =
      place.city || extractCityFromAddress(place.displayName, 'Delhi');
  });

  document.getElementById('saved-hub-form').addEventListener('submit', (evt) => {
    evt.preventDefault();
    const label = document.getElementById('hub-label').value.trim();
    const address = document.getElementById('hub-address').value.trim();
    if (!label || !address) {
      showToast('Enter both a hub name and street address.', 'error');
      return;
    }
    const city =
      document.getElementById('hub-city').value.trim() || extractCityFromAddress(address, 'Delhi');
    const hubs = getSavedHubs();
    hubs.unshift({
      id: `hub-${Date.now()}`,
      label,
      address,
      city,
      lat: Number(document.getElementById('hub-lat').value || 28.6315),
      lng: Number(document.getElementById('hub-lng').value || 77.2167)
    });
    localStorage.setItem(HUBS_KEY, JSON.stringify(hubs.slice(0, 6)));
    document.getElementById('hub-label').value = '';
    document.getElementById('hub-address').value = '';
    renderSavedHubs();
    showToast(`Saved "${label}" to Default Pickup Addresses.`, 'success');
  });

  document.getElementById('btn-refresh-invoices').addEventListener('click', loadInvoiceHistory);
  renderSavedHubs();
}

function escapeHtml(str) {
  return String(str ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

async function downloadTaxInvoice(shipmentId) {
  try {
    const s = await api.get(`/shipments/${shipmentId}`);
    const b2b = getB2bSettings();
    const buyerName = b2b.companyName || document.getElementById('p-name').value || session?.user?.name || 'Commercial Shipper';
    const buyerGstin = b2b.gstin || 'Unregistered / B2C Consignor';
    const html = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Tax Invoice ${escapeHtml(s.trackingCode)} — Apna Samaan</title>
<style>
  body { font-family: system-ui, -apple-system, sans-serif; color: #0f172a; margin: 32px; line-height: 1.5; }
  .sheet { max-width: 760px; margin: 0 auto; border: 1px solid #cbd5e1; border-radius: 12px; padding: 28px; }
  .hdr { display: flex; justify-content: space-between; border-bottom: 2px solid #0f172a; padding-bottom: 16px; margin-bottom: 20px; }
  .mono { font-family: ui-monospace, monospace; }
  table { width: 100%; border-collapse: collapse; margin: 18px 0; }
  th, td { padding: 10px 12px; border-bottom: 1px solid #e2e8f0; text-align: left; font-size: 14px; }
  th { background: #f8fafc; font-size: 12px; text-transform: uppercase; color: #475569; }
  .num { text-align: right; font-family: ui-monospace, monospace; }
  .total { font-size: 18px; font-weight: 700; color: #d9381e; }
</style>
</head>
<body>
  <div class="sheet">
    <div class="hdr">
      <div>
        <h1 style="margin:0;font-size:22px;">Apna Samaan</h1>
        <div style="font-size:13px;color:#475569;">Commercial Goods Transport &amp; GST Tax Invoice</div>
        <div style="font-size:12px;color:#64748b;">Support: teamapnasamaan@gmail.com</div>
      </div>
      <div style="text-align:right;">
        <div class="mono" style="font-weight:700;font-size:15px;">INVOICE #INV-${escapeHtml(s.trackingCode)}</div>
        <div style="font-size:12px;color:#64748b;">Date: ${escapeHtml(formatDateTime(s.createdAt))}</div>
        <div style="font-size:12px;font-weight:600;color:#059669;">Status: ${escapeHtml(humanStatusLabel(s.status))}</div>
      </div>
    </div>
    <div style="display:flex;justify-content:space-between;gap:24px;font-size:13px;margin-bottom:16px;">
      <div>
        <strong>Billed To (Consignor):</strong><br>
        ${escapeHtml(buyerName)}<br>
        GSTIN: <span class="mono">${escapeHtml(buyerGstin)}</span><br>
        Place of Supply: ${escapeHtml(b2b.state || '07 - Delhi')}
      </div>
      <div>
        <strong>Dispatch Route (${escapeHtml(s.vehicleTypeName)} · ${escapeHtml(s.distanceKm)} km):</strong><br>
        Pickup: ${escapeHtml(s.pickupAddress)} (${escapeHtml(s.pickupCity)})<br>
        Drop-off: ${escapeHtml(s.dropAddress)} (${escapeHtml(s.dropCity)})
      </div>
    </div>
    <table>
      <thead><tr><th>Description</th><th>HSN / SAC</th><th class="num">Amount (INR)</th></tr></thead>
      <tbody>
        <tr><td>Base Commercial Dispatch Charge (${escapeHtml(s.vehicleTypeName)})</td><td class="mono">996511</td><td class="num">${escapeHtml(formatInr(s.baseFare))}</td></tr>
        <tr><td>Verified Road Distance Charge (${escapeHtml(s.distanceKm)} km)</td><td class="mono">996511</td><td class="num">${escapeHtml(formatInr(s.distanceFare))}</td></tr>
        <tr><td>GST (18% Commercial Freight Tax)</td><td class="mono">18%</td><td class="num">${escapeHtml(formatInr(s.gstAmount))}</td></tr>
        <tr><td colspan="2" style="text-align:right;font-weight:700;">Total Freight Invoice</td><td class="num total">${escapeHtml(formatInr(s.totalFare))}</td></tr>
      </tbody>
    </table>
    <div style="font-size:12px;color:#64748b;margin-top:20px;">
      Computer-generated freight tax receipt · © 2026 Apna Samaan. Built by Shudanshu Bora
    </div>
  </div>
</body>
</html>`;

    const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `ApnaSamaan-TaxInvoice-${s.trackingCode}.html`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1500);
    showToast(`Downloaded Tax Invoice for ${s.trackingCode}.`, 'success');
  } catch (err) {
    showToast(err.message || 'Unable to generate invoice.', 'error');
  }
}

async function loadInvoiceHistory() {
  const host = document.getElementById('invoice-history-list');
  if (!host) return;
  try {
    const page = await api.get('/shipments/my?page=0&size=20');
    const eligible = (page.items || []).filter(
      (s) => !['PENDING_PAYMENT', 'CANCELLED', 'FAILED'].includes(s.status)
    );
    clearNode(host);

    if (eligible.length === 0) {
      host.appendChild(
        el('div', {
          className: 'p-3 rounded-2 text-center text-secondary small',
          style: 'background:var(--paper);border:1px dashed var(--hairline-strong);',
          text: 'Paid and delivered shipments will appear here with 1-click GST invoice downloads.'
        })
      );
      return;
    }

    eligible.forEach((s) => {
      host.appendChild(
        el('div', { className: 'vehicle-option-card justify-content-between py-2' }, [
          el('div', { className: 'min-w-0' }, [
            el('div', { className: 'd-flex align-items-center gap-2' }, [
              el('span', { className: 'tracking-id', text: s.trackingCode }),
              createStatusBadge(s.status)
            ]),
            el('div', {
              className: 'text-secondary text-truncate',
              style: 'font-size:0.74rem;',
              text: `${s.pickupCity} → ${s.dropCity} · ${formatDateTime(s.createdAt)}`
            })
          ]),
          el('div', { className: 'd-flex align-items-center gap-2 flex-shrink-0' }, [
            el('span', { className: 'waybill-price-pill', text: formatInr(s.totalFare) }),
            el('button', {
              type: 'button',
              className: 'btn btn-sm btn-as-ghost py-1 px-2 text-nowrap',
              onClick: () => downloadTaxInvoice(s.id)
            }, [
              el('i', { className: 'bi bi-download me-1', 'aria-hidden': 'true' }),
              el('span', { text: 'Invoice' })
            ])
          ])
        ])
      );
    });
  } catch {
    // Non-fatal
  }
}

initB2bWorkspace();
await loadProfile();
await initBookingWorkspace();
