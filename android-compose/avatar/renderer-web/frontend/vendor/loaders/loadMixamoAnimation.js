// Mixamo FBX -> VRM humanoid retarget.
//
// Ported from the official @pixiv/three-vrm example (MIT):
//   https://github.com/pixiv/three-vrm/blob/v3.4.5/packages/three-vrm/examples/humanoidAnimation/loadMixamoAnimation.js
//   https://github.com/pixiv/three-vrm/blob/v3.4.5/packages/three-vrm/examples/humanoidAnimation/mixamoVRMRigMap.js
// Copyright (c) pixiv Inc. — MIT (three-vrm LICENSE).
//
// Adapted for our vendored layout: imports the vendored FBXLoader by relative
// path (the example used the `three/addons/...` specifier) and folds the rig
// map inline. The retarget itself is unchanged — it already targets the
// three-vrm NORMALIZED humanoid bones (getNormalizedBoneNode), which is exactly
// the space our rest pose and standard gesture clips use, so a Mixamo clip
// composes with them without extra conversion. Hips translation is rescaled by
// the VRM/Mixamo hips-height ratio (hipsPositionScale) so motion built on a
// Mixamo skeleton fits any VRM's proportions.

import * as THREE from 'three';
import { FBXLoader } from './FBXLoader.js';

/** A map from Mixamo rig name to VRM Humanoid bone name. */
const mixamoVRMRigMap = {
  mixamorigHips: 'hips',
  mixamorigSpine: 'spine',
  mixamorigSpine1: 'chest',
  mixamorigSpine2: 'upperChest',
  mixamorigNeck: 'neck',
  mixamorigHead: 'head',
  mixamorigLeftShoulder: 'leftShoulder',
  mixamorigLeftArm: 'leftUpperArm',
  mixamorigLeftForeArm: 'leftLowerArm',
  mixamorigLeftHand: 'leftHand',
  mixamorigLeftHandThumb1: 'leftThumbMetacarpal',
  mixamorigLeftHandThumb2: 'leftThumbProximal',
  mixamorigLeftHandThumb3: 'leftThumbDistal',
  mixamorigLeftHandIndex1: 'leftIndexProximal',
  mixamorigLeftHandIndex2: 'leftIndexIntermediate',
  mixamorigLeftHandIndex3: 'leftIndexDistal',
  mixamorigLeftHandMiddle1: 'leftMiddleProximal',
  mixamorigLeftHandMiddle2: 'leftMiddleIntermediate',
  mixamorigLeftHandMiddle3: 'leftMiddleDistal',
  mixamorigLeftHandRing1: 'leftRingProximal',
  mixamorigLeftHandRing2: 'leftRingIntermediate',
  mixamorigLeftHandRing3: 'leftRingDistal',
  mixamorigLeftHandPinky1: 'leftLittleProximal',
  mixamorigLeftHandPinky2: 'leftLittleIntermediate',
  mixamorigLeftHandPinky3: 'leftLittleDistal',
  mixamorigRightShoulder: 'rightShoulder',
  mixamorigRightArm: 'rightUpperArm',
  mixamorigRightForeArm: 'rightLowerArm',
  mixamorigRightHand: 'rightHand',
  mixamorigRightHandPinky1: 'rightLittleProximal',
  mixamorigRightHandPinky2: 'rightLittleIntermediate',
  mixamorigRightHandPinky3: 'rightLittleDistal',
  mixamorigRightHandRing1: 'rightRingProximal',
  mixamorigRightHandRing2: 'rightRingIntermediate',
  mixamorigRightHandRing3: 'rightRingDistal',
  mixamorigRightHandMiddle1: 'rightMiddleProximal',
  mixamorigRightHandMiddle2: 'rightMiddleIntermediate',
  mixamorigRightHandMiddle3: 'rightMiddleDistal',
  mixamorigRightHandIndex1: 'rightIndexProximal',
  mixamorigRightHandIndex2: 'rightIndexIntermediate',
  mixamorigRightHandIndex3: 'rightIndexDistal',
  mixamorigRightHandThumb1: 'rightThumbMetacarpal',
  mixamorigRightHandThumb2: 'rightThumbProximal',
  mixamorigRightHandThumb3: 'rightThumbDistal',
  mixamorigLeftUpLeg: 'leftUpperLeg',
  mixamorigLeftLeg: 'leftLowerLeg',
  mixamorigLeftFoot: 'leftFoot',
  mixamorigLeftToeBase: 'leftToes',
  mixamorigRightUpLeg: 'rightUpperLeg',
  mixamorigRightLeg: 'rightLowerLeg',
  mixamorigRightFoot: 'rightFoot',
  mixamorigRightToeBase: 'rightToes',
};

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

    const tracks = [];

    const restRotationInverse = new THREE.Quaternion();
    const parentRestWorldRotation = new THREE.Quaternion();
    const _quatA = new THREE.Quaternion();

    // Rescale hips translation by the VRM/Mixamo hips-height ratio so the clip
    // fits this model's proportions rather than the Mixamo skeleton's.
    const mixamoHips = asset.getObjectByName('mixamorigHips');
    if (!mixamoHips) {
      throw new Error('FBX is not a Mixamo humanoid rig (no mixamorigHips)');
    }
    const motionHipsHeight = mixamoHips.position.y;
    const vrmHipsHeight = vrm.humanoid.normalizedRestPose.hips.position[1];
    const hipsPositionScale = vrmHipsHeight / motionHipsHeight;

    clip.tracks.forEach((track) => {
      const trackSplitted = track.name.split('.');
      const mixamoRigName = trackSplitted[0];
      const vrmBoneName = mixamoVRMRigMap[mixamoRigName];
      const vrmNodeName = vrm.humanoid?.getNormalizedBoneNode(vrmBoneName)?.name;
      const mixamoRigNode = asset.getObjectByName(mixamoRigName);

      if (vrmNodeName != null) {
        const propertyName = trackSplitted[1];

        // Rest-pose world rotations for the retarget conjugation.
        mixamoRigNode.getWorldQuaternion(restRotationInverse).invert();
        mixamoRigNode.parent.getWorldQuaternion(parentRestWorldRotation);

        if (track instanceof THREE.QuaternionKeyframeTrack) {
          for (let i = 0; i < track.values.length; i += 4) {
            const flatQuaternion = track.values.slice(i, i + 4);
            _quatA.fromArray(flatQuaternion);
            // parentRestWorld * trackRotation * restWorldInverse
            _quatA.premultiply(parentRestWorldRotation).multiply(restRotationInverse);
            _quatA.toArray(flatQuaternion);
            flatQuaternion.forEach((v, index) => {
              track.values[index + i] = v;
            });
          }

          tracks.push(
            new THREE.QuaternionKeyframeTrack(
              `${vrmNodeName}.${propertyName}`,
              track.times,
              track.values.map((v, i) => (vrm.meta?.metaVersion === '0' && i % 2 === 0 ? -v : v)),
            ),
          );
        } else if (track instanceof THREE.VectorKeyframeTrack) {
          const value = track.values.map(
            (v, i) => (vrm.meta?.metaVersion === '0' && i % 3 !== 1 ? -v : v) * hipsPositionScale,
          );
          tracks.push(new THREE.VectorKeyframeTrack(`${vrmNodeName}.${propertyName}`, track.times, value));
        }
      }
    });

    return new THREE.AnimationClip('vrmAnimation', clip.duration, tracks);
  });
}
