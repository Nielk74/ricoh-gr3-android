const STORAGE_KEY = "gr-film-lab-review-v1";

const elements = {
  app: document.querySelector("#app"),
  renderSummary: document.querySelector("#renderSummary"),
  sceneCount: document.querySelector("#sceneCount"),
  lookCount: document.querySelector("#lookCount"),
  sceneList: document.querySelector("#sceneList"),
  lookList: document.querySelector("#lookList"),
  sourceFilters: document.querySelector("#sourceFilters"),
  scenePosition: document.querySelector("#scenePosition"),
  sceneName: document.querySelector("#sceneName"),
  sourceBadge: document.querySelector("#sourceBadge"),
  sceneFacts: document.querySelector("#sceneFacts"),
  previousScene: document.querySelector("#previousScene"),
  nextScene: document.querySelector("#nextScene"),
  viewModes: document.querySelector("#viewModes"),
  zoomModes: document.querySelector("#zoomModes"),
  viewport: document.querySelector("#viewport"),
  canvas: document.querySelector("#canvas"),
  imageStage: document.querySelector("#imageStage"),
  originalImage: document.querySelector("#originalImage"),
  lookImage: document.querySelector("#lookImage"),
  strongLookImage: document.querySelector("#strongLookImage"),
  skinMaskImage: document.querySelector("#skinMaskImage"),
  lookLabel: document.querySelector("#lookLabel"),
  imageLoading: document.querySelector("#imageLoading"),
  resolution: document.querySelector("#resolution"),
  openImage: document.querySelector("#openImage"),
  fullscreen: document.querySelector("#fullscreen"),
  grainLoupeButtons: document.querySelectorAll("[data-grain-loupe]"),
  intensitySlider: document.querySelector("#intensitySlider"),
  intensityValue: document.querySelector("#intensityValue"),
  filmExposureModes: document.querySelector("#filmExposureModes"),
  filmExposureValue: document.querySelector("#filmExposureValue"),
  filmExposureHint: document.querySelector("#filmExposureHint"),
  adaptationMetrics: document.querySelector("#adaptationMetrics"),
  verdicts: document.querySelector("#verdicts"),
  reviewNotes: document.querySelector("#reviewNotes"),
  saveState: document.querySelector("#saveState"),
  exportReview: document.querySelector("#exportReview"),
  keyboardHelp: document.querySelector("#keyboardHelp"),
  shortcutDialog: document.querySelector("#shortcutDialog"),
  closeShortcuts: document.querySelector("#closeShortcuts"),
  toast: document.querySelector("#toast"),
};

const state = {
  data: null,
  sceneIndex: 0,
  lookIndex: 0,
  sourceFilter: "all",
  mode: "compare",
  zoom: "fit",
  split: 50,
  intensity: 100,
  filmExposure: 0,
  reviews: loadReviews(),
  spaceDown: false,
  panning: null,
  splitDragging: false,
};

async function init() {
  bindEvents();
  try {
    const response = await fetch("./manifest.json", { cache: "no-store" });
    if (!response.ok) throw new Error(`manifest returned ${response.status}`);
    state.data = await response.json();
    elements.sceneCount.textContent = state.data.scenes.length;
    elements.lookCount.textContent = state.data.looks.length;
    elements.renderSummary.textContent =
      `${state.data.scenes.length} scenes · ${state.data.looks.length} looks · ` +
      `${state.data.renderLongEdge}px review masters`;
    renderSceneList();
    renderLookList();
    selectScene(0);
    selectLook(0);
    elements.app.setAttribute("aria-busy", "false");
  } catch (error) {
    elements.renderSummary.textContent = "Review assets could not be loaded";
    showToast(`Could not load the review manifest: ${error.message}`, 6000);
    console.error(error);
  }
}

function bindEvents() {
  elements.previousScene.addEventListener("click", () => stepScene(-1));
  elements.nextScene.addEventListener("click", () => stepScene(1));
  elements.fullscreen.addEventListener("click", toggleFullscreen);
  elements.grainLoupeButtons.forEach((button) => {
    button.addEventListener("click", inspectGrain);
  });
  elements.exportReview.addEventListener("click", exportReview);
  elements.intensitySlider.addEventListener("input", () => {
    setIntensity(Number(elements.intensitySlider.value));
  });
  elements.keyboardHelp.addEventListener("click", () => setShortcutsOpen(true));
  elements.closeShortcuts.addEventListener("click", () => setShortcutsOpen(false));
  elements.shortcutDialog.addEventListener("click", (event) => {
    if (event.target === elements.shortcutDialog) setShortcutsOpen(false);
  });

  elements.sourceFilters.addEventListener("click", (event) => {
    const button = event.target.closest("[data-source]");
    if (!button) return;
    state.sourceFilter = button.dataset.source;
    setActiveButton(elements.sourceFilters, button);
    renderSceneList();
  });

  elements.viewModes.addEventListener("click", (event) => {
    const button = event.target.closest("[data-mode]");
    if (!button) return;
    setMode(button.dataset.mode);
  });

  elements.zoomModes.addEventListener("click", (event) => {
    const button = event.target.closest("[data-zoom]");
    if (!button) return;
    setZoom(button.dataset.zoom);
  });

  elements.filmExposureModes.addEventListener("click", (event) => {
    const button = event.target.closest("[data-film-exposure]");
    if (!button || button.disabled) return;
    setFilmExposure(Number(button.dataset.filmExposure));
  });

  elements.verdicts.addEventListener("click", (event) => {
    const button = event.target.closest("[data-verdict]");
    if (!button) return;
    const review = currentReview();
    review.verdict = review.verdict === button.dataset.verdict ? "" : button.dataset.verdict;
    review.intensity = state.intensity;
    review.filmExposureEv = state.filmExposure;
    review.updatedAt = new Date().toISOString();
    persistReviews();
    renderFeedback();
    renderSceneList();
  });

  let noteTimer;
  elements.reviewNotes.addEventListener("input", () => {
    const review = currentReview();
    review.notes = elements.reviewNotes.value;
    review.intensity = state.intensity;
    review.filmExposureEv = state.filmExposure;
    review.updatedAt = new Date().toISOString();
    elements.saveState.textContent = "Saving…";
    clearTimeout(noteTimer);
    noteTimer = setTimeout(() => {
      persistReviews();
      elements.saveState.textContent = "Saved";
      renderSceneList();
    }, 260);
  });

  elements.imageStage.addEventListener("pointerdown", onStagePointerDown);
  elements.imageStage.addEventListener("pointermove", onStagePointerMove);
  elements.imageStage.addEventListener("pointerup", onStagePointerUp);
  elements.imageStage.addEventListener("pointercancel", onStagePointerUp);
  elements.imageStage.addEventListener("dblclick", () => {
    setZoom(state.zoom === "fit" ? "1" : "fit");
  });

  window.addEventListener("resize", applyZoom);
  document.addEventListener("fullscreenchange", () => requestAnimationFrame(applyZoom));
  document.addEventListener("keydown", onKeyDown);
  document.addEventListener("keyup", onKeyUp);
}

function renderSceneList() {
  if (!state.data) return;
  const activeScene = currentScene();
  elements.sceneList.innerHTML = "";
  state.data.scenes.forEach((scene, index) => {
    if (state.sourceFilter !== "all" && scene.sourceType !== state.sourceFilter) return;
    const button = document.createElement("button");
    const reviewed = sceneHasReview(scene.id);
    button.type = "button";
    button.className = `scene-item${activeScene?.id === scene.id ? " active" : ""}`;
    button.dataset.sceneIndex = index;
    button.setAttribute("role", "listitem");
    button.innerHTML = `
      <img src="${scene.thumbnail}" alt="" loading="lazy" />
      <span class="scene-item-copy">
        <strong>${escapeHtml(scene.name)}</strong>
        <span class="scene-item-meta">
          ${escapeHtml(scene.sourceType)}
          ${reviewed ? '<span class="reviewed-dot" title="Reviewed"></span>' : ""}
        </span>
      </span>
    `;
    button.addEventListener("click", () => selectScene(index));
    elements.sceneList.append(button);
  });
}

function renderLookList() {
  if (!state.data) return;
  const activeLook = currentLook();
  elements.lookList.innerHTML = "";
  state.data.looks.forEach((look, index) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = `look-item${activeLook?.id === look.id ? " active" : ""}`;
    button.dataset.lookIndex = index;
    button.setAttribute("role", "listitem");
    button.innerHTML = `
      <span
        class="look-swatch"
        style="background: linear-gradient(145deg, ${look.swatchTop}, ${look.swatchBottom})"
        aria-hidden="true"
      ></span>
      <span class="look-copy">
        <strong>${escapeHtml(look.name)}</strong>
        <small>${escapeHtml(look.description)}</small>
      </span>
    `;
    button.addEventListener("click", () => selectLook(index));
    elements.lookList.append(button);
  });
}

function selectScene(index) {
  if (!state.data?.scenes.length) return;
  state.sceneIndex = wrap(index, state.data.scenes.length);
  state.zoom = "fit";
  state.split = 50;
  renderSceneList();
  renderCurrentResult();
  requestAnimationFrame(() => {
    scrollWithin(
      elements.sceneList.querySelector(".scene-item.active"),
      elements.sceneList,
      window.innerWidth <= 900,
    );
  });
}

function selectLook(index) {
  if (!state.data?.looks.length) return;
  state.lookIndex = wrap(index, state.data.looks.length);
  renderLookList();
  renderCurrentResult();
  requestAnimationFrame(() => {
    scrollWithin(
      elements.lookList.querySelector(".look-item.active"),
      elements.lookList,
      window.innerWidth <= 900,
    );
  });
}

function renderCurrentResult() {
  const scene = currentScene();
  const look = currentLook();
  if (!scene || !look) return;
  const result = scene.looks[look.id];
  syncFilmExposureControl(result);

  elements.scenePosition.textContent =
    `Scene ${state.sceneIndex + 1} of ${state.data.scenes.length}`;
  elements.sceneName.textContent = scene.name;
  elements.sourceBadge.textContent = scene.sourceType;
  elements.sceneFacts.innerHTML = `
    <span>${scene.profile.tone}</span>
    <span>${scene.profile.contrast}</span>
    <span>${scene.faces || 0} face${scene.faces === 1 ? "" : "s"} isolated</span>
    <span>${Math.round(scene.profile.clipped * 100)}% clipped highlights</span>
    <span>${Math.round(scene.profile.crushed * 100)}% deep black</span>
  `;
  elements.resolution.textContent =
    `${scene.width.toLocaleString()} × ${scene.height.toLocaleString()} px · ` +
    `${state.zoom === "fit" ? "Fit" : `${Number(state.zoom) * 100}%`}`;

  elements.imageLoading.classList.remove("hidden");
  loadResultImages(scene, look, result);
  applyIntensityLayers();

  elements.imageStage.style.setProperty("--split", `${state.split}%`);
  setMode(state.mode);
  setZoom(state.zoom);
  renderAdaptation(result);
  renderFeedback();
  preloadNeighbors();
}

let imageLoadGeneration = 0;
function loadResultImages(scene, look, result) {
  const generation = ++imageLoadGeneration;
  const masters = resultMasters(result);
  const exposureLabel =
    state.filmExposure === 0 ? "" : `, ${filmExposureText(state.filmExposure)}`;
  const sources = [
    [elements.originalImage, scene.original, `${scene.name}, original`],
    [elements.lookImage, masters.src, `${scene.name}, ${look.name}${exposureLabel} at 100%`],
    [
      elements.strongLookImage,
      masters.strongSrc || masters.src,
      `${scene.name}, ${look.name}${exposureLabel} at 150%`,
    ],
    [elements.skinMaskImage, scene.skinMask, `${scene.name}, isolated skin mask`],
  ];
  let pending = sources.length;

  sources.forEach(([image, src, alt]) => {
    let settled = false;
    const settle = (failed = false) => {
      if (settled) return;
      settled = true;
      pending -= 1;
      if (failed) showToast(`Could not load ${look.name}`, 5000);
      if (generation === imageLoadGeneration && pending === 0) {
        elements.imageLoading.classList.add("hidden");
      }
    };
    image.onload = () => settle();
    image.onerror = () => settle(true);
    image.alt = alt;
    image.src = src;
    if (image.complete) queueMicrotask(() => settle(image.naturalWidth === 0));
  });
}

function renderAdaptation(result) {
  const effect = state.intensity / 100;
  const protectionMix = Math.min(effect, 1);
  const layerMix = Math.min(result.mix * effect, 1.25);
  const metrics = [
    ["Exposure", signed(result.ev * protectionMix, 2) + " EV"],
    ["Highlight roll-off", percent(result.highlights * protectionMix)],
    ["Colour mix", percent(Math.min(result.mix * effect, 1))],
    ["Saturation", `${(1 + (result.saturation - 1) * protectionMix).toFixed(2)}×`],
    ["Emulsion grain", `${(result.grain * layerMix).toFixed(2)}×`],
    ["Halation edge", percent((result.halation || 0) * layerMix)],
  ];
  elements.adaptationMetrics.innerHTML = metrics
    .map(
      ([label, value]) =>
        `<span class="metric"><strong>${value}</strong><span>${label}</span></span>`,
    )
    .join("");
}

function renderFeedback() {
  const review = currentReview();
  elements.verdicts.querySelectorAll("[data-verdict]").forEach((button) => {
    button.classList.toggle("active", button.dataset.verdict === review.verdict);
  });
  elements.reviewNotes.value = review.notes || "";
  elements.saveState.textContent = review.updatedAt
    ? `Saved · ${review.intensity || 100}% · ${filmExposureText(review.filmExposureEv || 0)}`
    : "Local";
}

function currentReview() {
  const key = reviewKey();
  if (!state.reviews[key]) state.reviews[key] = { verdict: "", notes: "", updatedAt: null };
  return state.reviews[key];
}

function reviewKey() {
  const base = `${currentScene()?.id || "scene"}::${currentLook()?.id || "look"}`;
  return state.filmExposure === 0 ? base : `${base}::ev${state.filmExposure}`;
}

function sceneHasReview(sceneId) {
  return Object.entries(state.reviews).some(
    ([key, review]) =>
      key.startsWith(`${sceneId}::`) && Boolean(review.verdict || review.notes?.trim()),
  );
}

function persistReviews() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state.reviews));
}

function loadReviews() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
  } catch {
    return {};
  }
}

function exportReview() {
  const entries = Object.entries(state.reviews)
    .filter(([, review]) => review.verdict || review.notes?.trim())
    .map(([key, review]) => {
      const [sceneId, lookId, exposureKey] = key.split("::");
      const scene = state.data.scenes.find((item) => item.id === sceneId);
      const look = state.data.looks.find((item) => item.id === lookId);
      return {
        sceneId,
        sceneName: scene?.name || sceneId,
        sourceType: scene?.sourceType,
        lookId,
        lookName: look?.name || lookId,
        filmExposureEv:
          review.filmExposureEv ??
          (exposureKey ? Number(exposureKey.replace("ev", "")) : 0),
        ...review,
      };
    });

  if (!entries.length) {
    showToast("Add at least one verdict or note before exporting.");
    return;
  }

  const payload = {
    exportedAt: new Date().toISOString(),
    reviewSet: "GR Film Lab adaptive calibration",
    entries,
  };
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: "application/json" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = `gr-film-review-${new Date().toISOString().slice(0, 10)}.json`;
  link.click();
  URL.revokeObjectURL(link.href);
  showToast(`Exported ${entries.length} review ${entries.length === 1 ? "entry" : "entries"}.`);
}

function setMode(mode) {
  state.mode = mode;
  elements.imageStage.classList.remove(
    "mode-original",
    "mode-compare",
    "mode-look",
    "mode-mask",
  );
  elements.imageStage.classList.add(`mode-${mode}`);
  const button = elements.viewModes.querySelector(`[data-mode="${mode}"]`);
  if (button) setActiveButton(elements.viewModes, button);
  if (mode === "mask") {
    const scene = currentScene();
    elements.openImage.href = scene?.skinMask || "#";
    elements.openImage.title = "Open full-resolution skin isolation audit";
  } else {
    applyIntensityLayers();
  }
}

function setIntensity(value) {
  state.intensity = clamp(Math.round(Number(value) / 5) * 5, 50, 150);
  elements.intensitySlider.value = String(state.intensity);
  elements.intensitySlider.setAttribute(
    "aria-valuetext",
    `${state.intensity}% film effect`,
  );
  elements.intensitySlider.style.setProperty(
    "--intensity-progress",
    `${state.intensity - 50}%`,
  );
  elements.intensityValue.textContent = `${state.intensity}%`;
  applyIntensityLayers();

  const result = currentScene()?.looks[currentLook()?.id];
  if (result) renderAdaptation(result);
}

function inspectGrain() {
  setMode("look");
  setIntensity(150);
  setZoom("2");
  if (window.innerWidth <= 900) {
    requestAnimationFrame(() => {
      elements.viewport.scrollIntoView({ behavior: "smooth", block: "center" });
    });
  }
  showToast("Grain inspection · 150% effect · 200% zoom");
}

function setFilmExposure(value) {
  const target = clamp(Math.round(Number(value)), -1, 1);
  const result = currentScene()?.looks[currentLook()?.id];
  if (target !== 0 && !result?.exposureMasters?.[String(target)]) {
    showToast(
      "Exposure brackets are rendered for Portra 400/800, CineStill 800T, and Vision3 250D.",
    );
    return;
  }
  state.filmExposure = target;
  renderCurrentResult();
}

function syncFilmExposureControl(result) {
  const hasBracket = Boolean(result?.exposureMasters);
  if (!hasBracket) state.filmExposure = 0;
  elements.filmExposureModes.querySelectorAll("[data-film-exposure]").forEach((button) => {
    const value = Number(button.dataset.filmExposure);
    button.disabled = value !== 0 && !hasBracket;
    button.classList.toggle("active", value === state.filmExposure);
  });
  elements.filmExposureValue.textContent = filmExposureText(state.filmExposure);
  elements.filmExposureHint.textContent = hasBracket
    ? "Exposure is applied before dye formation; intensity remains an independent control."
    : "Bracketed calibration is available on Portra 400/800, CineStill 800T, and Vision3 250D.";
}

function resultMasters(result) {
  return (
    result.exposureMasters?.[String(state.filmExposure)] || {
      src: result.src,
      strongSrc: result.strongSrc || result.src,
    }
  );
}

function applyIntensityLayers() {
  const scene = currentScene();
  const look = currentLook();
  const result = scene?.looks[look?.id];
  if (!scene || !look || !result) return;
  const masters = resultMasters(result);

  const baseOpacity = Math.min(state.intensity / 100, 1);
  const strongOpacity = Math.max(0, (state.intensity - 100) / 50);
  elements.lookImage.style.opacity = baseOpacity.toFixed(3);
  elements.strongLookImage.style.opacity = strongOpacity.toFixed(3);
  const exposure =
    state.filmExposure === 0 ? "" : ` · ${filmExposureText(state.filmExposure)}`;
  elements.lookLabel.textContent = `${look.name} · ${state.intensity}%${exposure}`;

  const useStrongMaster = state.intensity >= 125 && masters.strongSrc;
  elements.openImage.href = useStrongMaster ? masters.strongSrc : masters.src;
  elements.openImage.title =
    `Open ${look.name} ${useStrongMaster ? "150%" : "100%"} ` +
    `${filmExposureText(state.filmExposure)} master at full resolution`;
}

function setZoom(zoom) {
  state.zoom = String(zoom);
  const button = elements.zoomModes.querySelector(`[data-zoom="${state.zoom}"]`);
  if (button) setActiveButton(elements.zoomModes, button);
  applyZoom();
}

function applyZoom() {
  const scene = currentScene();
  if (!scene) return;
  const viewportWidth = elements.viewport.clientWidth;
  const viewportHeight = elements.viewport.clientHeight;
  if (!viewportWidth || !viewportHeight) return;

  const scale =
    state.zoom === "fit"
      ? Math.min(
          (viewportWidth - 38) / scene.width,
          (viewportHeight - 38) / scene.height,
          1,
        )
      : Number(state.zoom);
  const stageWidth = Math.max(1, Math.round(scene.width * scale));
  const stageHeight = Math.max(1, Math.round(scene.height * scale));
  const canvasWidth = Math.max(viewportWidth, stageWidth + 38);
  const canvasHeight = Math.max(viewportHeight, stageHeight + 38);

  elements.canvas.style.width = `${canvasWidth}px`;
  elements.canvas.style.height = `${canvasHeight}px`;
  elements.imageStage.style.width = `${stageWidth}px`;
  elements.imageStage.style.height = `${stageHeight}px`;
  elements.imageStage.style.left = `${Math.round((canvasWidth - stageWidth) / 2)}px`;
  elements.imageStage.style.top = `${Math.round((canvasHeight - stageHeight) / 2)}px`;
  elements.resolution.textContent =
    `${scene.width.toLocaleString()} × ${scene.height.toLocaleString()} px · ` +
    `${state.zoom === "fit" ? "Fit" : `${Number(state.zoom) * 100}%`}`;

  if (state.zoom === "fit") {
    elements.viewport.scrollTo({ left: 0, top: 0 });
  }
}

function onStagePointerDown(event) {
  if (state.spaceDown && state.zoom !== "fit") {
    state.panning = {
      x: event.clientX,
      y: event.clientY,
      left: elements.viewport.scrollLeft,
      top: elements.viewport.scrollTop,
    };
    elements.viewport.classList.add("panning");
  } else if (state.mode === "compare") {
    state.splitDragging = true;
    updateSplit(event);
  }
  elements.imageStage.setPointerCapture(event.pointerId);
}

function onStagePointerMove(event) {
  if (state.panning) {
    elements.viewport.scrollLeft = state.panning.left - (event.clientX - state.panning.x);
    elements.viewport.scrollTop = state.panning.top - (event.clientY - state.panning.y);
  } else if (state.splitDragging) {
    updateSplit(event);
  }
}

function onStagePointerUp(event) {
  state.panning = null;
  state.splitDragging = false;
  elements.viewport.classList.remove("panning");
  if (elements.imageStage.hasPointerCapture(event.pointerId)) {
    elements.imageStage.releasePointerCapture(event.pointerId);
  }
}

function updateSplit(event) {
  const rect = elements.imageStage.getBoundingClientRect();
  state.split = clamp(((event.clientX - rect.left) / rect.width) * 100, 0, 100);
  elements.imageStage.style.setProperty("--split", `${state.split}%`);
}

function stepScene(amount) {
  selectScene(state.sceneIndex + amount);
}

function stepLook(amount) {
  selectLook(state.lookIndex + amount);
}

function onKeyDown(event) {
  if (event.target.matches("textarea, input") || event.metaKey || event.ctrlKey || event.altKey) {
    return;
  }
  if (event.key === "Escape" && !elements.shortcutDialog.hidden) {
    setShortcutsOpen(false);
    return;
  }
  if (event.key === " ") {
    state.spaceDown = true;
    event.preventDefault();
  } else if (event.key === "ArrowLeft") {
    stepScene(-1);
  } else if (event.key === "ArrowRight") {
    stepScene(1);
  } else if (event.key === "[") {
    stepLook(-1);
  } else if (event.key === "]") {
    stepLook(1);
  } else if (event.key.toLowerCase() === "o") {
    setMode(state.mode === "original" ? "compare" : "original");
  } else if (event.key.toLowerCase() === "f") {
    toggleFullscreen();
  } else if (["0", "1", "2"].includes(event.key)) {
    setZoom(event.key === "0" ? "fit" : event.key);
  } else if (event.key === "-" || event.key === "_") {
    setIntensity(state.intensity - 5);
  } else if (event.key === "+" || event.key === "=") {
    setIntensity(state.intensity + 5);
  } else if (event.key === "," || event.key === "<") {
    setFilmExposure(state.filmExposure - 1);
  } else if (event.key === "." || event.key === ">") {
    setFilmExposure(state.filmExposure + 1);
  } else if (event.key === "?") {
    setShortcutsOpen(true);
  }
}

function onKeyUp(event) {
  if (event.key === " ") state.spaceDown = false;
}

async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await elements.viewport.requestFullscreen();
  } catch (error) {
    showToast(`Fullscreen is unavailable: ${error.message}`);
  }
}

function setShortcutsOpen(open) {
  elements.shortcutDialog.hidden = !open;
  if (open) elements.closeShortcuts.focus();
}

function preloadNeighbors() {
  const nextScene = state.data.scenes[wrap(state.sceneIndex + 1, state.data.scenes.length)];
  const look = currentLook();
  const nextResult = nextScene.looks[look.id];
  const nextMasters = resultMasters(nextResult);
  [
    nextScene.original,
    nextMasters.src,
    nextMasters.strongSrc || nextMasters.src,
    nextScene.skinMask,
  ].forEach((src) => {
    const image = new Image();
    image.src = src;
  });
}

function currentScene() {
  return state.data?.scenes[state.sceneIndex];
}

function currentLook() {
  return state.data?.looks[state.lookIndex];
}

function setActiveButton(container, activeButton) {
  container.querySelectorAll("button").forEach((button) => {
    button.classList.toggle("active", button === activeButton);
  });
}

function scrollWithin(item, container, horizontal) {
  if (!item) return;
  const start = horizontal ? item.offsetLeft : item.offsetTop;
  const size = horizontal ? item.offsetWidth : item.offsetHeight;
  const current = horizontal ? container.scrollLeft : container.scrollTop;
  const viewport = horizontal ? container.clientWidth : container.clientHeight;
  let target = current;
  if (start < current) target = start;
  else if (start + size > current + viewport) target = start + size - viewport;
  if (horizontal) container.scrollTo({ left: target, behavior: "smooth" });
  else container.scrollTo({ top: target, behavior: "smooth" });
}

function percent(value) {
  return `${Math.round(value * 100)}%`;
}

function signed(value, decimals = 2) {
  const number = Number(value);
  return `${number >= 0 ? "+" : ""}${number.toFixed(decimals)}`;
}

function filmExposureText(value) {
  const number = Number(value) || 0;
  return `${number > 0 ? "+" : ""}${number} EV`;
}

function wrap(value, length) {
  return ((value % length) + length) % length;
}

function clamp(value, minimum, maximum) {
  return Math.min(maximum, Math.max(minimum, value));
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

let toastTimer;
function showToast(message, duration = 3200) {
  elements.toast.textContent = message;
  elements.toast.classList.add("visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => elements.toast.classList.remove("visible"), duration);
}

init();
