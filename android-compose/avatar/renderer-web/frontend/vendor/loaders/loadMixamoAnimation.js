// Mixamo FBX -> VRM humanoid retarget.
//
// Ported from the official @pixiv/three-vrm example (MIT):
//   https://github.com/pixiv/three-vrm/blob/v3.4.5/packages/three-vrm/examples/humanoidAnimation/loadMixamoAnimation.js
//   https://github.com/pixiv/three-vrm/blob/v3.4.5/packages/three-vrm/examples/humanoidAnimation/mixamoVRMRigMap.js
// Copyright (c) pixiv Inc. — MIT (three-vrm LICENSE).
//
// This file is now a thin FBX front-end: it loads the .fbx, picks the
// "mixamo.com" take, and hands the loaded asset + clip to the shared retarget
// core (retargetMixamoClip.js), which owns the rig map, prefix detection, and
// the rest-rotation conjugation / hips rescale / VRM0 sign-flip math. The GLB
// loader (Ready Player Me library) shares that same core, so the retarget itself
// lives in exactly one place.

import { FBXLoader } from './FBXLoader.js';
import { retargetMixamoClip } from './retargetMixamoClip.js';
import * as THREE from 'three';

/**
 * Load a Mixamo FBX animation, retarget it onto `vrm`'s normalized humanoid
 * bones, and return the resulting THREE.AnimationClip.
 *
 * @param {string} url  URL of the Mixamo .fbx animation
 * @param {object} vrm  Target @pixiv/three-vrm VRM (must have .humanoid)
 * @returns {Promise<THREE.AnimationClip>}
 */
export function loadMixamoAnimation(url, vrm) {
  const loader = new FBXLoader();
  return loader.loadAsync(url).then((asset) => {
    // Mixamo exports name the take "mixamo.com".
    const clip = THREE.AnimationClip.findByName(asset.animations, 'mixamo.com');
    if (!clip) {
      throw new Error("FBX has no 'mixamo.com' animation take");
    }
    return retargetMixamoClip(asset, clip, vrm);
  });
}
