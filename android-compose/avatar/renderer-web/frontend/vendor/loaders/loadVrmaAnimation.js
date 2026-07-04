// VRMA (.vrma) -> THREE.AnimationClip for a target VRM.
//
// Thin wrapper over the vendored @pixiv/three-vrm-animation 3.4.5:
// VRMAnimationLoaderPlugin parses the VRMC_vrm_animation glTF extension, then
// createVRMAnimationClip retargets the parsed VRMAnimation onto the given VRM's
// normalized humanoid bones (same normalized space as our rest pose and
// standard gesture clips). MIT — see three-vrm-animation.module.min.js header.

import { GLTFLoader } from './GLTFLoader.js';
import { VRMAnimationLoaderPlugin, createVRMAnimationClip } from '@pixiv/three-vrm-animation';

/**
 * Load a .vrma animation and retarget it onto `vrm`.
 *
 * @param {string} url  URL of the .vrma file
 * @param {object} vrm  Target @pixiv/three-vrm VRM (must have .humanoid)
 * @returns {Promise<THREE.AnimationClip>}
 */
export async function loadVrmaAnimation(url, vrm) {
  const loader = new GLTFLoader();
  loader.register((parser) => new VRMAnimationLoaderPlugin(parser));
  const gltf = await loader.loadAsync(url);
  const vrmAnimations = gltf.userData.vrmAnimations;
  if (!vrmAnimations || vrmAnimations.length === 0) {
    throw new Error('No VRMC_vrm_animation found in .vrma');
  }
  return createVRMAnimationClip(vrmAnimations[0], vrm);
}
