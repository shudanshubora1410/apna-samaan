/**
 * Apna Samaan — Commercial Fleet Visuals & Metadata (`fleet-art.js`)
 * Combines studio-grade Indian commercial vehicle photography (`assets/fleet/*.jpg`)
 * for hero/feature cards with crisp compact SVG icons for dense dashboard rows.
 */

export const FLEET_PHOTOS = Object.freeze({
  BIKE: 'assets/fleet/bike.jpg',
  CARGO_AUTO: 'assets/fleet/cargo-auto.jpg',
  MINI_TRUCK: 'assets/fleet/mini-truck.jpg',
  TRUCK: 'assets/fleet/truck.jpg'
});

export const FLEET_TAGLINES = Object.freeze({
  BIKE: 'Urgent documents, machine spares & retail parcels across city traffic.',
  CARGO_AUTO: '3-wheeler commercial loader for wholesale cartons, appliances & shop restocking.',
  MINI_TRUCK: '8ft commercial pickup for warehouse crates, furniture & multi-stop distribution.',
  TRUCK: '14ft enclosed container truck for palletized B2B freight & inter-city highway loads.'
});

export const FLEET_SCALE_BADGES = Object.freeze({
  BIKE: '2-Wheeler Parcel Box · Up to 20 kg',
  CARGO_AUTO: '3-Wheeler Loader · Up to 500 kg · 5.5ft Deck',
  MINI_TRUCK: '8ft Pickup Truck · Up to 1,000 kg',
  TRUCK: '14ft Enclosed Container · Up to 3,500 kg'
});

export const FLEET_CAPACITY_PCT = Object.freeze({
  BIKE: 14,
  CARGO_AUTO: 38,
  MINI_TRUCK: 66,
  TRUCK: 100
});

export const FLEET_EARNINGS = Object.freeze({
  BIKE: '₹18k – ₹28k/mo',
  CARGO_AUTO: '₹30k – ₹45k/mo',
  MINI_TRUCK: '₹42k – ₹62k/mo',
  TRUCK: '₹60k – ₹88k/mo'
});

const FLEET_SVGS = {
  BIKE: `
    <svg viewBox="0 0 320 140" fill="none" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="2-Wheeler Parcel Bike">
      <ellipse cx="160" cy="126" rx="112" ry="6" fill="#0F172A" opacity="0.10"/>
      <rect x="64" y="30" width="60" height="46" rx="6" fill="#0F172A" stroke="#0F172A" stroke-width="2.5"/>
      <rect x="64" y="49" width="60" height="8" fill="#D9381E"/>
      <rect x="74" y="38" width="24" height="5" rx="2" fill="#FFFFFF" opacity="0.85"/>
      <path d="M68 76H126L136 84H92L68 76Z" fill="#334155"/>
      <path d="M122 72C136 72 148 76 158 76H172L166 85H120L122 72Z" fill="#1E293B"/>
      <path d="M168 64C184 58 204 60 214 70L198 82H166L168 64Z" fill="#D9381E" stroke="#0F172A" stroke-width="2.2"/>
      <rect x="148" y="84" width="36" height="22" rx="4" fill="#475569" stroke="#0F172A" stroke-width="2"/>
      <path d="M204 46L232 104" stroke="#0F172A" stroke-width="4.5" stroke-linecap="round"/>
      <path d="M210 52L224 55C227 56 228 62 224 65L212 66L210 52Z" fill="#0F172A"/>
      <circle cx="96" cy="105" r="20" fill="#0F172A"/>
      <circle cx="96" cy="105" r="11" fill="#F8FAFC"/>
      <circle cx="232" cy="105" r="20" fill="#0F172A"/>
      <circle cx="232" cy="105" r="11" fill="#F8FAFC"/>
    </svg>
  `,
  CARGO_AUTO: `
    <svg viewBox="0 0 320 140" fill="none" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="3-Wheeler Cargo Auto Loader">
      <ellipse cx="162" cy="127" rx="126" ry="6" fill="#0F172A" opacity="0.11"/>
      <rect x="42" y="34" width="136" height="62" rx="4" fill="#FFFFFF" stroke="#0F172A" stroke-width="2.5"/>
      <line x1="76" y1="36" x2="76" y2="94" stroke="#CBD5E1" stroke-width="2.5"/>
      <line x1="110" y1="36" x2="110" y2="94" stroke="#CBD5E1" stroke-width="2.5"/>
      <line x1="144" y1="36" x2="144" y2="94" stroke="#CBD5E1" stroke-width="2.5"/>
      <rect x="43" y="82" width="134" height="7" fill="#D9381E"/>
      <path d="M178 30H228C236 30 242 35 247 45L264 78V96H178V30Z" fill="#F59E0B" stroke="#0F172A" stroke-width="2.5"/>
      <path d="M188 38H225L241 68H188V38Z" fill="#E0F2FE" stroke="#0F172A" stroke-width="2"/>
      <rect x="38" y="96" width="228" height="9" rx="3" fill="#0F172A"/>
      <circle cx="90" cy="110" r="18" fill="#0F172A"/>
      <circle cx="90" cy="110" r="9" fill="#F8FAFC"/>
      <circle cx="248" cy="110" r="18" fill="#0F172A"/>
      <circle cx="248" cy="110" r="9" fill="#F8FAFC"/>
    </svg>
  `,
  MINI_TRUCK: `
    <svg viewBox="0 0 320 140" fill="none" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="8ft Commercial Mini Truck">
      <ellipse cx="160" cy="127" rx="134" ry="6" fill="#0F172A" opacity="0.11"/>
      <rect x="28" y="26" width="166" height="68" rx="5" fill="#FFFFFF" stroke="#0F172A" stroke-width="2.5"/>
      <line x1="68" y1="28" x2="68" y2="92" stroke="#CBD5E1" stroke-width="2.2"/>
      <line x1="110" y1="28" x2="110" y2="92" stroke="#CBD5E1" stroke-width="2.2"/>
      <line x1="152" y1="28" x2="152" y2="92" stroke="#CBD5E1" stroke-width="2.2"/>
      <rect x="29" y="81" width="164" height="7" fill="#D9381E"/>
      <path d="M194 40H248C257 40 264 45 270 56L284 82V94H194V40Z" fill="#F8FAFC" stroke="#0F172A" stroke-width="2.5"/>
      <path d="M206 48H247L261 73H206V48Z" fill="#E0F2FE" stroke="#0F172A" stroke-width="2"/>
      <rect x="24" y="94" width="264" height="8" rx="3" fill="#0F172A"/>
      <circle cx="80" cy="109" r="19" fill="#0F172A"/>
      <circle cx="80" cy="109" r="9" fill="#F8FAFC"/>
      <circle cx="250" cy="109" r="19" fill="#0F172A"/>
      <circle cx="250" cy="109" r="9" fill="#F8FAFC"/>
    </svg>
  `,
  TRUCK: `
    <svg viewBox="0 0 320 140" fill="none" xmlns="http://www.w3.org/2000/svg" role="img" aria-label="14ft Commercial Container Truck">
      <ellipse cx="162" cy="128" rx="142" ry="6" fill="#0F172A" opacity="0.12"/>
      <rect x="16" y="16" width="198" height="78" rx="5" fill="#FFFFFF" stroke="#0F172A" stroke-width="2.6"/>
      <line x1="56" y1="18" x2="56" y2="92" stroke="#CBD5E1" stroke-width="2"/>
      <line x1="96" y1="18" x2="96" y2="92" stroke="#CBD5E1" stroke-width="2"/>
      <line x1="136" y1="18" x2="136" y2="92" stroke="#CBD5E1" stroke-width="2"/>
      <line x1="176" y1="18" x2="176" y2="92" stroke="#CBD5E1" stroke-width="2"/>
      <rect x="17" y="81" width="196" height="8" fill="#D9381E"/>
      <path d="M214 34H272C281 34 287 40 292 52L302 78V94H214V34Z" fill="#1E3A8A" stroke="#0F172A" stroke-width="2.5"/>
      <path d="M226 43H272L284 70H226V43Z" fill="#E0F2FE" stroke="#0F172A" stroke-width="2"/>
      <rect x="14" y="94" width="290" height="8" rx="2" fill="#0F172A"/>
      <circle cx="58" cy="109" r="19" fill="#0F172A"/>
      <circle cx="58" cy="109" r="9" fill="#F8FAFC"/>
      <circle cx="102" cy="109" r="19" fill="#0F172A"/>
      <circle cx="102" cy="109" r="9" fill="#F8FAFC"/>
      <circle cx="264" cy="109" r="19" fill="#0F172A"/>
      <circle cx="264" cy="109" r="9" fill="#F8FAFC"/>
    </svg>
  `
};

/**
 * Creates a realistic photographic fleet showcase element with overlay badge.
 */
export function createFleetPhotoNode(code, altLabel = 'Commercial logistics vehicle') {
  const wrap = document.createElement('div');
  wrap.className = 'fleet-photo-frame';

  const img = document.createElement('img');
  img.src = FLEET_PHOTOS[code] || FLEET_PHOTOS.CARGO_AUTO;
  img.alt = altLabel;
  img.loading = 'eager';
  img.decoding = 'async';
  img.className = 'fleet-photo-img';

  const badge = document.createElement('span');
  badge.className = 'fleet-photo-caption';
  badge.textContent = FLEET_SCALE_BADGES[code] || 'Verified Commercial Fleet';

  wrap.append(img, badge);
  return wrap;
}

/**
 * Compact SVG thumbnail for dense list rows and small previews.
 */
export function createFleetSvgNode(code, className = 'fleet-illustration') {
  const wrap = document.createElement('div');
  wrap.className = className;
  wrap.setAttribute('aria-hidden', 'true');
  const safeSvg = FLEET_SVGS[code] || FLEET_SVGS.MINI_TRUCK;
  const parsed = new DOMParser().parseFromString(safeSvg.trim(), 'image/svg+xml');
  if (parsed.documentElement) {
    wrap.appendChild(document.importNode(parsed.documentElement, true));
  }
  return wrap;
}
