/**
 * Apna Samaan — Admin Operations & KYC Console (`admin.js`)
 * Full-screen Sidebar Shell + KYC Compliance Queue + Live Corridor Map + Payment Refunds + Fleet Tariff Matrix.
 */

import { api } from './api.js';
import { createFleetPhotoNode, createFleetSvgNode, FLEET_TAGLINES } from './fleet-art.js';
import {
  clearNode,
  createStatusBadge,
  el,
  formatInr,
  humanStatusLabel,
  openConfirmModal,
  openReasonModal,
  renderAppSidebar,
  requireRole,
  showToast
} from './ui.js';
import { LogisticsMap } from './map-service.js';

const session = requireRole(['ADMIN']);
if (!session) {
  throw new Error('Redirecting to login');
}

document.getElementById('btn-adm-mobile-logout').addEventListener('click', async () => {
  await api.logout();
  window.location.href = 'login.html';
});

const NAV_ITEMS = [
  { key: 'kyc', label: 'KYC & Metrics', icon: 'bi-person-vcard' },
  { key: 'shipments', label: 'Waybills & Map', icon: 'bi-globe-central-south-asia' },
  { key: 'vehicles', label: 'Tariff Matrix', icon: 'bi-truck-flatbed' }
];

let activeTabKey = 'kyc';
let adminMap = null;
let docBlobUrl = null;
let selectedAdminShipmentId = null;
let paymentsByShipmentId = new Map();

function switchTab(tabKey) {
  activeTabKey = tabKey;
  renderAppSidebar('admin-rail', {
    roleLabel: 'OPS ADMIN',
    navItems: NAV_ITEMS,
    activeKey: activeTabKey,
    onSelect: switchTab
  });

  const titles = {
    kyc: 'Driver KYC & Compliance Queue',
    shipments: 'All Waybills, Escrow Refunds & Live Map',
    vehicles: 'Commercial Fleet & INR Tariff Matrix'
  };
  document.getElementById('adm-view-title').textContent = titles[tabKey];

  ['kyc', 'shipments', 'vehicles'].forEach((k) => {
    document.getElementById(`atab-${k}`).classList.toggle('d-none', k !== tabKey);
  });

  if (tabKey === 'shipments') {
    if (!adminMap) adminMap = new LogisticsMap('admin-map');
    adminMap.invalidateSize();
    loadAdminShipments();
  }
  if (tabKey === 'vehicles') {
    loadAdminVehicles();
  }
}

renderAppSidebar('admin-rail', {
  roleLabel: 'OPS ADMIN',
  navItems: NAV_ITEMS,
  activeKey: activeTabKey,
  onSelect: switchTab
});

/* 1. KPI Metrics aggregated from Admin endpoints */
async function loadMetrics() {
  try {
    const [allDrivers, pendingDrivers, allShipments, allPayments] = await Promise.all([
      api.get('/admin/drivers?page=0&size=100'),
      api.get('/admin/drivers?status=PENDING&page=0&size=100'),
      api.get('/admin/shipments?page=0&size=100'),
      api.get('/admin/payments?page=0&size=100')
    ]);

    const driversList = allDrivers.items || [];
    const shipmentsList = allShipments.items || [];
    const paymentsList = allPayments.items || [];

    paymentsByShipmentId = new Map();
    paymentsList.forEach((p) => {
      paymentsByShipmentId.set(p.shipmentId, p);
    });

    const verifiedCount = driversList.filter((d) => d.approvalStatus === 'APPROVED').length;
    const onlineCount = driversList.filter((d) => d.online).length;

    const activeCount = shipmentsList.filter((s) =>
      ['SEARCHING_DRIVER', 'ASSIGNED', 'PICKED_UP', 'IN_TRANSIT'].includes(s.status)
    ).length;
    const deliveredCount = shipmentsList.filter((s) => s.status === 'DELIVERED').length;
    const cancelledCount = shipmentsList.filter((s) => s.status === 'CANCELLED').length;

    const capturedRevenue = paymentsList
      .filter((p) => p.status === 'CAPTURED')
      .reduce((acc, p) => acc + Number(p.amountInr || 0), 0);

    const refundedTotal = paymentsList
      .filter((p) => p.status === 'REFUNDED')
      .reduce((acc, p) => acc + Number(p.refundedAmountInr || p.amountInr || 0), 0);

    document.getElementById('kpi-users').textContent = String(allDrivers.totalElements || driversList.length);
    document.getElementById('kpi-users-sub').textContent = `Registered Fleet Operators`;
    document.getElementById('kpi-pending-kyc').textContent = String(pendingDrivers.totalElements || 0);
    document.getElementById('kpi-drivers-sub').textContent = `${verifiedCount} Verified · ${onlineCount} On Duty`;
    document.getElementById('kpi-active-ship').textContent = String(activeCount);
    document.getElementById('kpi-ship-sub').textContent = `${deliveredCount} Delivered · ${cancelledCount} Cancelled`;
    document.getElementById('kpi-revenue').textContent = formatInr(capturedRevenue);
    document.getElementById('kpi-refund-sub').textContent = `Refunded: ${formatInr(refundedTotal)}`;
  } catch (err) {
    showToast(err.message || 'Unable to sync KPIs.', 'error');
  }
}

/* 2. Driver KYC Queue & Authenticated Document Preview */
const docModal = document.getElementById('adm-doc-backdrop');
document.getElementById('btn-close-adm-doc').addEventListener('click', () => {
  docModal.classList.add('d-none');
  docModal.classList.remove('d-flex');
  if (docBlobUrl) {
    URL.revokeObjectURL(docBlobUrl);
    docBlobUrl = null;
  }
});

async function previewKycDoc(doc) {
  try {
    const blob = await api.fetchDocumentBlob(doc.downloadPath || `/driver/documents/${doc.id}`);
    if (docBlobUrl) URL.revokeObjectURL(docBlobUrl);
    docBlobUrl = URL.createObjectURL(blob);

    document.getElementById('adm-doc-title').textContent = `${doc.docType} · ${doc.originalFilename}`;
    const body = document.getElementById('adm-doc-body');
    clearNode(body);

    const mime = blob.type || doc.contentType || '';
    if (mime.includes('pdf')) {
      body.appendChild(el('iframe', { src: docBlobUrl, className: 'w-100 border-0', style: 'height: 68vh;' }));
    } else {
      body.appendChild(el('img', { src: docBlobUrl, alt: doc.docType, className: 'img-fluid rounded-2' }));
    }

    docModal.classList.remove('d-none');
    docModal.classList.add('d-flex');
  } catch (err) {
    showToast(err.message || 'Unable to load KYC scan.', 'error');
  }
}

async function loadKycDrivers() {
  const status = document.getElementById('filter-kyc-status').value;
  const query = status ? `?status=${encodeURIComponent(status)}&page=0&size=30` : '?page=0&size=30';
  const tbody = document.getElementById('kyc-table-body');

  try {
    const page = await api.get(`/admin/drivers${query}`);
    const drivers = page.items || [];
    clearNode(tbody);

    if (drivers.length === 0) {
      tbody.appendChild(
        el('tr', {}, [
          el('td', { colspan: '6', className: 'text-center text-secondary py-4', text: 'No operators match this filter.' })
        ])
      );
      return;
    }

    drivers.forEach((d) => {
      const docBtns = el('div', { className: 'd-flex flex-wrap gap-1' });
      (d.documents || []).forEach((doc) => {
        docBtns.appendChild(
          el('button', {
            type: 'button',
            className: 'corridor-chip py-1 px-2',
            text: doc.docType === 'DRIVING_LICENCE' ? 'Inspect DL' : 'Inspect RC',
            onClick: () => previewKycDoc(doc)
          })
        );
      });

      const actions = el('div', { className: 'd-flex flex-wrap justify-content-end gap-1' });
      if (d.approvalStatus !== 'APPROVED') {
        actions.appendChild(
          el('button', {
            type: 'button',
            className: 'btn btn-sm btn-success py-1',
            text: 'Approve',
            onClick: async () => {
              await api.patch(`/admin/drivers/${d.driverProfileId}/approve`, {});
              showToast(`Approved ${d.name}.`, 'success');
              await loadMetrics();
              await loadKycDrivers();
            }
          })
        );
      }
      if (d.approvalStatus !== 'REJECTED') {
        actions.appendChild(
          el('button', {
            type: 'button',
            className: 'btn btn-sm btn-outline-danger py-1',
            text: 'Reject',
            onClick: async () => {
              const reason = await openReasonModal({
                eyebrow: 'COMPLIANCE REVIEW',
                title: 'Request KYC Document Revision',
                subtitle: 'The driver will see this note in their console and can upload a fresh scan immediately.',
                summaryRows: [
                  { label: 'Driver Partner', value: d.name },
                  { label: 'Vehicle & Plate', value: `${d.vehicleTypeName} · ${d.vehicleNumber}`, mono: true }
                ],
                reasons: [
                  'Driving Licence scan is blurry or unreadable',
                  'Vehicle RC registration plate does not match',
                  'Uploaded document is expired or incomplete'
                ],
                cancelText: 'Cancel',
                confirmText: 'Mark for Revision',
                danger: true
              });
              if (!reason) return;
              await api.patch(`/admin/drivers/${d.driverProfileId}/reject`, { reason });
              showToast(`Requested updated KYC scan from ${d.name}.`, 'info');
              await loadMetrics();
              await loadKycDrivers();
            }
          })
        );
      }
      if (d.approvalStatus !== 'DEACTIVATED') {
        actions.appendChild(
          el('button', {
            type: 'button',
            className: 'btn btn-sm btn-as-ghost py-1',
            text: 'Suspend',
            onClick: async () => {
              const confirmed = await openConfirmModal({
                eyebrow: 'ACCOUNT SAFETY RAIL',
                title: 'Suspend Driver Partner Account?',
                subtitle: 'Suspended drivers are immediately taken off duty and cannot receive new load offers.',
                summaryRows: [
                  { label: 'Driver Partner', value: d.name },
                  { label: 'Vehicle & City', value: `${d.vehicleTypeName} · ${d.city || '—'}` },
                  { label: 'Registration Plate', value: d.vehicleNumber, mono: true }
                ],
                cancelText: 'Keep Active',
                confirmText: 'Confirm Suspension',
                danger: true
              });
              if (!confirmed) return;
              await api.patch(`/admin/drivers/${d.driverProfileId}/deactivate`, {});
              showToast(`Suspended driver partner ${d.name}.`, 'info');
              await loadMetrics();
              await loadKycDrivers();
            }
          })
        );
      }

      tbody.appendChild(
        el('tr', {}, [
          el('td', {}, [
            el('div', { className: 'fw-bold', text: d.name }),
            el('div', { className: 'font-mono text-secondary', style: 'font-size:0.74rem;', text: `${d.email} · ${d.phone}` })
          ]),
          el('td', {}, [
            el('div', { className: 'd-flex align-items-center gap-2' }, [
              createFleetSvgNode(d.vehicleTypeCode, 'vehicle-mini-art'),
              el('div', {}, [
                el('div', { className: 'fw-semibold small', text: d.vehicleTypeName }),
                el('div', { className: 'font-mono text-secondary', style: 'font-size:0.72rem;', text: d.city || '—' })
              ])
            ])
          ]),
          el('td', { className: 'font-mono small', text: d.vehicleNumber }),
          el('td', {}, [docBtns]),
          el('td', {}, [createStatusBadge(d.approvalStatus)]),
          el('td', { className: 'text-end' }, [actions])
        ])
      );
    });
  } catch (err) {
    showToast(err.message || 'Unable to load KYC queue.', 'error');
  }
}

document.getElementById('filter-kyc-status').addEventListener('change', loadKycDrivers);

/* 3. All Waybills + Live Corridor Map + Admin Refunds (`POST /admin/payments/{paymentId}/refund`) */
async function loadAdminShipments() {
  const status = document.getElementById('filter-ship-status').value;
  const query = status ? `?status=${encodeURIComponent(status)}&page=0&size=30` : '?page=0&size=30';
  const box = document.getElementById('adm-shipments-list');

  try {
    await loadMetrics();
    const page = await api.get(`/admin/shipments${query}`);
    const list = page.items || [];
    clearNode(box);

    if (list.length === 0) {
      box.appendChild(
        el('div', { className: 'as-panel p-4 text-center text-secondary small', text: 'No waybills match this filter.' })
      );
      return;
    }

    list.forEach((s) => {
      const payment = paymentsByShipmentId.get(s.id);
      const canRefund = payment && payment.status === 'CAPTURED';

      const card = el(
        'div',
        { className: `vehicle-option-card flex-column align-items-stretch ${selectedAdminShipmentId === s.id ? 'selected' : ''}` },
        [
          el('div', { className: 'd-flex justify-content-between align-items-center gap-2' }, [
            el('span', { className: 'tracking-id', text: s.trackingCode || `#${s.id}` }),
            el('span', { className: 'waybill-price-pill', text: formatInr(s.totalFare) })
          ]),
          el('div', { className: 'd-flex flex-wrap align-items-center gap-1 mt-1' }, [
            createStatusBadge(s.status),
            payment ? createStatusBadge(payment.status) : null
          ]),
          el('div', {
            className: 'small text-secondary mt-2',
            text: `${s.pickupCity} → ${s.dropCity} · ${s.vehicleTypeName} · Shipper: ${s.customerName}`
          }),
          el('div', { className: 'd-flex flex-wrap justify-content-between align-items-center gap-2 mt-2 pt-2 border-top' }, [
            el('span', {
              className: 'font-mono text-secondary',
              style: 'font-size:0.72rem;',
              text: s.assignedDriverName ? `Driver: ${s.assignedDriverName}` : 'Unassigned'
            }),
            el('div', { className: 'd-flex flex-wrap gap-1' }, [
              el('button', {
                type: 'button',
                className: 'btn btn-sm btn-as-ghost py-1 text-nowrap',
                text: 'Inspect Map',
                onClick: () => inspectAdminMap(s.id)
              }),
              canRefund
                ? el('button', {
                    type: 'button',
                    className: 'btn btn-sm btn-outline-danger py-1 text-nowrap',
                    text: 'Issue Refund',
                    onClick: async () => {
                      const reason = await openReasonModal({
                        eyebrow: 'ADMIN PAYMENT SAFETY RAIL',
                        title: 'Confirm Full Payment Refund',
                        subtitle:
                          'Are you sure you want to issue a 100% refund to the shipper’s original payment method? This action cannot be undone.',
                        summaryRows: [
                          { label: 'Waybill ID', value: s.trackingCode || `#${s.id}`, mono: true },
                          { label: 'Shipper', value: s.customerName || '—' },
                          { label: 'Route', value: `${s.pickupCity} → ${s.dropCity}` },
                          { label: 'Refund Amount', value: formatInr(payment.amountInr || s.totalFare), mono: true }
                        ],
                        reasons: [
                          'Customer requested pre-pickup cancellation',
                          'Dispatch SLA or pickup delay',
                          'Duplicate or test booking',
                          'Operations goodwill refund'
                        ],
                        cancelText: 'Back',
                        confirmText: `Confirm & Refund ${formatInr(payment.amountInr || s.totalFare)}`,
                        danger: true
                      });
                      if (!reason) return;
                      try {
                        await api.post(`/admin/payments/${payment.id}/refund`, { reason });
                        showToast(`Full refund of ${formatInr(payment.amountInr || s.totalFare)} processed for ${s.trackingCode}.`, 'success');
                        await loadAdminShipments();
                      } catch (err) {
                        showToast(err.message || 'Refund failed.', 'error');
                      }
                    }
                  })
                : null
            ])
          ])
        ]
      );
      box.appendChild(card);
    });

    if (!selectedAdminShipmentId && list[0]) {
      inspectAdminMap(list[0].id);
    }
  } catch (err) {
    showToast(err.message || 'Could not load waybills.', 'error');
  }
}

document.getElementById('filter-ship-status').addEventListener('change', loadAdminShipments);

async function inspectAdminMap(shipmentId) {
  selectedAdminShipmentId = shipmentId;
  try {
    const trk = await api.get(`/shipments/${shipmentId}/tracking`);
    document.getElementById('adm-map-title').textContent = `${trk.trackingCode} · ${humanStatusLabel(trk.status)}`;
    if (!adminMap) adminMap = new LogisticsMap('admin-map');
    adminMap.setRoutePoints(
      { lat: trk.pickupLat, lng: trk.pickupLng, label: 'Pickup Location' },
      { lat: trk.dropLat, lng: trk.dropLng, label: 'Drop-off Location' },
      true
    );
    if (trk.driverLat !== null && trk.driverLng !== null) {
      adminMap.setDriverPosition(trk.driverLat, trk.driverLng, [trk.dropLat, trk.dropLng], false);
    } else {
      adminMap.setDriverPosition(null, null);
    }
  } catch (err) {
    showToast(err.message || 'Unable to load tracking coordinates.', 'error');
  }
}

/* 4. Commercial Fleet & Server-Side INR Tariff Schedule (`GET /vehicle-types`) */
async function loadAdminVehicles() {
  const grid = document.getElementById('admin-vehicles-grid');
  try {
    const list = await api.get('/vehicle-types', false);
    clearNode(grid);

    list.forEach((v) => {
      const card = el('div', { className: 'as-panel p-4 h-100' }, [
        el('div', { className: 'mb-3 rounded-3 overflow-hidden' }, [createFleetPhotoNode(v.code, v.name)]),
        el('div', { className: 'mb-3 pb-3 border-bottom' }, [
          el('div', { className: 'small fw-semibold text-secondary', text: `Vehicle Size 0${v.id}` }),
          el('div', { className: 'h5 fw-bold mb-1', text: v.name }),
          el('div', { className: 'small text-secondary', text: FLEET_TAGLINES[v.code] || v.description })
        ]),
        el('div', { className: 'row g-2 font-mono small' }, [
          el('div', { className: 'col-6 col-sm-3' }, [
            el('div', { className: 'as-label', text: 'Max Payload' }),
            el('div', { className: 'fw-bold', text: `${v.maxWeightKg} kg` })
          ]),
          el('div', { className: 'col-6 col-sm-3' }, [
            el('div', { className: 'as-label', text: 'Deck L×W×H' }),
            el('div', { className: 'fw-bold', text: `${v.maxLengthCm}×${v.maxWidthCm}×${v.maxHeightCm}` })
          ]),
          el('div', { className: 'col-6 col-sm-3' }, [
            el('div', { className: 'as-label', text: 'Base Fare' }),
            el('div', { className: 'fw-bold', text: formatInr(v.baseFare) })
          ]),
          el('div', { className: 'col-6 col-sm-3' }, [
            el('div', { className: 'as-label', text: 'Per-Km Rate' }),
            el('div', { className: 'fw-bold', style: 'color: var(--as-vermilion);', text: `${formatInr(v.perKmRate)}/km` })
          ])
        ])
      ]);

      grid.appendChild(el('div', { className: 'col-12 col-md-6' }, [card]));
    });
  } catch (err) {
    showToast(err.message || 'Unable to load vehicle tariffs.', 'error');
  }
}

document.getElementById('btn-admin-refresh').addEventListener('click', async () => {
  await loadMetrics();
  await loadKycDrivers();
  if (activeTabKey === 'shipments') await loadAdminShipments();
  if (activeTabKey === 'vehicles') await loadAdminVehicles();
  showToast('Operations ledger synced.', 'info', 2000);
});

await loadMetrics();
await loadKycDrivers();
