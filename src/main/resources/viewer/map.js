// A map from the air, laid out as the game's client lays it — TileLayout, TerrainScene and the looks of what stands
// on it, ported — and drawn over its relief. Nothing is edited here: a click, a drag or a stroke of the brush is
// posted to the IDE, which writes the file, and the file comes back as the next scene.
import * as THREE from 'three';
import * as SkeletonUtils from 'three/addons/utils/SkeletonUtils.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { model, skin, dress, hang } from './common.js';

// The client uses a file's colours as they are, its numbers over 255 and no conversion either way.
THREE.ColorManagement.enabled = false;

const view = document.getElementById('view');
const status = document.getElementById('status');

const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.setPixelRatio(window.devicePixelRatio);
view.appendChild(renderer.domElement);

const world = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(45, 1, 1, 4000);
const controls = new OrbitControls(camera, renderer.domElement);
// The left button is the tool's; the camera turns on the right and slides on the middle, as an editor's does.
controls.mouseButtons = { LEFT: null, MIDDLE: THREE.MOUSE.PAN, RIGHT: THREE.MOUSE.ROTATE };
controls.screenSpacePanning = false;
controls.enableDamping = true;
controls.maxPolarAngle = Math.PI * 0.48;

const ambient = new THREE.AmbientLight(0xffffff, Math.PI);
const sunlight = new THREE.DirectionalLight(0xffffff, Math.PI);
world.add(ambient, sunlight, sunlight.target);

const terrain = new THREE.Group();
const things = new THREE.Group();
const marks = new THREE.Group();
world.add(terrain, things, marks);

const DEG = Math.PI / 180;
const STEPS_PER_CELL = 16; // SAGE's MAP_HEIGHT_SCALE: a height step is a sixteenth of a cell
const CLIFF_STEPS = 16;    // SAGE's cliff: corners this far apart are too steep to walk onto

// ---- the ground, as the engine reads a map: MapLoader and PathGrid ----

class Grid {
  constructor(scene) {
    this.cell = scene.cell;
    this.levelHeight = scene.levelHeight;
    this.rows = scene.cells.map(row => row.replace(/\s+$/, ''));
    this.height = this.rows.length;
    this.width = Math.max(1, ...this.rows.map(row => row.length));
    const count = this.width * this.height;
    this.blocked = new Uint8Array(count);
    this.level = new Int32Array(count);
    this.ramp = new Uint8Array(count);
    const solid = scene.solid ?? '#';
    for (let y = 0; y < this.height; y++) {
      for (let x = 0; x < this.rows[y].length; x++) {
        const c = this.rows[y][x];
        if (solid.includes(c) || c === 'X' || c === 'x') this.blocked[y * this.width + x] = 1;
        else if (c >= '0' && c <= '9') this.level[y * this.width + x] = c.charCodeAt(0) - 48;
      }
    }
    const done = new Uint8Array(count);
    for (let y = 0; y < this.height; y++) {
      for (let x = 0; x < this.rows[y].length; x++) {
        if (this.rows[y][x] === '/' && !done[y * this.width + x]) this.settleStair(x, y, done);
      }
    }
    this.columns = this.width + 1;
    this.relief = scene.relief && scene.relief.length === this.height + 1 && scene.relief.every(row => row.length === this.columns)
      ? Int32Array.from(scene.relief.flat()) : null;
  }

  /** A run of stair cells, all on the lowest floor beside it: MapLoader.settleStair. */
  settleStair(fromX, fromY, done) {
    const run = [];
    const queue = [[fromX, fromY]];
    done[fromY * this.width + fromX] = 1;
    let lowest = Infinity;
    while (queue.length) {
      const at = queue.shift();
      run.push(at);
      for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
        const x = at[0] + dx;
        const y = at[1] + dy;
        if (!this.inBounds(x, y) || x >= this.rows[y].length) continue;
        if (this.rows[y][x] === '/') {
          if (!done[y * this.width + x]) {
            done[y * this.width + x] = 1;
            queue.push([x, y]);
          }
        } else if (!this.blocked[y * this.width + x]) {
          lowest = Math.min(lowest, this.level[y * this.width + x]);
        }
      }
    }
    const foot = lowest === Infinity ? 0 : lowest;
    for (const [x, y] of run) {
      this.ramp[y * this.width + x] = 1;
      this.level[y * this.width + x] = foot;
    }
  }

  inBounds(cx, cy) { return cx >= 0 && cy >= 0 && cx < this.width && cy < this.height; }
  isBlocked(cx, cy) { return !this.inBounds(cx, cy) || this.blocked[cy * this.width + cx] === 1; }
  levelAt(cx, cy) { return this.inBounds(cx, cy) ? this.level[cy * this.width + cx] : 0; }
  isRamp(cx, cy) { return this.inBounds(cx, cy) && this.ramp[cy * this.width + cx] === 1; }
  storeyHeight(cx, cy) { return this.levelAt(cx, cy) * this.levelHeight; }

  corner(i, j) {
    if (!this.relief) return 0;
    return this.relief[Math.min(Math.max(j, 0), this.height) * this.columns + Math.min(Math.max(i, 0), this.width)];
  }

  isCliff(cx, cy) {
    if (!this.relief) return false;
    const heights = [this.corner(cx, cy), this.corner(cx + 1, cy), this.corner(cx, cy + 1), this.corner(cx + 1, cy + 1)];
    return Math.max(...heights) - Math.min(...heights) >= CLIFF_STEPS;
  }

  canStep(fromX, fromY, toX, toY) {
    if (this.isBlocked(fromX, fromY) || this.isBlocked(toX, toY)) return false;
    if (this.isCliff(toX, toY)) return false;
    const climb = this.levelAt(toX, toY) - this.levelAt(fromX, fromY);
    if (climb === 0) return true;
    if (climb > 1 || climb < -1 || (fromX !== toX && fromY !== toY)) return false;
    return this.isRamp(fromX, fromY) || this.isRamp(toX, toY);
  }

  rampDirection(cx, cy) {
    if (!this.isRamp(cx, cy)) return null;
    const here = this.levelAt(cx, cy);
    for (const step of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const nx = cx + step[0];
      const ny = cy + step[1];
      if (!this.isBlocked(nx, ny) && this.levelAt(nx, ny) === here + 1 && this.canStep(cx, cy, nx, ny)) return step;
    }
    return null;
  }

  /** The relief's height under a point, as SAGE's two triangles a cell give it. */
  reliefAt(x, z) {
    if (!this.relief) return 0;
    const cx = Math.floor(x / this.cell);
    const cy = Math.floor(z / this.cell);
    const fx = x / this.cell - cx;
    const fy = z / this.cell - cy;
    const p0 = this.corner(cx, cy);
    const p2 = this.corner(cx + 1, cy + 1);
    const steps = fy > fx
      ? this.corner(cx, cy + 1) + (1 - fy) * (p0 - this.corner(cx, cy + 1)) + fx * (p2 - this.corner(cx, cy + 1))
      : this.corner(cx + 1, cy) + fy * (p2 - this.corner(cx + 1, cy)) + (1 - fx) * (p0 - this.corner(cx + 1, cy));
    return steps * this.cell / STEPS_PER_CELL;
  }

  /** Where a body standing at a point has its feet: the storey, up a stair, and the relief over it. */
  groundHeight(x, z) {
    const cx = Math.floor(x / this.cell);
    const cy = Math.floor(z / this.cell);
    let storey = this.storeyHeight(cx, cy);
    const rise = this.rampDirection(cx, cy);
    if (rise) {
      const alongX = x / this.cell - cx;
      const alongY = z / this.cell - cy;
      const across = rise[0] !== 0 ? (rise[0] > 0 ? alongX : 1 - alongX) : (rise[1] > 0 ? alongY : 1 - alongY);
      storey += this.levelHeight * Math.min(Math.max(across, 0), 1);
    }
    return storey + this.reliefAt(x, z);
  }
}

// ---- the layout: TileLayout.of ----

const SIDES = [[0, -1, 0], [1, 0, 270], [0, 1, 180], [-1, 0, 90]];
const CORNERS = [[0, 0, 0], [0, 1, 90], [1, 1, 180], [1, 0, 270]];

const solidAt = (grid, cx, cy) => grid.isBlocked(cx, cy);

function highestFloorAround(grid, cx, cy) {
  let highest = 0;
  for (let dy = -1; dy <= 1; dy++) {
    for (let dx = -1; dx <= 1; dx++) {
      if (!solidAt(grid, cx + dx, cy + dy)) highest = Math.max(highest, grid.storeyHeight(cx + dx, cy + dy));
    }
  }
  return highest;
}

function layout(grid) {
  const pieces = [];
  const cell = grid.cell;
  const piece = (kind, cx, cy, x, z, yaw, ground) => pieces.push({ piece: kind, cx, cy, x, z, yaw, ground });
  const storey = grid.levelHeight;
  for (let cy = 0; cy < grid.height; cy++) {
    for (let cx = 0; cx < grid.width; cx++) {
      if (solidAt(grid, cx, cy)) {
        const lid = highestFloorAround(grid, cx, cy);
        piece('CAP', cx, cy, (cx + 0.5) * cell, (cy + 0.5) * cell, 0, lid);
        if (storey > 0) {
          for (const side of SIDES) {
            const nx = cx + side[0];
            const ny = cy + side[1];
            if (!solidAt(grid, nx, ny)) continue;
            const theirs = highestFloorAround(grid, nx, ny);
            if (theirs >= lid) continue;
            const x = (cx + 0.5 + side[0] * 0.5) * cell;
            const z = (cy + 0.5 + side[1] * 0.5) * cell;
            for (let foot = theirs; foot < lid - storey * 0.5; foot += storey) piece('LEDGE', cx, cy, x, z, (side[2] + 180) % 360, foot);
          }
        }
        continue;
      }
      const ground = grid.storeyHeight(cx, cy);
      piece('FLOOR', cx, cy, (cx + 0.5) * cell, (cy + 0.5) * cell, 0, ground);
      for (const side of SIDES) {
        const nx = cx + side[0];
        const ny = cy + side[1];
        const x = (cx + 0.5 + side[0] * 0.5) * cell;
        const z = (cy + 0.5 + side[1] * 0.5) * cell;
        if (solidAt(grid, nx, ny)) {
          const lid = highestFloorAround(grid, nx, ny);
          for (let foot = ground; foot <= lid + 0.001; foot += Math.max(storey, 1)) {
            piece('WALL', cx, cy, x, z, side[2], foot);
            if (storey <= 0) break;
          }
          continue;
        }
        if (grid.canStep(cx, cy, nx, ny)) continue;
        const there = grid.storeyHeight(nx, ny);
        if (ground <= there || storey <= 0) continue;
        for (let foot = there; foot < ground - storey * 0.5; foot += storey) piece('WALL', cx, cy, x, z, (side[2] + 180) % 360, foot);
      }
      for (const corner of CORNERS) {
        const dx = corner[0] === 0 ? -1 : 1;
        const dy = corner[1] === 0 ? -1 : 1;
        const walled = (nx, ny) => solidAt(grid, nx, ny) || !grid.canStep(cx, cy, nx, ny);
        if (!walled(cx + dx, cy) || !walled(cx, cy + dy)) continue;
        const open = (nx, ny) => (solidAt(grid, nx, ny) ? ground : grid.storeyHeight(nx, ny));
        const foot = Math.min(open(cx + dx, cy), open(cx, cy + dy));
        for (let y = foot; y < ground + 0.001; y += Math.max(storey, 1)) {
          piece('CORNER', cx, cy, (cx + corner[0]) * cell, (cy + corner[1]) * cell, corner[2], y);
          if (storey <= 0) break;
        }
      }
      const rise = grid.rampDirection(cx, cy);
      if (rise) {
        const yaw = rise[0] > 0 ? 90 : rise[0] < 0 ? 270 : rise[1] > 0 ? 0 : 180;
        piece('STAIR', cx, cy, (cx + 0.5) * cell, (cy + 0.5) * cell, yaw, ground);
      }
    }
  }
  return pieces;
}

// ---- the plan: TerrainScene.plan, for a kit of things standing in the rock ----

const upright = piece => piece === 'WALL' || piece === 'CORNER' || piece === 'LEDGE';

function rockFacedBy(p, grid) {
  const nx = p.cx + Math.round((p.x / grid.cell - (p.cx + 0.5)) * 2);
  const ny = p.cy + Math.round((p.z / grid.cell - (p.cy + 0.5)) * 2);
  return grid.inBounds(nx, ny) && grid.isBlocked(nx, ny) ? ny * grid.width + nx : -1;
}

function plan(pieces, grid, kit) {
  if (!kit.fillsRock) return pieces.map(p => ({ ...p, inRock: false }));
  const proud = kit.wallHeight * (grid.cell / kit.wallTileSize);
  const planned = [];
  const bodies = new Map();
  for (const p of pieces) {
    if (!upright(p.piece) || p.piece === 'CORNER') {
      planned.push({ ...p, inRock: false });
      continue;
    }
    const rock = rockFacedBy(p, grid);
    if (p.piece === 'LEDGE' && rock < 0) continue;
    const top = p.piece === 'LEDGE' ? highestFloorAround(grid, p.cx, p.cy)
      : rock >= 0 ? highestFloorAround(grid, rock % grid.width, Math.floor(rock / grid.width)) : grid.storeyHeight(p.cx, p.cy);
    if (top + proud > p.ground + 0.001) planned.push({ ...p, piece: 'ROCK_FACE', inRock: false });
    if (rock >= 0 && !bodies.has(rock)) bodies.set(rock, { yaw: p.yaw, cx: p.cx, cy: p.cy });
  }
  for (const [rock, found] of bodies) {
    const rx = rock % grid.width;
    const ry = Math.floor(rock / grid.width);
    planned.push({ piece: 'WALL', cx: found.cx, cy: found.cy, x: (rx + 0.5) * grid.cell, z: (ry + 0.5) * grid.cell,
      yaw: found.yaw, ground: highestFloorAround(grid, rx, ry), inRock: true });
  }
  return planned;
}

// ---- each piece where the kit puts it: TerrainScene.addKitPiece, addStair, addRockFace ----

const floatBits = new Float32Array(1);
const intBits = new Int32Array(floatBits.buffer);
const bits = v => { floatBits[0] = v; return intBits[0]; };

/** A settled number in [0, 1) for a place, a copy and a purpose: TerrainScene.steady, to the bit. */
function steady(x, z, copy, purpose) {
  let hash = Math.imul(bits(x), 0x27d4eb2d);
  hash = Math.imul(hash ^ bits(z), 0x165667b1);
  hash = Math.imul(hash ^ (Math.imul(copy, 0x9e3779b9) + purpose), 0x85ebca6b);
  hash ^= hash >>> 15;
  return (hash >>> 8) / 16777216;
}

function shaded(rgb, by) {
  if (by === 1) return rgb;
  const channel = shift => Math.min(Math.max(Math.round(((rgb >> shift) & 0xff) * by), 0), 255);
  return (channel(16) << 16) | (channel(8) << 8) | channel(0);
}

function blend(a, b) {
  const channel = shift => Math.floor((((a >> shift) & 0xff) * ((b >> shift) & 0xff)) / 255);
  return (channel(16) << 16) | (channel(8) << 8) | channel(0);
}

const measured = new Map();

/** A model's box and, for a stair, which way it climbs — read off the model, as the client reads them. */
function measure(asset, file) {
  if (measured.has(asset)) return measured.get(asset);
  file.scene.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(file.scene);
  // The climb from the mesh's own points, as the client takes it: the high points' middle less the low points'.
  const points = [];
  file.scene.traverse(node => {
    if (!node.isMesh) return;
    const positions = node.geometry.getAttribute('position');
    for (let i = 0; i < positions.count; i++) points.push([positions.getX(i), positions.getY(i), positions.getZ(i)]);
  });
  let climb = new THREE.Vector3(0, 0, 1);
  if (points.length) {
    let low = Infinity;
    let high = -Infinity;
    for (const p of points) {
      low = Math.min(low, p[1]);
      high = Math.max(high, p[1]);
    }
    const tall = high - low;
    const mean = picked => picked.reduce((sum, p) => sum.add(new THREE.Vector3(p[0], 0, p[2])), new THREE.Vector3()).divideScalar(Math.max(1, picked.length));
    const direction = mean(points.filter(p => p[1] >= low + 0.8 * tall)).sub(mean(points.filter(p => p[1] <= low + 0.2 * tall)));
    if (direction.lengthSq() >= 0.0001) climb = direction.normalize();
  }
  const facts = { box, climb };
  measured.set(asset, facts);
  return facts;
}

/** Every piece of the map as the model it is drawn with, its tint, and where it stands. */
async function arrange(grid, kit) {
  const cell = grid.cell;
  const storey = grid.levelHeight;
  const floorScale = cell / kit.tileSize;
  const wallScale = cell / kit.wallTileSize;
  const pieces = layout(grid);
  const planned = plan(pieces, grid, kit);
  let tallest = 0;
  if (storey > 0) for (const p of planned) tallest = Math.max(tallest, Math.round(p.ground / storey));
  const tintOf = p => {
    const shade = storey <= 0 ? 1 : Math.pow(kit.storeyShade, Math.round(p.ground / storey) - tallest);
    const own = p.piece === 'CAP' ? shaded(kit.capTint, shade) : p.piece === 'FLOOR' ? shaded(0xffffff, shade) : 0xffffff;
    return blend(kit.tint, own);
  };
  const assetOf = p => ({
    FLOOR: kit.floor, WALL: kit.wall, CORNER: kit.corner, CAP: kit.wall ? kit.floor : null,
    LEDGE: kit.wall, STAIR: kit.stairs, ROCK_FACE: kit.rockFace,
  })[p.piece] ?? null;
  const files = new Map();
  for (const asset of new Set(planned.map(assetOf).filter(Boolean))) files.set(asset, await model(asset));

  const placed = [];
  const put = (asset, tint, position, yaw, scale) => placed.push({
    asset, tint,
    matrix: new THREE.Matrix4().compose(position, new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 1, 0), yaw), scale),
  });
  for (const p of planned) {
    const asset = assetOf(p);
    const file = asset && files.get(asset);
    if (!file) continue;
    const { box, climb } = measure(asset, file);
    if (p.piece === 'STAIR') {
      const size = box.getSize(new THREE.Vector3());
      const centre = box.getCenter(new THREE.Vector3());
      const run = Math.max(0.001, Math.abs(climb.x) > Math.abs(climb.z) ? size.x : size.z);
      const yaw = p.yaw * DEG - Math.atan2(climb.x, climb.z);
      const across = centre.x * cell / run;
      const along = centre.z * cell / run;
      put(asset, 0xffffff, new THREE.Vector3(
        p.x - (across * Math.cos(yaw) + along * Math.sin(yaw)),
        p.ground - box.min.y * storey / run,
        p.z - (along * Math.cos(yaw) - across * Math.sin(yaw))), yaw, new THREE.Vector3(cell / run, storey / run, cell / run));
      continue;
    }
    if (p.piece === 'ROCK_FACE') {
      if (storey <= 0) continue;
      const scale = storey / Math.max(0.001, box.max.y - box.min.y);
      const yaw = p.yaw * DEG;
      const back = -box.max.z * scale;
      put(asset, 0xffffff, new THREE.Vector3(p.x + back * Math.sin(yaw), p.ground - box.min.y * scale, p.z + back * Math.cos(yaw)),
        yaw, new THREE.Vector3(scale, scale, scale));
      continue;
    }
    const standing = upright(p.piece);
    const clump = standing ? kit.clump : 1;
    for (let copy = 0; copy < clump; copy++) {
      const yaw = p.yaw * DEG;
      let scale = standing ? wallScale : floorScale;
      let facing = yaw;
      let side = 0;
      let ring = 0;
      if (clump > 1) {
        const turn = 2 * Math.PI * (copy + steady(p.x, p.z, copy, 0)) / clump;
        const radius = kit.spread * cell * (0.55 + 0.45 * steady(p.x, p.z, copy, 1));
        side = radius * Math.sin(turn);
        ring = radius * (p.inRock ? Math.cos(turn) : -(1 + Math.cos(turn)));
        facing = 2 * Math.PI * steady(p.x, p.z, copy, 2);
      }
      if (kit.variety > 0 && standing) scale *= 1 + kit.variety * (steady(p.x, p.z, copy, 3) - 0.5);
      const surface = p.piece === 'FLOOR' ? box.max.y * scale : 0;
      const y = p.ground + ({
        CAP: kit.wallHeight * wallScale,
        LEDGE: (kit.wallHeight + kit.wallLift) * wallScale,
        WALL: kit.wallLift * wallScale,
        CORNER: kit.wallLift * wallScale,
      }[p.piece] ?? -surface);
      const back = (standing && !p.inRock ? kit.wallShift * wallScale : 0) + ring;
      put(asset, tintOf(p), new THREE.Vector3(p.x + back * Math.sin(yaw) + side * Math.cos(yaw), y,
        p.z + back * Math.cos(yaw) - side * Math.sin(yaw)), facing, new THREE.Vector3(scale, scale, scale));
    }
  }
  const count = list => list.reduce((all, p) => ({ ...all, [p.piece]: (all[p.piece] ?? 0) + 1 }), {});
  return { placed, files, counts: { laid: count(pieces), planned: count(planned), drawn: placed.length } };
}

// ---- the relief, laid over everything the kit put down ----

const relief = { map: { value: null }, size: { value: new THREE.Vector2(2, 2) }, cell: { value: 10 }, on: { value: 0 } };

/**
 * A material whose every point is raised by the relief under it — in the vertex shader, so a map of ten thousand
 * pieces drawn a few hundred at a time bends over its hills without a mesh of its own for each.
 */
function draped(material) {
  material.onBeforeCompile = shader => {
    Object.assign(shader.uniforms, { dukeRelief: relief.map, dukeReliefSize: relief.size, dukeCell: relief.cell, dukeReliefOn: relief.on });
    shader.vertexShader = shader.vertexShader
      .replace('#include <common>', `#include <common>
uniform sampler2D dukeRelief;
uniform vec2 dukeReliefSize;
uniform float dukeCell;
uniform float dukeReliefOn;
float dukeHeight(vec2 xz) {
  return dukeReliefOn < 0.5 ? 0.0 : texture2D(dukeRelief, (xz / dukeCell + 0.5) / dukeReliefSize).r;
}
vec2 dukeSlope(vec2 xz) {
  float e = dukeCell * 0.5;
  return vec2(dukeHeight(xz + vec2(e, 0.0)) - dukeHeight(xz - vec2(e, 0.0)),
              dukeHeight(xz + vec2(0.0, e)) - dukeHeight(xz - vec2(0.0, e))) / (2.0 * e);
}`)
      // A point raised by the ground under it leans with the ground: its normal sheared by the slope there, so a hill
      // is lit as a hill rather than as flat floor pushed up.
      .replace('#include <defaultnormal_vertex>', `vec3 dukeNormal = objectNormal;
vec4 dukeAt = vec4(position, 1.0);
#ifdef USE_INSTANCING
dukeNormal = mat3(instanceMatrix) * dukeNormal;
dukeAt = instanceMatrix * dukeAt;
#endif
dukeNormal = normalize(mat3(modelMatrix) * dukeNormal);
dukeAt = modelMatrix * dukeAt;
vec2 dukeLean = dukeSlope(dukeAt.xz);
dukeNormal = normalize(vec3(dukeNormal.x - dukeLean.x * dukeNormal.y, dukeNormal.y, dukeNormal.z - dukeLean.y * dukeNormal.y));
vec3 transformedNormal = normalize(mat3(viewMatrix) * dukeNormal);
#ifdef FLIP_SIDED
transformedNormal = - transformedNormal;
#endif`)
      .replace('#include <project_vertex>', `vec4 mvPosition = vec4(transformed, 1.0);
#ifdef USE_INSTANCING
mvPosition = instanceMatrix * mvPosition;
#endif
vec4 dukeWorld = modelMatrix * mvPosition;
dukeWorld.y += dukeHeight(dukeWorld.xz);
mvPosition = viewMatrix * dukeWorld;
gl_Position = projectionMatrix * mvPosition;`);
  };
  material.customProgramCacheKey = () => 'duke-draped';
  return material;
}

function layRelief(grid) {
  if (!grid.relief) {
    relief.on.value = 0;
    return;
  }
  const data = new Uint16Array(grid.columns * (grid.height + 1));
  for (let i = 0; i < data.length; i++) data[i] = THREE.DataUtils.toHalfFloat(grid.relief[i] * grid.cell / STEPS_PER_CELL);
  const texture = new THREE.DataTexture(data, grid.columns, grid.height + 1, THREE.RedFormat, THREE.HalfFloatType);
  texture.magFilter = THREE.LinearFilter;
  texture.minFilter = THREE.LinearFilter;
  texture.needsUpdate = true;
  relief.map.value?.dispose();
  relief.map.value = texture;
  relief.size.value.set(grid.columns, grid.height + 1);
  relief.cell.value = grid.cell;
  relief.on.value = 1;
}

// ---- the scene ----

let scene = null;
let grid = null;
let kit = null;
let layoutKey = '';
let builds = 0;
let framed = false;
const standing = new Map(); // a thing's key → what is drawn for it

function paint(colours) {
  if (!colours) return;
  for (const [name, value] of Object.entries(colours)) document.documentElement.style.setProperty('--' + name, value);
}

async function showMap(next, colours) {
  paint(colours);
  scene = next;
  grid = new Grid(next);
  kit = next.kit;
  lightFrom(next.sun);
  renderer.setClearColor(new THREE.Color(kit?.fog ?? 0x07060a));
  layRelief(grid);
  const key = JSON.stringify([next.cells, next.levelHeight, next.kit]);
  if (key !== layoutKey) {
    layoutKey = key;
    await lay(grid, kit);
  }
  await standThingsUp(next.things);
  if (!framed) frameOn(next);
  redrawMarks();
}

function lightFrom(sun) {
  const s = sun ?? { pitch: 40, yaw: 219, strength: 100, ambient: 55, colour: 0xffffff, ambientTint: 0xffffff };
  const pitch = Math.min(Math.max(s.pitch, 5), 90) * DEG;
  const yaw = s.yaw * DEG;
  // Where the light goes, as the client's Sunlight works it out; the light sits back along it.
  const travels = new THREE.Vector3(Math.cos(pitch) * Math.sin(yaw), -Math.sin(pitch), Math.cos(pitch) * Math.cos(yaw));
  sunlight.position.copy(travels).multiplyScalar(-100);
  sunlight.target.position.set(0, 0, 0);
  sunlight.color.setHex(s.colour).multiplyScalar(s.strength / 100);
  ambient.color.setHex(s.ambientTint).multiplyScalar(s.ambient / 100 * 0.55);
}

async function lay(grid, kit) {
  const build = ++builds;
  for (const mesh of [...terrain.children]) {
    terrain.remove(mesh);
    for (const material of Array.isArray(mesh.material) ? mesh.material : [mesh.material]) material.dispose();
    mesh.dispose();
  }
  if (!kit) {
    status.textContent = 'No theme to lay this map with: a block with Tones is what the floor is built of.';
    return;
  }
  const { placed, files, counts } = await arrange(grid, kit);
  if (build !== builds) return;
  const groups = new Map();
  for (const p of placed) {
    const key = p.asset + '|' + p.tint;
    if (!groups.has(key)) groups.set(key, { asset: p.asset, tint: p.tint, matrices: [] });
    groups.get(key).matrices.push(p.matrix);
  }
  for (const group of groups.values()) {
    const file = files.get(group.asset);
    file.scene.updateMatrixWorld(true);
    file.scene.traverse(node => {
      if (!node.isMesh) return;
      const materials = (Array.isArray(node.material) ? node.material : [node.material]).map(material => draped(new THREE.MeshLambertMaterial({
        map: material.map ?? null,
        color: new THREE.Color(group.tint),
        transparent: material.transparent,
        alphaTest: material.alphaTest,
        side: material.side,
      })));
      const mesh = new THREE.InstancedMesh(node.geometry, Array.isArray(node.material) ? materials : materials[0], group.matrices.length);
      group.matrices.forEach((matrix, i) => mesh.setMatrixAt(i, new THREE.Matrix4().multiplyMatrices(matrix, node.matrixWorld)));
      mesh.instanceMatrix.needsUpdate = true;
      mesh.frustumCulled = false;
      terrain.add(mesh);
    });
  }
  window.dukeCounts = counts;
}

const thingKey = thing => `${thing.layer}|${thing.kind ?? ''}|${thing.x}|${thing.y}|${JSON.stringify(thing.look)}`;

async function standThingsUp(list) {
  const wanted = new Map(list.map(thing => [thingKey(thing), thing]));
  for (const [key, drawn] of standing) {
    if (!wanted.has(key)) {
      things.remove(drawn);
      standing.delete(key);
    }
  }
  for (const [key, thing] of wanted) {
    const drawn = standing.get(key) ?? await body(thing);
    if (!standing.has(key)) {
      standing.set(key, drawn);
      things.add(drawn);
    }
    const x = (thing.x + 0.5) * grid.cell;
    const z = (thing.y + 0.5) * grid.cell;
    drawn.position.set(x, grid.groundHeight(x, z), z);
  }
}

/** What a thing is drawn as: its own model dressed as the game dresses it, or a marker where it has none. */
async function body(thing) {
  const holder = new THREE.Group();
  holder.userData.thing = thing;
  const layer = scene.layers.find(l => l.key === thing.layer);
  const file = thing.look ? await model(thing.look.model) : null;
  if (file) {
    const shape = SkeletonUtils.clone(file.scene);
    const texture = thing.look.texture ? await skin(thing.look.texture) : null;
    const tint = new THREE.Color(thing.look.tint);
    dress(shape, texture, tint);
    for (const held of thing.look.held) await hang(shape, held, tint);
    shape.scale.setScalar(thing.look.scale);
    shape.rotation.y = thing.look.facing * DEG;
    holder.add(shape);
  } else {
    holder.add(marker(thing, layer));
  }
  if (layer?.single) {
    const ring = new THREE.Mesh(new THREE.RingGeometry(4.2, 5, 32), new THREE.MeshBasicMaterial({
      color: layer.kinded ? 0xffd25a : 0x5aaaff, side: THREE.DoubleSide, transparent: true, opacity: 0.9 }));
    ring.rotation.x = -Math.PI / 2;
    ring.position.y = 0.3;
    holder.add(ring);
  }
  return holder;
}

function marker(thing, layer) {
  const colour = thing.kind == null ? 0x5aaaff : thing.colour ?? hashColour(thing.kind);
  const material = new THREE.MeshLambertMaterial({ color: colour });
  const shape = layer && !layer.single && layer.shape % 3 === 1
    ? new THREE.Mesh(new THREE.BoxGeometry(4, 4, 4), material)
    : new THREE.Mesh(new THREE.CylinderGeometry(2.2, 2.2, thing.kind == null ? 12 : 7, 16), material);
  shape.position.y = shape.geometry.parameters.height / 2;
  return shape;
}

function hashColour(kind) {
  let hash = 0;
  for (const c of kind) hash = Math.imul(hash, 31) + c.charCodeAt(0) | 0;
  return new THREE.Color().setHSL(((hash & 0xffff) / 65535), 0.55, 0.6).getHex();
}

/** The camera where the game's own starts: over the way in, looking north and down at the client's angle. */
function frameOn(next) {
  framed = true;
  const entrance = next.things.find(thing => thing.kind == null && scene.layers.find(l => l.key === thing.layer)?.single);
  const cx = entrance ? entrance.x + 0.5 : grid.width / 2;
  const cy = entrance ? entrance.y + 0.5 : grid.height / 2;
  const target = new THREE.Vector3(cx * grid.cell, 0, cy * grid.cell);
  target.y = grid.groundHeight(target.x, target.z);
  const distance = 140;
  controls.target.copy(target);
  camera.position.copy(target).add(new THREE.Vector3(0, 0.82 * distance, 0.57 * distance));
  controls.update();
}

// ---- picking and the hand ----

let tool = { mode: 'place', radius: 2, strength: 2, storey: 0 };
let hover = null;       // { x, z, cx, cy, vx, vy }
let pressed = null;     // { button, at, x, y, thing, stroke }
const raycaster = new THREE.Raycaster();

function capTop(cx, cy) {
  if (!kit) return 0;
  return highestFloorAround(grid, cx, cy) + kit.wallHeight * (grid.cell / kit.wallTileSize);
}

/** The height of what the eye sees at a point: rock's roof over stone, the ground everywhere else. */
function surfaceAt(x, z) {
  const cx = Math.floor(x / grid.cell);
  const cy = Math.floor(z / grid.cell);
  return (grid.isBlocked(cx, cy) ? capTop(cx, cy) : grid.storeyHeight(cx, cy)) + grid.reliefAt(x, z);
}

/** Where the ray under the cursor first meets what is drawn: walked along it, then halved down to the spot. */
function pick(event) {
  if (!grid) return null;
  const rect = renderer.domElement.getBoundingClientRect();
  const ndc = new THREE.Vector2(((event.clientX - rect.left) / rect.width) * 2 - 1, -((event.clientY - rect.top) / rect.height) * 2 + 1);
  raycaster.setFromCamera(ndc, camera);
  const ray = raycaster.ray;
  const step = grid.cell / 4;
  let before = 0;
  for (let t = 0; t < 4000; t += step) {
    const at = ray.at(t, new THREE.Vector3());
    if (at.y <= surfaceAt(at.x, at.z)) {
      let low = before;
      let high = t;
      for (let i = 0; i < 12; i++) {
        const middle = (low + high) / 2;
        const probe = ray.at(middle, new THREE.Vector3());
        if (probe.y <= surfaceAt(probe.x, probe.z)) high = middle; else low = middle;
      }
      const hit = ray.at(high, new THREE.Vector3());
      const cx = Math.floor(hit.x / grid.cell);
      const cy = Math.floor(hit.z / grid.cell);
      if (!grid.inBounds(cx, cy)) return null;
      return { x: hit.x, z: hit.z, cx, cy, vx: Math.round(hit.x / grid.cell), vy: Math.round(hit.z / grid.cell) };
    }
    before = t;
  }
  return null;
}

function thingAt(cx, cy) {
  const all = scene?.things.filter(thing => thing.x === cx && thing.y === cy) ?? [];
  return all.find(thing => scene.layers.find(l => l.key === thing.layer)?.single) ?? all[0] ?? null;
}

/** What the hand did, told to the IDE in words: the cell, and what else the change needs. */
function post(what, ...words) {
  fetch('../do/' + what, { method: 'POST', body: words.join(' ') }).catch(() => {});
}

const reliefTools = new Set(['raise', 'lower', 'smooth', 'flatten']);
const cellTools = new Set(['floor', 'stone', 'stair']);

renderer.domElement.addEventListener('contextmenu', event => event.preventDefault());

renderer.domElement.addEventListener('pointerdown', event => {
  const at = pick(event);
  if (!at) return;
  pressed = { button: event.button, screen: [event.clientX, event.clientY], at, thing: thingAt(at.cx, at.cy), cells: new Map(), last: 0 };
  if (event.button !== 0) return;
  renderer.domElement.setPointerCapture(event.pointerId);
  if (reliefTools.has(tool.mode)) {
    if (!grid.relief) grid.relief = new Int32Array(grid.columns * (grid.height + 1));
    pressed.target = grid.corner(at.vx, at.vy);
    stroke(at);
  } else if (cellTools.has(tool.mode)) {
    paintCell(at);
  }
});

renderer.domElement.addEventListener('pointermove', event => {
  const at = pick(event);
  hover = at;
  describe(at);
  if (pressed?.button === 0 && at) {
    if (reliefTools.has(tool.mode) && performance.now() - pressed.last > 90) stroke(at);
    else if (cellTools.has(tool.mode)) paintCell(at);
  }
  redrawMarks();
});

renderer.domElement.addEventListener('pointerup', event => {
  const down = pressed;
  pressed = null;
  if (!down) return;
  const at = pick(event) ?? hover;
  const still = Math.hypot(event.clientX - down.screen[0], event.clientY - down.screen[1]) < 5;
  if (down.button === 2) {
    // A right click is a thing taken off; a right drag was the camera turning.
    if (still && at && thingAt(at.cx, at.cy)) post('remove', at.cx, at.cy);
    return;
  }
  if (down.button !== 0) return;
  if (reliefTools.has(tool.mode)) {
    post('relief', reliefRows().join('\n'));
    return;
  }
  if (cellTools.has(tool.mode)) {
    post('cells', [...down.cells.values()].map(cell => cell.join(' ')).join('\n'));
    return;
  }
  if (!at) return;
  if (tool.mode === 'erase') {
    if (thingAt(at.cx, at.cy)) post('remove', at.cx, at.cy);
    return;
  }
  if (down.thing) {
    if (at.cx === down.at.cx && at.cy === down.at.cy) post('pick', at.cx, at.cy, down.thing.layer);
    else post('move', down.at.cx, down.at.cy, at.cx, at.cy, down.thing.layer);
    return;
  }
  post('place', at.cx, at.cy);
});

window.addEventListener('keydown', event => {
  if ((event.key === 'Delete' || event.key === 'Backspace') && hover && thingAt(hover.cx, hover.cy)) post('remove', hover.cx, hover.cy);
});

/** One touch of the brush on the relief, seen at once; the file hears of the whole stroke when it ends. */
function stroke(at) {
  pressed.last = performance.now();
  const radius = Math.max(1, tool.radius);
  const strength = Math.max(1, tool.strength);
  const next = Int32Array.from(grid.relief);
  for (let j = at.vy - radius; j <= at.vy + radius; j++) {
    for (let i = at.vx - radius; i <= at.vx + radius; i++) {
      if (i < 0 || j < 0 || i > grid.width || j > grid.height) continue;
      const distance = Math.hypot(i - at.vx, j - at.vy);
      if (distance > radius) continue;
      const weight = 1 - distance / (radius + 1);
      const index = j * grid.columns + i;
      const here = grid.relief[index];
      if (tool.mode === 'raise') next[index] = here + Math.max(1, Math.round(strength * weight));
      else if (tool.mode === 'lower') next[index] = here - Math.max(1, Math.round(strength * weight));
      else if (tool.mode === 'flatten') next[index] = here + Math.round((pressed.target - here) * Math.min(1, 0.35 * strength * weight));
      else {
        let sum = 0;
        let count = 0;
        for (const [di, dj] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
          const ni = i + di;
          const nj = j + dj;
          if (ni < 0 || nj < 0 || ni > grid.width || nj > grid.height) continue;
          sum += grid.relief[nj * grid.columns + ni];
          count++;
        }
        next[index] = here + Math.round((sum / count - here) * Math.min(1, 0.3 * strength * weight));
      }
    }
  }
  grid.relief = next;
  layRelief(grid);
  for (const drawn of standing.values()) {
    drawn.position.y = grid.groundHeight(drawn.position.x, drawn.position.z);
  }
}

function reliefRows() {
  const rows = [];
  for (let j = 0; j <= grid.height; j++) rows.push(Array.from(grid.relief.subarray(j * grid.columns, (j + 1) * grid.columns)).join(' '));
  return rows;
}

/** A cell painted: stone, floor at the tool's storey, or a stair — kept until the stroke ends, and shown meanwhile. */
function paintCell(at) {
  const char = tool.mode === 'stone' ? (scene.solid ?? '#')[0] : tool.mode === 'stair' ? '/' : String(Math.min(Math.max(tool.storey | 0, 0), 9));
  pressed.cells.set(at.cx + ',' + at.cy, [at.cx, at.cy, char]);
}

function describe(at) {
  if (!at) {
    status.textContent = '';
    return;
  }
  const ground =grid.isBlocked(at.cx, at.cy) ? 'rock' : grid.isRamp(at.cx, at.cy) ? 'stair'
    : `floor, storey ${grid.levelAt(at.cx, at.cy)}${grid.isCliff(at.cx, at.cy) ? ', too steep to stand on' : ''}`;
  const height = grid.relief ? ` · relief ${grid.corner(at.vx, at.vy)} at corner ${at.vx}, ${at.vy}` : '';
  const here = scene.things.filter(thing => thing.x === at.cx && thing.y === at.cy)
    .map(thing => thing.kind ? `${thing.kind} (${thing.layer})` : thing.layer).join(', ');
  status.textContent = `${at.cx}, ${at.cy} · ${ground}${height}${here ? ' · ' + here : ''}`;
}

// ---- what the hand is over: the cell, the brush, the cells a stroke has painted ----

const markMaterial = colour => draped(new THREE.MeshBasicMaterial({ color: colour, transparent: true, opacity: 0.35, depthWrite: false }));
const cellMark = new THREE.Mesh(new THREE.PlaneGeometry(1, 1, 4, 4).rotateX(-Math.PI / 2), markMaterial(0xffffff));
const paintMark = markMaterial(0x3574f0);

function redrawMarks() {
  for (const mark of [...marks.children]) if (mark !== cellMark) marks.remove(mark);
  marks.remove(cellMark);
  if (!grid) return;
  if (hover && !reliefTools.has(tool.mode)) {
    cellMark.scale.set(grid.cell, 1, grid.cell);
    cellMark.position.set((hover.cx + 0.5) * grid.cell, (grid.isBlocked(hover.cx, hover.cy) ? capTop(hover.cx, hover.cy) : grid.storeyHeight(hover.cx, hover.cy)) + 0.15,
      (hover.cy + 0.5) * grid.cell);
    marks.add(cellMark);
  }
  if (hover && reliefTools.has(tool.mode)) {
    const points = [];
    const radius = Math.max(1, tool.radius) * grid.cell;
    for (let k = 0; k <= 48; k++) {
      const angle = (k / 48) * Math.PI * 2;
      const x = hover.vx * grid.cell + Math.cos(angle) * radius;
      const z = hover.vy * grid.cell + Math.sin(angle) * radius;
      points.push(new THREE.Vector3(x, grid.storeyHeight(Math.floor(x / grid.cell), Math.floor(z / grid.cell)) + 0.4, z));
    }
    const ring = new THREE.Line(new THREE.BufferGeometry().setFromPoints(points), draped(new THREE.LineBasicMaterial({ color: 0xffd25a })));
    marks.add(ring);
  }
  if (pressed?.cells?.size) {
    for (const [x, y] of pressed.cells.values()) {
      const tile = new THREE.Mesh(new THREE.PlaneGeometry(grid.cell, grid.cell, 2, 2).rotateX(-Math.PI / 2), paintMark);
      tile.position.set((x + 0.5) * grid.cell, grid.storeyHeight(x, y) + 0.2, (y + 0.5) * grid.cell);
      marks.add(tile);
    }
  }
}

// ---- the page ----

function resize() {
  const width = view.clientWidth;
  const height = Math.max(view.clientHeight, 1);
  renderer.setSize(width, height, false);
  camera.aspect = width / height;
  camera.updateProjectionMatrix();
}
new ResizeObserver(resize).observe(view);
resize();

renderer.setAnimationLoop(() => {
  controls.update();
  renderer.render(world, camera);
});

function setTool(next) {
  tool = { ...tool, ...next };
  redrawMarks();
}

const waiting = window.duke.waiting;
/** A word from the IDE about the last thing the hand did, until the hand moves again. */
function say(text) {
  status.textContent = text;
}

window.duke = { showMap, tool: setTool, say };
for (const [call, ...args] of waiting) window.duke[call](...args);
