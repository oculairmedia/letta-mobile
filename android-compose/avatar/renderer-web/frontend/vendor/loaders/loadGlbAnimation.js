// GLB (Mixamo-compatible skeleton) -> VRM humanoid retarget.
//
// For animation-only GLB clips authored on a Mixamo-compatible skeleton — the
// Ready Player Me open animation library (https://github.com/readyplayerme/
// animation-library) ships this way: an `Armature` whose bones use the Mixamo
// naming scheme WITHOUT the "mixamorig" prefix ("Hips", "Spine", "LeftForeArm",
// ...). We load via the vendored GLTFLoader, take the (single) embedded clip,
// and retarget it through the SAME shared core as the FBX path
// (retargetMixamoClip.js) — its prefix detection handles the empty prefix, so a
// plain "Hips" retargets exactly like a Mixamo "mixamorigHips".
//
// NOTE: unlike Mixamo FBX (whose take is always named "mixamo.com"), a GLB clip
// is named per-file (e.g. "M_Standing_Idle_001"), so we do NOT filter by name —
// we take the first animation in the file. The renderer renames the returned
// clip to the source id anyway.

import { GLTFLoader } from './GLTFLoader.js';
import { retargetMixamoClip } from './retargetMixamoClip.js';

/**
 * Load a GLB animation on a Mixamo-compatible (prefix-less) skeleton, retarget
 * it onto `vrm`'s normalized humanoid bones, and return the resulting
 * THREE.AnimationClip.
 *
 * @param {string} url  URL of the .glb animation
 * @param {object} vrm  Target @pixiv/three-vrm VRM (must have .humanoid)
 * @returns {Promise<THREE.AnimationClip>}
 */
export async function loadGlbAnimation(url, vrm) {
  const loader = new GLTFLoader();
  const gltf = await loader.loadAsync(url);
  const clip = gltf.animations && gltf.animations[0];
  if (!clip) {
    throw new Error('GLB has no embedded animation clip');
  }
  // gltf.scene is the loaded skeleton root; retargetMixamoClip traverses it for
  // the *Hips bone and rest-pose world rotations. A GLB with no recognizable
  // Hips bone throws a clear per-source error here (caught by the import loop).
  return retargetMixamoClip(gltf.scene, clip, vrm);
}
