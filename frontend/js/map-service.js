/**
 * Apna Samaan — Unified Cartography & India Geocoding Service (`map-service.js`)
 * Clean CARTO Voyager OpenStreetMap tiles, custom Pickup / Dropoff / Pulsing Truck pins,
 * crisp dual-layer route lines, and debounced Nominatim India autocomplete.
 */

import { api } from './api.js';
import { clearNode, el } from './ui.js';

const DELHI_CENTER = [28.6139, 77.209];

export const INDIA_ROUTE_PRESETS = [
  {
    label: 'Delhi NCR · Connaught Place → Cyber Hub',
    pickup: {
      address: 'Block A, Connaught Place, New Delhi 110001',
      city: 'Delhi',
      lat: 28.6315,
      lng: 77.2167
    },
    drop: {
      address: 'DLF Cyber Hub, Phase 2, Gurugram 122002',
      city: 'Gurugram',
      lat: 28.495,
      lng: 77.0895
    }
  },
  {
    label: 'Mumbai · MIDC Andheri → Bandra Kurla',
    pickup: {
      address: 'MIDC Industrial Area, Andheri East, Mumbai',
      city: 'Mumbai',
      lat: 19.1197,
      lng: 72.8697
    },
    drop: {
      address: 'Bandra Kurla Complex, Bandra East, Mumbai',
      city: 'Mumbai',
      lat: 19.0653,
      lng: 72.8683
    }
  },
  {
    label: 'Bengaluru · Indiranagar → Whitefield ITPL',
    pickup: {
      address: '100 Feet Road, Indiranagar, Bengaluru',
      city: 'Bengaluru',
      lat: 12.9784,
      lng: 77.6408
    },
    drop: {
      address: 'ITPL Main Road, Whitefield, Bengaluru',
      city: 'Bengaluru',
      lat: 12.985,
      lng: 77.7362
    }
  },
  {
    label: 'Expressway · Pune Hinjawadi → Navi Mumbai',
    pickup: {
      address: 'Hinjawadi Rajiv Gandhi Infotech Park, Pune',
      city: 'Pune',
      lat: 18.5913,
      lng: 73.7389
    },
    drop: {
      address: 'Vashi Sector 17, Navi Mumbai',
      city: 'Mumbai',
      lat: 19.0745,
      lng: 72.9978
    }
  }
];

function ensureLeafletLoaded() {
  return typeof window.L !== 'undefined';
}

function createPinIcon(letter, variantClass) {
  return window.L.divIcon({
    className: 'bg-transparent border-0',
    html: `<div class="as-pin ${variantClass}"><span>${letter}</span></div>`,
    iconSize: [30, 30],
    iconAnchor: [15, 30],
    popupAnchor: [0, -28]
  });
}

function createDriverBeaconIcon() {
  return window.L.divIcon({
    className: 'bg-transparent border-0',
    html: '<div class="as-driver-beacon" title="Live Driver GPS"><i class="bi bi-truck"></i></div>',
    iconSize: [38, 38],
    iconAnchor: [19, 19],
    popupAnchor: [0, -18]
  });
}

export class LogisticsMap {
  constructor(containerId, options = {}) {
    this.container = document.getElementById(containerId);
    this.map = null;
    this.pickupMarker = null;
    this.dropMarker = null;
    this.driverMarker = null;
    this.routeShadow = null;
    this.routeLine = null;
    this.driverLine = null;
    this.onMapPick = options.onMapPick || null;

    if (!this.container || !ensureLeafletLoaded()) {
      return;
    }

    this.map = window.L.map(this.container, {
      center: options.center || DELHI_CENTER,
      zoom: options.zoom || 11,
      zoomControl: false
    });

    // Place zoom controls in bottom-right so top-left HUD pills never overlap +/- buttons
    window.L.control.zoom({ position: 'bottomright' }).addTo(this.map);

    // Official OpenStreetMap tiles (100% free, zero API-key watermarks)
    window.L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap contributors'
    }).addTo(this.map);

    if (this.onMapPick) {
      this.map.on('click', (evt) => {
        const lat = Number(evt.latlng.lat.toFixed(5));
        const lng = Number(evt.latlng.lng.toFixed(5));
        this.onMapPick({ lat, lng });
      });
    }
  }

  invalidateSize() {
    if (!this.map) return;
    window.requestAnimationFrame(() => {
      if (this.map) this.map.invalidateSize();
    });
    window.setTimeout(() => {
      if (this.map) this.map.invalidateSize();
    }, 160);
  }

  setRoutePoints(pickup, drop, fit = true) {
    if (!this.map) return;

    if (pickup && Number.isFinite(Number(pickup.lat)) && Number.isFinite(Number(pickup.lng))) {
      const latlng = [Number(pickup.lat), Number(pickup.lng)];
      if (!this.pickupMarker) {
        this.pickupMarker = window.L.marker(latlng, { icon: createPinIcon('P', 'as-pin-pickup') }).addTo(this.map);
      } else {
        this.pickupMarker.setLatLng(latlng);
      }
      if (pickup.label) this.pickupMarker.bindPopup(String(pickup.label));
    }

    if (drop && Number.isFinite(Number(drop.lat)) && Number.isFinite(Number(drop.lng))) {
      const latlng = [Number(drop.lat), Number(drop.lng)];
      if (!this.dropMarker) {
        this.dropMarker = window.L.marker(latlng, { icon: createPinIcon('D', 'as-pin-drop') }).addTo(this.map);
      } else {
        this.dropMarker.setLatLng(latlng);
      }
      if (drop.label) this.dropMarker.bindPopup(String(drop.label));
    }

    if (this.routeShadow) {
      this.map.removeLayer(this.routeShadow);
      this.routeShadow = null;
    }
    if (this.routeLine) {
      this.map.removeLayer(this.routeLine);
      this.routeLine = null;
    }

    if (this.pickupMarker && this.dropMarker) {
      const p = this.pickupMarker.getLatLng();
      const d = this.dropMarker.getLatLng();

      this.routeShadow = window.L.polyline([p, d], {
        color: '#111315',
        weight: 5,
        opacity: 0.22
      }).addTo(this.map);

      this.routeLine = window.L.polyline([p, d], {
        color: '#d9381e',
        weight: 3,
        opacity: 0.95,
        dashArray: '7, 7'
      }).addTo(this.map);

      if (fit) {
        const bounds = window.L.latLngBounds([p, d]);
        if (this.driverMarker) bounds.extend(this.driverMarker.getLatLng());
        this.map.fitBounds(bounds, { padding: [52, 52], maxZoom: 14 });
      }
    }
  }

  setDriverPosition(lat, lng, targetLatLng = null, fit = false) {
    if (!this.map) return;

    if (lat === null || lat === undefined || lng === null || lng === undefined) {
      if (this.driverMarker) {
        this.map.removeLayer(this.driverMarker);
        this.driverMarker = null;
      }
      if (this.driverLine) {
        this.map.removeLayer(this.driverLine);
        this.driverLine = null;
      }
      return;
    }

    const pos = [Number(lat), Number(lng)];
    if (!this.driverMarker) {
      this.driverMarker = window.L.marker(pos, { icon: createDriverBeaconIcon(), zIndexOffset: 600 }).addTo(this.map);
    } else {
      this.driverMarker.setLatLng(pos);
    }

    if (this.driverLine) {
      this.map.removeLayer(this.driverLine);
      this.driverLine = null;
    }

    if (targetLatLng && Number.isFinite(Number(targetLatLng[0])) && Number.isFinite(Number(targetLatLng[1]))) {
      this.driverLine = window.L.polyline([pos, targetLatLng], {
        color: '#0d6e4f',
        weight: 3,
        opacity: 0.85
      }).addTo(this.map);
    }

    if (fit) {
      const pts = [pos];
      if (this.pickupMarker) pts.push(this.pickupMarker.getLatLng());
      if (this.dropMarker) pts.push(this.dropMarker.getLatLng());
      this.map.fitBounds(window.L.latLngBounds(pts), { padding: [52, 52], maxZoom: 14 });
    }
  }
}

export function bindIndiaPlaceAutocomplete(inputElement, onSelect) {
  if (!inputElement) return;

  const wrapper = inputElement.closest('.geocode-wrap') || inputElement.parentElement;
  const menu = el('div', { className: 'geocode-menu d-none', role: 'listbox' });
  wrapper.appendChild(menu);

  let timerId = null;

  function hideMenu() {
    menu.classList.add('d-none');
    clearNode(menu);
  }

  inputElement.addEventListener('input', () => {
    const q = inputElement.value.trim();
    window.clearTimeout(timerId);
    if (q.length < 3) {
      hideMenu();
      return;
    }

    timerId = window.setTimeout(async () => {
      try {
        const places = await api.get(`/maps/geocode?q=${encodeURIComponent(q)}&limit=5`, false);
        clearNode(menu);
        if (!Array.isArray(places) || places.length === 0) {
          hideMenu();
          return;
        }

        places.forEach((place) => {
          const item = el(
            'button',
            {
              type: 'button',
              className: 'geocode-item',
              role: 'option',
              onClick: () => {
                inputElement.value = place.displayName;
                hideMenu();
                onSelect(place);
              }
            },
            [
              el('div', { className: 'fw-semibold text-truncate', text: place.displayName }),
              el('div', {
                className: 'font-mono text-secondary',
                style: 'font-size:0.74rem;',
                text: `${place.city || 'India'} · ${Number(place.lat).toFixed(4)}, ${Number(place.lng).toFixed(4)}`
              })
            ]
          );
          menu.appendChild(item);
        });
        menu.classList.remove('d-none');
      } catch {
        hideMenu();
      }
    }, 320);
  });

  document.addEventListener('click', (evt) => {
    if (!wrapper.contains(evt.target)) hideMenu();
  });
}

const KNOWN_INDIAN_CITIES = [
  ['new delhi', 'Delhi'],
  ['delhi', 'Delhi'],
  ['gurugram', 'Gurugram'],
  ['gurgaon', 'Gurugram'],
  ['noida', 'Noida'],
  ['faridabad', 'Faridabad'],
  ['ghaziabad', 'Ghaziabad'],
  ['navi mumbai', 'Mumbai'],
  ['mumbai', 'Mumbai'],
  ['thane', 'Mumbai'],
  ['pune', 'Pune'],
  ['bengaluru', 'Bengaluru'],
  ['bangalore', 'Bengaluru'],
  ['hyderabad', 'Hyderabad'],
  ['chennai', 'Chennai'],
  ['kolkata', 'Kolkata'],
  ['ahmedabad', 'Ahmedabad'],
  ['jaipur', 'Jaipur'],
  ['lucknow', 'Lucknow'],
  ['surat', 'Surat'],
  ['indore', 'Indore'],
  ['chandigarh', 'Chandigarh']
];

export function extractCityFromAddress(addressText, fallback = 'Delhi') {
  const raw = String(addressText || '').trim();
  if (!raw) return fallback;
  const lower = raw.toLowerCase();
  for (const [needle, canonical] of KNOWN_INDIAN_CITIES) {
    if (lower.includes(needle)) return canonical;
  }
  const parts = raw
    .split(',')
    .map((s) => s.replace(/\b\d{6}\b/g, '').trim())
    .filter((s) => s.length >= 3);
  return parts.length >= 2 ? parts[parts.length - 1] : fallback;
}

