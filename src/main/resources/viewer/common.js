// What every page of the viewer draws the same way the game's client does: where a file is served from, a model
// dressed as a creature is, and what it carries hung on its bones.
import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';

export const loader = new GLTFLoader();

/** A path from the resource root, as the IDE serves it. */
export const res = path => '../res/' + path.split('/').map(encodeURIComponent).join('/');

const loaded = new Map();

/** A model file, read once however many things are drawn from it; null where it cannot be read. */
export function model(path) {
  if (!loaded.has(path)) loaded.set(path, loader.loadAsync(res(path)).catch(() => null));
  return loaded.get(path);
}

const skins = new Map();

/** A texture named for a model, read the way the game reads one for glTF: not flipped, in sRGB. */
export function skin(path) {
  if (!skins.has(path)) {
    skins.set(path, new THREE.TextureLoader().loadAsync(res(path)).then(texture => {
      texture.flipY = false;
      texture.colorSpace = THREE.SRGBColorSpace;
      return texture;
    }).catch(() => null));
  }
  return skins.get(path);
}

/**
 * Plain lighting over the colour map, times the tint, as the game draws a creature: its client lights no PBR
 * material, so a picture that did would show something the game does not. Skinning needs nothing of the material.
 */
export function dress(object, texture, tint) {
  object.traverse(node => {
    if (!node.isMesh) return;
    const dressed = (Array.isArray(node.material) ? node.material : [node.material]).map(material => new THREE.MeshLambertMaterial({
      map: texture ?? material.map ?? null,
      color: tint,
      transparent: material.transparent,
      alphaTest: material.alphaTest,
      side: material.side,
    }));
    node.material = Array.isArray(node.material) ? dressed : dressed[0];
  });
}

/** One carried model on its bone: turned, moved and sized as the block says. A problem is said, not thrown. */
export async function hang(body, held, tint) {
  // The loader names nodes as animation tracks do, without the dots: `handslot.r` is `handslotr`.
  const bone = body.getObjectByName(THREE.PropertyBinding.sanitizeNodeName(held.bone));
  if (!bone) return `no bone ${held.bone}`;
  const file = await model(held.model);
  if (!file) return `no model ${held.model}`;
  const thing = file.scene.clone(true);
  dress(thing, null, tint);
  const degrees = THREE.MathUtils.degToRad;
  thing.scale.setScalar(held.scale);
  // jME's fromAngles(pitch, yaw, roll) turns in this order.
  thing.rotation.set(degrees(held.pitch), degrees(held.yaw), degrees(held.roll), 'YZX');
  thing.position.set(held.x, held.y, held.z);
  bone.add(thing);
  return null;
}
