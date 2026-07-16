const state = {
  guest: JSON.parse(localStorage.getItem("singGuest") || "null"),
  snapshot: null,
};

const els = {
  statusLine: document.getElementById("statusLine"),
  joinPanel: document.getElementById("joinPanel"),
  partyPanel: document.getElementById("partyPanel"),
  joinForm: document.getElementById("joinForm"),
  nameInput: document.getElementById("nameInput"),
  searchForm: document.getElementById("searchForm"),
  searchInput: document.getElementById("searchInput"),
  searchResults: document.getElementById("searchResults"),
  queueList: document.getElementById("queueList"),
  messageList: document.getElementById("messageList"),
  nowTitle: document.getElementById("nowTitle"),
  nowArtist: document.getElementById("nowArtist"),
  nowBy: document.getElementById("nowBy"),
  loadLyrics: document.getElementById("loadLyrics"),
  lyricsBox: document.getElementById("lyricsBox"),
};

async function api(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(data.error || `Request failed (${response.status})`);
  }
  return data;
}

function showParty() {
  els.joinPanel.classList.add("hidden");
  els.partyPanel.classList.remove("hidden");
}

function renderSnapshot(snapshot) {
  state.snapshot = snapshot;
  const np = snapshot.nowPlaying || {};
  els.nowTitle.textContent = np.track?.title || "Nothing yet";
  els.nowArtist.textContent = np.track?.artist || "";
  els.nowBy.textContent = np.addedByName ? `Queued by ${np.addedByName}` : "";

  els.queueList.innerHTML = "";
  (snapshot.queue || []).forEach((item) => {
    const li = document.createElement("li");
    li.innerHTML = `<strong>${escapeHtml(item.track.title)}</strong><span>${escapeHtml(item.track.artist)} · added by ${escapeHtml(item.addedByName)}</span>`;
    els.queueList.appendChild(li);
  });
  if (!snapshot.queue?.length) {
    els.queueList.innerHTML = `<li class="muted">Queue is empty — search and add a song.</li>`;
  }

  els.messageList.innerHTML = "";
  (snapshot.messages || []).slice(0, 12).forEach((msg) => {
    const li = document.createElement("li");
    li.textContent = msg.text;
    els.messageList.appendChild(li);
  });

  const bridge = snapshot.bridgeReady ? "Tidal bridge ready" : "Waiting for Tidal";
  const configured = snapshot.tidalConfigured ? "API configured" : "Set TIDAL credentials on TV";
  els.statusLine.textContent = `${state.guest?.name || "Guest"} · ${bridge} · ${configured}`;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

function renderSearchResults(tracks) {
  els.searchResults.innerHTML = "";
  if (!tracks.length) {
    els.searchResults.innerHTML = `<div class="result"><span>No tracks found</span></div>`;
    return;
  }
  tracks.forEach((track) => {
    const row = document.createElement("div");
    row.className = "result";
    row.innerHTML = `
      <div>
        <strong>${escapeHtml(track.title)}</strong>
        <span>${escapeHtml(track.artist)}${track.album ? " · " + escapeHtml(track.album) : ""}</span>
      </div>
      <button type="button">Add</button>
    `;
    row.querySelector("button").addEventListener("click", async () => {
      try {
        await api("/api/queue", {
          method: "POST",
          body: JSON.stringify({ guestId: state.guest.id, track }),
        });
      } catch (error) {
        alert(error.message);
      }
    });
    els.searchResults.appendChild(row);
  });
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
    alert(error.message);
  }
});

els.searchForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  try {
    const payload = await api("/api/search", {
      method: "POST",
      body: JSON.stringify({ query: els.searchInput.value }),
    });
    renderSearchResults(payload.tracks || []);
  } catch (error) {
    alert(error.message);
  }
});

document.querySelectorAll("[data-action]").forEach((button) => {
  button.addEventListener("click", async () => {
    if (!state.guest) return;
    const action = button.getAttribute("data-action");
    try {
      const snapshot = await api(`/api/${action}`, {
        method: "POST",
        body: JSON.stringify({ guestId: state.guest.id }),
      });
      renderSnapshot(snapshot);
    } catch (error) {
      alert(error.message);
    }
  });
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
    els.lyricsBox.textContent = error.message;
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
    if (state.guest) {
      showParty();
      renderSnapshot(snapshot);
    }
  } catch (_) {
    els.statusLine.textContent = "Waiting for party server…";
  }
  connectSocket();
}

boot();
