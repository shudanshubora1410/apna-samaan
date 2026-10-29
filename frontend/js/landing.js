/**
 * Apna Samaan — Landing Page Controller (`landing.js`)
 * Audience Switcher (Shippers vs Drivers), Photographic Commercial Fleet Showcase & Live Reactive Fare Calculator.
 */

import { api, getAuthSession } from './api.js';
import {
  createFleetPhotoNode,
  createFleetSvgNode,
  FLEET_CAPACITY_PCT,
  FLEET_EARNINGS,
  FLEET_TAGLINES
} from './fleet-art.js';
import {
  applyApiFormErrors,
  clearFormErrors,
  clearNode,
  el,
  formatInr,
  portalPathForRole,
  renderFloatingSupportButton,
  renderFooter,
  renderNavbar,
  showToast
} from './ui.js';
import {
  bindIndiaPlaceAutocomplete,
  extractCityFromAddress,
  INDIA_ROUTE_PRESETS,
  LogisticsMap
} from './map-service.js';

renderNavbar('home');
renderFooter();
renderFloatingSupportButton();

let mapInstance = null;
let pinMode = 'pickup';
let fleetCatalog = [];
let activeStageCode = 'CARGO_AUTO';
let audienceMode = 'shipper'; // 'shipper' | 'driver'
let liveQuoteDebounce = null;

/* Audience Switcher: For Shippers vs For Driver Partners */
function setAudienceMode(mode) {
  audienceMode = mode;
  const isDriver = mode === 'driver';

  const tabShipper = document.getElementById('aud-shipper');
  const tabDriver = document.getElementById('aud-driver');
  tabShipper.classList.toggle('active', !isDriver);
  tabShipper.setAttribute('aria-selected', String(!isDriver));
  tabDriver.classList.toggle('active', isDriver);
  tabDriver.setAttribute('aria-selected', String(isDriver));

  document.getElementById('hero-shipper-pane').classList.toggle('d-none', isDriver);
  document.getElementById('hero-driver-pane').classList.toggle('d-none', !isDriver);

  const currentVehicle = fleetCatalog.find((v) => v.code === activeStageCode) || fleetCatalog[0];
  if (currentVehicle) updateHeroFleetStage(currentVehicle);
}

document.getElementById('aud-shipper').addEventListener('click', () => setAudienceMode('shipper'));
document.getElementById('aud-driver').addEventListener('click', () => setAudienceMode('driver'));

if (
  window.location.hash === '#drivers' ||
  new URLSearchParams(window.location.search).get('audience') === 'driver'
) {
  setAudienceMode('driver');
}

function updateHeroFleetStage(vehicle) {
  if (!vehicle) return;
  activeStageCode = vehicle.code;

  document.querySelectorAll('#fleet-stage-tabs .fleet-tab-btn').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset.code === vehicle.code);
  });

  const isDriver = audienceMode === 'driver';
  const earningText = FLEET_EARNINGS[vehicle.code] || '₹30k – ₹45k/mo';

  const contextChip = document.getElementById('stage-context-chip');
  if (contextChip) {
    contextChip.textContent = isDriver ? `Avg. Partner Earnings · ${earningText}` : 'Click size to preview rates';
  }

  document.getElementById('stage-vehicle-name').textContent = vehicle.name;
  document.getElementById('stage-vehicle-desc').textContent =
    FLEET_TAGLINES[vehicle.code] || vehicle.description || 'Verified commercial goods vehicle.';

  const rateCaption = document.getElementById('stage-rate-caption');
  if (rateCaption) {
    rateCaption.textContent = isDriver ? 'Trip Payout Rate' : 'Starting at';
  }
  document.getElementById('stage-rate-pill').textContent =
    `₹${Number(vehicle.baseFare).toFixed(0)} + ₹${Number(vehicle.perKmRate).toFixed(0)}/km`;

  const meterFill = document.getElementById('stage-capacity-fill');
  if (meterFill) {
    meterFill.style.width = `${FLEET_CAPACITY_PCT[vehicle.code] || 40}%`;
  }

  document.getElementById('stage-max-weight').textContent = `${Number(vehicle.maxWeightKg).toFixed(0)} kg`;
  document.getElementById('stage-max-dims').textContent =
    `${vehicle.maxLengthCm}×${vehicle.maxWidthCm}×${vehicle.maxHeightCm} cm`;

  document.getElementById('stage-third-label').textContent = isDriver ? 'Est. Partner Income' : 'Base Fare';
  document.getElementById('stage-base-fare').textContent = isDriver ? earningText : formatInr(vehicle.baseFare);

  const stageBtn = document.getElementById('btn-stage-use-vehicle');
  if (isDriver) {
    stageBtn.textContent = `Attach Your ${vehicle.name} →`;
    stageBtn.onclick = () => {
      window.location.href = 'register-driver.html';
    };
  } else {
    stageBtn.textContent = `Check Fare with ${vehicle.name} ↓`;
    stageBtn.onclick = () => {
      const sel = document.getElementById('q-vehicle');
      sel.value = vehicle.code;
      document.getElementById('fare-estimator').scrollIntoView({ behavior: 'smooth' });
      submitQuote();
    };
  }

  const artHost = document.getElementById('fleet-stage-art');
  clearNode(artHost);
  artHost.appendChild(createFleetPhotoNode(vehicle.code, `${vehicle.name} commercial freight vehicle`));
}

async function loadVehiclesCatalog() {
  const select = document.getElementById('q-vehicle');
  const tabsHost = document.getElementById('fleet-stage-tabs');

  try {
    fleetCatalog = await api.get('/vehicle-types', false);
    clearNode(tabsHost);

    fleetCatalog.forEach((v) => {
      select.appendChild(
        el('option', {
          value: v.code,
          text: `${v.name} (up to ${Number(v.maxWeightKg).toFixed(0)} kg)`
        })
      );

      tabsHost.appendChild(
        el(
          'button',
          {
            type: 'button',
            role: 'tab',
            className: `fleet-tab-btn ${v.code === activeStageCode ? 'active' : ''}`,
            dataset: { code: v.code },
            onClick: () => updateHeroFleetStage(v),
            text: v.name
          }
        )
      );
    });

    const initial = fleetCatalog.find((v) => v.code === 'CARGO_AUTO') || fleetCatalog[0];
    if (initial) updateHeroFleetStage(initial);
  } catch {
    showToast('Unable to load vehicle rates. Please ensure the server is running.', 'error');
  }
}

function getRouteStateFromInputs() {
  return {
    pickup: {
      lat: Number(document.getElementById('q-pickup-lat').value),
      lng: Number(document.getElementById('q-pickup-lng').value),
      label: document.getElementById('q-pickup-address').value || 'Pickup'
    },
    drop: {
      lat: Number(document.getElementById('q-drop-lat').value),
      lng: Number(document.getElementById('q-drop-lng').value),
      label: document.getElementById('q-drop-address').value || 'Drop-off'
    }
  };
}

function syncMapMarkers() {
  if (!mapInstance) return;
  const { pickup, drop } = getRouteStateFromInputs();
  mapInstance.setRoutePoints(pickup, drop, true);
}

function scheduleReactiveQuote() {
  window.clearTimeout(liveQuoteDebounce);
  liveQuoteDebounce = window.setTimeout(() => {
    submitQuote();
  }, 260);
}

function applyPreset(preset, activeChipBtn = null) {
  document.getElementById('q-pickup-address').value = preset.pickup.address;
  document.getElementById('q-pickup-city').value = preset.pickup.city;
  document.getElementById('q-pickup-lat').value = String(preset.pickup.lat);
  document.getElementById('q-pickup-lng').value = String(preset.pickup.lng);

  document.getElementById('q-drop-address').value = preset.drop.address;
  document.getElementById('q-drop-city').value = preset.drop.city;
  document.getElementById('q-drop-lat').value = String(preset.drop.lat);
  document.getElementById('q-drop-lng').value = String(preset.drop.lng);

  if (activeChipBtn) {
    document.querySelectorAll('#route-presets .corridor-chip').forEach((b) => b.classList.remove('active'));
    activeChipBtn.classList.add('active');
  }

  syncMapMarkers();
  submitQuote();
}

function initPresetsAndMap() {
  const presetsBox = document.getElementById('route-presets');
  INDIA_ROUTE_PRESETS.forEach((p, idx) => {
    const chip = el('button', {
      type: 'button',
      className: `corridor-chip ${idx === 0 ? 'active' : ''}`,
      text: p.label,
      onClick: () => applyPreset(p, chip)
    });
    presetsBox.appendChild(chip);
  });

  mapInstance = new LogisticsMap('landing-map', {
    onMapPick: ({ lat, lng }) => {
      if (pinMode === 'pickup') {
        document.getElementById('q-pickup-lat').value = String(lat);
        document.getElementById('q-pickup-lng').value = String(lng);
        const addrInput = document.getElementById('q-pickup-address');
        if (!addrInput.value.trim() || addrInput.value.startsWith('Pinned Pickup')) {
          addrInput.value = `Pinned Pickup (${lat.toFixed(4)}, ${lng.toFixed(4)})`;
        }
      } else {
        document.getElementById('q-drop-lat').value = String(lat);
        document.getElementById('q-drop-lng').value = String(lng);
        const addrInput = document.getElementById('q-drop-address');
        if (!addrInput.value.trim() || addrInput.value.startsWith('Pinned Drop-off')) {
          addrInput.value = `Pinned Drop-off (${lat.toFixed(4)}, ${lng.toFixed(4)})`;
        }
      }
      syncMapMarkers();
      scheduleReactiveQuote();
    }
  });

  const btnP = document.getElementById('btn-pin-pickup');
  const btnD = document.getElementById('btn-pin-drop');
  const lblP = document.getElementById('lbl-pin-pickup');
  const lblD = document.getElementById('lbl-pin-drop');
  const hint = document.getElementById('map-pin-hint');

  const setPinMode = (mode) => {
    pinMode = mode;
    const isPickup = mode === 'pickup';
    btnP.classList.toggle('active', isPickup);
    btnD.classList.toggle('active', !isPickup);
    btnP.setAttribute('aria-pressed', String(isPickup));
    btnD.setAttribute('aria-pressed', String(!isPickup));
    if (lblP) lblP.textContent = isPickup ? 'Set Pickup Pin · Active' : 'Set Pickup Pin';
    if (lblD) lblD.textContent = !isPickup ? 'Set Drop-off Pin · Active' : 'Set Drop-off Pin';
    if (hint) {
      hint.textContent = isPickup
        ? '· Click map to place Pickup location'
        : '· Click map to place Drop-off location';
    }
  };

  btnP.addEventListener('click', () => setPinMode('pickup'));
  btnD.addEventListener('click', () => setPinMode('drop'));

  bindIndiaPlaceAutocomplete(document.getElementById('q-pickup-address'), (place) => {
    document.getElementById('q-pickup-lat').value = String(place.lat);
    document.getElementById('q-pickup-lng').value = String(place.lng);
    document.getElementById('q-pickup-city').value =
      place.city || extractCityFromAddress(place.displayName, 'Delhi');
    syncMapMarkers();
    scheduleReactiveQuote();
  });

  bindIndiaPlaceAutocomplete(document.getElementById('q-drop-address'), (place) => {
    document.getElementById('q-drop-lat').value = String(place.lat);
    document.getElementById('q-drop-lng').value = String(place.lng);
    document.getElementById('q-drop-city').value =
      place.city || extractCityFromAddress(place.displayName, 'Gurugram');
    syncMapMarkers();
    scheduleReactiveQuote();
  });

  document.getElementById('q-pickup-address').addEventListener('blur', (e) => {
    document.getElementById('q-pickup-city').value = extractCityFromAddress(e.target.value, 'Delhi');
  });
  document.getElementById('q-drop-address').addEventListener('blur', (e) => {
    document.getElementById('q-drop-city').value = extractCityFromAddress(e.target.value, 'Gurugram');
  });

  // Reactive updates whenever cargo weight, dimensions, category, vehicle, or chips change
  ['q-weight', 'q-length', 'q-width', 'q-height', 'q-category', 'q-vehicle', 'q-fragile', 'q-gst'].forEach((id) => {
    const input = document.getElementById(id);
    if (input) {
      input.addEventListener('change', scheduleReactiveQuote);
      if (input.type === 'number') {
        input.addEventListener('input', scheduleReactiveQuote);
      }
    }
  });
}

function syncLoggedInBookingCtas() {
  const session = getAuthSession();
  const bookBtn = document.getElementById('btn-book-now');
  const heroSec = document.getElementById('hero-shipper-secondary');
  const cardShipperCta = document.getElementById('card-shipper-cta');
  const cardShipperLogin = document.getElementById('card-shipper-login');
  const cardDriverCta = document.getElementById('card-driver-cta');
  const cardDriverLogin = document.getElementById('card-driver-login');

  if (!session?.user) return;

  const role = session.user.role;
  const target = portalPathForRole(role);

  if (role === 'CUSTOMER') {
    bookBtn.href = target;
    bookBtn.textContent = 'Book This Shipment →';
    if (heroSec) {
      heroSec.href = target;
      heroSec.textContent = 'Open Shipper Dashboard →';
    }
    if (cardShipperCta) {
      cardShipperCta.href = target;
      cardShipperCta.textContent = 'Open Shipper Dashboard →';
    }
    if (cardShipperLogin) cardShipperLogin.classList.add('d-none');
  } else if (role === 'DRIVER') {
    bookBtn.href = 'register-customer.html';
    bookBtn.textContent = 'Create Shipper Account to Book →';
    if (cardDriverCta) {
      cardDriverCta.href = target;
      cardDriverCta.textContent = 'Open Driver Console →';
    }
    if (cardDriverLogin) cardDriverLogin.classList.add('d-none');
  } else {
    bookBtn.href = 'register-customer.html';
    bookBtn.textContent = 'Create Shipper Account to Book →';
  }
}

function renderQuoteResult(quote) {
  document.getElementById('quote-empty').classList.add('d-none');
  document.getElementById('quote-result').classList.remove('d-none');

  document.getElementById('quote-vehicle-name').textContent = quote.selectedVehicleName || 'Cargo Auto';
  document.getElementById('quote-distance').textContent =
    `${Number(quote.distanceKm).toFixed(1)} km road route · ~${quote.estimatedDurationMinutes} min drive`;
  document.getElementById('quote-route-source').textContent = 'Verified Road Distance';

  const artBox = document.getElementById('quote-vehicle-art');
  clearNode(artBox);
  artBox.appendChild(createFleetSvgNode(quote.selectedVehicleCode, 'w-100'));

  document.getElementById('quote-base').textContent = formatInr(quote.baseFare);
  document.getElementById('quote-dist-charge').textContent = formatInr(quote.distanceFare);
  document.getElementById('quote-fragile-charge').textContent = formatInr(quote.subtotalFare);
  document.getElementById('quote-gst-charge').textContent = formatInr(quote.gstAmount);
  document.getElementById('quote-total').textContent = formatInr(quote.totalFare);

  const aiTitle = document.getElementById('quote-ai-title');
  if (aiTitle) {
    aiTitle.textContent =
      quote.suggestionSource === 'GROQ_AI'
        ? 'Groq AI Verified Fitment & Protection'
        : 'Vehicle Fitment & Protection';
  }
  document.getElementById('quote-ai-reason').textContent =
    quote.suggestionNote || 'Best-fitting commercial vehicle for your cargo weight and dimensions.';

  syncLoggedInBookingCtas();
}

async function submitQuote(evt) {
  if (evt) evt.preventDefault();
  const form = document.getElementById('quote-form');
  const submitBtn = document.getElementById('btn-calc-quote');
  const sheetCard = document.getElementById('quote-sheet-card');
  const computingPill = document.getElementById('quote-computing-pill');
  clearFormErrors(form);

  const selectedCode = document.getElementById('q-vehicle').value;
  const payload = {
    pickupLat: Number(document.getElementById('q-pickup-lat').value),
    pickupLng: Number(document.getElementById('q-pickup-lng').value),
    dropLat: Number(document.getElementById('q-drop-lat').value),
    dropLng: Number(document.getElementById('q-drop-lng').value),
    weightKg: Number(document.getElementById('q-weight').value),
    lengthCm: Number(document.getElementById('q-length').value),
    widthCm: Number(document.getElementById('q-width').value),
    heightCm: Number(document.getElementById('q-height').value),
    packageCategory: document.getElementById('q-category').value,
    fragile: document.getElementById('q-fragile').checked,
    gstApplicable: document.getElementById('q-gst').checked
  };
  if (selectedCode) {
    payload.vehicleTypeCode = selectedCode;
  }

  submitBtn.disabled = true;
  submitBtn.textContent = 'Updating Estimate…';
  sheetCard?.classList.add('is-computing');
  computingPill?.classList.remove('d-none');

  try {
    const quote = await api.post('/shipments/quote', payload, false);
    renderQuoteResult(quote);
  } catch (err) {
    applyApiFormErrors(form, err);
  } finally {
    submitBtn.disabled = false;
    submitBtn.textContent = 'Recalculate Fare Estimate';
    sheetCard?.classList.remove('is-computing');
    computingPill?.classList.add('d-none');
  }
}

document.getElementById('quote-form').addEventListener('submit', submitQuote);

syncLoggedInBookingCtas();
await loadVehiclesCatalog();
initPresetsAndMap();
applyPreset(INDIA_ROUTE_PRESETS[0]);
