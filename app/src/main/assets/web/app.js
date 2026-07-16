const state = {
  guest: JSON.parse(localStorage.getItem("singGuest") || "null"),
  snapshot: null,
  addTab: "library",
  drag: null,
  lastSearchQuery: "",
  lastSearchTracks: [],
  artistBrowse: null,
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
  queueList: document.getElementById("queueList"),
  queueViewport: document.getElementById("queueViewport"),
  messageList: document.getElementById("messageList"),
  nowTitle: document.getElementById("nowTitle"),
  nowArtist: document.getElementById("nowArtist"),
  nowBy: document.getElementById("nowBy"),
  openLyrics: document.getElementById("openLyrics"),
  loadLyrics: document.getElementById("loadLyrics"),
  lyricsBox: document.getElementById("lyricsBox"),
  toastHost: document.getElementById("toastHost"),
  playPauseBtn: document.getElementById("playPauseBtn"),
};

const ICONS = {
  play: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg>`,
  pause: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 5h4v14H6zm8 0h4v14h-4z"/></svg>`,
  heart: `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 21s-6.7-4.3-9.3-8.1C.7 10.1 1.4 6.6 4.4 5.2 6.3 4.3 8.5 4.8 10 6.2L12 8l2-1.8c1.5-1.4 3.7-1.9 5.6-1 3 1.4 3.7 4.9 1.7 7.7C18.7 16.7 12 21 12 21z"/></svg>`,
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
    loadLibrary();
    els.libraryInput.focus();
  } else {
    els.searchInput.focus();
  }
}

function closeModal() {
  els.addModal.classList.add("hidden");
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

function libraryTrackSet(snapshot) {
  return new Set(snapshot?.libraryTrackIds || []);
}

function renderSnapshot(snapshot) {
  state.snapshot = snapshot;
  const np = snapshot.nowPlaying || {};
  els.nowTitle.textContent = np.track?.title || "Nothing yet";
  els.nowArtist.textContent = np.track?.artist || "";
  els.nowBy.textContent = np.addedByName ? `Queued by ${np.addedByName}` : "";
  updatePlayPauseButton(!!np.isPlaying);

  const inLibrary = libraryTrackSet(snapshot);
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
      buildQueueItem(item, { history: false, index, inLibrary }),
    );
  });

  if (!history.length && !upcoming.length) {
    els.queueList.innerHTML = `<li class="muted">Queue is empty — tap Add a song.</li>`;
  }

  scrollQueueToUpcoming();


  els.messageList.innerHTML = "";
  (snapshot.messages || []).slice(0, 12).forEach((msg) => {
    const li = document.createElement("li");
    li.textContent = msg.text;
    els.messageList.appendChild(li);
  });

  const bridge = snapshot.bridgeReady ? "Tidal bridge ready" : "Waiting for Tidal";
  const configured = snapshot.tidalConfigured ? "API configured" : "Set TIDAL credentials on TV";
  const library = snapshot.libraryConfigured
    ? `Library: ${snapshot.libraryPlaylistName || "set"}`
    : "No karaoke library";
  els.statusLine.textContent = `${state.guest?.name || "Guest"} · ${bridge} · ${configured} · ${library}`;
  els.libraryHint.textContent = snapshot.libraryConfigured
    ? `Browsing ${snapshot.libraryPlaylistName || "your karaoke playlist"}`
    : "Host hasn’t set a karaoke library yet — use Search all Tidal, or configure one in TV Settings.";
}

function scrollQueueToUpcoming() {
  const viewport = els.queueViewport;
  if (!viewport) return;
  const anchor =
    els.queueList.querySelector(".queue-divider") ||
    els.queueList.querySelector(".queue-item:not(.history)");
  if (anchor) {
    viewport.scrollTop = anchor.offsetTop;
    return;
  }
  const historyItems = els.queueList.querySelectorAll(".queue-item.history");
  if (historyItems.length) {
    const last = historyItems[historyItems.length - 1];
    viewport.scrollTop = Math.max(0, last.offsetTop - viewport.clientHeight / 2);
  } else {
    viewport.scrollTop = 0;
  }
}

function buildQueueItem(item, { history, index = 0, inLibrary }) {
  const li = document.createElement("li");
  li.className = history ? "queue-item history" : "queue-item";
  li.dataset.itemId = item.id;
  if (!history) li.dataset.index = String(index);
  const hearted = inLibrary.has(item.track.tidalTrackId);
  li.innerHTML = history
    ? `
      <span class="drag-spacer" aria-hidden="true"></span>
      <div>
        <strong>${escapeHtml(item.track.title)}</strong>
        <span>${escapeHtml(item.track.artist)} · added by ${escapeHtml(item.addedByName)}</span>
      </div>
      <div class="queue-actions">
        <button type="button" data-play aria-label="Jump back to this track">${ICONS.play}</button>
      </div>
    `
    : `
      <span class="drag-handle" title="Drag to reorder" aria-hidden="true">⋮⋮</span>
      <div>
        <strong>${escapeHtml(item.track.title)}</strong>
        <span>${escapeHtml(item.track.artist)} · added by ${escapeHtml(item.addedByName)}</span>
      </div>
      <div class="queue-actions">
        <button type="button" class="heart ${hearted ? "filled" : ""}" data-heart aria-label="${hearted ? "In library" : "Add to karaoke library"}">${ICONS.heart}</button>
        <button type="button" data-play aria-label="Play this track">${ICONS.play}</button>
      </div>
    `;
  bindQueueItem(li, item, { history, hearted });
  return li;
}

function clearDropTarget() {
  els.queueList.querySelectorAll(".drop-target").forEach((el) => el.classList.remove("drop-target"));
}

function bindPointerReorder(handle, li, item) {
  handle.addEventListener("pointerdown", (event) => {
    if (event.button !== 0 && event.pointerType === "mouse") return;
    event.preventDefault();
    handle.setPointerCapture(event.pointerId);
    state.drag = {
      itemId: item.id,
      fromIndex: Number(li.dataset.index),
      pointerId: event.pointerId,
      dropIndex: Number(li.dataset.index),
    };
    li.classList.add("dragging");
  });

  handle.addEventListener("pointermove", (event) => {
    const drag = state.drag;
    if (!drag || drag.pointerId !== event.pointerId) return;
    const row = document.elementFromPoint(event.clientX, event.clientY)?.closest(".queue-item:not(.history)");
    clearDropTarget();
    if (!row || row === li) {
      drag.dropIndex = drag.fromIndex;
      return;
    }
    row.classList.add("drop-target");
    drag.dropIndex = Number(row.dataset.index);
  });

  const endDrag = async (event) => {
    const drag = state.drag;
    if (!drag || drag.pointerId !== event.pointerId) return;
    try {
      handle.releasePointerCapture(event.pointerId);
    } catch (_) {
      /* already released */
    }
    li.classList.remove("dragging");
    clearDropTarget();
    const { itemId, fromIndex, dropIndex } = drag;
    state.drag = null;
    if (dropIndex === fromIndex || Number.isNaN(dropIndex)) return;
    try {
      const snapshot = await withGuest((guest) =>
        api("/api/queue/reorder", {
          method: "POST",
          body: JSON.stringify({ guestId: guest.id, itemId, toIndex: dropIndex }),
        }),
      );
      renderSnapshot(snapshot);
    } catch (error) {
      showToast(error.message);
    }
  };

  handle.addEventListener("pointerup", endDrag);
  handle.addEventListener("pointercancel", endDrag);
}

function bindQueueItem(li, item, { history, hearted }) {
  if (!history) {
    const handle = li.querySelector(".drag-handle");
    if (handle) bindPointerReorder(handle, li, item);
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
    if (hearted) {
      showToast("Already in the karaoke library", { ok: true });
      return;
    }
    if (!state.snapshot?.libraryConfigured) {
      showToast("Host hasn't set a karaoke library yet");
      return;
    }
    try {
      const snapshot = await withGuest((guest) =>
        api("/api/library/favorite", {
          method: "POST",
          body: JSON.stringify({ guestId: guest.id, track: item.track }),
        }),
      );
      renderSnapshot(snapshot);
      showToast(`Added to ${snapshot.libraryPlaylistName || "library"}`, { ok: true });
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

function renderSearchResults(tracks, { artistBrowse = null } = {}) {
  state.artistBrowse = artistBrowse;
  els.searchResults.innerHTML = "";

  if (artistBrowse) {
    const header = document.createElement("div");
    header.className = "results-header";
    header.innerHTML = `
      <button type="button" class="link-btn" data-back-search>← Back</button>
      <strong>Top tracks — ${escapeHtml(artistBrowse.name)}</strong>
    `;
    header.querySelector("[data-back-search]").addEventListener("click", () => {
      renderSearchResults(state.lastSearchTracks);
    });
    els.searchResults.appendChild(header);
  }

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
    const artistControl = track.artistId && !artistBrowse
      ? `<button type="button" class="artist-link" data-artist>${artistLabel}</button>${albumBit}`
      : `<span>${artistLabel}${albumBit}</span>`;
    row.innerHTML = `
      <div>
        <strong>${escapeHtml(track.title)}</strong>
        ${artistControl}
      </div>
      <button type="button" data-add>Add</button>
    `;
    const artistBtn = row.querySelector("[data-artist]");
    if (artistBtn) {
      artistBtn.addEventListener("click", async () => {
        try {
          const payload = await api(`/api/artists/${encodeURIComponent(track.artistId)}/tracks`);
          renderSearchResults(payload.tracks || [], {
            artistBrowse: { id: track.artistId, name: track.artist },
          });
        } catch (error) {
          showToast(error.message);
        }
      });
    }
    row.querySelector("[data-add]").addEventListener("click", async () => {
      try {
        await withGuest((guest) =>
          api("/api/queue", {
            method: "POST",
            body: JSON.stringify({ guestId: guest.id, track }),
          }),
        );
        closeModal();
        showToast(`Queued ${track.title}`, { ok: true });
      } catch (error) {
        showToast(error.message);
      }
    });
    els.searchResults.appendChild(row);
  });
}

async function loadLibrary(query = "") {
  console.log("[library] load", { query });
  try {
    const path = query ? `/api/library?q=${encodeURIComponent(query)}` : "/api/library";
    const payload = await api(path);
    console.log("[library] result", {
      configured: payload.configured,
      playlistName: payload.playlistName,
      count: (payload.tracks || []).length,
    });
    if (!payload.configured) {
      els.searchResults.innerHTML = `<div class="result"><span>No karaoke library configured on the TV yet.</span></div>`;
      return;
    }
    renderSearchResults(payload.tracks || []);
  } catch (error) {
    console.error("[library] failed", error);
    showToast(error.message);
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
    if (tab === "library") loadLibrary(els.libraryInput.value);
  });
});

els.libraryForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  await loadLibrary(els.libraryInput.value);
});

els.searchForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  const query = els.searchInput.value;
  console.log("[search] submit", { query });
  try {
    const payload = await api("/api/search", {
      method: "POST",
      body: JSON.stringify({ query }),
    });
    console.log("[search] results", {
      count: (payload.tracks || []).length,
      sample: (payload.tracks || []).slice(0, 5).map((t) => `${t.title} — ${t.artist}`),
    });
    state.lastSearchQuery = query;
    state.lastSearchTracks = payload.tracks || [];
    renderSearchResults(state.lastSearchTracks);
    if (!(payload.tracks || []).length) {
      showToast("No tracks found");
    }
  } catch (error) {
    console.error("[search] failed", error);
    showToast(error.message);
  }
});

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

els.loadLyrics.addEventListener("click", async () => {
  try {
    const lyrics = await api("/api/lyrics");
    if (lyrics.instrumental) {
      els.lyricsBox.textContent = "(Instrumental)";
      return;
    }
    els.lyricsBox.textContent = lyrics.syncedLyrics || lyrics.plainLyrics || "No lyrics found.";
  } catch (error) {
    showToast(error.message);
  }
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
