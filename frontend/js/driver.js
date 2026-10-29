/**
 * Apna Samaan — Driver Dispatch Terminal (`driver.js`)
 * Full-screen Sidebar + Radar Map Shell, 60s sequential offer spotlight, 10s GPS stream, and KYC Vault.
 */

import { api } from './api.js';
import { createFleetSvgNode } from './fleet-art.js';
import {
  buildDeliveryStepper,
  clearNode,
  createStatusBadge,
  el,
  enhanceFileDropzone,
  formatBytes,
  formatDateTime,
  formatInr,
  humanStatusLabel,
  renderAppSidebar,
  requireRole,
  showToast
} from './ui.js';
import { LogisticsMap } from './map-service.js';

const session = requireRole(['DRIVER']);
if (!session) {
  throw new Error('Redirecting to login');
}

document.getElementById('btn-drv-mobile-logout').addEventListener('click', async () => {
  await api.logout();
  window.location.href = 'login.html';
});

const NAV_ITEMS = [
  { key: 'radar', label: 'Dispatch Radar', icon: 'bi-broadcast-pin' },
  { key: 'history', label: 'Haul Ledger', icon: 'bi-journal-text' },
  { key: 'kyc', label: 'KYC Vault', icon: 'bi-shield-check' }
];

let activeTabKey = 'radar';
let driverProfile = null;
let driverMap = null;
let activeJob = null;
let currentOffers = [];
let previewBlobUrl = null;

function switchTab(tabKey) {
  activeTabKey = tabKey;
  renderAppSidebar('driver-rail', {
    roleLabel: 'DRIVER',
    navItems: NAV_ITEMS,
    activeKey: activeTabKey,
    onSelect: switchTab
  });

  ['radar', 'history', 'kyc'].forEach((k) => {
    document.getElementById(`dtab-${k}`).classList.toggle('d-none', k !== tabKey);
  });

  if (tabKey === 'radar' && driverMap) driverMap.invalidateSize();
}

renderAppSidebar('driver-rail', {
  roleLabel: 'DRIVER',
  navItems: NAV_ITEMS,
  activeKey: activeTabKey,
  onSelect: switchTab
});

/* 1. Driver Profile (`GET /driver/me`) & Duty Toggle (`PATCH /driver/availability`) */
async function loadDriverProfile() {
  driverProfile = await api.get('/driver/me');
  document.getElementById('drv-name').textContent = driverProfile.name;
  document.getElementById('drv-plate-chip').textContent = `${driverProfile.vehicleNumber} · ${driverProfile.city}`;
  document.getElementById('drv-meta').textContent =
    `${driverProfile.vehicleTypeName} · ${driverProfile.vehicleNumber} (${driverProfile.city})`;

  const unitArt = document.getElementById('drv-unit-art');
  clearNode(unitArt);
  unitArt.appendChild(createFleetSvgNode(driverProfile.vehicleTypeCode, 'w-100'));

  const badgeHost = document.getElementById('drv-approval-badge');
  clearNode(badgeHost);
  badgeHost.appendChild(createStatusBadge(driverProfile.approvalStatus));

  const onlineSwitch = document.getElementById('toggle-online');
  onlineSwitch.checked = Boolean(driverProfile.online);
  onlineSwitch.disabled = driverProfile.approvalStatus !== 'APPROVED';
  document.getElementById('lbl-duty-state').textContent = driverProfile.online ? 'ON DUTY · LIVE' : 'OFF DUTY';

  const banner = document.getElementById('kyc-banner');
  if (driverProfile.approvalStatus !== 'APPROVED') {
    banner.classList.remove('d-none');
    document.getElementById('kyc-banner-title').textContent =
      driverProfile.approvalStatus === 'REJECTED' ? 'KYC REVISION REQUIRED' : 'AWAITING ADMIN KYC SIGN-OFF';
    document.getElementById('kyc-banner-body').textContent =
      driverProfile.rejectionReason ||
      'An Operations Admin must inspect and approve your Driving Licence & Vehicle RC before you can switch ON DUTY.';
  } else {
    banner.classList.add('d-none');
  }

  renderDocuments(driverProfile.documents || []);
}

document.getElementById('toggle-online').addEventListener('change', async (evt) => {
  const desired = evt.currentTarget.checked;
  try {
    driverProfile = await api.patch('/driver/availability', { online: desired });
    document.getElementById('lbl-duty-state').textContent = desired ? 'ON DUTY · LIVE' : 'OFF DUTY';
    showToast(desired ? 'Terminal ON DUTY — listening for nearby waybills.' : 'Switched OFF DUTY.', 'success');
    if (desired) {
      await pushGpsLocation(true);
      await loadPendingOffers();
    }
  } catch (err) {
    evt.currentTarget.checked = !desired;
    showToast(err.message || 'Unable to change duty status.', 'error');
  }
});

/* 2. Map & 10s GPS Telemetry (`PUT /driver/location`) */
function initDriverMap() {
  driverMap = new LogisticsMap('driver-map', {
    onMapPick: ({ lat, lng }) => {
      document.getElementById('gps-lat').value = String(lat);
      document.getElementById('gps-lng').value = String(lng);
      syncDriverMap();
      pushGpsLocation(false);
    }
  });
  syncDriverMap();
}

function syncDriverMap(fit = false) {
  if (!driverMap) return;
  const lat = Number(document.getElementById('gps-lat').value);
  const lng = Number(document.getElementById('gps-lng').value);

  if (activeJob) {
    driverMap.setRoutePoints(
      { lat: activeJob.pickupLat, lng: activeJob.pickupLng, label: 'Origin Pickup' },
      { lat: activeJob.dropLat, lng: activeJob.dropLng, label: 'Destination Dropoff' },
      fit
    );
    const target =
      activeJob.status === 'ASSIGNED'
        ? [activeJob.pickupLat, activeJob.pickupLng]
        : [activeJob.dropLat, activeJob.dropLng];
    driverMap.setDriverPosition(lat, lng, target, false);
  } else {
    driverMap.setDriverPosition(lat, lng, null, fit);
  }
}

async function pushGpsLocation(silent = false) {
  const lat = Number(document.getElementById('gps-lat').value);
  const lng = Number(document.getElementById('gps-lng').value);
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;

  try {
    await api.put('/driver/location', { lat, lng });
    document.getElementById('gps-last-sent').textContent =
      `GPS HEARTBEAT SENT · ${new Date().toLocaleTimeString('en-IN')} (${lat.toFixed(4)}, ${lng.toFixed(4)})`;
    syncDriverMap(false);
    if (!silent) showToast('Broadcasted live truck coordinates.', 'success', 2200);
  } catch (err) {
    if (!silent) showToast(err.message || 'GPS broadcast failed.', 'error');
  }
}

document.getElementById('btn-send-gps').addEventListener('click', () => pushGpsLocation(false));
document.getElementById('btn-gps-delhi').addEventListener('click', () => {
  document.getElementById('gps-lat').value = '28.6315';
  document.getElementById('gps-lng').value = '77.2167';
  pushGpsLocation(false);
});
document.getElementById('btn-gps-mumbai').addEventListener('click', () => {
  document.getElementById('gps-lat').value = '19.1197';
  document.getElementById('gps-lng').value = '72.8697';
  pushGpsLocation(false);
});
document.getElementById('btn-gps-blr').addEventListener('click', () => {
  document.getElementById('gps-lat').value = '12.9784';
  document.getElementById('gps-lng').value = '77.6408';
  pushGpsLocation(false);
});
document.getElementById('btn-gps-browser').addEventListener('click', () => {
  if (!navigator.geolocation) {
    showToast('Browser Geolocation unavailable.', 'error');
    return;
  }
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      document.getElementById('gps-lat').value = pos.coords.latitude.toFixed(5);
      document.getElementById('gps-lng').value = pos.coords.longitude.toFixed(5);
      pushGpsLocation(false);
    },
    () => showToast('Location permission denied — click the map or use a city preset.', 'info')
  );
});

/* 3. 60-Second Job Offer Spotlight (`GET /driver/offers/pending`) */
async function loadPendingOffers() {
  try {
    currentOffers = await api.get('/driver/offers/pending');
    renderOffersList();
  } catch {
    // Non-fatal
  }
}

function renderOffersList() {
  const container = document.getElementById('offers-container');
  clearNode(container);

  if (!currentOffers || currentOffers.length === 0) {
    container.appendChild(
      el('div', {
        className: 'as-panel p-3 small text-secondary text-center',
        text: driverProfile?.online
          ? 'Radar clear — scanning for paid waybills in your city.'
          : 'Switch ON DUTY above to receive 60-second job spotlights.'
      })
    );
    return;
  }

  currentOffers.forEach((offer) => {
    const secsLeft = Math.max(0, Math.round((new Date(offer.expiresAt).getTime() - Date.now()) / 1000));
    const card = el('div', { className: 'offer-spotlight p-3 mb-2' }, [
      el('div', { className: 'd-flex justify-content-between align-items-center mb-2' }, [
        el('span', {
          className: 'font-mono small text-warning fw-bold',
          text: `OFFER · ${offer.trackingCode || `#${offer.shipmentId}`}`
        }),
        el('div', { className: 'countdown-ring', dataset: { expires: offer.expiresAt }, text: `${secsLeft}s` })
      ]),
      el('div', { className: 'display-font fw-bold fs-4 text-white mb-1', text: formatInr(offer.totalFare) }),
      el('div', {
        className: 'font-mono small mb-2',
        style: 'color:#a3a8b0;',
        text: `${offer.distanceToPickupKm} km to origin · ${offer.routeDistanceKm} km haul`
      }),
      el('div', { className: 'small mb-3', style: 'color:#e5e7eb;' }, [
        el('div', { className: 'text-truncate', text: `P: ${offer.pickupAddress}` }),
        el('div', { className: 'text-truncate', text: `D: ${offer.dropAddress}` })
      ]),
      el('div', { className: 'd-flex gap-2' }, [
        el('button', {
          type: 'button',
          className: 'btn btn-sm btn-as-primary flex-grow-1',
          text: 'Accept Load',
          onClick: async () => {
            try {
              const accepted = await api.post(`/driver/offers/${offer.offerId}/accept`, {});
              if (accepted && accepted.id) {
                activeJob = accepted;
                renderActiveJob();
                syncDriverMap(true);
              }
              showToast(`Shipment ${offer.trackingCode} assigned to you!`, 'success');
              await loadPendingOffers();
              await loadDriverJobs();
            } catch (err) {
              showToast(err.message || 'Offer expired.', 'error');
              await loadPendingOffers();
            }
          }
        }),
        el('button', {
          type: 'button',
          className: 'btn btn-sm btn-outline-light',
          text: 'Pass',
          onClick: async () => {
            try {
              await api.post(`/driver/offers/${offer.offerId}/decline`, {});
              showToast('Passed to next nearest driver.', 'info');
              await loadPendingOffers();
            } catch (err) {
              showToast(err.message || 'Could not decline offer.', 'error');
            }
          }
        })
      ])
    ]);
    container.appendChild(card);
  });
}

window.setInterval(() => {
  document.querySelectorAll('.countdown-ring[data-expires]').forEach((ring) => {
    const left = Math.max(0, Math.round((new Date(ring.dataset.expires).getTime() - Date.now()) / 1000));
    ring.textContent = `${left}s`;
  });
}, 1000);

/* 4. Active Job Execution (`/driver/shipments/active`) & History (`/driver/shipments`) */
async function loadDriverJobs() {
  try {
    const prevJobId = activeJob?.id || null;
    const page = await api.get('/driver/shipments?page=0&size=25');
    const jobs = page.items || [];
    renderJobHistory(jobs);

    try {
      const activeList = await api.get('/driver/shipments/active');
      activeJob = Array.isArray(activeList) ? activeList[0] || null : activeList?.id ? activeList : null;
    } catch {
      activeJob = null;
    }

    renderActiveJob();
    syncDriverMap(Boolean(activeJob && activeJob.id !== prevJobId));
  } catch {
    // Non-fatal
  }
}

function renderActiveJob() {
  const empty = document.getElementById('active-job-empty');
  const panel = document.getElementById('active-job-panel');

  if (!activeJob || !activeJob.id) {
    empty.classList.remove('d-none');
    panel.classList.add('d-none');
    return;
  }

  empty.classList.add('d-none');
  panel.classList.remove('d-none');

  document.getElementById('aj-title').textContent = `${activeJob.trackingCode} · ${formatInr(activeJob.totalFare)}`;
  const badgeHost = document.getElementById('aj-badge');
  clearNode(badgeHost);
  badgeHost.appendChild(createStatusBadge(activeJob.status));

  const stepperHost = document.getElementById('aj-stepper');
  clearNode(stepperHost);
  stepperHost.appendChild(buildDeliveryStepper(activeJob.status));

  document.getElementById('aj-pickup').textContent = `${activeJob.pickupAddress} (${activeJob.pickupCity})`;
  document.getElementById('aj-pickup-contact').textContent =
    `Sender: ${activeJob.pickupContactName} · ${activeJob.pickupContactPhone}`;

  document.getElementById('aj-drop').textContent = `${activeJob.dropAddress} (${activeJob.dropCity})`;
  document.getElementById('aj-drop-contact').textContent =
    `Receiver: ${activeJob.dropContactName} · ${activeJob.dropContactPhone}`;

  const actions = document.getElementById('aj-actions');
  clearNode(actions);

  const transitionMap = {
    ASSIGNED: {
      endpoint: `/driver/shipments/${activeJob.id}/pickup`,
      label: 'Mark Picked Up →',
      cls: 'btn-as-primary'
    },
    PICKED_UP: {
      endpoint: `/driver/shipments/${activeJob.id}/in-transit`,
      label: 'Mark In Transit →',
      cls: 'btn-as-dark'
    },
    IN_TRANSIT: {
      endpoint: `/driver/shipments/${activeJob.id}/deliver`,
      label: 'Mark Delivered ✓',
      cls: 'btn-success fw-bold'
    }
  };

  const stepAction = transitionMap[activeJob.status];
  if (stepAction) {
    actions.appendChild(
      el('button', {
        type: 'button',
        className: `btn ${stepAction.cls}`,
        text: stepAction.label,
        onClick: async (evt) => {
          const btn = evt.currentTarget;
          btn.disabled = true;
          try {
            const updated = await api.post(stepAction.endpoint, { note: 'Updated from Driver Console' });
            showToast(`Shipment ${activeJob.trackingCode} marked ${humanStatusLabel(updated.status)}!`, 'success');
            await loadDriverJobs();
            await loadDriverProfile();
          } catch (err) {
            btn.disabled = false;
            showToast(err.message || 'Could not update status.', 'error');
          }
        }
      })
    );
  }
}

function renderJobHistory(jobs) {
  const tbody = document.getElementById('drv-history-body');
  clearNode(tbody);

  if (jobs.length === 0) {
    tbody.appendChild(
      el('tr', {}, [el('td', { colspan: '6', className: 'text-center text-secondary py-4', text: 'No hauls recorded yet.' })])
    );
    return;
  }

  jobs.forEach((j) => {
    tbody.appendChild(
      el('tr', {}, [
        el('td', { className: 'font-mono fw-bold', text: j.trackingCode || `#${j.id}` }),
        el('td', { text: `${j.pickupCity} → ${j.dropCity}` }),
        el('td', { className: 'font-mono', text: `${j.distanceKm} km` }),
        el('td', { className: 'font-mono fw-semibold', text: formatInr(j.totalFare) }),
        el('td', {}, [createStatusBadge(j.status)]),
        el('td', { className: 'font-mono small text-secondary', text: formatDateTime(j.createdAt) })
      ])
    );
  });
}

/* 5. KYC Vault & Authenticated Blob Preview (`doc.docType`, `doc.downloadPath`) */
const docModal = document.getElementById('doc-preview-backdrop');
document.getElementById('btn-close-doc-preview').addEventListener('click', () => {
  docModal.classList.add('d-none');
  docModal.classList.remove('d-flex');
  if (previewBlobUrl) {
    URL.revokeObjectURL(previewBlobUrl);
    previewBlobUrl = null;
  }
});

function renderDocuments(docs) {
  const box = document.getElementById('drv-docs-list');
  clearNode(box);

  if (!docs || docs.length === 0) {
    box.appendChild(el('div', { className: 'text-secondary small', text: 'No compliance scans uploaded yet.' }));
    return;
  }

  docs.forEach((doc) => {
    const label = String(doc.docType || '').replace('_', ' ');
    box.appendChild(
      el('div', { className: 'vehicle-option-card justify-content-between' }, [
        el('div', {}, [
          el('div', { className: 'fw-bold small', text: label }),
          el('div', {
            className: 'font-mono text-secondary',
            style: 'font-size:0.72rem;',
            text: `${doc.originalFilename} · ${formatBytes(doc.fileSizeBytes)}`
          })
        ]),
        el('button', {
          type: 'button',
          className: 'btn btn-sm btn-as-ghost py-1',
          text: 'Inspect Scan',
          onClick: () => previewDocument(doc)
        })
      ])
    );
  });
}

async function previewDocument(doc) {
  try {
    const blob = await api.fetchDocumentBlob(doc.downloadPath || `/driver/documents/${doc.id}`);
    if (previewBlobUrl) URL.revokeObjectURL(previewBlobUrl);
    previewBlobUrl = URL.createObjectURL(blob);

    document.getElementById('doc-preview-title').textContent = `${doc.docType} · ${doc.originalFilename}`;
    const body = document.getElementById('doc-preview-body');
    clearNode(body);

    const mime = blob.type || doc.contentType || '';
    if (mime.includes('pdf')) {
      body.appendChild(el('iframe', { src: previewBlobUrl, className: 'w-100 border-0', style: 'height: 65vh;' }));
    } else {
      body.appendChild(el('img', { src: previewBlobUrl, alt: doc.docType, className: 'img-fluid rounded-2' }));
    }

    docModal.classList.remove('d-none');
    docModal.classList.add('d-flex');
  } catch (err) {
    showToast(err.message || 'Unable to load scan.', 'error');
  }
}

const reuploadInput = document.getElementById('re-doc-file');
enhanceFileDropzone(reuploadInput, {
  title: 'Drop updated DL or RC scan here or click to browse',
  hint: 'JPEG, PNG, or PDF · Max 5 MB'
});

document.getElementById('doc-reupload-form').addEventListener('submit', async (evt) => {
  evt.preventDefault();
  const type = document.getElementById('re-doc-type').value;
  const file = reuploadInput.files[0];
  if (!file) {
    showToast('Select a document scan first.', 'error');
    return;
  }
  const fd = new FormData();
  fd.append('file', file);
  const btn = document.getElementById('btn-reupload');
  btn.disabled = true;
  try {
    await api.putForm(`/driver/documents/${type}`, fd, true);
    reuploadInput.value = '';
    reuploadInput.dispatchEvent(new Event('change'));
    showToast('Verification document uploaded successfully.', 'success');
    await loadDriverProfile();
  } catch (err) {
    showToast(err.message || 'Upload failed.', 'error');
  } finally {
    btn.disabled = false;
  }
});

/* Initial Boot & Polling */
await loadDriverProfile();
initDriverMap();
await loadPendingOffers();
await loadDriverJobs();

window.setInterval(() => {
  if (driverProfile?.online) {
    loadPendingOffers();
  }
  if (driverProfile?.online || activeJob?.id) {
    loadDriverJobs();
  }
}, 4000);

window.setInterval(() => {
  if (driverProfile?.online && document.getElementById('toggle-auto-gps').checked) {
    pushGpsLocation(true);
  }
}, 10000);
