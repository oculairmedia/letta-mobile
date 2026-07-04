// Letta avatar web renderer — mechanism only, no behavior policy.
//
// Speaks AvatarWireProtocol v1 (see AvatarWireProtocol.kt): the host sends
// command JSON via handleCommand(), the renderer emits event JSON through the
// `emit` callback. High-level behavior (blink cadence, gaze targeting, emotion
// policy) lives in the Kotlin director — this file just applies state to the
// three-vrm scene, so any future native renderer can replay the same command
// stream. The two exceptions are inert baseline mechanics that every renderer
// must reproduce identically for a VRM to look alive at all: a procedural
// arms-down rest pose (VRM humanoids bind in a T-pose that nothing else undoes)
// and a subtle deterministic breathing sway. Both are additive, humanoid-only,
// and suspended whenever an embedded animation clip is driving the skeleton.
//
// Renderer stack (vendored, pinned): three r180 + @pixiv/three-vrm 3.4.5 —
// the reference VRM runtime (MToon, spring bones, expressions, look-at).

import * as THREE from 'three';
import { GLTFLoader } from './vendor/loaders/GLTFLoader.js';
import { VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm';
import { loadVrmaAnimation } from './vendor/loaders/loadVrmaAnimation.js';
import { loadMixamoAnimation } from './vendor/loaders/loadMixamoAnimation.js';
import { loadGlbAnimation } from './vendor/loaders/loadGlbAnimation.js';

export const PROTOCOL_VERSION = 2;

const VISEME_KEYS = ['aa', 'ih', 'ou', 'ee', 'oh'];

// --- Baseline body mechanics (rest pose + breathing) ------------------------
// VRM humanoids bind in a T-pose and the face-level channels never touch the
// body, so without this the arms stay stuck out sideways. We apply a fixed
// arms-down rest offset per humanoid bone, then add a small sinusoidal
// breathing sway on top each frame. Everything is expressed in the three-vrm
// NORMALIZED humanoid space (getNormalizedBoneNode), where three-vrm has
// already reconciled VRM0/VRM1 axis differences for us — so we must NOT
// re-apply the rotateVRM0 compensation here.
//
// Sign convention (verified live against the sample VRM in normalized space):
// a NEGATIVE Z rotation on leftUpperArm swings it DOWN to the model's side; the
// right side is the mirror (positive Z). ~1.2 rad drops the arms to a natural
// rest angle without punching through the hips/skirt.
const ARM_DOWN_Z = 1.2; // upper-arm drop from T-pose (~69°)
const ELBOW_BEND_Z = 0.22; // slight lower-arm bend (~13°)
const SHOULDER_DROP_Z = 0.08; // tiny natural shoulder relax (~4.5°)
// Per-bone rest offsets, keyed by three-vrm humanoid bone name. Left/right are
// mirrored on Z. Applied as the base local rotation; breathing adds onto it.
const REST_POSE = {
  leftShoulder: { x: 0, y: 0, z: SHOULDER_DROP_Z },
  rightShoulder: { x: 0, y: 0, z: -SHOULDER_DROP_Z },
  leftUpperArm: { x: 0, y: 0, z: -ARM_DOWN_Z },
  rightUpperArm: { x: 0, y: 0, z: ARM_DOWN_Z },
  leftLowerArm: { x: 0, y: 0, z: -ELBOW_BEND_Z },
  rightLowerArm: { x: 0, y: 0, z: ELBOW_BEND_Z },
};
// Breathing: chest/spine lift on X plus faint shoulder + head follow. Driven by
// accumulated delta time (no Math.random) so every renderer breathes in step.
const BREATH_PERIOD = 4.0; // seconds per breath
const BREATH_CHEST_X = 0.02; // primary spine/chest rise (~1.1°)
const BREATH_SHOULDER_Z = 0.01; // faint shoulder swell
const BREATH_HEAD_X = 0.006; // barely-there head bob

export function createAvatarRenderer(canvas, emit) {
  const renderer = new THREE.WebGLRenderer({
    canvas,
    alpha: true, // transparent clear — pet-mode windows composite over the desktop
    antialias: true,
  });
  renderer.setPixelRatio(window.devicePixelRatio);
  renderer.setClearColor(0x000000, 0);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(30, 1, 0.1, 50);
  camera.position.set(0, 1.35, 1.8);

  // 0.55x/0.22x of the stock PI-based rig: full intensity read blown-out when
  // composited over desktop wallpapers (pet mode has no dark app chrome).
  const keyLight = new THREE.DirectionalLight(0xffffff, 0.55 * Math.PI);
  keyLight.position.set(1, 2, 1.5);
  scene.add(keyLight);
  scene.add(new THREE.AmbientLight(0xffffff, 0.22 * Math.PI));

  const loader = new GLTFLoader();
  loader.register((parser) => new VRMLoaderPlugin(parser));

  const clock = new THREE.Clock();
  const lookAtTarget = new THREE.Object3D();
  scene.add(lookAtTarget);

  let breathPhase = 0; // accumulated seconds — deterministic breathing input

  let current = null; // { root, vrm|null, mixer|null, clipsById, accessoryBindings, restBones, activeClips }
  // Only the newest load may mutate the scene; older in-flight loadAsync
  // resolutions are discarded (the host ignores their acks by requestId too).
  let activeLoadRequestId = null;

  function resize() {
    const width = canvas.clientWidth || canvas.width;
    const height = canvas.clientHeight || canvas.height;
    renderer.setSize(width, height, false);
    camera.aspect = width / height;
    camera.updateProjectionMatrix();
  }
  window.addEventListener('resize', resize);
  resize();

  renderer.setAnimationLoop(() => {
    const delta = clock.getDelta();
    if (current) {
      // Rest pose + breathing own the humanoid skeleton ONLY while no embedded
      // clip is driving it — a clip must win outright, so we skip our overrides
      // the moment one is playing and let it restore naturally when it stops.
      if (current.restBones && !clipDriving()) {
        breathPhase += delta;
        applyRestAndBreathing(current.restBones, breathPhase);
      }
      if (current.mixer) current.mixer.update(delta);
      // vrm.update drives expressions, look-at, spring bones, constraints.
      if (current.vrm) current.vrm.update(delta);
    }
    renderer.render(scene, camera);
  });

  // A clip is driving the skeleton if any registered mixer action is still
  // running. When true the rest-pose/breathing overrides stand down so the clip
  // wins; finished (non-loop) actions are pruned so we resume afterward.
  function clipDriving() {
    const actions = current?.activeClips;
    if (!actions || actions.size === 0) return false;
    let driving = false;
    for (const action of actions) {
      if (action.isRunning() && action.getEffectiveWeight() > 0) driving = true;
      else if (!action.isRunning()) actions.delete(action);
    }
    return driving;
  }

  // Collect the normalized humanoid bone nodes we pose, keyed by bone name.
  // Missing bones (partial rigs) are simply skipped.
  function collectRestBones(vrm) {
    if (!vrm?.humanoid) return null;
    const bones = {};
    for (const name of Object.keys(REST_POSE)) {
      const node = vrm.humanoid.getNormalizedBoneNode(name);
      if (node) bones[name] = node;
    }
    // Breathing targets — optional, posed additively over any rest offset.
    for (const name of ['spine', 'chest', 'upperChest', 'head']) {
      const node = vrm.humanoid.getNormalizedBoneNode(name);
      if (node) bones[name] = node;
    }
    return Object.keys(bones).length > 0 ? bones : null;
  }

  // Write the rest offset plus this frame's breathing onto each bone's local
  // rotation. Deterministic: breathing is a pure function of accumulated time.
  function applyRestAndBreathing(bones, phase) {
    const s = Math.sin((phase / BREATH_PERIOD) * Math.PI * 2);
    for (const [name, rest] of Object.entries(REST_POSE)) {
      const node = bones[name];
      if (!node) continue;
      let bz = 0;
      if (name === 'leftShoulder' || name === 'rightShoulder') {
        bz = (name === 'leftShoulder' ? 1 : -1) * BREATH_SHOULDER_Z * s;
      }
      node.rotation.set(rest.x, rest.y, rest.z + bz);
    }
    // Spine/chest lift is the dominant breathing motion; head gives a hint.
    const chest = bones.upperChest ?? bones.chest ?? bones.spine;
    if (chest) chest.rotation.x = BREATH_CHEST_X * s;
    if (bones.head) bones.head.rotation.x = BREATH_HEAD_X * s;
  }

  // --- Standard built-in gesture clips --------------------------------------
  // Procedural THREE.AnimationClips authored on the SAME normalized humanoid
  // bones the rest pose drives (getNormalizedBoneNode), so a clip and the rest
  // pose are expressed in one space. Each track keys a bone's LOCAL quaternion,
  // and every clip both STARTS and ENDS on the rest-pose rotation for that bone
  // (rest offset from REST_POSE, or identity for bones the rest pose leaves
  // alone). That anchoring is what lets playGesture's fade-in/out cross between
  // the additive rest-pose/breathing world (suspended while a clip drives, see
  // clipDriving()) and the clip without a pop: at t=0 and t=end the clip's pose
  // equals the pose the frame loop resumes writing.
  //
  // A track value at a keyframe is (rest euler for that bone) + (per-keyframe
  // delta), converted to a quaternion. Deltas reuse the rest-pose sign
  // convention: on the arms a MORE-NEGATIVE Z (left) / MORE-POSITIVE Z (right)
  // pushes further down; the opposite raises toward/over the head.
  const _euler = new THREE.Euler();
  const _quat = new THREE.Quaternion();

  // Rest euler (x,y,z) for a bone name — REST_POSE offset if it has one, else 0.
  function restEuler(boneName) {
    const r = REST_POSE[boneName];
    return r ? [r.x, r.y, r.z] : [0, 0, 0];
  }

  // Build a QuaternionKeyframeTrack for `.quaternion` of the normalized bone
  // `boneName`. `keys` is [{ t, dx?, dy?, dz? }, …]; each delta is added to the
  // bone's rest euler before conversion. Returns null if the rig lacks the bone
  // so partial rigs degrade gracefully.
  function boneTrack(vrm, boneName, keys) {
    const node = vrm.humanoid.getNormalizedBoneNode(boneName);
    if (!node) return null;
    const [rx, ry, rz] = restEuler(boneName);
    const times = new Float32Array(keys.length);
    const values = new Float32Array(keys.length * 4);
    keys.forEach((k, i) => {
      times[i] = k.t;
      _euler.set(rx + (k.dx ?? 0), ry + (k.dy ?? 0), rz + (k.dz ?? 0), 'XYZ');
      _quat.setFromEuler(_euler);
      values[i * 4 + 0] = _quat.x;
      values[i * 4 + 1] = _quat.y;
      values[i * 4 + 2] = _quat.z;
      values[i * 4 + 3] = _quat.w;
    });
    // Track name is the node's own name; the mixer is rooted at the model, and
    // normalized bone nodes have unique names, so this resolves unambiguously.
    return new THREE.QuaternionKeyframeTrack(`${node.name}.quaternion`, times, values);
  }

  function makeClip(name, duration, vrm, trackSpecs) {
    const tracks = [];
    for (const [boneName, keys] of trackSpecs) {
      const track = boneTrack(vrm, boneName, keys);
      if (track) tracks.push(track);
    }
    return tracks.length > 0 ? new THREE.AnimationClip(name, duration, tracks) : null;
  }

  // The four standard gestures. Durations match the spec; every track's first
  // and last key are delta-zero (rest pose). Angles are radians.
  function buildStandardGestureClips(vrm) {
    if (!vrm?.humanoid) return new Map();
    const clips = new Map();

    // wave (~1.8s): raise the right arm up past horizontal, then 3 hand waves
    // from the elbow, then lower back to rest. Right arm rests at +ARM_DOWN_Z
    // (down); a NEGATIVE dz raises it toward/over the head.
    const waveUp = -(ARM_DOWN_Z + 0.35); // right arm up beside the head
    const wave = makeClip('wave', 1.8, vrm, [
      ['rightUpperArm', [
        { t: 0, dz: 0 }, { t: 0.35, dz: waveUp },
        { t: 1.45, dz: waveUp }, { t: 1.8, dz: 0 },
      ]],
      ['rightLowerArm', [
        // Rock the forearm side to side from the elbow for the wave. Right
        // forearm rests at +ELBOW_BEND_Z; oscillate around a raised-out pose.
        { t: 0, dz: 0 }, { t: 0.35, dz: 0.5 },
        { t: 0.6, dz: 0.85 }, { t: 0.85, dz: 0.2 },
        { t: 1.1, dz: 0.85 }, { t: 1.35, dz: 0.2 },
        { t: 1.45, dz: 0.5 }, { t: 1.8, dz: 0 },
      ]],
    ]);
    if (wave) clips.set('wave', wave);

    // nod (~1.2s): head pitches down twice (positive X dips the chin down in
    // normalized space) and returns.
    const nodDown = 0.35;
    const nod = makeClip('nod', 1.2, vrm, [
      ['head', [
        { t: 0, dx: 0 }, { t: 0.3, dx: nodDown }, { t: 0.55, dx: 0 },
        { t: 0.85, dx: nodDown }, { t: 1.2, dx: 0 },
      ]],
    ]);
    if (nod) clips.set('nod', nod);

    // shrug (~1.6s): shoulders lift, elbows swing out into the classic
    // palms-out shrug, head tilts; hold ~0.65s; release.
    const shrug = makeClip('shrug', 1.6, vrm, [
      ['leftShoulder', [
        { t: 0, dz: 0 }, { t: 0.45, dz: 0.4 }, { t: 1.1, dz: 0.4 }, { t: 1.6, dz: 0 },
      ]],
      ['rightShoulder', [
        { t: 0, dz: 0 }, { t: 0.45, dz: -0.4 }, { t: 1.1, dz: -0.4 }, { t: 1.6, dz: 0 },
      ]],
      // Elbows out: raise upper arms (less drop) and swing forearms outward.
      ['leftUpperArm', [
        { t: 0, dz: 0 }, { t: 0.45, dz: 0.55 }, { t: 1.1, dz: 0.55 }, { t: 1.6, dz: 0 },
      ]],
      ['rightUpperArm', [
        { t: 0, dz: 0 }, { t: 0.45, dz: -0.55 }, { t: 1.1, dz: -0.55 }, { t: 1.6, dz: 0 },
      ]],
      ['leftLowerArm', [
        { t: 0, dz: 0 }, { t: 0.45, dz: -0.9 }, { t: 1.1, dz: -0.9 }, { t: 1.6, dz: 0 },
      ]],
      ['rightLowerArm', [
        { t: 0, dz: 0 }, { t: 0.45, dz: 0.9 }, { t: 1.1, dz: 0.9 }, { t: 1.6, dz: 0 },
      ]],
      ['head', [
        { t: 0, dz: 0 }, { t: 0.45, dz: 0.22 }, { t: 1.1, dz: 0.22 }, { t: 1.6, dz: 0 },
      ]],
    ]);
    if (shrug) clips.set('shrug', shrug);

    // celebrate (~2s): both arms swing up overhead with a small settle bounce.
    // Left arm rests at -ARM_DOWN_Z, right at +ARM_DOWN_Z; raising overhead
    // means POSITIVE dz on the left and NEGATIVE dz on the right (mirror).
    const armsUp = ARM_DOWN_Z + 0.5; // magnitude past horizontal, overhead
    const celebrate = makeClip('celebrate', 2.0, vrm, [
      ['leftUpperArm', [
        { t: 0, dz: 0 }, { t: 0.45, dz: armsUp },
        { t: 0.85, dz: armsUp - 0.25 }, { t: 1.2, dz: armsUp },
        { t: 1.6, dz: armsUp }, { t: 2.0, dz: 0 },
      ]],
      ['rightUpperArm', [
        { t: 0, dz: 0 }, { t: 0.45, dz: -armsUp },
        { t: 0.85, dz: -(armsUp - 0.25) }, { t: 1.2, dz: -armsUp },
        { t: 1.6, dz: -armsUp }, { t: 2.0, dz: 0 },
      ]],
      // Slight elbow bend so hands read as raised, not stiff.
      ['leftLowerArm', [
        { t: 0, dz: 0 }, { t: 0.45, dz: -0.25 }, { t: 1.6, dz: -0.25 }, { t: 2.0, dz: 0 },
      ]],
      ['rightLowerArm', [
        { t: 0, dz: 0 }, { t: 0.45, dz: 0.25 }, { t: 1.6, dz: 0.25 }, { t: 2.0, dz: 0 },
      ]],
      // Small upward bob via chest lift synced to the arm swing.
      ['chest', [
        { t: 0, dx: 0 }, { t: 0.45, dx: -0.06 }, { t: 0.85, dx: 0 },
        { t: 1.2, dx: -0.06 }, { t: 2.0, dx: 0 },
      ]],
    ]);
    if (celebrate) clips.set('celebrate', celebrate);

    return clips;
  }

  function sendEvent(event) {
    emit(JSON.stringify(event));
  }

  function unloadCurrent() {
    activeLoadRequestId = null; // discard any in-flight load's late result
    // Match the host's command-state reset (HeadlessAvatarRuntime): framing
    // and gaze return to defaults for the next avatar.
    cameraFraming = 'fullBody';
    lookTargetActive = false;
    lastLookTarget = null;
    if (!current) return;
    scene.remove(current.root);
    VRMUtils.deepDispose(current.root);
    current = null;
  }

  // Framing presets: which vertical band of the model the camera covers.
  // Proportions are relative to the bounding box, so tall/chibi models both
  // frame sensibly.
  const FRAMINGS = {
    headshot: { bandBottom: 0.72, bandTop: 1.02, eyeline: 0.88 },
    bust: { bandBottom: 0.45, bandTop: 1.02, eyeline: 0.85 },
    fullBody: { bandBottom: -0.02, bandTop: 1.02, eyeline: 0.55 },
  };
  let cameraFraming = 'fullBody';
  let lookTargetActive = false;
  let lastLookTarget = null; // { space, x, y, z } — reprojected on reframe

  // Recompute the world-space look target from the last host command using the
  // CURRENT camera. Screen-space targets must be re-unprojected after any
  // reframe, or a stationary pointer maps to a stale world point.
  function applyLookTarget() {
    if (!lastLookTarget) return;
    const { space, x, y, z } = lastLookTarget;
    if (space === 'screen') {
      const ndc = new THREE.Vector3(x * 2 - 1, -(y * 2 - 1), 0.5);
      ndc.unproject(camera);
      const direction = ndc.sub(camera.position).normalize();
      lookAtTarget.position.copy(camera.position).addScaledVector(direction, 2);
    } else {
      lookAtTarget.position.set(x, y, z);
    }
  }

  function frameCamera(root) {
    const preset = FRAMINGS[cameraFraming] ?? FRAMINGS.fullBody;
    const box = new THREE.Box3().setFromObject(root);
    const size = box.getSize(new THREE.Vector3());
    const center = box.getCenter(new THREE.Vector3());
    const focusY = box.min.y + size.y * preset.eyeline;
    // The eyeline is not the band's center, so size the camera distance from
    // the LARGER half-extent — otherwise the far edge of the band crops
    // (e.g. the waist in bust framing).
    const bandTopY = box.min.y + size.y * preset.bandTop;
    const bandBottomY = box.min.y + size.y * preset.bandBottom;
    const halfExtent = Math.max(bandTopY - focusY, focusY - bandBottomY);
    const distance = Math.max(
      halfExtent / Math.tan((camera.fov * Math.PI) / 360),
      size.z * 1.5,
    ) * 1.1;
    camera.position.set(center.x, focusY, center.z + distance);
    camera.lookAt(center.x, focusY, center.z);
    // Reframing must not stomp an active host-driven gaze target — reproject
    // it through the new camera; only fall back to camera-facing idle gaze.
    if (lookTargetActive) applyLookTarget();
    else lookAtTarget.position.copy(camera.position);
  }

  function setCameraFraming(framing) {
    cameraFraming = FRAMINGS[framing] ? framing : 'fullBody';
    if (current) frameCamera(current.root);
  }

  function captureThumbnail({ requestId, width, height }) {
    try {
      if (!current) throw new Error('No avatar loaded');
      // Render a fresh frame, then cover-fit the live canvas into the
      // requested size via a 2D canvas.
      renderer.render(scene, camera);
      const source = renderer.domElement;
      const target = document.createElement('canvas');
      target.width = width;
      target.height = height;
      const context = target.getContext('2d');
      const scale = Math.max(width / source.width, height / source.height);
      const drawWidth = source.width * scale;
      const drawHeight = source.height * scale;
      context.drawImage(
        source,
        (width - drawWidth) / 2,
        (height - drawHeight) / 2,
        drawWidth,
        drawHeight,
      );
      sendEvent({ type: 'thumbnailCaptured', requestId, dataUrl: target.toDataURL('image/png') });
    } catch (error) {
      sendEvent({ type: 'thumbnailFailed', requestId, message: String(error?.message ?? error) });
    }
  }

  // Fetch + parse + retarget one user-provided animation source onto the
  // loaded VRM, returning its THREE.AnimationClip. `format` is "vrma", "fbx", or
  // "glb" (a Mixamo-compatible skeleton with prefix-less bones, e.g. the Ready
  // Player Me animation library). Throws on any failure; the caller treats each
  // source as best-effort so one bad file never fails the avatar load.
  async function loadImportedAnimation(source, vrm) {
    const format = String(source.format ?? '').toLowerCase();
    if (format === 'vrma') return loadVrmaAnimation(source.url, vrm);
    if (format === 'fbx') return loadMixamoAnimation(source.url, vrm);
    if (format === 'glb') return loadGlbAnimation(source.url, vrm);
    throw new Error(`Unsupported animation format: ${source.format}`);
  }

  async function loadAvatar({ url, format, requestId, accessories, animations }) {
    activeLoadRequestId = requestId;
    try {
      const gltf = await loader.loadAsync(url);
      if (activeLoadRequestId !== requestId) {
        // Superseded by a newer load or an unload while fetching/parsing —
        // never touch the live scene; just release what we loaded.
        VRMUtils.deepDispose(gltf.userData.vrm ? gltf.userData.vrm.scene : gltf.scene);
        return;
      }
      unloadCurrent(); // clears activeLoadRequestId — restore it below so the
      // per-source supersession guard in the import loop stays valid for OUR
      // load (unloadCurrent sets it to null, but this load is still the active
      // one until a newer command arrives).
      activeLoadRequestId = requestId;

      const vrm = gltf.userData.vrm ?? null;
      const root = vrm ? vrm.scene : gltf.scene;
      if (vrm) {
        // Legacy VRM 0.x assets face +Z; normalize to the VRM 1.0 convention
        // (no-op for VRM 1.0 models).
        VRMUtils.rotateVRM0(vrm);
        // Perf prep per three-vrm guidance; harmless on small scenes.
        VRMUtils.removeUnnecessaryVertices(root);
        VRMUtils.combineSkeletons(root);
        // Note: lookAt.target stays unset — idle gaze until the host sends
        // an explicit setLookTarget.
      }
      scene.add(root);
      frameCamera(root);

      const clipsById = new Map();
      // Renderer-built-in standard gestures for VRM humanoids, authored against
      // THIS model's normalized bones. Registered FIRST so embedded clips below
      // win on any id collision (a model shipping its own "wave" overrides ours).
      const standardClips = buildStandardGestureClips(vrm);
      for (const [id, clip] of standardClips) clipsById.set(id, clip);

      // User-provided standard animations (VRMA + Mixamo FBX) from the host's
      // drop-in folder, retargeted onto THIS VRM. Registered AFTER the built-in
      // standards (so a user's "wave" overrides ours) but BEFORE the embedded
      // clips below (so a model-embedded clip still wins over an imported one):
      // precedence embedded > imported > built-in. Humanoid-only — skipped for
      // non-humanoid GLB, which has no normalized bones to retarget onto. Each
      // source is best-effort: a parse/retarget failure emits a rendererError
      // with the id and continues, never failing the avatar load.
      if (vrm?.humanoid && Array.isArray(animations)) {
        for (const source of animations) {
          if (!source || !source.id || !source.url) continue;
          try {
            const clip = await loadImportedAnimation(source, vrm);
            // Superseded while we were fetching/parsing — this root was added to
            // the scene before the import loop but `current` isn't assigned yet,
            // so a newer load's unloadCurrent() can't reach it. Release it here
            // and bail without touching the (now newer) scene.
            if (activeLoadRequestId !== requestId) {
              scene.remove(root);
              VRMUtils.deepDispose(root);
              return;
            }
            clip.name = source.id;
            clipsById.set(source.id, clip);
          } catch (error) {
            sendEvent({
              type: 'rendererError',
              message: `Animation '${source.id}' failed to load: ${String(error?.message ?? error)}`,
            });
          }
        }
      }

      if (gltf.animations && gltf.animations.length > 0) {
        const seen = new Set();
        gltf.animations.forEach((clip, index) => {
          // Mirror AvatarFormatDetector's id scheme exactly: non-blank name,
          // deduped from the same base, else anim-<index>.
          const base = clip.name && clip.name.length > 0 ? clip.name : `anim-${index}`;
          let id = base;
          let suffix = index;
          while (seen.has(id)) { id = `${base}-${suffix}`; suffix += 1; }
          seen.add(id);
          clipsById.set(id, clip); // embedded wins on id clash with a standard clip
        });
      }
      // One mixer drives both standard and embedded clips; needed whenever any
      // clip exists (rooted at the model so normalized-bone track names bind).
      const mixer = clipsById.size > 0 ? new THREE.AnimationMixer(root) : null;

      const accessoryBindings = new Map(
        (accessories ?? []).map((a) => [a.id, a.nodeNames ?? []]),
      );
      // Humanoid rest-pose bones (null for non-humanoid GLB → body untouched).
      // activeClips tracks running mixer actions so the per-frame loop knows to
      // stand the rest pose down while a clip drives the skeleton.
      const restBones = collectRestBones(vrm);
      breathPhase = 0;
      current = { root, vrm, mixer, clipsById, accessoryBindings, restBones, activeClips: new Set() };

      const expressions = vrm?.expressionManager
        ? Object.keys(vrm.expressionManager.expressionMap)
        : [];
      sendEvent({
        type: 'avatarLoaded',
        requestId,
        capabilities: {
          supportsHumanoid: !!vrm?.humanoid,
          supportsExpressions: expressions.some((k) => !VISEME_KEYS.includes(k)),
          supportsVisemes: expressions.some((k) => VISEME_KEYS.includes(k)),
          supportsLookAt: !!vrm?.lookAt,
          supportsSpringBones: !!vrm?.springBoneManager,
          // Now true whenever ANY clip is registered — embedded or the standard
          // built-in gestures we author for VRM humanoids above.
          supportsEmbeddedAnimations: clipsById.size > 0,
          supportsAccessories: true, // node-visibility toggles always work
          // Playable clip ids (standard + embedded, deduped). The Kotlin
          // AvatarCapabilities schema has no id-list field, so this is an extra
          // wire key the host's forward-tolerant decoder (ignoreUnknownKeys)
          // drops into AvatarCapabilities — but it IS visible in the raw
          // avatarLoaded event for the host/director/harness log to inspect.
          animationIds: Array.from(clipsById.keys()),
        },
      });
    } catch (error) {
      sendEvent({ type: 'avatarLoadFailed', requestId, message: String(error?.message ?? error) });
    }
  }

  function setExpressionValue(key, weight) {
    current?.vrm?.expressionManager?.setValue(key, weight);
  }

  function setLookTarget(target) {
    lookTargetActive = true;
    lastLookTarget = target;
    // (Re)attach the target object — cleared by clearLookTarget below.
    if (current?.vrm?.lookAt) current.vrm.lookAt.target = lookAtTarget;
    applyLookTarget();
  }

  function playClip(id, { loop, fadeSeconds }) {
    if (!current?.mixer) return;
    const clip = current.clipsById.get(id);
    if (!clip) {
      sendEvent({ type: 'rendererError', message: `Unknown animation id: ${id}` });
      return;
    }
    const action = current.mixer.clipAction(clip);
    action.reset();
    action.setLoop(loop ? THREE.LoopRepeat : THREE.LoopOnce, Infinity);
    action.clampWhenFinished = !loop;
    if (fadeSeconds > 0) action.fadeIn(fadeSeconds);
    action.play();
    // Register so the frame loop suspends rest pose/breathing while this runs;
    // clipDriving() drops it again once it finishes (non-loop) or is stopped.
    current.activeClips.add(action);
  }

  function setAccessoryEnabled(id, enabled) {
    if (!current) return;
    // Manifest binding (logical id -> node names) wins; fall back to nodes
    // literally named after the id.
    const bound = current.accessoryBindings.get(id);
    const targets = bound && bound.length > 0 ? new Set(bound) : new Set([id]);
    current.root.traverse((node) => {
      if (targets.has(node.name)) node.visible = enabled;
    });
  }

  function handleCommand(messageText) {
    let command;
    try {
      command = JSON.parse(messageText);
    } catch {
      sendEvent({ type: 'rendererError', message: 'Malformed command JSON' });
      return;
    }
    switch (command.type) {
      case 'loadAvatar': loadAvatar(command); break;
      case 'unload': unloadCurrent(); break;
      case 'setExpression': setExpressionValue(command.key, command.weight); break;
      case 'setViseme':
        if (command.key === 'closed') {
          VISEME_KEYS.forEach((key) => setExpressionValue(key, 0));
        } else {
          setExpressionValue(command.key, command.weight);
        }
        break;
      case 'setMouthOpen': setExpressionValue('aa', command.value); break;
      case 'setLookTarget': setLookTarget(command); break;
      case 'clearLookTarget':
        // Detach entirely: the VRM returns to its idle gaze rather than
        // staying locked onto a target parked at the camera.
        lookTargetActive = false;
        lastLookTarget = null;
        if (current?.vrm?.lookAt) current.vrm.lookAt.target = null;
        break;
      case 'playGesture': playClip(command.id, { loop: false, fadeSeconds: command.fadeSeconds }); break;
      case 'playAnimation': playClip(command.id, { loop: command.loop, fadeSeconds: 0 }); break;
      case 'setAccessoryEnabled': setAccessoryEnabled(command.id, command.enabled); break;
      case 'setCameraFraming': setCameraFraming(command.framing); break;
      case 'captureThumbnail': captureThumbnail(command); break;
      default:
        sendEvent({ type: 'rendererError', message: `Unknown command type: ${command.type}` });
    }
  }

  sendEvent({ type: 'ready', protocolVersion: PROTOCOL_VERSION });

  return { handleCommand };
}
