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
        // Root-motion stripping (hips only). Mixamo clips downloaded WITHOUT the
        // "in place" option translate the hips across the floor (walks, dances),
        // which carries the avatar out of a stationary pet window. For the hips
        // position track we pin the horizontal (X/Z) channels while keeping Y
        // fully intact — crouches, jumps, sits and the natural walk bob all live
        // in Y and must survive. Two steps:
        //   1. strip the per-frame X/Z delta relative to the first keyframe, so
        //      the body no longer travels;
        //   2. recenter that now-constant X/Z onto the VRM rest hips X/Z, so a
        //      clip whose first frame is already offset (some Mixamo takes start
        //      the hips far from origin) still plays centered in frame.
        // Net effect: X/Z are held at the rest hips position for the whole clip
        // while Y animates freely. Applied only to the hips bone; no other
        // Mixamo bone carries a position track.
        if (vrmBoneName === 'hips' && value.length >= 3) {
          const restX = restHips[0];
          const restZ = restHips[2];
          for (let i = 0; i < value.length; i += 3) {
            // Step 1 (strip drift) collapses every frame's X/Z onto the first
            // frame's X/Z: value[i] − (value[i] − value[0]) = value[0]. Step 2
            // (recenter) then shifts that constant to the rest hips X/Z. The two
            // compose to a single assignment — every frame's X/Z becomes the rest
            // value — so we write it directly rather than reconstructing the
            // intermediate. Y (value[i+1]) is left untouched.
            value[i] = restX;
            value[i + 2] = restZ;
          }
        }
        tracks.push(new THREE.VectorKeyframeTrack(`${vrmNodeName}.${propertyName}`, track.times, value));
      }
    }
  });

  return new THREE.AnimationClip('vrmAnimation', clip.duration, tracks);
}
