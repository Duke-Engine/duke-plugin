// A block as the game draws it: its model, dressed as the client dresses a unit — the texture over it, the
// tint, what it carries on which bone — moving by the clips its fields name, and the sounds that are its.
import * as THREE from 'three';
import { loader, res, dress, hang, skin } from './common.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';

const view = document.getElementById('view');
const actionsBar = document.getElementById('actions');
const soundsBar = document.getElementById('sounds');
const status = document.getElementById('status');

const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(window.devicePixelRatio);
view.appendChild(renderer.domElement);

const world = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(35, 1, 0.01, 1000);
camera.position.set(1, 1, 3);
const controls = new OrbitControls(camera, renderer.domElement);
controls.enableDamping = true;
world.add(new THREE.HemisphereLight(0xffffff, 0x60646c, 1.6));
const sun = new THREE.DirectionalLight(0xffffff, 1.8);
sun.position.set(3, 5, 4);
world.add(sun);
let grid = new THREE.GridHelper(10, 10);
world.add(grid);

const clock = new THREE.Clock();

let body = null;
let mixer = null;
let clips = new Map();
let shownBody = '';
let loads = 0;
let actions = [];
let chosen = null;
let chosenLabel = null;
let audio = null;

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
  mixer?.update(clock.getDelta());
  controls.update();
  renderer.render(world, camera);
});

function show(scene, colours) {
  if (colours) paint(colours);
  document.body.classList.toggle('sounds-only', !scene.model);
  actions = scene.actions;
  const before = chosen;
  // An action whose clip was changed keeps playing as that action: the Walk just set to another clip is shown walking.
  if (!actions.some(action => action.clip === chosen)) chosen = (actions.find(action => action.label === chosenLabel) ?? actions[0])?.clip ?? null;
  chosenLabel = actions.find(action => action.clip === chosen)?.label ?? chosenLabel;
  drawSounds(scene.sounds);
  const key = JSON.stringify([scene.model, scene.texture, scene.tint, scene.held, scene.libraries]);
  if (key !== shownBody) {
    shownBody = key;
    load(scene);
  } else if (chosen !== before) {
    start();
  }
  drawActions();
}

/** The IDE's own colours, so the page is part of the tool window rather than a hole in it. */
function paint(colours) {
  for (const [name, value] of Object.entries(colours)) document.documentElement.style.setProperty('--' + name, value);
  const next = new THREE.GridHelper(10, 10, colours.border, colours.border);
  next.scale.copy(grid.scale);
  next.position.copy(grid.position);
  world.remove(grid);
  grid.geometry.dispose();
  grid.material.dispose();
  grid = next;
  world.add(grid);
}

async function load(scene) {
  const generation = ++loads;
  if (body) world.remove(body);
  body = null;
  mixer = null;
  clips = new Map();
  if (!scene.model) return;
  status.textContent = 'Loading…';
  const problems = [];
  try {
    const gltf = await loader.loadAsync(res(scene.model));
    if (generation !== loads) return;
    const model = gltf.scene;
    let texture = null;
    if (scene.texture) {
      texture = await skin(scene.texture);
      if (!texture) problems.push(`no texture ${scene.texture}`);
      if (generation !== loads) return;
    }
    const tint = new THREE.Color(scene.tint ?? '#ffffff');
    dress(model, texture, tint);
    for (const held of scene.held) {
      const problem = await hang(model, held, tint);
      if (generation !== loads) return;
      if (problem) problems.push(problem);
    }
    for (const clip of gltf.animations) clips.set(clip.name, clip);
    for (const library of scene.libraries) {
      try {
        const file = await loader.loadAsync(res(library));
        if (generation !== loads) return;
        for (const clip of file.animations) if (!clips.has(clip.name)) clips.set(clip.name, clip);
      } catch {
        problems.push(`no clips in ${library}`);
      }
    }
    body = model;
    world.add(model);
    mixer = new THREE.AnimationMixer(model);
    frame(model);
    status.textContent = problems.join(' · ');
    start();
  } catch {
    if (generation === loads) status.textContent = `Could not load ${scene.model}`;
  }
}

/** The camera where the whole body is in view, and the floor under its feet. */
function frame(object) {
  object.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(object);
  if (box.isEmpty()) return;
  const centre = box.getCenter(new THREE.Vector3());
  const size = box.getSize(new THREE.Vector3());
  const radius = Math.max(size.x, size.y, size.z) / 2 || 1;
  const distance = radius / Math.sin(THREE.MathUtils.degToRad(camera.fov / 2)) * 1.15;
  camera.near = distance / 100;
  camera.far = distance * 100;
  camera.updateProjectionMatrix();
  camera.position.copy(centre).add(new THREE.Vector3(0.45, 0.3, 1).normalize().multiplyScalar(distance));
  controls.target.copy(centre);
  controls.update();
  grid.scale.setScalar(radius * 0.4);
  grid.position.set(centre.x, box.min.y, centre.z);
}

function play(name) {
  chosen = name;
  chosenLabel = actions.find(action => action.clip === name)?.label ?? null;
  start();
}

/** The chosen clip from its start, once the body and its clips are there. */
function start() {
  if (mixer) {
    mixer.stopAllAction();
    const clip = clips.get(chosen);
    if (clip) mixer.clipAction(clip).reset().play();
  }
  drawActions();
}

function drawActions() {
  actionsBar.replaceChildren(...actions.map(({ label, clip }) => {
    const button = document.createElement('button');
    button.textContent = label;
    const missing = mixer && !clips.has(clip);
    button.title = missing ? `${clip} — in none of the files` : clip;
    button.classList.toggle('missing', Boolean(missing));
    button.classList.toggle('on', clip === chosen);
    button.onclick = () => play(clip);
    return button;
  }));
}

/** A button for each sound, each press the next of its files — and a click here is what lets the page play one. */
function drawSounds(sounds) {
  soundsBar.replaceChildren(...sounds.map(sound => {
    const button = document.createElement('button');
    button.textContent = '▶ ' + sound.label;
    button.title = sound.files.join('\n');
    let next = 0;
    button.onclick = () => {
      const file = sound.files[next++ % sound.files.length];
      audio?.pause();
      audio = new Audio(res(file));
      audio.volume = Math.min(Math.max(sound.gain, 0), 1);
      audio.play().catch(() => { status.textContent = `Could not play ${file}`; });
    };
    return button;
  }));
}

const waiting = window.duke.waiting;
window.duke = { show, play };
for (const [call, ...args] of waiting) window.duke[call](...args);
