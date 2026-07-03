// Letta avatar web renderer — mechanism only, no behavior policy.
//
// Speaks AvatarWireProtocol v1 (see AvatarWireProtocol.kt): the host sends
// command JSON via handleCommand(), the renderer emits event JSON through the
// `emit` callback. Behavior (blinking, idle sway, emotion policy) lives in the
// Kotlin director — this file just applies state to the three-vrm scene, so
// any future native renderer can replay the same command stream.
//
// Renderer stack (vendored, pinned): three r180 + @pixiv/three-vrm 3.4.5 —
// the reference VRM runtime (MToon, spring bones, expressions, look-at).

import * as THREE from 'three';
import { GLTFLoader } from './vendor/loaders/GLTFLoader.js';
import { VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm';

export const PROTOCOL_VERSION = 1;

const VISEME_KEYS = ['aa', 'ih', 'ou', 'ee', 'oh'];

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

  const keyLight = new THREE.DirectionalLight(0xffffff, Math.PI);
  keyLight.position.set(1, 2, 1.5);
  scene.add(keyLight);
  scene.add(new THREE.AmbientLight(0xffffff, 0.4 * Math.PI));

  const loader = new GLTFLoader();
  loader.register((parser) => new VRMLoaderPlugin(parser));

  const clock = new THREE.Clock();
  const lookAtTarget = new THREE.Object3D();
  scene.add(lookAtTarget);

  let current = null; // { root, vrm|null, mixer|null, clipsById, accessoryBindings }
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
      if (current.mixer) current.mixer.update(delta);
      // vrm.update drives expressions, look-at, spring bones, constraints.
      if (current.vrm) current.vrm.update(delta);
    }
    renderer.render(scene, camera);
  });

  function sendEvent(event) {
    emit(JSON.stringify(event));
  }

  function unloadCurrent() {
    activeLoadRequestId = null; // discard any in-flight load's late result
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

  function frameCamera(root) {
    const preset = FRAMINGS[cameraFraming] ?? FRAMINGS.fullBody;
    const box = new THREE.Box3().setFromObject(root);
    const size = box.getSize(new THREE.Vector3());
    const center = box.getCenter(new THREE.Vector3());
    const bandHeight = size.y * (preset.bandTop - preset.bandBottom);
    const focusY = box.min.y + size.y * preset.eyeline;
    const distance = Math.max(
      bandHeight / (2 * Math.tan((camera.fov * Math.PI) / 360)),
      size.z * 1.5,
    ) * 1.1;
    camera.position.set(center.x, focusY, center.z + distance);
    camera.lookAt(center.x, focusY, center.z);
    lookAtTarget.position.copy(camera.position);
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
      current = { root, vrm, mixer, clipsById, accessoryBindings };

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

  function setLookTarget({ space, x, y, z }) {
    // (Re)attach the target object — cleared by clearLookTarget below.
    if (current?.vrm?.lookAt) current.vrm.lookAt.target = lookAtTarget;
    if (space === 'screen') {
      // Normalized (0,0)=top-left → NDC, unprojected onto a plane 2m out.
      const ndc = new THREE.Vector3(x * 2 - 1, -(y * 2 - 1), 0.5);
      ndc.unproject(camera);
      const direction = ndc.sub(camera.position).normalize();
      lookAtTarget.position.copy(camera.position).addScaledVector(direction, 2);
    } else {
      lookAtTarget.position.set(x, y, z);
    }
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
