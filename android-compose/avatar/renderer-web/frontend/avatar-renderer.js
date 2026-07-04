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

  async function loadAvatar({ url, format, requestId, accessories }) {
    activeLoadRequestId = requestId;
    try {
      const gltf = await loader.loadAsync(url);
      if (activeLoadRequestId !== requestId) {
        // Superseded by a newer load or an unload while fetching/parsing —
        // never touch the live scene; just release what we loaded.
        VRMUtils.deepDispose(gltf.userData.vrm ? gltf.userData.vrm.scene : gltf.scene);
        return;
      }
      unloadCurrent();

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
      let mixer = null;
      if (gltf.animations && gltf.animations.length > 0) {
        mixer = new THREE.AnimationMixer(root);
        const seen = new Set();
        gltf.animations.forEach((clip, index) => {
          // Mirror AvatarFormatDetector's id scheme exactly: non-blank name,
          // deduped from the same base, else anim-<index>.
          const base = clip.name && clip.name.length > 0 ? clip.name : `anim-${index}`;
          let id = base;
          let suffix = index;
          while (seen.has(id)) { id = `${base}-${suffix}`; suffix += 1; }
          seen.add(id);
          clipsById.set(id, clip);
        });
      }

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
          supportsEmbeddedAnimations: clipsById.size > 0,
          supportsAccessories: true, // node-visibility toggles always work
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
