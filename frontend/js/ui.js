/**
 * Apna Samaan — Shared XSS-Safe UI Primitives, Brand Constants & Layout Shells (`ui.js`)
 * Every dynamic value is written exclusively via `textContent` — never `innerHTML` on user data.
 */

import { api, getAuthSession } from './api.js';
import { createFleetSvgNode } from './fleet-art.js';

export const BRAND = Object.freeze({
  name: 'Apna Samaan',
  supportEmail: 'teamapnasamaan@gmail.com',
  githubUrl: 'https://github.com/shudanshubora1410',
  linkedinUrl: 'https://www.linkedin.com/in/shudanshubora1410',
  copyright: '© 2026 Apna Samaan. Built by Shudanshu Bora'
});

/* ==========================================================================
   Safe DOM Element Factory
   ========================================================================== */
export function el(tag, attrs = {}, children = []) {
  const node = document.createElement(tag);
  for (const [key, val] of Object.entries(attrs)) {
    if (val === null || val === undefined || val === false) continue;
    if (key === 'className') {
      node.className = val;
    } else if (key === 'text') {
      node.textContent = String(val);
    } else if (key === 'dataset' && typeof val === 'object') {
      for (const [dk, dv] of Object.entries(val)) {
        node.dataset[dk] = String(dv);
      }
    } else if (key.startsWith('on') && typeof val === 'function') {
      node.addEventListener(key.slice(2).toLowerCase(), val);
    } else if (val === true) {
      node.setAttribute(key, '');
    } else {
      node.setAttribute(key, String(val));
    }
  }

  const list = Array.isArray(children) ? children : [children];
  for (const child of list) {
    if (child === null || child === undefined || child === false) continue;
    if (child instanceof Node) {
      node.appendChild(child);
    } else {
      node.appendChild(document.createTextNode(String(child)));
    }
  }
  return node;
}

export function clearNode(node) {
  if (node) node.replaceChildren();
}

/* ==========================================================================
   Formatting & Role Routing Helpers
   ========================================================================== */
const inrFormatter = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2
});

export function formatInr(amount) {
  const num = Number(amount);
  return Number.isFinite(num) ? inrFormatter.format(num) : '₹0.00';
}

export function formatDateTime(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  return d.toLocaleString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  });
}

export function formatBytes(bytes) {
  const n = Number(bytes);
  if (!Number.isFinite(n) || n <= 0) return '0 B';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
}

export function portalPathForRole(role) {
  switch (role) {
    case 'DRIVER':
      return 'driver.html';
    case 'ADMIN':
      return 'admin.html';
    case 'CUSTOMER':
    default:
      return 'customer.html';
  }
}

export function roleDisplayName(role) {
  switch (role) {
    case 'DRIVER':
      return 'Driver Partner';
    case 'ADMIN':
      return 'Operations Admin';
    case 'CUSTOMER':
    default:
      return 'Shipper';
  }
}

export function requireRole(allowedRoles = []) {
  const session = getAuthSession();
  if (!session || !session.user) {
    const next = encodeURIComponent(window.location.pathname.split('/').pop() || 'index.html');
    window.location.replace(`login.html?next=${next}`);
    return null;
  }
  if (allowedRoles.length > 0 && !allowedRoles.includes(session.user.role)) {
    window.location.replace(portalPathForRole(session.user.role));
    return null;
  }
  return session;
}

/* ==========================================================================
   Accessible Toast Notifications & Form Validation Binding
   ========================================================================== */
function getToastContainer() {
  let stack = document.getElementById('as-toast-stack');
  if (!stack) {
    stack = el('div', {
      id: 'as-toast-stack',
      className: 'as-toast-stack',
      role: 'region',
      'aria-live': 'polite',
      'aria-label': 'Notifications'
    });
    document.body.appendChild(stack);
  }
  return stack;
}

export function showToast(message, type = 'info', durationMs = 4200) {
  const stack = getToastContainer();
  const iconCls =
    type === 'success'
      ? 'bi-check-circle-fill text-success'
      : type === 'error'
        ? 'bi-exclamation-triangle-fill text-danger'
        : 'bi-info-circle-fill text-warning';

  const toast = el('div', { className: `as-toast ${type}`, role: 'status' }, [
    el('i', { className: `bi ${iconCls} mt-1`, 'aria-hidden': 'true' }),
    el('div', { className: 'flex-grow-1', text: message }),
    el('button', {
      type: 'button',
      className: 'btn-close btn-close-white btn-sm ms-2',
      'aria-label': 'Dismiss notification',
      onClick: () => toast.remove()
    })
  ]);

  stack.appendChild(toast);
  window.setTimeout(() => toast.remove(), durationMs);
}

export function clearFormErrors(form) {
  if (!form) return;
  form.querySelectorAll('.is-invalid').forEach((elNode) => elNode.classList.remove('is-invalid'));
  form.querySelectorAll('.invalid-feedback[data-dynamic="1"]').forEach((n) => n.remove());
  const alertBox = form.querySelector('[data-form-error]');
  if (alertBox) {
    alertBox.classList.add('d-none');
    alertBox.textContent = '';
  }
}

export function applyApiFormErrors(form, error) {
  if (!form) {
    showToast(error?.message || 'Something went wrong.', 'error');
    return;
  }
  clearFormErrors(form);

  const fieldErrors = error?.fieldErrors || {};
  let attachedField = false;

  for (const [fieldName, msg] of Object.entries(fieldErrors)) {
    const field = form.elements.namedItem(fieldName);
    if (field && field instanceof HTMLElement) {
      field.classList.add('is-invalid');
      const feedback = el('div', {
        className: 'invalid-feedback d-block',
        dataset: { dynamic: '1' },
        text: msg
      });
      field.insertAdjacentElement('afterend', feedback);
      attachedField = true;
    }
  }

  const alertBox = form.querySelector('[data-form-error]');
  const topMsg = error?.message || 'Please check the highlighted fields.';
  if (alertBox) {
    alertBox.textContent = topMsg;
    alertBox.classList.remove('d-none');
  } else if (!attachedField) {
    showToast(topMsg, 'error');
  }
}

/* ==========================================================================
   Human-Friendly Status Labels, Badges & Connected Progress Stepper
   ========================================================================== */
const STATUS_META = {
  PENDING_PAYMENT: { label: 'Payment Pending', cls: 'as-badge-amber' },
  SEARCHING_DRIVER: { label: 'Finding Driver', cls: 'as-badge-saffron' },
  ASSIGNED: { label: 'Driver Assigned', cls: 'as-badge-indigo' },
  PICKED_UP: { label: 'Picked Up', cls: 'as-badge-indigo' },
  IN_TRANSIT: { label: 'In Transit', cls: 'as-badge-saffron' },
  DELIVERED: { label: 'Delivered', cls: 'as-badge-emerald' },
  CANCELLED: { label: 'Cancelled', cls: 'as-badge-slate' },
  FAILED: { label: 'No Driver Found', cls: 'as-badge-crimson' },
  PENDING: { label: 'Under Review', cls: 'as-badge-amber' },
  APPROVED: { label: 'Verified', cls: 'as-badge-emerald' },
  REJECTED: { label: 'Action Needed', cls: 'as-badge-crimson' },
  DEACTIVATED: { label: 'Suspended', cls: 'as-badge-slate' },
  CREATED: { label: 'Payment Due', cls: 'as-badge-amber' },
  CAPTURED: { label: 'Paid', cls: 'as-badge-emerald' },
  REFUNDED: { label: 'Refunded', cls: 'as-badge-indigo' }
};

export function humanStatusLabel(status) {
  if (!status) return 'Pending';
  return STATUS_META[status]?.label || String(status).replace(/_/g, ' ');
}

export function createStatusBadge(status) {
  const meta = STATUS_META[status] || { label: humanStatusLabel(status), cls: 'as-badge-slate' };
  return el('span', { className: `as-badge ${meta.cls}`, text: meta.label });
}

export function createVehicleIconElement(code) {
  return createFleetSvgNode(code, 'vehicle-mini-art');
}

export function buildDeliveryStepper(currentStatus) {
  if (currentStatus === 'CANCELLED' || currentStatus === 'FAILED') {
    const isCancelled = currentStatus === 'CANCELLED';
    return el(
      'div',
      {
        className: 'p-2 px-3 rounded-2 small fw-semibold d-flex align-items-center gap-2',
        style: isCancelled
          ? 'background:#f1f5f9;color:#475569;border:1px solid #cbd5e1;'
          : 'background:#fef2f2;color:#991b1b;border:1px solid #fecaca;'
      },
      [
        el('i', { className: `bi ${isCancelled ? 'bi-x-circle' : 'bi-exclamation-circle'}`, 'aria-hidden': 'true' }),
        el('span', {
          text: isCancelled
            ? 'Shipment cancelled before pickup'
            : 'No nearby driver accepted within the dispatch window'
        })
      ]
    );
  }

  const steps = [
    { key: 'PENDING_PAYMENT', label: 'Booked' },
    { key: 'SEARCHING_DRIVER', label: 'Matching' },
    { key: 'ASSIGNED', label: 'Assigned' },
    { key: 'PICKED_UP', label: 'Picked Up' },
    { key: 'IN_TRANSIT', label: 'In Transit' },
    { key: 'DELIVERED', label: 'Delivered' }
  ];
  const idx = steps.findIndex((s) => s.key === currentStatus);

  return el(
    'div',
    { className: 'as-stepper', role: 'list', 'aria-label': 'Shipment progress' },
    steps.map((step, i) => {
      const isDone = currentStatus === 'DELIVERED' || (idx > -1 && i < idx);
      const isCurrent = currentStatus !== 'DELIVERED' && i === idx;
      const stateCls = isDone ? 'done' : isCurrent ? 'current' : '';

      return el('div', { className: `as-step-node ${stateCls}`, role: 'listitem' }, [
        el('span', { className: 'as-step-dot', text: isDone ? '✓' : String(i + 1) }),
        el('span', { className: 'as-step-lbl', text: step.label })
      ]);
    })
  );
}

/* ==========================================================================
   Support & Policy Modals + Floating Dispatch Help Escape Hatch
   ========================================================================== */
const POLICY_COPY = {
  terms: {
    title: 'Terms of Service',
    body: 'Apna Samaan connects businesses and individual shippers with KYC-verified commercial vehicle partners across Indian cities. Quotes reflect road distance, selected vehicle capacity, and optional 18% GST invoicing. Hazardous or prohibited cargo is not permitted.'
  },
  privacy: {
    title: 'Privacy Policy',
    body: 'We collect only the contact, address, and verification documents required to execute and track deliveries. Driver DL and RC scans are stored outside the public web root and accessed strictly by authorized compliance reviewers.'
  },
  refunds: {
    title: 'Cancellation & Refund Policy',
    body: 'Shippers may cancel any booking free of charge prior to cargo pickup. Any captured online payment is automatically refunded 100% to the original payment method if cancelled before pickup or if no nearby driver accepts within the assignment window.'
  },
  support: {
    title: 'Dispatch & Enterprise Support Desk',
    body: 'Need help sizing a commercial vehicle, scheduling recurring B2B dispatches, or checking a GST invoice? Email our operations desk directly and our team responds promptly during business hours.'
  }
};

export function openSupportOrPolicyModal(key = 'support') {
  const info = POLICY_COPY[key] || POLICY_COPY.support;
  const isSupport = key === 'support' || !POLICY_COPY[key];
  let backdrop = document.getElementById('as-policy-modal');
  if (backdrop) backdrop.remove();

  const defaultMessage = 'Hi Apna Samaan team, I need help booking a shipment.';
  const msgInput = isSupport
    ? el('textarea', {
        className: 'form-control form-control-sm mt-2',
        rows: '3',
        value: defaultMessage,
        'aria-label': 'Pre-filled support message'
      })
    : null;
  if (msgInput) msgInput.value = defaultMessage;

  const buildMailtoHref = (bodyText) => {
    const subject = encodeURIComponent('Help Booking a Shipment — Apna Samaan');
    const body = encodeURIComponent(bodyText || defaultMessage);
    return `mailto:${BRAND.supportEmail}?subject=${subject}&body=${body}`;
  };

  const composeLink = el('a', {
    href: buildMailtoHref(defaultMessage),
    className: 'btn btn-sm btn-as-primary',
    text: isSupport ? 'Send Pre-Filled Enquiry →' : 'Email Support →'
  });

  if (msgInput) {
    msgInput.addEventListener('input', () => {
      composeLink.href = buildMailtoHref(msgInput.value.trim());
    });
  }

  const quickTopics = isSupport
    ? el('div', { className: 'd-flex flex-wrap gap-1 mt-2' }, [
        ...[
          {
            label: 'Vehicle Sizing',
            msg: 'Hi Apna Samaan team, I need help choosing the right commercial vehicle size for my cargo.'
          },
          {
            label: 'Bulk / B2B Dispatch',
            msg: 'Hi Apna Samaan team, I need help scheduling recurring B2B dispatches for our warehouse.'
          },
          {
            label: '18% GST Invoice',
            msg: 'Hi Apna Samaan team, I have a question regarding 18% GST tax invoicing on a booking.'
          }
        ].map((topic) =>
          el('button', {
            type: 'button',
            className: 'corridor-chip py-1 px-2',
            style: 'font-size:0.74rem;',
            text: topic.label,
            onClick: () => {
              msgInput.value = topic.msg;
              composeLink.href = buildMailtoHref(topic.msg);
            }
          })
        )
      ])
    : null;

  backdrop = el(
    'div',
    {
      id: 'as-policy-modal',
      className: 'as-modal-backdrop',
      onClick: (e) => {
        if (e.target === backdrop) backdrop.remove();
      }
    },
    [
      el('div', { className: 'as-panel p-4 w-100', style: 'margin:auto;max-width:510px;max-height:calc(100dvh - 1.7rem);overflow-y:auto;' }, [
        el('div', { className: 'd-flex justify-content-between align-items-center mb-2' }, [
          el('h3', { className: 'h5 fw-bold mb-0', text: info.title }),
          el('button', {
            type: 'button',
            className: 'btn-close',
            'aria-label': 'Close',
            onClick: () => backdrop.remove()
          })
        ]),
        el('p', { className: 'small text-secondary mb-3', text: info.body }),
        isSupport
          ? el('div', { className: 'mb-3' }, [
              el('label', { className: 'as-label mb-1', text: 'Quick Pre-Filled Dispatch Note' }),
              quickTopics,
              msgInput
            ])
          : null,
        el('div', { className: 'p-3 rounded-2 mb-3 small', style: 'background:var(--paper);border:1px solid var(--hairline);' }, [
          el('div', { className: 'd-flex justify-content-between align-items-center gap-2' }, [
            el('div', {}, [
              el('div', { className: 'fw-bold text-dark mb-1', text: 'Email Dispatch Desk' }),
              el('a', {
                href: buildMailtoHref(defaultMessage),
                className: 'fw-semibold text-decoration-none',
                style: 'color:var(--navy);',
                text: BRAND.supportEmail
              })
            ]),
            el('button', {
              type: 'button',
              className: 'btn btn-sm btn-as-ghost py-1 px-2',
              text: 'Copy Email',
              onClick: async () => {
                try {
                  await navigator.clipboard.writeText(BRAND.supportEmail);
                  showToast(`Copied ${BRAND.supportEmail}`, 'success');
                } catch {
                  showToast(BRAND.supportEmail, 'info');
                }
              }
            })
          ]),
          el('div', { className: 'text-secondary mt-2', style: 'font-size:0.75rem;', text: BRAND.copyright }),
          el('div', { className: 'd-flex gap-3 mt-1', style: 'font-size:0.75rem;' }, [
            el('a', { href: BRAND.githubUrl, target: '_blank', rel: 'noopener noreferrer', text: 'GitHub ↗' }),
            el('a', { href: BRAND.linkedinUrl, target: '_blank', rel: 'noopener noreferrer', text: 'LinkedIn ↗' })
          ])
        ]),
        el('div', { className: 'd-flex justify-content-end gap-2' }, [
          composeLink,
          el('button', {
            type: 'button',
            className: 'btn btn-sm btn-as-ghost',
            text: 'Close',
            onClick: () => backdrop.remove()
          })
        ])
      ])
    ]
  );

  document.body.appendChild(backdrop);
}

export function renderFloatingSupportButton() {
  if (document.getElementById('as-floating-support')) return;
  const btn = el(
    'button',
    {
      id: 'as-floating-support',
      type: 'button',
      className: 'floating-support-btn',
      onClick: () => openSupportOrPolicyModal('support')
    },
    [
      el('i', { className: 'bi bi-headset text-warning', 'aria-hidden': 'true' }),
      el('span', { text: 'Need help booking? Support Desk' })
    ]
  );
  document.body.appendChild(btn);

  const syncVisibility = () => {
    const pastHero = window.scrollY > 260;
    const footer = document.querySelector('.as-footer');
    const nearFooter = footer ? footer.getBoundingClientRect().top <= window.innerHeight - 16 : false;
    btn.classList.toggle('is-visible', pastHero && !nearFooter);
  };
  window.addEventListener('scroll', syncVisibility, { passive: true });
  window.addEventListener('resize', syncVisibility, { passive: true });
  syncVisibility();
}

/* ==========================================================================
   Public Masthead, Clean App-Shell Sidebar & Footer
   ========================================================================== */
export function renderNavbar(activePage = '') {
  const host = document.getElementById('app-navbar');
  if (!host) return;
  clearNode(host);

  const session = getAuthSession();
  const user = session?.user;

  const brandLink = el('a', { href: 'index.html', className: 'as-brand' }, [
    el('span', { className: 'as-brand-seal fw-bold small', 'aria-hidden': 'true', text: 'AS' }),
    el('span', { text: BRAND.name })
  ]);

  const right = el('div', { className: 'd-flex flex-wrap align-items-center gap-2 ms-auto' });

  if (user) {
    right.append(
      el('span', {
        className: 'as-badge as-badge-slate d-none d-md-inline-flex',
        text: `Signed in · ${roleDisplayName(user.role)}`
      }),
      el('a', {
        href: portalPathForRole(user.role),
        className: 'btn btn-sm btn-as-navy',
        text: `Go to ${roleDisplayName(user.role)} Dashboard →`
      }),
      el('button', {
        type: 'button',
        className: 'btn btn-sm btn-as-ghost',
        text: 'Sign Out',
        onClick: async () => {
          await api.logout();
          window.location.href = 'index.html';
        }
      })
    );
  } else {
    right.append(
      el('a', {
        href: activePage === 'home' ? '#fare-estimator' : 'index.html#fare-estimator',
        className: 'as-nav-link d-none d-md-inline-block',
        text: 'Check Fare'
      }),
      el('a', {
        href: activePage === 'home' ? '#why-apna-samaan' : 'index.html#why-apna-samaan',
        className: 'as-nav-link d-none d-lg-inline-block',
        text: 'How It Works'
      }),
      el('a', {
        href: 'register-driver.html',
        className: `as-nav-link d-none d-sm-inline-block ${activePage === 'register-driver' ? 'active' : ''}`,
        text: 'For Drivers'
      }),
      el('a', {
        href: 'login.html',
        className: `btn btn-sm btn-as-ghost ${activePage === 'login' ? 'border-dark' : ''}`,
        text: 'Sign In'
      }),
      el('a', {
        href: activePage === 'home' ? '#fare-estimator' : 'register-customer.html',
        className: 'btn btn-sm btn-as-primary',
        text: 'Book Now'
      })
    );
  }

  host.appendChild(
    el('header', { className: 'as-navbar py-2' }, [
      el('div', { className: 'container d-flex align-items-center justify-content-between gap-2 py-1' }, [
        brandLink,
        right
      ])
    ])
  );
}

/**
 * Clean, distraction-free Web-App Sidebar (User identity + Sign Out + subtle Help modal trigger).
 */
export function renderAppSidebar(containerId, { roleLabel, navItems, activeKey, onSelect }) {
  const host = document.getElementById(containerId);
  if (!host) return;
  clearNode(host);

  const session = getAuthSession();
  const userName = session?.user?.name || 'User';

  const topGroup = el('div', { className: 'w-100' }, [
    el('a', { href: 'index.html', className: 'app-rail-brand' }, [
      el('span', {
        className: 'd-inline-grid fw-bold small flex-shrink-0',
        style: 'width:28px;height:28px;place-items:center;background:#d9381e;border-radius:7px;color:#fff;',
        text: 'AS'
      }),
      el('div', { className: 'min-w-0' }, [
        el('div', { className: 'lh-1 text-truncate', text: BRAND.name }),
        el('span', { style: 'font-size:0.68rem;color:#94a3b8;font-weight:600;', text: roleLabel })
      ])
    ]),
    el(
      'nav',
      { className: 'app-rail-nav', 'aria-label': `${roleLabel} workspace navigation` },
      navItems.map((item) =>
        el(
          'button',
          {
            type: 'button',
            className: `app-rail-btn ${item.key === activeKey ? 'active' : ''}`,
            dataset: { railKey: item.key },
            onClick: () => onSelect(item.key)
          },
          [
            el('i', { className: `bi ${item.icon}`, 'aria-hidden': 'true' }),
            el('span', { text: item.label })
          ]
        )
      )
    )
  ]);

  const bottomGroup = el('div', { className: 'd-none d-lg-block pt-3 border-top border-secondary border-opacity-25 small' }, [
    el('div', { className: 'fw-semibold text-white text-truncate mb-2', text: userName }),
    el('div', { className: 'd-flex gap-2' }, [
      el(
        'button',
        {
          type: 'button',
          className: 'btn btn-sm btn-outline-light flex-grow-1 py-1',
          onClick: async () => {
            await api.logout();
            window.location.href = 'login.html';
          }
        },
        'Sign Out'
      ),
      el(
        'button',
        {
          type: 'button',
          className: 'btn btn-sm btn-outline-secondary text-white-50 py-1 px-2',
          title: 'Help & Support',
          'aria-label': 'Help and support',
          onClick: () => openSupportOrPolicyModal('support')
        },
        [el('i', { className: 'bi bi-question-circle', 'aria-hidden': 'true' })]
      )
    ])
  ]);

  host.append(topGroup, bottomGroup);
}

export function renderFooter() {
  const host = document.getElementById('app-footer');
  if (!host) return;
  clearNode(host);

  host.appendChild(
    el('footer', { className: 'as-footer' }, [
      el('div', { className: 'container' }, [
        el('div', { className: 'row gy-4 pb-4 border-bottom border-secondary border-opacity-25' }, [
          el('div', { className: 'col-12 col-lg-5' }, [
            el('div', { className: 'display-font fw-bold fs-4 text-white mb-2', text: BRAND.name }),
            el('p', {
              className: 'small mb-3',
              style: 'color:#94a3b8;max-width:28rem;',
              text: 'Reliable intra-city and inter-city goods transport across India. Upfront per-kilometre pricing, verified commercial vehicles, and live GPS tracking.'
            }),
            el('a', {
              href: `mailto:${BRAND.supportEmail}`,
              className: 'btn btn-sm btn-outline-light',
              text: `Email Support · ${BRAND.supportEmail}`
            })
          ]),
          el('div', { className: 'col-6 col-lg-3 small' }, [
            el('div', { className: 'fw-bold text-white mb-2', text: 'Service Corridors' }),
            el('div', { className: 'd-flex flex-column gap-1' }, [
              el('a', { href: 'index.html#fare-estimator', text: 'Goods Transport in Delhi NCR' }),
              el('a', { href: 'index.html#fare-estimator', text: 'Mini Truck Booking in Mumbai' }),
              el('a', { href: 'index.html#fare-estimator', text: 'Freight Delivery in Bengaluru' }),
              el('a', { href: 'index.html#fare-estimator', text: 'Pune – Mumbai Expressway' })
            ])
          ]),
          el('div', { className: 'col-6 col-lg-2 small' }, [
            el('div', { className: 'fw-bold text-white mb-2', text: 'Platform' }),
            el('div', { className: 'd-flex flex-column gap-1' }, [
              el('a', { href: 'index.html#fare-estimator', text: 'Fare Calculator' }),
              el('a', { href: 'register-customer.html', text: 'Shipper Sign Up' }),
              el('a', { href: 'register-driver.html', text: 'Attach Your Vehicle' }),
              el('a', { href: 'login.html', text: 'Account Sign In' })
            ])
          ]),
          el('div', { className: 'col-12 col-lg-2 small' }, [
            el('div', { className: 'fw-bold text-white mb-2', text: 'Policies & Connect' }),
            el('div', { className: 'd-flex flex-column gap-1 mb-2' }, [
              el('a', {
                href: '#terms',
                text: 'Terms of Service',
                onClick: (e) => { e.preventDefault(); openSupportOrPolicyModal('terms'); }
              }),
              el('a', {
                href: '#privacy',
                text: 'Privacy Policy',
                onClick: (e) => { e.preventDefault(); openSupportOrPolicyModal('privacy'); }
              }),
              el('a', {
                href: '#refunds',
                text: 'Refund Policy',
                onClick: (e) => { e.preventDefault(); openSupportOrPolicyModal('refunds'); }
              })
            ]),
            el('div', { className: 'd-flex gap-3 pt-1 fw-semibold' }, [
              el('a', { href: BRAND.githubUrl, target: '_blank', rel: 'noopener noreferrer', text: 'GitHub ↗' }),
              el('a', { href: BRAND.linkedinUrl, target: '_blank', rel: 'noopener noreferrer', text: 'LinkedIn ↗' })
            ])
          ])
        ]),
        el('div', { className: 'pt-3 d-flex flex-column flex-sm-row justify-content-between gap-2 small', style: 'color:#94a3b8;' }, [
          el('span', { text: BRAND.copyright }),
          el('span', { text: 'Built in India · Secure Payments Powered by Razorpay' })
        ])
      ])
    ])
  );
}

/* ==========================================================================
   Custom Styled Confirmation & Reason Modals (Replaces Native prompt/confirm)
   ========================================================================== */
export function openReasonModal({
  eyebrow = 'CONFIRM ACTION',
  title = 'Select a Reason',
  subtitle = '',
  summaryRows = [],
  reasons = ['Pickup schedule changed', 'Found alternative transport', 'Booked by mistake'],
  customLabel = 'Other reason',
  customPlaceholder = 'Enter details (at least 5 characters)...',
  confirmText = 'Confirm',
  cancelText = 'Cancel',
  danger = true
} = {}) {
  return new Promise((resolve) => {
    const existing = document.getElementById('as-action-modal');
    if (existing) existing.remove();

    let settled = false;
    const finish = (val) => {
      if (settled) return;
      settled = true;
      window.removeEventListener('keydown', onKey);
      backdrop.remove();
      resolve(val);
    };

    const customInput = el('input', {
      type: 'text',
      className: 'form-control form-control-sm mt-2 d-none',
      placeholder: customPlaceholder,
      maxlength: '200'
    });
    const errorHint = el('div', { className: 'text-danger small mt-1 d-none' });

    const allOptions = [...reasons, '__CUSTOM__'];
    let selectedVal = reasons[0] || '__CUSTOM__';

    const radioGroup = el(
      'div',
      { className: 'd-flex flex-column gap-1 mb-1', role: 'radiogroup', 'aria-label': title },
      allOptions.map((opt, idx) => {
        const isCustom = opt === '__CUSTOM__';
        const radio = el('input', {
          type: 'radio',
          name: 'as-modal-reason',
          value: opt,
          ...(idx === 0 ? { checked: 'checked' } : {}),
          onChange: () => {
            selectedVal = opt;
            errorHint.classList.add('d-none');
            customInput.classList.toggle('d-none', !isCustom);
            if (isCustom) customInput.focus();
          }
        });
        return el('label', { className: 'reason-radio-card' }, [
          radio,
          el('span', { text: isCustom ? customLabel : opt })
        ]);
      })
    );

    const summaryBox =
      summaryRows.length > 0
        ? el(
            'div',
            {
              className: 'p-2 px-3 rounded-2 mb-2 small',
              style: 'background:var(--paper);border:1px solid var(--hairline);'
            },
            summaryRows.map((row) =>
              el('div', { className: 'd-flex justify-content-between align-items-center gap-2 py-1' }, [
                el('span', { className: 'text-secondary', text: row.label }),
                el('span', { className: `fw-bold ${row.mono ? 'font-mono' : ''}`, text: row.value })
              ])
            )
          )
        : null;

    const handleConfirm = () => {
      const finalReason =
        selectedVal === '__CUSTOM__' ? customInput.value.trim() : String(selectedVal).trim();
      if (finalReason.length < 5) {
        errorHint.textContent = 'Please provide a brief reason (at least 5 characters).';
        errorHint.classList.remove('d-none');
        if (selectedVal === '__CUSTOM__') customInput.focus();
        return;
      }
      finish(finalReason);
    };

    const onKey = (e) => {
      if (e.key === 'Escape') finish(null);
    };
    window.addEventListener('keydown', onKey);

    const backdrop = el(
      'div',
      {
        id: 'as-action-modal',
        className: 'as-modal-backdrop',
        role: 'dialog',
        'aria-modal': 'true',
        onClick: (e) => {
          if (e.target === backdrop) finish(null);
        }
      },
      [
        el('div', { className: 'as-modal-card' }, [
          el('div', { className: 'waybill-header d-flex justify-content-between align-items-center' }, [
            el('div', {}, [
              el('div', {
                className: 'text-white-50 fw-semibold',
                style: 'font-size:0.7rem;letter-spacing:0.06em;text-transform:uppercase;',
                text: eyebrow
              }),
              el('div', { className: 'fw-bold', text: title })
            ]),
            el('button', {
              type: 'button',
              className: 'btn-close btn-close-white btn-sm',
              'aria-label': 'Close',
              onClick: () => finish(null)
            })
          ]),
          el('div', { className: 'as-modal-body' }, [
            subtitle ? el('p', { className: 'small text-secondary mb-2', text: subtitle }) : null,
            summaryBox,
            el('div', { className: 'as-label mb-1', text: 'Select Reason' }),
            radioGroup,
            customInput,
            errorHint
          ]),
          el('div', { className: 'as-modal-footer' }, [
            el('button', {
              type: 'button',
              className: 'btn btn-sm btn-as-ghost px-3 text-nowrap',
              text: cancelText,
              onClick: () => finish(null)
            }),
            el('button', {
              type: 'button',
              className: `btn btn-sm ${danger ? 'btn-as-primary' : 'btn-as-navy'} px-3 text-nowrap`,
              text: confirmText,
              onClick: handleConfirm
            })
          ])
        ])
      ]
    );

    document.body.appendChild(backdrop);
  });
}

export function openConfirmModal({
  eyebrow = 'CONFIRM ACTION',
  title = 'Are you sure?',
  subtitle = '',
  summaryRows = [],
  confirmText = 'Confirm',
  cancelText = 'Cancel',
  danger = true
} = {}) {
  return new Promise((resolve) => {
    const existing = document.getElementById('as-action-modal');
    if (existing) existing.remove();

    let settled = false;
    const finish = (val) => {
      if (settled) return;
      settled = true;
      window.removeEventListener('keydown', onKey);
      backdrop.remove();
      resolve(val);
    };

    const onKey = (e) => {
      if (e.key === 'Escape') finish(false);
    };
    window.addEventListener('keydown', onKey);

    const summaryBox =
      summaryRows.length > 0
        ? el(
            'div',
            {
              className: 'p-3 rounded-2 mb-3 small',
              style: 'background:var(--paper);border:1px solid var(--hairline);'
            },
            summaryRows.map((row) =>
              el('div', { className: 'd-flex justify-content-between align-items-center gap-2 py-1' }, [
                el('span', { className: 'text-secondary', text: row.label }),
                el('span', { className: `fw-bold ${row.mono ? 'font-mono' : ''}`, text: row.value })
              ])
            )
          )
        : null;

    const backdrop = el(
      'div',
      {
        id: 'as-action-modal',
        className: 'as-modal-backdrop',
        role: 'dialog',
        'aria-modal': 'true',
        onClick: (e) => {
          if (e.target === backdrop) finish(false);
        }
      },
      [
        el('div', { className: 'as-modal-card' }, [
          el('div', { className: 'waybill-header d-flex justify-content-between align-items-center' }, [
            el('div', {}, [
              el('div', {
                className: 'text-white-50 fw-semibold',
                style: 'font-size:0.7rem;letter-spacing:0.06em;text-transform:uppercase;',
                text: eyebrow
              }),
              el('div', { className: 'fw-bold', text: title })
            ]),
            el('button', {
              type: 'button',
              className: 'btn-close btn-close-white btn-sm',
              'aria-label': 'Close',
              onClick: () => finish(false)
            })
          ]),
          el('div', { className: 'as-modal-body' }, [
            subtitle ? el('p', { className: 'small text-secondary mb-2', text: subtitle }) : null,
            summaryBox
          ]),
          el('div', { className: 'as-modal-footer' }, [
            el('button', {
              type: 'button',
              className: 'btn btn-sm btn-as-ghost px-3 text-nowrap',
              text: cancelText,
              onClick: () => finish(false)
            }),
            el('button', {
              type: 'button',
              className: `btn btn-sm ${danger ? 'btn-as-primary' : 'btn-as-navy'} px-3 text-nowrap`,
              text: confirmText,
              onClick: () => finish(true)
            })
          ])
        ])
      ]
    );

    document.body.appendChild(backdrop);
  });
}

/* ==========================================================================
   Custom KYC Document Drop-Zone Enhancer (JPEG / PNG / PDF <= 5 MB)
   ========================================================================== */
export function enhanceFileDropzone(
  fileInput,
  {
    title = 'Drop document scan here or click to browse',
    hint = 'Accepted: JPEG, PNG, or PDF · Max 5 MB'
  } = {}
) {
  if (!fileInput || fileInput.dataset.dropzoneEnhanced === '1') return;
  fileInput.dataset.dropzoneEnhanced = '1';

  let thumbUrl = null;
  const iconEl = el('i', { className: 'bi bi-cloud-arrow-up-fill', 'aria-hidden': 'true' });
  const iconBadge = el('span', { className: 'kyc-dropzone-icon' }, [iconEl]);
  const titleEl = el('div', { className: 'fw-semibold small text-dark', text: title });
  const hintEl = el('div', { className: 'text-secondary', style: 'font-size:0.74rem;', text: hint });
  const thumbHost = el('div');

  const wrapper = el('div', { className: 'kyc-dropzone' }, [iconBadge, titleEl, hintEl, thumbHost]);
  fileInput.parentNode.insertBefore(wrapper, fileInput);
  fileInput.classList.remove('form-control');
  wrapper.appendChild(fileInput);

  const syncFileState = () => {
    if (thumbUrl) {
      URL.revokeObjectURL(thumbUrl);
      thumbUrl = null;
    }
    clearNode(thumbHost);

    const file = fileInput.files && fileInput.files[0];
    if (!file) {
      wrapper.classList.remove('has-file');
      iconEl.className = 'bi bi-cloud-arrow-up-fill';
      titleEl.textContent = title;
      hintEl.textContent = hint;
      return;
    }

    if (file.size > 5 * 1024 * 1024) {
      showToast(`${file.name} exceeds the 5 MB limit.`, 'error');
      fileInput.value = '';
      syncFileState();
      return;
    }

    wrapper.classList.add('has-file');
    iconEl.className = 'bi bi-check-lg';
    titleEl.textContent = `✓ ${file.name}`;
    hintEl.textContent = `${formatBytes(file.size)} · Ready to upload (click to replace)`;

    if (file.type && file.type.startsWith('image/')) {
      thumbUrl = URL.createObjectURL(file);
      thumbHost.appendChild(
        el('img', { src: thumbUrl, alt: 'Selected document preview', className: 'kyc-dropzone-thumb' })
      );
    }
  };

  fileInput.addEventListener('change', syncFileState);
  ['dragenter', 'dragover'].forEach((ev) =>
    wrapper.addEventListener(ev, (e) => {
      e.preventDefault();
      wrapper.classList.add('is-dragover');
    })
  );
  ['dragleave', 'drop'].forEach((ev) =>
    wrapper.addEventListener(ev, () => wrapper.classList.remove('is-dragover'))
  );
}

