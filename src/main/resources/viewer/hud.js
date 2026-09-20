// The hero's bar as the client lays it out — HeroPanel's blocks, in the order and at the sizes the game's PanelLook
// gives, in its colours — with every Skin laid on its edges as the client lays them: the picture cut into nine, the
// corners kept at their size and the edges stretched along their length, times the tint. A stand-in for the bar
// rather than the bar: the arrangement, the palette and the borders are the game's; the words and figures on it
// are an example.

const canvas = document.getElementById('bar');
const view = document.getElementById('view');
const status = document.getElementById('status');
const ctx = canvas.getContext('2d');

/** A path from the resource root, as the IDE serves it. */
const res = path => '../res/' + path.split('/').map(encodeURIComponent).join('/');

// HeroPanel's own measures, in the pixels the design was drawn at.
const PAD = 10, BAND = 172, SLAB_HEIGHT = BAND + PAD * 2, MINIMAP = BAND, DIVIDER = 3, DIVIDER_MARGIN = 10;
const PORTRAIT = 126, PORTRAIT_HEIGHT = 128, PORTRAIT_COLUMN = 150, PORTRAIT_GAP = 10, VITALS_WIDTH = 424;
const ORDER_BUTTON = 38, ORDER_GAP = 5, ORDER_COLUMN_GAP = 7, ITEM_GAP = 5, HEADING_SIZE = 12, HEADING_GAP = 6;
const BAR_HEIGHT = 17, MANA_HEIGHT = 14, XP_HEIGHT = 30, SLOT_GAP = 9, DEPTH_WIDTH = 96;
const PIP_WIDTH = 11, PIP_HEIGHT = 5, PIP_GAP = 3, PIP_MARGIN = 4, RANK_TEXT = 11;
const UNDER_A_SLOT = PIP_MARGIN + PIP_HEIGHT + PIP_MARGIN + RANK_TEXT;
/** The skins a banner is drawn with; the rest are the bar's. */
const BANNERS = ['Banner', 'BannerWon', 'BannerLost'];

let scene = null;
let look = null;
let drawn = [];       // every frame laid this pass: { part, x, y, w, h } in canvas pixels
let problems = new Set();
const pictures = new Map();  // path → HTMLImageElement, or null once it failed
const tinted = new Map();    // path|tint → canvas

// ---- the page ----

function showHud(next, colours) {
  if (colours) for (const [name, value] of Object.entries(colours)) document.documentElement.style.setProperty('--' + name, value);
  scene = next;
  look = lookOf(next.look);
  problems = new Set();
  draw();
}

/** The look as numbers: the file writes 0x4A4033 and 1.3, and a list of words for the blocks. */
function lookOf(written) {
  const out = {};
  for (const [key, value] of Object.entries(written ?? {})) {
    out[key] = Array.isArray(value) ? value.map(item => numberOr(item)) : numberOr(value);
  }
  out.blocks = (written?.blocks ?? []).map(word => String(word).toUpperCase());
  return out;
}

function numberOr(text) {
  const number = Number(text);
  return text !== '' && text != null && !Number.isNaN(number) ? number : text;
}

const hex = value => '#' + (Number(value) >>> 0 & 0xFFFFFF).toString(16).padStart(6, '0');

new ResizeObserver(() => draw()).observe(view);

// ---- the pictures, tinted as the client tints them ----

function picture(path) {
  if (!path) return null;
  if (!pictures.has(path)) {
    const image = new Image();
    pictures.set(path, image);
    image.onload = () => draw();
    image.onerror = () => {
      pictures.set(path, null);
      problems.add(`no picture ${path}`);
      draw();
    };
    image.src = res(path);
  }
  const image = pictures.get(path);
  return image && image.complete && image.naturalWidth > 0 ? image : null;
}

/** The picture times the tint, its own alpha kept: a white drawing comes out in the tint's colour. */
function tintedPicture(path, tint) {
  const image = picture(path);
  if (!image) return null;
  const key = path + '|' + tint;
  if (!tinted.has(key)) {
    const out = document.createElement('canvas');
    out.width = image.naturalWidth;
    out.height = image.naturalHeight;
    const c = out.getContext('2d');
    c.drawImage(image, 0, 0);
    c.globalCompositeOperation = 'multiply';
    c.fillStyle = hex(tint);
    c.fillRect(0, 0, out.width, out.height);
    c.globalCompositeOperation = 'destination-in';
    c.drawImage(image, 0, 0);
    tinted.set(key, out);
  }
  return tinted.get(key);
}

const skinOf = part => scene?.skins?.find(skin => skin.name === part) ?? null;

// ---- drawing, in the design's pixels with y up from the foot of the band, as HeroPanel places things ----

let origin = { x: 0, y: 0 };  // where the block being drawn stands, in design pixels from the foot of the window
let unit = 1;                  // canvas pixels a design pixel
let foot = 0;                  // the canvas y of the window's bottom edge
let leftEdge = 0;              // and the canvas x of its left one

const X = x => leftEdge + (origin.x + x) * unit;
const Y = (y, h = 0) => foot - (origin.y + y + h) * unit;

function box(x, y, w, h, colour) {
  ctx.fillStyle = colour;
  ctx.fillRect(X(x), Y(y, h), w * unit, h * unit);
}

function gradient(x, y, w, h, top, bottom) {
  const fill = ctx.createLinearGradient(0, Y(y, h), 0, Y(y));
  fill.addColorStop(0, top);
  fill.addColorStop(1, bottom);
  ctx.fillStyle = fill;
  ctx.fillRect(X(x), Y(y, h), w * unit, h * unit);
}

function words(text, size, colour, x, y, width, align = 'center') {
  if (!text) return;
  ctx.font = `${Math.max(1, size * unit)}px system-ui, sans-serif`;
  ctx.fillStyle = colour;
  ctx.textAlign = align;
  ctx.textBaseline = 'bottom';
  const at = align === 'center' ? x + width / 2 : align === 'right' ? x + width : x;
  ctx.fillText(text, X(at), Y(y));
}

/**
 * A part's frame, cut into nine as NineSlice cuts it: corners kept at the inset times the scale — less where the
 * frame is too small for them — the four edges stretched along their length, and the middle to fill.
 */
function frame(part, x, y, w, h) {
  const skin = skinOf(part);
  if (!skin) return;
  const image = tintedPicture(skin.texture, skin.tint ?? 0xFFFFFF);
  if (!image) return;
  const inset = Number(skin.inset) || 0;
  const border = Math.min(Math.max(0, inset * (Number(skin.scale) || 1)), Math.min(w, h) / 2);
  const tw = image.width, th = image.height;
  const sx = [0, inset, tw - inset, tw], sy = [0, inset, th - inset, th];
  const dx = [X(x), X(x + border), X(x + w - border), X(x + w)];
  const dy = [Y(y, h), Y(y, h - border), Y(y, border), Y(y)];
  for (let row = 0; row < 3; row++) {
    for (let column = 0; column < 3; column++) {
      const sw = sx[column + 1] - sx[column], sh = sy[row + 1] - sy[row];
      const width = dx[column + 1] - dx[column], height = dy[row + 1] - dy[row];
      if (sw > 0 && sh > 0 && width > 0 && height > 0) ctx.drawImage(image, sx[column], sy[row], sw, sh, dx[column], dy[row], width, height);
    }
  }
  drawn.push({ part, x: dx[0], y: dy[0], w: dx[3] - dx[0], h: dy[3] - dy[0] });
}

/** The divider: the picture stood on end — a quarter turn — at the foot of the band, and again hanging from its top. */
function divider(x) {
  const skin = skinOf('Divider');
  const image = skin ? tintedPicture(skin.texture, skin.tint ?? 0xFFFFFF) : null;
  box(x, 0, DIVIDER, BAND, hex(look.dropColour));
  if (!image) {
    box(x + DIVIDER, 0, 1, BAND, 'rgba(255,255,255,0.05)');
    return;
  }
  const scale = Number(skin.scale) || 1;
  const length = image.width * scale, thickness = image.height * scale;
  const across = (DIVIDER - thickness) / 2;
  for (const hanging of [false, true]) {
    ctx.save();
    // Standing: the picture's left end at the top, its bottom row to the left — a quarter turn clockwise.
    const left = X(x + across), top = hanging ? Y(BAND) : Y(0, length);
    ctx.translate(left + thickness * unit, top);
    if (hanging) {
      ctx.translate(0, length * unit);
      ctx.scale(1, -1);
    }
    ctx.rotate(Math.PI / 2);
    ctx.drawImage(image, 0, 0, length * unit, thickness * unit);
    ctx.restore();
  }
  drawn.push({ part: 'Divider', x: X(x + across), y: Y(BAND), w: thickness * unit, h: BAND * unit });
}

// ---- the blocks ----

/** Each block's width, as layOut adds them up: the order buttons beside the map, four sockets of skills. */
function widthOf(block) {
  switch (block) {
    case 'MINIMAP': return MINIMAP + ORDER_COLUMN_GAP + ORDER_BUTTON;
    case 'HERO': return PORTRAIT_COLUMN + PORTRAIT_GAP + VITALS_WIDTH;
    case 'BAG': return look.itemColumns * itemSlot() + (look.itemColumns - 1) * ITEM_GAP;
    case 'SKILLS': return skillSlot() * 3 + ultimateSlot() + SLOT_GAP * 3;
    case 'DEPTH': return DEPTH_WIDTH;
    default: return 0;
  }
}

// As big as asked, as far as the band holds them — the client's own clamp.
const itemSlot = () => Math.min(look.itemSlot, (BAND - HEADING_SIZE - HEADING_GAP - (look.itemRows - 1) * ITEM_GAP) / look.itemRows);
const skillSlot = () => Math.min(look.skillSlot, BAND - HEADING_SIZE - HEADING_GAP - UNDER_A_SLOT);
const ultimateSlot = () => Math.min(look.ultimateSlot, BAND - HEADING_SIZE - HEADING_GAP - UNDER_A_SLOT);

function drawBlock(block) {
  switch (block) {
    case 'MINIMAP': return drawMinimap();
    case 'HERO': return drawHero();
    case 'BAG': return drawBag();
    case 'SKILLS': return drawSkills();
    case 'DEPTH': return drawDepth();
  }
}

function drawMinimap() {
  box(-2, -2, MINIMAP + 4, MINIMAP + 4, hex(look.dropColour));
  box(-1, -1, MINIMAP + 2, MINIMAP + 2, hex(look.socketRimColour));
  box(0, 0, MINIMAP, MINIMAP, hex(look.holeColour));
  // A few rooms, dim, for a map to be in the socket.
  for (const [x, y, w, h] of [[18, 96, 44, 40], [70, 110, 30, 16], [104, 70, 50, 56], [40, 30, 36, 50], [76, 44, 28, 10]]) {
    box(x, y, w, h, 'rgba(120, 110, 90, 0.35)');
  }
  frame('Minimap', -3, -3, MINIMAP + 6, MINIMAP + 6);
  const x = MINIMAP + ORDER_COLUMN_GAP;
  const count = 4;
  const height = count * ORDER_BUTTON + (count - 1) * ORDER_GAP;
  const top = (BAND + height) / 2;
  const icons = [scene.hud?.cmdMoveIcon, scene.hud?.cmdAttackIcon, scene.hud?.cmdStopIcon, scene.hud?.cmdGuardIcon];
  for (let i = 0; i < count; i++) {
    const y = top - (i + 1) * ORDER_BUTTON - i * ORDER_GAP;
    button(x, y, icons[i], i === 0);
  }
}

function button(x, y, icon, lit) {
  const size = ORDER_BUTTON;
  box(x - 1.5, y - 3.5, size + 3, size + 3, hex(look.dropColour));
  if (lit) box(x - 3, y - 3, size + 6, size + 6, hex(look.torchColour));
  box(x - 1.5, y - 1.5, size + 3, size + 3, hex(look.edgeColour));
  gradient(x, y, size, size, hex(look.stoneLitColour), hex(look.stoneColour));
  glyph(icon, x, y, size, 0.68, look.boneColour);
  frame('Button', x - 1.5, y - 1.5, size + 3, size + 3);
}

/** A game's picture in a square, a share of it wide, white drawings taking the colour. */
function glyph(path, x, y, size, share, colour) {
  const image = path ? tintedPicture(path, colour) : null;
  if (!image) return;
  const side = size * share;
  ctx.drawImage(image, X(x + (size - side) / 2), Y(y + (size - side) / 2, side), side * unit, side * unit);
}

function drawHero() {
  // The portrait hangs from the top of the band, centred in its column.
  const px = (PORTRAIT_COLUMN - PORTRAIT) / 2, py = BAND - PORTRAIT_HEIGHT;
  box(px - 2, py - 2, PORTRAIT + 4, PORTRAIT_HEIGHT + 4, hex(look.dropColour));
  gradient(px, py, PORTRAIT, PORTRAIT_HEIGHT, hex(look.portraitTopColour), hex(look.portraitBottomColour));
  box(px - 1, py + PORTRAIT_HEIGHT - 1, PORTRAIT + 2, 2, hex(look.portraitRimColour));
  // A head and shoulders, where the client puts the live picture.
  ctx.fillStyle = hex(look.fleshColour);
  ctx.beginPath();
  ctx.ellipse(X(px + PORTRAIT / 2), Y(py + 78), 22 * unit, 26 * unit, 0, 0, Math.PI * 2);
  ctx.fill();
  ctx.fillRect(X(px + 22), Y(py + 4, 40), (PORTRAIT - 44) * unit, 40 * unit);
  frame('Portrait', px - 3, py - 3, PORTRAIT + 6, PORTRAIT_HEIGHT + 6);

  const healthY = BAND - PORTRAIT_HEIGHT - 6 - BAR_HEIGHT;
  const manaY = healthY - 3 - MANA_HEIGHT;
  gauge(0, healthY, PORTRAIT_COLUMN, BAR_HEIGHT, look.bloodColour, 0.64, '128 / 200', 12);
  gauge(0, manaY, PORTRAIT_COLUMN, MANA_HEIGHT, look.manaColour, 0.8, '40 / 50', 10);

  const vx = PORTRAIT_COLUMN + PORTRAIT_GAP;
  const saved = origin;
  origin = { x: origin.x + vx, y: origin.y };
  words('Erika', 20, hex(look.boneColour), 0, 136, VITALS_WIDTH);
  words(spaced(scene.hud?.heroTitle || 'O’Q USTASI'), 13, hex(look.goldColour), 0, 118, VITALS_WIDTH);
  const xpY = 76;
  gauge(0, xpY, VITALS_WIDTH, XP_HEIGHT, look.arcaneColour, 0.38, '38 / 100', 13);
  words('7', 15, hex(look.boneColour), 10, xpY + 7, 40, 'left');
  const stats = [[scene.hud?.attackIcon, scene.hud?.attackWord || 'Zarba', '34'], [scene.hud?.armourIcon, scene.hud?.armourWord || 'Zirh', '12'],
    [scene.hud?.speedIcon, scene.hud?.speedWord || 'Tezlik', '52']];
  stats.forEach(([icon, word, value], i) => {
    const y = xpY - 26 - i * 22;
    box(0, y, 18, 18, hex(look.stoneDeepColour));
    glyph(icon, 0, y, 18, 0.9, look.goldColour);
    words(word, 12, hex(look.labelColour), 24, y + 3, 80, 'left');
    words(value, 12, hex(look.boneColour), 90, y + 3, 40, 'left');
  });
  origin = saved;
}

function gauge(x, y, w, h, colour, share, text, size) {
  box(x, y, w, h, hex(look.stoneDeepColour));
  gradient(x + 1, y + 1, (w - 2) * share, h - 2, hex(colour), shade(colour, 0.55));
  words(text, size, hex(look.boneColour), x, y + (h - size) / 2, w - (w > 200 ? 11 : 0), w > 200 ? 'right' : 'center');
  frame('Gauge', x - 1, y - 1, w + 2, h + 2);
}

const shade = (colour, by) => {
  const value = Number(colour);
  const channel = shift => Math.round(((value >> shift) & 0xFF) * by);
  return `rgb(${channel(16)}, ${channel(8)}, ${channel(0)})`;
};

const spaced = text => [...String(text).toUpperCase()].join(' ');

function heading(text, width) {
  const wash = ctx.createLinearGradient(X(0), 0, X(width), 0);
  const gold = hex(look.goldColour);
  wash.addColorStop(0, gold + '00');
  wash.addColorStop(0.25, gold + '29');
  wash.addColorStop(0.75, gold + '29');
  wash.addColorStop(1, gold + '00');
  ctx.fillStyle = wash;
  ctx.fillRect(X(0), Y(-3, HEADING_SIZE + 5), width * unit, (HEADING_SIZE + 5) * unit);
  words(text, HEADING_SIZE, gold, 0, 0, width);
}

function drawBag() {
  const slot = itemSlot();
  const columns = look.itemColumns, rows = look.itemRows;
  const bagHeight = rows * slot + (rows - 1) * ITEM_GAP;
  const bottom = (BAND - bagHeight - HEADING_SIZE - HEADING_GAP) / 2;
  const saved = origin;
  origin = { x: origin.x, y: origin.y + bottom + bagHeight + HEADING_GAP };
  heading(scene.hud?.itemsWord || 'ITEMS', widthOf('BAG'));
  origin = { x: saved.x, y: saved.y + bottom };
  const colours = look.itemColours?.length ? look.itemColours : [0xC4564A];
  for (let i = 0; i < columns * rows; i++) {
    const x = (i % columns) * (slot + ITEM_GAP);
    const y = (rows - 1 - Math.floor(i / columns)) * (slot + ITEM_GAP);
    box(x - 1.5, y - 3.5, slot + 3, slot + 3, hex(look.dropColour));
    box(x - 1.5, y - 1.5, slot + 3, slot + 3, hex(look.edgeColour));
    gradient(x, y, slot, slot, hex(look.itemTopColour), hex(look.itemBottomColour));
    words(String(i + 1), 10, hex(look.itemNumberColour), x + 2, y + slot - 13, slot, 'left');
    if (i < 3) {
      // Something carried, in its socket's colour.
      ctx.strokeStyle = hex(colours[i % colours.length]);
      ctx.lineWidth = Math.max(1, 2 * unit);
      ctx.beginPath();
      ctx.arc(X(x + slot / 2), Y(y + slot / 2), slot * 0.22 * unit, 0, Math.PI * 2);
      ctx.stroke();
    } else {
      ctx.strokeStyle = hex(look.stoneLitColour);
      ctx.setLineDash([2 * unit, 2 * unit]);
      ctx.lineWidth = Math.max(1, unit);
      ctx.strokeRect(X(x + 7), Y(y + 7, slot - 14), (slot - 14) * unit, (slot - 14) * unit);
      ctx.setLineDash([]);
    }
    frame('Item', x - 1.5, y - 1.5, slot + 3, slot + 3);
  }
  origin = saved;
}

function drawSkills() {
  const sizes = [skillSlot(), skillSlot(), skillSlot(), ultimateSlot()];
  const tallest = Math.max(...sizes);
  const columnHeight = HEADING_SIZE + HEADING_GAP + tallest + UNDER_A_SLOT;
  const rowY = (BAND - columnHeight) / 2 + UNDER_A_SLOT;
  const saved = origin;
  origin = { x: saved.x, y: saved.y + rowY + tallest + HEADING_GAP };
  heading(scene.hud?.skillsWord || 'SKILLS', widthOf('SKILLS'));
  origin = { x: saved.x, y: saved.y + rowY };
  let x = 0;
  sizes.forEach((size, i) => {
    const y = (tallest - size) / 2;
    box(x - 2, y - 2 - 3, size + 4, size + 4, hex(look.dropColour));
    box(x - 2, y - 2, size + 4, size + 4, hex(look.edgeColour));
    const locked = i === 3;
    gradient(x, y, size, size, hex(locked ? look.stoneDeadLitColour : look.stoneLitColour), hex(locked ? look.stoneDeadColour : look.stoneColour));
    // A drawing where the skill's picture goes, in the colour its state is drawn in.
    ctx.strokeStyle = hex(locked ? look.deadColour : i === 1 ? look.glyphColdColour : look.torchColour);
    ctx.lineWidth = Math.max(1, 2.5 * unit);
    ctx.beginPath();
    ctx.moveTo(X(x + size * 0.3), Y(y + size * 0.3));
    ctx.lineTo(X(x + size * 0.7), Y(y + size * 0.7));
    ctx.moveTo(X(x + size * 0.3), Y(y + size * 0.7));
    ctx.lineTo(X(x + size * 0.7), Y(y + size * 0.3));
    ctx.stroke();
    if (i === 1) {
      // Reloading: the dark sweep over what is still to come back.
      ctx.fillStyle = 'rgba(8, 6, 5, 0.82)';
      ctx.beginPath();
      ctx.moveTo(X(x + size / 2), Y(y + size / 2));
      ctx.arc(X(x + size / 2), Y(y + size / 2), size * 0.72 * unit, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * 0.56);
      ctx.closePath();
      ctx.save();
      ctx.beginPath();
      ctx.rect(X(x), Y(y, size), size * unit, size * unit);
      ctx.clip();
      ctx.fill();
      ctx.restore();
    }
    frame('Slot', x - 2, y - 2, size + 4, size + 4);
    // The pips under it: one per rank, lit up to what is in it.
    const pips = 4, lit = [2, 1, 3, 0][i];
    const across = pips * PIP_WIDTH + (pips - 1) * PIP_GAP;
    for (let p = 0; p < pips; p++) {
      box(x + (size - across) / 2 + p * (PIP_WIDTH + PIP_GAP), y - PIP_MARGIN - PIP_HEIGHT, PIP_WIDTH, PIP_HEIGHT,
        hex(p < lit ? look.goldColour : look.pipDarkColour));
    }
    x += size + SLOT_GAP;
  });
  origin = saved;
}

function drawDepth() {
  words(scene.hud?.depthWord || 'DEPTH', 11, hex(look.labelColour), 0, BAND / 2 - 20, DEPTH_WIDTH);
  words('III', 34, hex(look.torchColour), 0, BAND / 2 - 8, DEPTH_WIDTH);
}

/** The plaque a banner is written on, over the middle of the bar: what a Banner skin is laid on. */
function drawBanner(part, width) {
  const w = Math.min(width / unit * 0.6, 520), h = 90;
  const x = (width / unit - w) / 2;
  const saved = origin;
  origin = { x: x - barLeft / unit, y: SLAB_HEIGHT + 30 };
  gradient(0, 0, w, h, hex(look.slabTopColour), hex(look.slabLowColour));
  words(part === 'BannerLost' ? '†' : part === 'BannerWon' ? '★' : scene.hud?.depthWord || 'DEPTH', 30,
    hex(look.torchColour), 0, h / 2 - 12, w);
  frame(part, 0, 0, w, h);
  origin = saved;
}

// ---- the whole of it ----

function draw() {
  if (!scene || !look) return;
  const dpr = window.devicePixelRatio || 1;
  const width = Math.max(1, view.clientWidth), height = Math.max(1, view.clientHeight);
  canvas.width = Math.round(width * dpr);
  canvas.height = Math.round(height * dpr);
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, width, height);
  drawn = [];

  const blocks = look.blocks.filter(block => widthOf(block) > 0);
  const places = (look.places ?? []).map(placeOf).filter(place => place && widthOf(place.block) > 0);
  const gap = DIVIDER + DIVIDER_MARGIN * 2;
  const total = gap * Math.max(0, blocks.length - 1) + blocks.reduce((sum, block) => sum + widthOf(block), 0);
  const banner = BANNERS.includes(scene.focus);

  // A bar on its own is drawn as wide as the page will take it; blocks hung from the window's corners need the
  // window, so the whole screen is drawn instead, at the size the bar was designed for.
  let screenWide;
  let screenTall;
  if (places.length) {
    screenWide = look.designWidth;
    screenTall = Math.round(look.designWidth * 9 / 16);
    unit = Math.min(width / screenWide, height / screenTall);
  } else {
    // HeroPanel.scaleFor: legible for the window, never so large it runs off it — then what the page has room for.
    const tall = SLAB_HEIGHT + (banner ? 150 : 0);
    unit = Math.min(Math.min(Math.max(width / look.designWidth, look.minScale), look.maxScale),
      (width - PAD * 2) / Math.max(1, total), (height - 8) / tall);
    screenWide = width / unit;
    screenTall = tall;
  }
  leftEdge = Math.max(0, (width - screenWide * unit) / 2);
  foot = height - Math.max(0, (height - screenTall * unit) / 2);

  if (places.length) {
    // The world the HUD is over, so a block hung in a corner is seen to be in one.
    ctx.fillStyle = '#0c0b0a';
    ctx.fillRect(leftEdge, foot - screenTall * unit, screenWide * unit, screenTall * unit);
  }
  if (blocks.length) {
    // The slab spans the window, the lit rim along its top.
    const slab = ctx.createLinearGradient(0, foot - SLAB_HEIGHT * unit, 0, foot);
    [look.slabTopColour, look.slabHighColour, look.slabMidColour, look.slabLowColour]
      .forEach((colour, i) => slab.addColorStop(i / 3, hex(colour)));
    ctx.fillStyle = slab;
    ctx.fillRect(leftEdge, foot - SLAB_HEIGHT * unit, screenWide * unit, SLAB_HEIGHT * unit);
    ctx.fillStyle = hex(look.slabRimColour);
    ctx.fillRect(leftEdge, foot - SLAB_HEIGHT * unit, screenWide * unit, 3 * unit);
  }

  const barAt = Math.max(PAD, (screenWide - total) / 2);
  let x = 0;
  blocks.forEach((block, i) => {
    if (i > 0) {
      x += DIVIDER_MARGIN;
      origin = { x: barAt + x, y: PAD };
      divider(0);
      x += DIVIDER + DIVIDER_MARGIN;
    }
    origin = { x: barAt + x, y: PAD };
    drawBlock(block);
    x += widthOf(block);
  });
  for (const place of places) {
    const wide = widthOf(place.block);
    const tall = heightOf(place.block);
    const at = {
      x: place.anchor.endsWith('LEFT') || place.anchor === 'LEFT' ? PAD + place.x
        : place.anchor === 'TOP' || place.anchor === 'MIDDLE' || place.anchor === 'BOTTOM' ? (screenWide - wide) / 2 + place.x
        : screenWide - wide - PAD - place.x,
      y: place.anchor.startsWith('BOTTOM') ? PAD + place.y
        : place.anchor === 'LEFT' || place.anchor === 'MIDDLE' || place.anchor === 'RIGHT' ? (screenTall - tall) / 2 + place.y
        : screenTall - tall - PAD - place.y,
    };
    origin = { x: at.x, y: at.y - footOf(place.block) };
    plate(place.block, wide, tall);
    drawBlock(place.block);
  }
  origin = { x: 0, y: 0 };
  if (banner) drawBanner(scene.focus, width);
  pointAt(scene.focus);
  say();
}

/** `Minimap TopRight 12 12`, as the file writes it and {@code PanelPlace.of} reads it. */
function placeOf(line) {
  const words = String(line).trim().split(/\s+/);
  const plain = word => word.replace(/[_-]/g, '').toUpperCase();
  const block = ['MINIMAP', 'HERO', 'BAG', 'SKILLS', 'DEPTH'].find(name => name === plain(words[0]));
  const anchor = ['TOPLEFT', 'TOP', 'TOPRIGHT', 'LEFT', 'MIDDLE', 'RIGHT', 'BOTTOMLEFT', 'BOTTOM', 'BOTTOMRIGHT']
    .find(name => name === plain(words[1] ?? 'TopLeft'));
  if (!block || !anchor) {
    problems.add(`'${line}' is a block of the bar and where it hangs`);
    return null;
  }
  const spelt = { TOPLEFT: 'TOP_LEFT', TOPRIGHT: 'TOP_RIGHT', BOTTOMLEFT: 'BOTTOM_LEFT', BOTTOMRIGHT: 'BOTTOM_RIGHT' };
  return { block, anchor: spelt[anchor] ?? anchor, x: Number(words[2] ?? 0) || 0, y: Number(words[3] ?? 0) || 0 };
}

/** How much of the band each block fills, and how far up the band that part starts — HeroPanel's own arithmetic. */
function heightOf(block) {
  switch (block) {
    case 'BAG': return look.itemRows * itemSlot() + (look.itemRows - 1) * ITEM_GAP + HEADING_GAP + HEADING_SIZE;
    case 'SKILLS': return HEADING_SIZE + HEADING_GAP + Math.max(skillSlot(), ultimateSlot()) + UNDER_A_SLOT;
    case 'DEPTH': return 46;
    default: return BAND;
  }
}

function footOf(block) {
  switch (block) {
    case 'BAG': case 'SKILLS': return (BAND - heightOf(block)) / 2;
    case 'DEPTH': return BAND / 2 - 24;
    default: return 0;
  }
}

/** The stone a block standing on its own is laid on: the slab's own recipe, cut to the block. */
function plate(block, width, height) {
  const saved = origin;
  origin = { x: saved.x, y: saved.y + footOf(block) };
  gradient(-PAD, -PAD, width + PAD * 2, height + PAD * 2, hex(look.slabTopColour), hex(look.slabLowColour));
  box(-PAD, height + PAD - 3, width + PAD * 2, 3, hex(look.slabRimColour));
  origin = saved;
}

/** Where the part being edited is laid, outlined: every place its picture goes. */
function pointAt(part) {
  const places = drawn.filter(place => place.part === part);
  ctx.save();
  ctx.strokeStyle = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() || '#3574f0';
  ctx.lineWidth = 2;
  ctx.setLineDash([5, 3]);
  for (const place of places) ctx.strokeRect(place.x - 2, place.y - 2, place.w + 4, place.h + 4);
  ctx.restore();
}

function say() {
  const focus = scene.focus;
  const skin = focus && focus !== 'bar' ? skinOf(focus) : null;
  const where = !focus || focus === 'bar' ? 'The bar as this look lays it out.'
    : !skin?.texture ? `${focus}: no picture — the client draws that edge in flat colour.`
    : drawn.some(place => place.part === focus) ? `${focus}: ${skin.texture}, inset ${skin.inset}, scale ${skin.scale}.`
    : `${focus} is not on this bar.`;
  status.textContent = [where, ...problems].join(' · ');
}

const waiting = window.duke.waiting;
window.duke = { showHud };
for (const [call, ...args] of waiting) window.duke[call](...args);
