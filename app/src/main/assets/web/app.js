const state = {
  guest: JSON.parse(localStorage.getItem("singGuest") || "null"),
  snapshot: null,
  addTab: "library",
  feedTab: "queue",
  seenMessageIds: null,
  lastSearchQuery: "",
  lastSearchHits: [],
  libraryCache: null,
  addBusyCount: 0,
  queueScrollReady: false,
  artistBrowse: null,
  lyrics: {
    open: false,
    loading: false,
    loadId: 0,
    lines: [],
    trackId: null,
    activeIndex: -1,
    basePositionMs: 0,
    baseReceivedAt: 0,
    isPlaying: false,
    rafId: null,
  },
};

const els = {
  statusLine: document.getElementById("statusLine"),
  joinPanel: document.getElementById("joinPanel"),
  partyPanel: document.getElementById("partyPanel"),
  joinForm: document.getElementById("joinForm"),
  nameInput: document.getElementById("nameInput"),
  openAddModal: document.getElementById("openAddModal"),
  addModal: document.getElementById("addModal"),
  libraryForm: document.getElementById("libraryForm"),
  libraryInput: document.getElementById("libraryInput"),
  libraryHint: document.getElementById("libraryHint"),
  searchForm: document.getElementById("searchForm"),
  searchInput: document.getElementById("searchInput"),
  searchResults: document.getElementById("searchResults"),
  addBusyBar: document.getElementById("addBusyBar"),
  queueList: document.getElementById("queueList"),
  queueViewport: document.getElementById("queueViewport"),
  queuePane: document.getElementById("queuePane"),
  chatterPane: document.getElementById("chatterPane"),
  chatterForm: document.getElementById("chatterForm"),
  chatterInput: document.getElementById("chatterInput"),
  messageList: document.getElementById("messageList"),
  nowTitle: document.getElementById("nowTitle"),
  nowArtist: document.getElementById("nowArtist"),
  nowBy: document.getElementById("nowBy"),
  nowHeart: document.getElementById("nowHeart"),
  nowLyrics: document.getElementById("nowLyrics"),
  openLyrics: document.getElementById("openLyrics"),
  lyricsModal: document.getElementById("lyricsModal"),
  lyricsModalTitle: document.getElementById("lyricsModalTitle"),
  lyricsModalArtist: document.getElementById("lyricsModalArtist"),
  lyricsLines: document.getElementById("lyricsLines"),
  toastHost: document.getElementById("toastHost"),
  playPauseBtn: document.getElementById("playPauseBtn"),
};

const ICONS = {
  play: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>`,
  pause: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 5h4v14H6zm8 0h4v14h-4z"/></svg>`,
  heart: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 21s-6.7-4.3-9.3-8.1C.7 10.1 1.4 6.6 4.4 5.2 6.3 4.3 8.5 4.8 10 6.2L12 8l2-1.8c1.5-1.4 3.7-1.9 5.6-1 3 1.4 3.7 4.9 1.7 7.7C18.7 16.7 12 21 12 21z"/></svg>`,
  lyrics: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 4h16v2H4zm0 5h16v2H4zm0 5h10v2H4zm0 5h7v2H4z"/></svg>`,
  up: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 8l6 6-1.4 1.4L12 10.8 7.4 15.4 6 14z"/></svg>`,
  down: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 16 6 10l1.4-1.4L12 13.2l4.6-4.6L18 10z"/></svg>`,
};

function updatePlayPauseButton(isPlaying) {
  if (!els.playPauseBtn) return;
  els.playPauseBtn.innerHTML = isPlaying ? ICONS.pause : ICONS.play;
  els.playPauseBtn.setAttribute("aria-label", isPlaying ? "Pause" : "Play");
  els.playPauseBtn.dataset.playing = isPlaying ? "1" : "0";
}

function showToast(message, { ok = false } = {}) {
  const el = document.createElement("div");
  el.className = ok ? "toast ok" : "toast";
  el.textContent = message;
  els.toastHost.appendChild(el);
  setTimeout(() => el.remove(), 4000);
}

async function api(path, options = {}) {
  const method = (options.method || "GET").toUpperCase();
  console.log(`[api] → ${method} ${path}`, options.body ? JSON.parse(options.body) : undefined);
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });
  const raw = await response.text();
  let data = {};
  try {
    data = raw ? JSON.parse(raw) : {};
  } catch (parseError) {
    console.error(`[api] ← ${response.status} ${path} non-JSON body:`, raw);
    throw new Error(`Request failed (${response.status}): non-JSON response`);
  }
  if (!response.ok) {
    console.error(`[api] ← ${response.status} ${path}`, data);
    throw new Error(data.error || `Request failed (${response.status})`);
  }
  console.log(`[api] ← ${response.status} ${path}`, data);
  return data;
}

async function ensureGuest() {
  if (!state.guest?.name) return null;
  const snapshot = state.snapshot || (await api("/api/state"));
  const known = snapshot.guests?.some((g) => g.id === state.guest.id);
  if (known) return state.guest;
  const payload = await api("/api/join", {
    method: "POST",
    body: JSON.stringify({ name: state.guest.name }),
  });
  state.guest = payload.guest;
  localStorage.setItem("singGuest", JSON.stringify(state.guest));
  renderSnapshot(payload.snapshot);
  return state.guest;
}

async function withGuest(action) {
  await ensureGuest();
  if (!state.guest) throw new Error("Join the party first");
  try {
    return await action(state.guest);
  } catch (error) {
    if (String(error.message || "").includes("Unknown guest")) {
      localStorage.removeItem("singGuest");
      const name = state.guest.name;
      state.guest = { name };
      await ensureGuest();
      return action(state.guest);
    }
    throw error;
  }
}

function showParty() {
  els.joinPanel.classList.add("hidden");
  els.partyPanel.classList.remove("hidden");
}

function openModal() {
  els.addModal.classList.remove("hidden");
  setAddTab(state.snapshot?.libraryConfigured ? "library" : "tidal");
  if (state.addTab === "library") {
    const cached = state.libraryCache;
    if (cached?.configured && Array.isArray(cached.tracks) && !els.libraryInput.value.trim()) {
      renderSearchResults(cached.tracks);
    }
    loadLibrary(els.libraryInput.value);
    els.libraryInput.focus();
  } else {
    els.searchInput.focus();
  }
}

function closeModal() {
  els.addModal.classList.add("hidden");
  state.addBusyCount = 0;
  if (els.addBusyBar) {
    els.addBusyBar.classList.add("hidden");
    els.addBusyBar.setAttribute("aria-hidden", "true");
  }
}

function stopLyricsClock() {
  if (state.lyrics.rafId != null) {
    cancelAnimationFrame(state.lyrics.rafId);
    state.lyrics.rafId = null;
  }
}

function effectiveLyricsPositionMs() {
  const lyrics = state.lyrics;
  if (!lyrics.isPlaying) return lyrics.basePositionMs;
  return lyrics.basePositionMs + Math.max(0, Date.now() - lyrics.baseReceivedAt);
}

function findActiveLyricsIndex(positionMs) {
  const lines = state.lyrics.lines;
  if (!lines.length) return -1;
  let active = -1;
  for (let i = 0; i < lines.length; i += 1) {
    if (lines[i].timeMs <= positionMs) active = i;
    else break;
  }
  return active;
}

function setActiveLyricsLine(index) {
  if (index === state.lyrics.activeIndex) return;
  const previous = els.lyricsLines.querySelector(".lyrics-line.active");
  if (previous) previous.classList.remove("active");
  state.lyrics.activeIndex = index;
  if (index < 0) return;
  const next = els.lyricsLines.querySelector(`.lyrics-line[data-index="${index}"]`);
  if (!next) return;
  next.classList.add("active");
  next.scrollIntoView({ block: "center", behavior: "smooth" });
}

function tickLyricsHighlight() {
  if (!state.lyrics.open || !state.lyrics.lines.length) {
    state.lyrics.rafId = null;
    return;
  }
  setActiveLyricsLine(findActiveLyricsIndex(effectiveLyricsPositionMs()));
  state.lyrics.rafId = requestAnimationFrame(tickLyricsHighlight);
}

function startLyricsClock() {
  stopLyricsClock();
  if (!state.lyrics.open || !state.lyrics.lines.length) return;
  state.lyrics.rafId = requestAnimationFrame(tickLyricsHighlight);
}

function syncLyricsFromSnapshot(snapshot) {
  if (!state.lyrics.open) return;
  const np = snapshot?.nowPlaying || {};
  const trackId = np.track?.tidalTrackId || null;
  if (state.lyrics.trackId && trackId && trackId !== state.lyrics.trackId) {
    stopLyricsClock();
    state.lyrics.lines = [];
    state.lyrics.activeIndex = -1;
    state.lyrics.trackId = trackId;
    els.lyricsModalTitle.textContent = np.track?.title || "Track changed";
    els.lyricsModalArtist.textContent = np.track?.artist || "";
    els.lyricsLines.innerHTML = `<p class="lyrics-status">Track changed — load synced lyrics again.</p>`;
    return;
  }
  state.lyrics.basePositionMs = Number(np.positionMs) || 0;
  state.lyrics.baseReceivedAt = Date.now();
  state.lyrics.isPlaying = !!np.isPlaying;
  if (state.lyrics.lines.length) {
    setActiveLyricsLine(findActiveLyricsIndex(effectiveLyricsPositionMs()));
    if (state.lyrics.isPlaying && state.lyrics.rafId == null) startLyricsClock();
    if (!state.lyrics.isPlaying) stopLyricsClock();
  }
}

function renderLyricsContent(lyrics, track) {
  els.lyricsModalTitle.textContent = track?.title || "Lyrics";
  els.lyricsModalArtist.textContent = track?.artist || "";
  els.lyricsLines.innerHTML = "";
  state.lyrics.lines = [];
  state.lyrics.activeIndex = -1;

  if (lyrics.instrumental) {
    els.lyricsLines.innerHTML = `<p class="lyrics-status">(Instrumental)</p>`;
    return;
  }

  const lines = Array.isArray(lyrics.lines) ? lyrics.lines : [];
  if (lines.length) {
    state.lyrics.lines = lines.map((line) => ({
      timeMs: Number(line.timeMs) || 0,
      text: line.text || "",
    }));
    const frag = document.createDocumentFragment();
    state.lyrics.lines.forEach((line, index) => {
      const p = document.createElement("p");
      p.className = "lyrics-line";
      p.dataset.index = String(index);
      p.textContent = line.text;
      frag.appendChild(p);
    });
    els.lyricsLines.appendChild(frag);
    return;
  }

  const plain = lyrics.syncedLyrics || lyrics.plainLyrics;
  if (plain) {
    const pre = document.createElement("pre");
    pre.className = "lyrics-plain";
    pre.textContent = plain;
    els.lyricsLines.appendChild(pre);
    return;
  }

  els.lyricsLines.innerHTML = `<p class="lyrics-status">No lyrics found.</p>`;
}

function showLyricsLoading() {
  const track = state.snapshot?.nowPlaying?.track || null;
  stopLyricsClock();
  state.lyrics.open = true;
  state.lyrics.loading = true;
  state.lyrics.loadId = (state.lyrics.loadId || 0) + 1;
  state.lyrics.lines = [];
  state.lyrics.activeIndex = -1;
  state.lyrics.trackId = track?.tidalTrackId || null;
  state.lyrics.basePositionMs = Number(state.snapshot?.nowPlaying?.positionMs) || 0;
  state.lyrics.baseReceivedAt = Date.now();
  state.lyrics.isPlaying = !!state.snapshot?.nowPlaying?.isPlaying;
  els.lyricsModalTitle.textContent = track?.title || "Lyrics";
  els.lyricsModalArtist.textContent = track?.artist || "";
  els.lyricsLines.innerHTML = `
    <div class="lyrics-loading" role="status" aria-live="polite">
      <div class="lyrics-spinner" aria-hidden="true"></div>
      <p class="lyrics-status">Loading synced lyrics…</p>
    </div>
  `;
  els.lyricsModal.classList.remove("hidden");
  return state.lyrics.loadId;
}

function setAddBusy(busy) {
  if (busy) {
    state.addBusyCount += 1;
  } else {
    state.addBusyCount = Math.max(0, state.addBusyCount - 1);
  }
  const active = state.addBusyCount > 0;
  if (!els.addBusyBar) return;
  els.addBusyBar.classList.toggle("hidden", !active);
  els.addBusyBar.setAttribute("aria-hidden", active ? "false" : "true");
}

function showSearchLoading(message = "Loading…") {
  els.searchResults.innerHTML = `
    <div class="search-loading" role="status" aria-live="polite">
      <div class="search-spinner" aria-hidden="true"></div>
      <p class="lyrics-status">${escapeHtml(message)}</p>
    </div>
  `;
}

function submitOnEnter(input, form) {
  input.addEventListener("keydown", (event) => {
    if (event.key !== "Enter") return;
    event.preventDefault();
    if (typeof form.requestSubmit === "function") {
      form.requestSubmit();
    } else {
      form.dispatchEvent(new Event("submit", { cancelable: true, bubbles: true }));
    }
  });
}

function openLyricsModal(lyrics) {
  const track = state.snapshot?.nowPlaying?.track || null;
  state.lyrics.open = true;
  state.lyrics.loading = false;
  state.lyrics.trackId = track?.tidalTrackId || null;
  state.lyrics.basePositionMs = Number(state.snapshot?.nowPlaying?.positionMs) || 0;
  state.lyrics.baseReceivedAt = Date.now();
  state.lyrics.isPlaying = !!state.snapshot?.nowPlaying?.isPlaying;
  renderLyricsContent(lyrics, track);
  els.lyricsModal.classList.remove("hidden");
  if (state.lyrics.lines.length) {
    setActiveLyricsLine(findActiveLyricsIndex(effectiveLyricsPositionMs()));
    if (state.lyrics.isPlaying) startLyricsClock();
  }
}

function closeLyricsModal() {
  stopLyricsClock();
  state.lyrics.open = false;
  state.lyrics.loading = false;
  state.lyrics.loadId = (state.lyrics.loadId || 0) + 1;
  state.lyrics.lines = [];
  state.lyrics.trackId = null;
  state.lyrics.activeIndex = -1;
  els.lyricsModal.classList.add("hidden");
  els.lyricsLines.innerHTML = "";
}

function setAddTab(tab) {
  state.addTab = tab;
  document.querySelectorAll(".tab").forEach((btn) => {
    const active = btn.getAttribute("data-tab") === tab;
    btn.classList.toggle("active", active);
    btn.setAttribute("aria-selected", active ? "true" : "false");
  });
  els.libraryForm.classList.toggle("hidden", tab !== "library");
  els.searchForm.classList.toggle("hidden", tab !== "tidal");
}

function setFeedTab(tab) {
  state.feedTab = tab;
  document.querySelectorAll(".feed-tab").forEach((btn) => {
    const active = btn.getAttribute("data-feed-tab") === tab;
    btn.classList.toggle("active", active);
    btn.setAttribute("aria-selected", active ? "true" : "false");
  });
  els.queuePane.classList.toggle("hidden", tab !== "queue");
  els.chatterPane.classList.toggle("hidden", tab !== "chatter");
  if (tab === "queue") scrollQueueToUpcoming();
}

function libraryTrackSet(snapshot) {
  return new Set(snapshot?.libraryTrackIds || []);
}

function activeQueuedTrackIds(snapshot) {
  const ids = new Set();
  const nowId = snapshot?.nowPlaying?.track?.tidalTrackId;
  if (nowId) ids.add(nowId);
  (snapshot?.queue || []).forEach((item) => {
    if (item.track?.tidalTrackId) ids.add(item.track.tidalTrackId);
  });
  return ids;
}

function markAddButtonAdded(button) {
  if (!button) return;
  button.textContent = "Added";
  button.disabled = true;
  button.classList.add("added");
  button.classList.remove("busy");
  button.removeAttribute("aria-busy");
}

function favoriteTrack(track, { alreadyHearted = false } = {}) {
  if (alreadyHearted) {
    showToast("Already in the karaoke library", { ok: true });
    return Promise.resolve(null);
  }
  if (!state.snapshot?.libraryConfigured) {
    showToast("Host hasn't set a karaoke library yet");
    return Promise.resolve(null);
  }
  return withGuest((guest) =>
    api("/api/library/favorite", {
      method: "POST",
      body: JSON.stringify({ guestId: guest.id, track }),
    }),
  ).then((snapshot) => {
    renderSnapshot(snapshot);
    showToast(`Added to ${snapshot.libraryPlaylistName || "library"}`, { ok: true });
    return snapshot;
  });
}

function updateNowActions(snapshot) {
  const track = snapshot?.nowPlaying?.track;
  const hasTrack = !!track?.tidalTrackId;

  if (els.nowLyrics) {
    if (!hasTrack) {
      els.nowLyrics.classList.add("hidden");
      els.nowLyrics.onclick = null;
    } else {
      els.nowLyrics.classList.remove("hidden");
      els.nowLyrics.innerHTML = ICONS.lyrics;
      els.nowLyrics.disabled = false;
      els.nowLyrics.onclick = () => loadLiveLyrics(els.nowLyrics);
    }
  }

  const btn = els.nowHeart;
  if (!btn) return;
  if (!hasTrack) {
    btn.classList.add("hidden");
    btn.onclick = null;
    return;
  }
  btn.classList.remove("hidden");
  btn.innerHTML = ICONS.heart;
  const hearted = libraryTrackSet(snapshot).has(track.tidalTrackId);
  btn.classList.toggle("filled", hearted);
  btn.setAttribute("aria-label", hearted ? "In library" : "Add to karaoke library");
  btn.disabled = false;
  btn.onclick = async () => {
    try {
      await favoriteTrack(track, { alreadyHearted: hearted });
    } catch (error) {
      showToast(error.message);
    }
  };
}

async function loadLiveLyrics(triggerBtn) {
  const loadId = showLyricsLoading();
  try {
    if (triggerBtn) triggerBtn.disabled = true;
    const lyrics = await api("/api/lyrics");
    if (!state.lyrics.open || state.lyrics.loadId !== loadId) return;
    openLyricsModal(lyrics);
  } catch (error) {
    if (!state.lyrics.open || state.lyrics.loadId !== loadId) return;
    state.lyrics.loading = false;
    els.lyricsLines.innerHTML = `<p class="lyrics-status">${escapeHtml(error.message)}</p>`;
  } finally {
    if (triggerBtn) triggerBtn.disabled = false;
  }
}

function toastNewMessages(messages) {
  const list = messages || [];
  if (state.seenMessageIds === null) {
    state.seenMessageIds = new Set(list.map((m) => m.id));
    return;
  }
  const fresh = list.filter((m) => !state.seenMessageIds.has(m.id));
  fresh
    .slice()
    .reverse()
    .forEach((m) => showToast(m.text, { ok: true }));
  state.seenMessageIds = new Set(list.map((m) => m.id));
}

function renderSnapshot(snapshot) {
  const prevTrackId = state.snapshot?.nowPlaying?.track?.tidalTrackId || null;
  state.snapshot = snapshot;
  const np = snapshot.nowPlaying || {};
  const nextTrackId = np.track?.tidalTrackId || null;
  els.nowTitle.textContent = np.track?.title || "Nothing yet";
  const videoCue = np.track?.mediaType === "video" ? " · Video" : "";
  els.nowArtist.textContent = (np.track?.artist || "") + videoCue;
  els.nowBy.textContent = np.addedByName ? `Queued by ${np.addedByName}` : "";
  updatePlayPauseButton(!!np.isPlaying);
  updateNowActions(snapshot);

  const inLibrary = libraryTrackSet(snapshot);
  const viewport = els.queueViewport;
  const savedScrollTop = viewport ? viewport.scrollTop : 0;
  els.queueList.innerHTML = "";
  const history = snapshot.history || [];
  const upcoming = snapshot.queue || [];

  history.forEach((item) => {
    els.queueList.appendChild(buildQueueItem(item, { history: true, inLibrary }));
  });

  if (history.length && upcoming.length) {
    const divider = document.createElement("li");
    divider.className = "queue-divider";
    divider.textContent = "Up next";
    divider.setAttribute("aria-hidden", "true");
    els.queueList.appendChild(divider);
  }

  upcoming.forEach((item, index) => {
    els.queueList.appendChild(
      buildQueueItem(item, { history: false, index, count: upcoming.length, inLibrary }),
    );
  });

  if (!history.length && !upcoming.length) {
    els.queueList.innerHTML = `<li class="muted">Queue is empty — tap Add a song.</li>`;
  }

  // Position ticks arrive ~1/s over the websocket; only jump the queue when the song changes
  // (or on the first paint / when switching back to the queue tab).
  if (!state.queueScrollReady || prevTrackId !== nextTrackId) {
    scrollQueueToUpcoming();
    state.queueScrollReady = true;
  } else if (viewport) {
    viewport.scrollTop = savedScrollTop;
  }

  const messages = snapshot.messages || [];
  toastNewMessages(messages);
  els.messageList.innerHTML = "";
  messages.slice(0, 40).forEach((msg) => {
    const li = document.createElement("li");
    li.textContent = msg.text;
    els.messageList.appendChild(li);
  });
  if (els.chatterInput) els.chatterInput.disabled = !state.guest;

  const bridge = snapshot.bridgeReady ? "Tidal bridge ready" : "Waiting for Tidal";
  const configured = snapshot.tidalConfigured ? "API configured" : "Set TIDAL credentials on TV";
  const library = snapshot.libraryConfigured
    ? `Library: ${snapshot.libraryPlaylistName || "set"}`
    : "No karaoke library";
  els.statusLine.textContent = `${state.guest?.name || "Guest"} · ${bridge} · ${configured} · ${library}`;
  els.libraryHint.textContent = snapshot.libraryConfigured
    ? `Browsing ${snapshot.libraryPlaylistName || "your karaoke playlist"}`
    : "Host hasn’t set a karaoke library yet — use Search all Tidal, or configure one in TV Settings.";
  syncLyricsFromSnapshot(snapshot);
}

function scrollQueueToUpcoming() {
  const viewport = els.queueViewport;
  if (!viewport) return;
  // Pin the next (first upcoming) track to the top of the viewport.
  // Prefer the upcoming row over the sticky "Up next" divider so the song itself is flush top.
  const anchor =
    els.queueList.querySelector(".queue-item:not(.history)") ||
    els.queueList.querySelector(".queue-divider");
  if (!anchor) {
    viewport.scrollTop = 0;
    return;
  }
  const viewportRect = viewport.getBoundingClientRect();
  const anchorRect = anchor.getBoundingClientRect();
  viewport.scrollTop += anchorRect.top - viewportRect.top;
}

function buildQueueItem(item, { history, index = 0, count = 0, inLibrary }) {
  const li = document.createElement("li");
  li.className = history ? "queue-item history" : "queue-item";
  li.dataset.itemId = item.id;
  if (!history) li.dataset.index = String(index);
  const hearted = inLibrary.has(item.track.tidalTrackId);
  const canMoveUp = index > 0;
  const canMoveDown = index < count - 1;
  const videoBadge = item.track.mediaType === "video"
    ? `<span class="media-badge">Video</span>`
    : "";
  li.innerHTML = history
    ? `
      <span class="reorder-spacer" aria-hidden="true"></span>
      <div>
        <strong>${escapeHtml(item.track.title)}${videoBadge}</strong>
        <span>${escapeHtml(item.track.artist)} · added by ${escapeHtml(item.addedByName)}</span>
      </div>
      <div class="queue-actions">
        <button type="button" class="heart ${hearted ? "filled" : ""}" data-heart aria-label="${hearted ? "In library" : "Add to karaoke library"}">${ICONS.heart}</button>
        <button type="button" data-play aria-label="Jump back to this track">${ICONS.play}</button>
      </div>
    `
    : `
      <button type="button" class="reorder-btn" data-move-up aria-label="Move up" ${canMoveUp ? "" : "disabled"}>${ICONS.up}</button>
      <div>
        <strong>${escapeHtml(item.track.title)}${videoBadge}</strong>
        <span>${escapeHtml(item.track.artist)} · added by ${escapeHtml(item.addedByName)}</span>
      </div>
      <div class="queue-actions">
        <button type="button" class="heart ${hearted ? "filled" : ""}" data-heart aria-label="${hearted ? "In library" : "Add to karaoke library"}">${ICONS.heart}</button>
        <button type="button" data-play aria-label="Play this track">${ICONS.play}</button>
        <button type="button" class="reorder-btn" data-move-down aria-label="Move down" ${canMoveDown ? "" : "disabled"}>${ICONS.down}</button>
      </div>
    `;
  bindQueueItem(li, item, { history, hearted, index });
  return li;
}

async function moveQueueItem(item, toIndex) {
  try {
    const snapshot = await withGuest((guest) =>
      api("/api/queue/reorder", {
        method: "POST",
        body: JSON.stringify({ guestId: guest.id, itemId: item.id, toIndex }),
      }),
    );
    renderSnapshot(snapshot);
  } catch (error) {
    showToast(error.message);
  }
}

function bindQueueItem(li, item, { history, hearted, index = 0 }) {
  if (!history) {
    const upBtn = li.querySelector("[data-move-up]");
    if (upBtn && !upBtn.disabled) {
      upBtn.addEventListener("click", () => moveQueueItem(item, index - 1));
    }
    const downBtn = li.querySelector("[data-move-down]");
    if (downBtn && !downBtn.disabled) {
      downBtn.addEventListener("click", () => moveQueueItem(item, index + 1));
    }
  }

  li.querySelector("[data-play]").addEventListener("click", async () => {
    try {
      const snapshot = await withGuest((guest) =>
        api("/api/queue/play", {
          method: "POST",
          body: JSON.stringify({ guestId: guest.id, itemId: item.id }),
        }),
      );
      renderSnapshot(snapshot);
    } catch (error) {
      showToast(error.message);
    }
  });

  const heartBtn = li.querySelector("[data-heart]");
  if (!heartBtn) return;
  heartBtn.addEventListener("click", async () => {
    try {
      await favoriteTrack(item.track, { alreadyHearted: hearted });
    } catch (error) {
      showToast(error.message);
    }
  });
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function bindAddTrackButton(button, track, queuedIds) {
  if (!button || !track?.tidalTrackId) return;
  if (queuedIds.has(track.tidalTrackId)) return;
  button.addEventListener("click", async () => {
    button.disabled = true;
    button.classList.add("busy");
    button.setAttribute("aria-busy", "true");
    setAddBusy(true);
    try {
      await withGuest((guest) =>
        api("/api/queue", {
          method: "POST",
          body: JSON.stringify({ guestId: guest.id, track }),
        }),
      );
      markAddButtonAdded(button);
      queuedIds.add(track.tidalTrackId);
      const label = track.mediaType === "video" ? `Queued video ${track.title}` : `Queued ${track.title}`;
      showToast(label, { ok: true });
    } catch (error) {
      button.disabled = false;
      button.classList.remove("busy");
      button.removeAttribute("aria-busy");
      showToast(error.message);
    } finally {
      setAddBusy(false);
    }
  });
}

function renderSearchHits(hits, { artistBrowse = null } = {}) {
  state.artistBrowse = artistBrowse;
  els.searchResults.innerHTML = "";
  const queuedIds = activeQueuedTrackIds(state.snapshot);

  if (artistBrowse) {
    const header = document.createElement("div");
    header.className = "results-header";
    header.innerHTML = `
      <button type="button" class="link-btn" data-back-search>← Back</button>
      <strong>Top results — ${escapeHtml(artistBrowse.name)}</strong>
    `;
    header.querySelector("[data-back-search]").addEventListener("click", () => {
      renderSearchHits(state.lastSearchHits);
    });
    els.searchResults.appendChild(header);
  }

  if (!hits.length) {
    const empty = document.createElement("div");
    empty.className = "result";
    empty.innerHTML = `<span>No results found</span>`;
    els.searchResults.appendChild(empty);
    return;
  }

  hits.forEach((hit) => {
    const row = document.createElement("div");
    row.className = "result";
    const artistLabel = escapeHtml(hit.artist) || "Unknown artist";
    const albumBit = hit.album ? ` · ${escapeHtml(hit.album)}` : "";
    const artistId = hit.artistId || hit.song?.artistId || hit.video?.artistId;
    const artistControl = artistId && !artistBrowse
      ? `<button type="button" class="artist-link" data-artist>${artistLabel}</button>${albumBit}`
      : `<span>${artistLabel}${albumBit}</span>`;

    const songQueued = hit.song && queuedIds.has(hit.song.tidalTrackId);
    const videoQueued = hit.video && queuedIds.has(hit.video.tidalTrackId);
    const actions = [];
    if (hit.song) {
      actions.push(
        `<button type="button" data-add-song ${songQueued ? "disabled" : ""} class="${songQueued ? "added" : ""}">${songQueued ? "Added" : "Song"}</button>`,
      );
    }
    if (hit.video) {
      actions.push(
        `<button type="button" data-add-video ${videoQueued ? "disabled" : ""} class="${videoQueued ? "added" : ""}">${videoQueued ? "Added" : "Video"}</button>`,
      );
    }

    row.innerHTML = `
      <div>
        <strong>${escapeHtml(hit.title)}</strong>
        ${artistControl}
      </div>
      <div class="result-actions">${actions.join("")}</div>
    `;

    const artistBtn = row.querySelector("[data-artist]");
    if (artistBtn && artistId) {
      artistBtn.addEventListener("click", async () => {
        showSearchLoading(`Loading songs by ${hit.artist || "artist"}…`);
        setAddBusy(true);
        try {
          const payload = await api(`/api/artists/${encodeURIComponent(artistId)}/tracks`);
          renderSearchHits(payload.results || [], {
            artistBrowse: { id: artistId, name: hit.artist },
          });
        } catch (error) {
          showToast(error.message);
          renderSearchHits(hits, { artistBrowse });
        } finally {
          setAddBusy(false);
        }
      });
    }

    bindAddTrackButton(row.querySelector("[data-add-song]"), hit.song, queuedIds);
    bindAddTrackButton(row.querySelector("[data-add-video]"), hit.video, queuedIds);
    els.searchResults.appendChild(row);
  });
}

function renderSearchResults(tracks, { artistBrowse = null } = {}) {
  state.artistBrowse = artistBrowse;
  els.searchResults.innerHTML = "";
  const queuedIds = activeQueuedTrackIds(state.snapshot);

  if (!tracks.length) {
    const empty = document.createElement("div");
    empty.className = "result";
    empty.innerHTML = `<span>No tracks found</span>`;
    els.searchResults.appendChild(empty);
    return;
  }

  tracks.forEach((track) => {
    const row = document.createElement("div");
    row.className = "result";
    const artistLabel = escapeHtml(track.artist) || "Unknown artist";
    const albumBit = track.album ? ` · ${escapeHtml(track.album)}` : "";
    const artistControl = `<span>${artistLabel}${albumBit}</span>`;
    const alreadyQueued = queuedIds.has(track.tidalTrackId);
    const addLabel = track.mediaType === "video" ? "Video" : "Add";
    row.innerHTML = `
      <div>
        <strong>${escapeHtml(track.title)}${track.mediaType === "video" ? '<span class="media-badge">Video</span>' : ""}</strong>
        ${artistControl}
      </div>
      <button type="button" data-add ${alreadyQueued ? "disabled" : ""} class="${alreadyQueued ? "added" : ""}">${alreadyQueued ? "Added" : addLabel}</button>
    `;
    bindAddTrackButton(row.querySelector("[data-add]"), track, queuedIds);
    els.searchResults.appendChild(row);
  });
}

async function loadLibrary(query = "") {
  const cleaned = String(query || "").trim();
  console.log("[library] load", { query: cleaned });
  const canUseGuestCache = !cleaned && state.libraryCache?.configured && Array.isArray(state.libraryCache.tracks);
  if (!canUseGuestCache) {
    showSearchLoading(cleaned ? "Filtering library…" : "Loading karaoke library…");
  }
  setAddBusy(true);
  try {
    const path = cleaned ? `/api/library?q=${encodeURIComponent(cleaned)}` : "/api/library";
    const payload = await api(path);
    console.log("[library] result", {
      configured: payload.configured,
      playlistName: payload.playlistName,
      count: (payload.tracks || []).length,
    });
    if (!payload.configured) {
      state.libraryCache = null;
      els.searchResults.innerHTML = `<div class="result"><span>No karaoke library configured on the TV yet.</span></div>`;
      return;
    }
    if (!cleaned) {
      state.libraryCache = {
        configured: true,
        playlistName: payload.playlistName || null,
        tracks: payload.tracks || [],
      };
    }
    renderSearchResults(payload.tracks || []);
  } catch (error) {
    console.error("[library] failed", error);
    showToast(error.message);
    if (!canUseGuestCache) {
      els.searchResults.innerHTML = `<div class="result"><span>Could not load library.</span></div>`;
    }
  } finally {
    setAddBusy(false);
  }
}

els.joinForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    const payload = await api("/api/join", {
      method: "POST",
      body: JSON.stringify({ name: els.nameInput.value }),
    });
    state.guest = payload.guest;
    localStorage.setItem("singGuest", JSON.stringify(state.guest));
    showParty();
    renderSnapshot(payload.snapshot);
  } catch (error) {
    showToast(error.message);
  }
});

els.openAddModal.addEventListener("click", openModal);
els.addModal.querySelectorAll("[data-close-modal]").forEach((el) => {
  el.addEventListener("click", closeModal);
});
document.querySelectorAll(".tab").forEach((btn) => {
  btn.addEventListener("click", () => {
    const tab = btn.getAttribute("data-tab");
    setAddTab(tab);
    if (tab === "library") {
      const query = els.libraryInput.value;
      if (!query.trim() && state.libraryCache?.configured && Array.isArray(state.libraryCache.tracks)) {
        renderSearchResults(state.libraryCache.tracks);
      }
      loadLibrary(query);
    }
  });
});

document.querySelectorAll(".feed-tab").forEach((btn) => {
  btn.addEventListener("click", () => {
    setFeedTab(btn.getAttribute("data-feed-tab"));
  });
});

els.chatterForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const text = els.chatterInput.value.trim();
  if (!text) return;
  try {
    const snapshot = await withGuest((guest) =>
      api("/api/message", {
        method: "POST",
        body: JSON.stringify({ guestId: guest.id, text }),
      }),
    );
    els.chatterInput.value = "";
    renderSnapshot(snapshot);
  } catch (error) {
    showToast(error.message);
  }
});

els.libraryForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  await loadLibrary(els.libraryInput.value);
});

els.searchForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const query = els.searchInput.value;
  console.log("[search] submit", { query });
  showSearchLoading("Searching Tidal…");
  setAddBusy(true);
  try {
    const payload = await api("/api/search", {
      method: "POST",
      body: JSON.stringify({ query }),
    });
    console.log("[search] results", {
      count: (payload.results || []).length,
      sample: (payload.results || []).slice(0, 5).map((h) => `${h.title} — ${h.artist}`),
    });
    state.lastSearchQuery = query;
    state.lastSearchHits = payload.results || [];
    renderSearchHits(state.lastSearchHits);
    if (!(payload.results || []).length) {
      showToast("No results found");
    }
  } catch (error) {
    console.error("[search] failed", error);
    showToast(error.message);
    els.searchResults.innerHTML = `<div class="result"><span>Search failed.</span></div>`;
  } finally {
    setAddBusy(false);
  }
});

submitOnEnter(els.libraryInput, els.libraryForm);
submitOnEnter(els.searchInput, els.searchForm);

document.querySelectorAll("[data-action]").forEach((button) => {
  button.addEventListener("click", async () => {
    let action = button.getAttribute("data-action");
    if (action === "toggle-play") {
      action = button.dataset.playing === "1" ? "pause" : "play";
    }
    try {
      const snapshot = await withGuest((guest) =>
        api(`/api/${action}`, {
          method: "POST",
          body: JSON.stringify({ guestId: guest.id }),
        }),
      );
      renderSnapshot(snapshot);
    } catch (error) {
      showToast(error.message);
    }
  });
});

els.openLyrics.addEventListener("click", async () => {
  try {
    await withGuest((guest) =>
      api("/api/open-lyrics", {
        method: "POST",
        body: JSON.stringify({ guestId: guest.id }),
      }),
    );
    showToast("Asked Tidal to open lyrics", { ok: true });
  } catch (error) {
    showToast(error.message);
  }
});

els.lyricsModal.querySelectorAll("[data-close-lyrics-modal]").forEach((el) => {
  el.addEventListener("click", closeLyricsModal);
});

function connectSocket() {
  const protocol = location.protocol === "https:" ? "wss" : "ws";
  const socket = new WebSocket(`${protocol}://${location.host}/ws`);
  socket.addEventListener("open", () => {
    els.statusLine.textContent = state.guest
      ? `${state.guest.name} · live`
      : "Connected — join to start";
  });
  socket.addEventListener("message", (event) => {
    try {
      renderSnapshot(JSON.parse(event.data));
    } catch (_) {
      /* ignore malformed frames */
    }
  });
  socket.addEventListener("close", () => {
    els.statusLine.textContent = "Disconnected — retrying…";
    setTimeout(connectSocket, 1500);
  });
}

async function boot() {
  try {
    const snapshot = await api("/api/state");
    state.snapshot = snapshot;
    if (state.guest) {
      await ensureGuest();
      showParty();
      renderSnapshot(state.snapshot || snapshot);
    }
  } catch (_) {
    els.statusLine.textContent = "Waiting for party server…";
  }
  connectSocket();
}

boot();
