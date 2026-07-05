// Shared Mixamo-skeleton -> VRM humanoid retarget core.
//
// Extracted from the three-vrm Mixamo example port (see loadMixamoAnimation.js
// for the upstream provenance / MIT lineage). Both the FBX loader and the GLB
// loader (Ready Player Me animation library) parse to a THREE scene whose bones
// carry Mixamo-compatible names, then hand the loaded asset + one clip here to
// perform the retarget. Factoring the math out keeps the FBX and GLB paths from
// duplicating the rest-rotation conjugation, hips rescale, and VRM0 sign flip.
//
// The one generalization over the upstream example is prefix detection: Mixamo
// FBX names bones `mixamorigHips` (or numbered `mixamorig11Hips` for re-uploaded
// rigs); the RPM GLB library ships the SAME skeleton with the prefix stripped
// ("Hips", "Spine", "LeftForeArm", ...). We detect the prefix from the Hips bone
// and remap the rig table onto it, so an empty prefix ("Hips") retargets exactly
// like "mixamorigHips".

import * as THREE from 'three';

/**
 * A map from the *unprefixed* Mixamo bone name to the VRM Humanoid bone name.
 * The actual skeleton prefixes every key with a detected rig prefix
 * ("mixamorig", "mixamorig11", or "" for RPM-style plain names).
 */
const mixamoVRMRigMap = {
  Hips: 'hips',
  Spine: 'spine',
  Spine1: 'chest',
  Spine2: 'upperChest',
  Neck: 'neck',
  Head: 'head',
  LeftShoulder: 'leftShoulder',
  LeftArm: 'leftUpperArm',
  LeftForeArm: 'leftLowerArm',
  LeftHand: 'leftHand',
  LeftHandThumb1: 'leftThumbMetacarpal',
  LeftHandThumb2: 'leftThumbProximal',
  LeftHandThumb3: 'leftThumbDistal',
  LeftHandIndex1: 'leftIndexProximal',
  LeftHandIndex2: 'leftIndexIntermediate',
  LeftHandIndex3: 'leftIndexDistal',
  LeftHandMiddle1: 'leftMiddleProximal',
  LeftHandMiddle2: 'leftMiddleIntermediate',
  LeftHandMiddle3: 'leftMiddleDistal',
  LeftHandRing1: 'leftRingProximal',
  LeftHandRing2: 'leftRingIntermediate',
  LeftHandRing3: 'leftRingDistal',
  LeftHandPinky1: 'leftLittleProximal',
  LeftHandPinky2: 'leftLittleIntermediate',
  LeftHandPinky3: 'leftLittleDistal',
  RightShoulder: 'rightShoulder',
  RightArm: 'rightUpperArm',
  RightForeArm: 'rightLowerArm',
  RightHand: 'rightHand',
  RightHandPinky1: 'rightLittleProximal',
  RightHandPinky2: 'rightLittleIntermediate',
  RightHandPinky3: 'rightLittleDistal',
  RightHandRing1: 'rightRingProximal',
  RightHandRing2: 'rightRingIntermediate',
  RightHandRing3: 'rightRingDistal',
  RightHandMiddle1: 'rightMiddleProximal',
  RightHandMiddle2: 'rightMiddleIntermediate',
  RightHandMiddle3: 'rightMiddleDistal',
  RightHandIndex1: 'rightIndexProximal',
  RightHandIndex2: 'rightIndexIntermediate',
  RightHandIndex3: 'rightIndexDistal',
  RightHandThumb1: 'rightThumbMetacarpal',
  RightHandThumb2: 'rightThumbProximal',
  RightHandThumb3: 'rightThumbDistal',
  LeftUpLeg: 'leftUpperLeg',
  LeftLeg: 'leftLowerLeg',
  LeftFoot: 'leftFoot',
  LeftToeBase: 'leftToes',
  RightUpLeg: 'rightUpperLeg',
  RightLeg: 'rightLowerLeg',
  RightFoot: 'rightFoot',
  RightToeBase: 'rightToes',
};

/**
 * Detect the rig prefix from a Mixamo-compatible skeleton and build the rig map
 * keyed on the actual bone names in `asset`.
 *
 * The prefix is whatever precedes "Hips" on the hips bone:
 *   - "mixamorig"     — standard Mixamo FBX
 *   - "mixamorig11"   — re-uploaded / re-processed Mixamo rigs (FBXLoader strips
 *                       the ":" separator, leaving digits fused to the prefix)
 *   - ""              — Ready Player Me GLB library (plain "Hips", "Spine", ...)
 *
 * @param {THREE.Object3D} asset  Loaded scene root to traverse for the hips bone
 * @returns {{ hipsNode: THREE.Object3D, rigMap: Record<string,string> }}
 * @throws if no `<prefix>Hips` bone is found (not a Mixamo-compatible rig)
 */
export function resolveMixamoRig(asset) {
  let hipsNode = null;
  let rigPrefix = null;
  asset.traverse((node) => {
    // Match "<prefix>Hips" where prefix is "mixamorig", "mixamorig<digits>", or
    // empty. Anchor to the whole name so "...UpperHips" style bones can't match.
    if (rigPrefix === null && /^(mixamorig\d*)?Hips$/.test(node.name)) {
      hipsNode = node;
      rigPrefix = node.name.slice(0, node.name.length - 'Hips'.length);
    }
  });
  if (!hipsNode) {
    throw new Error('not a Mixamo-compatible humanoid rig (no *Hips bone)');
  }
  const rigMap = rigPrefix === ''
    ? mixamoVRMRigMap
    : Object.fromEntries(
        Object.entries(mixamoVRMRigMap).map(
          ([name, bone]) => [rigPrefix + name, bone],
        ),
      );
  return { hipsNode, rigMap };
}

/**
 * Retarget one loaded clip authored on a Mixamo-compatible skeleton onto `vrm`'s
 * normalized humanoid bones, returning a fresh THREE.AnimationClip.
 *
 * Shared by both loaders. `asset` is the loaded scene root (needed for the
 * per-bone rest-pose world rotations that drive the conjugation); `clip` is the
 * source clip whose tracks are `<boneName>.quaternion` / `.position`. The output
 * clip is named "vrmAnimation" (the caller renames it to the source id).
 *
 * Math is unchanged from the upstream example:
 *   - each quaternion track is conjugated by the bone's rest world rotation
 *     (parentRestWorld * trackRotation * restWorldInverse),
 *   - hips translation is rescaled by the VRM/Mixamo hips-height ratio,
 *   - VRM 0.x targets get the x/z sign flip (metaVersion === '0').
 *
 * @param {THREE.Object3D} asset  Loaded scene root (source skeleton)
 * @param {THREE.AnimationClip} clip  Source clip to retarget
 * @param {object} vrm  Target @pixiv/three-vrm VRM (must have .humanoid)
 * @returns {THREE.AnimationClip}
 */
export function retargetMixamoClip(asset, clip, vrm) {
  const { hipsNode, rigMap } = resolveMixamoRig(asset);

  const tracks = [];
  const restRotationInverse = new THREE.Quaternion();
  const parentRestWorldRotation = new THREE.Quaternion();
  const _quatA = new THREE.Quaternion();

  const motionHipsHeight = hipsNode.position.y;
  const restHips = vrm.humanoid.normalizedRestPose.hips.position;
  const vrmHipsHeight = restHips[1];
  const hipsPositionScale = vrmHipsHeight / motionHipsHeight;

  clip.tracks.forEach((track) => {
    const trackSplitted = track.name.split('.');
    const mixamoRigName = trackSplitted[0];
    const vrmBoneName = rigMap[mixamoRigName];
    const vrmNodeName = vrm.humanoid?.getNormalizedBoneNode(vrmBoneName)?.name;
    const mixamoRigNode = asset.getObjectByName(mixamoRigName);

    if (vrmNodeName != null && mixamoRigNode != null) {
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

        const values = track.values.map(
          (v, i) => (vrm.meta?.metaVersion === '0' && i % 2 === 0 ? -v : v),
        );

        // Quaternion hemisphere continuity pass. q and -q are the same
        // rotation, but the mixer interpolates the raw 4-vectors: if two
        // successive keys land on opposite hemispheres (dot < 0) the slerp
        // takes the long way round, and with keyframe-reduced 24fps tracks
        // that reads as a violent limb spin between neighbours (the "glitch
        // fest"). The conjugation above (premultiply parentRestWorld,
        // multiply restWorldInverse) and the VRM0 x/z flip can both introduce
        // such sign flips. Walk the keys and negate any whose dot with the
        // previous key is negative so every neighbour pair stays on the short
        // arc. Standard, cheap, and lossless (same rotation, canonical sign).
        for (let i = 4; i < values.length; i += 4) {
          const dot = values[i] * values[i - 4]
            + values[i + 1] * values[i - 3]
            + values[i + 2] * values[i - 2]
            + values[i + 3] * values[i - 1];
          if (dot < 0) {
            values[i] = -values[i];
            values[i + 1] = -values[i + 1];
            values[i + 2] = -values[i + 2];
            values[i + 3] = -values[i + 3];
          }
        }

        tracks.push(
          new THREE.QuaternionKeyframeTrack(
            `${vrmNodeName}.${propertyName}`,
            track.times,
            values,
          ),
        );
      } else if (track instanceof THREE.VectorKeyframeTrack) {
        const value = track.values.map(
          (v, i) => (vrm.meta?.metaVersion === '0' && i % 3 !== 1 ? -v : v) * hipsPositionScale,
        );
        // Root-motion handling (hips only; no other Mixamo bone has a position
        // track). Mixamo clips downloaded WITHOUT "in place" translate the hips
        // across the floor, carrying the avatar out of the stationary pet
        // window. The previous pass pinned X/Z to a constant, which also killed
        // the choreographed side-to-side sway of dances and stationary idles —
        // the body then slides/floats instead of shifting its weight. We keep
        // the sway and remove only the net travel:
        //   1. DETREND (locomotion only): if the total X/Z displacement from the
        //      first to the last key exceeds ~0.25 m, this is a travelling clip
        //      (walk/strafe). Subtract a linear ramp from 0 (at key 0) to that
        //      full displacement (at the last key) from every key, cancelling
        //      the steady drift while leaving the per-step bob and weight-shift
        //      wobble on top. Under the threshold it is a stationary clip whose
        //      X/Z motion IS the intended sway — left untouched.
        //   2. RECENTRE (always): shift key 0's X/Z onto the VRM rest hips X/Z
        //      so a clip whose first frame starts offset still plays centred.
        // Y is never touched — crouches, jumps, sits and the walk bob live there.
        if (vrmBoneName === 'hips' && value.length >= 6) {
          const restX = restHips[0];
          const restZ = restHips[2];
          const n = value.length;
          const firstX = value[0];
          const firstZ = value[2];
          const lastX = value[n - 3];
          const lastZ = value[n - 1];
          const dispX = lastX - firstX;
          const dispZ = lastZ - firstZ;
          const dispMag = Math.hypot(dispX, dispZ);
          const DRIFT_THRESHOLD = 0.25; // metres of net XZ travel = locomotion
          const keyCount = n / 3;
          const lastIndex = keyCount - 1; // ramp denominator (>= 1 here)
          const detrend = dispMag > DRIFT_THRESHOLD;
          for (let k = 0; k < keyCount; k += 1) {
            const i = k * 3;
            if (detrend) {
              // Linear ramp 0 -> full displacement across the clip, removed.
              const f = k / lastIndex;
              value[i] -= dispX * f;
              value[i + 2] -= dispZ * f;
            }
            // Recentre key 0 onto rest hips X/Z (same shift applied to every
            // key so relative sway/detrended motion is preserved).
            value[i] += restX - firstX;
            value[i + 2] += restZ - firstZ;
          }
        }
        tracks.push(new THREE.VectorKeyframeTrack(`${vrmNodeName}.${propertyName}`, track.times, value));
      }
    }
  });

  return new THREE.AnimationClip('vrmAnimation', clip.duration, tracks);
}
