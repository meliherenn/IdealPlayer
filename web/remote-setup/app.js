// app.js - Connected IdealPlayer web controller

const PAIRING_CODE_LENGTH = 8;
const PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const MAX_PLAYLIST_BYTES = 5 * 1024 * 1024;

const params = new URLSearchParams(window.location.search);
const apiBaseUrl = normalizeApiBaseUrl(params.get("api") || "");

const form = document.querySelector("#setupForm");
const tabs = [...document.querySelectorAll(".tab")];
const fields = [...document.querySelectorAll(".mode-fields")];
const pairingCode = document.querySelector("#pairingCode");
const pairingState = document.querySelector("#pairingState");
const resultCard = document.querySelector("#resultCard");
const resultTitle = document.querySelector("#resultTitle");
const resultText = document.querySelector("#resultText");
const fileInput = document.querySelector("#m3uFile");
const fileTitle = document.querySelector("#fileTitle");
const fileMeta = document.querySelector("#fileMeta");

const setup = {
  mode: "xtream",
  file: null,
  payload: null,
};

const codeFromUrl = params.get("code") || params.get("pair") || "";

if (codeFromUrl) {
  pairingCode.value = normalizePairingCode(codeFromUrl);
  updatePairingState();
  if (apiBaseUrl && isPairingCodeReady(pairingCode.value)) {
    checkPairingStatusDb();
  }
}

tabs.forEach((tab) => {
  tab.addEventListener("click", () => setMode(tab.dataset.mode));
});

let pairingCheckTimeout = null;

pairingCode.addEventListener("input", () => {
  pairingCode.value = normalizePairingCode(pairingCode.value);
  updatePairingState();

  clearTimeout(pairingCheckTimeout);
  if (apiBaseUrl && isPairingCodeReady(pairingCode.value)) {
    pairingCheckTimeout = setTimeout(checkPairingStatusDb, 500);
  }
});

fileInput.addEventListener("change", async () => {
  const [file] = fileInput.files;
  if (!file) {
    setup.file = null;
    fileTitle.textContent = "M3U dosyası seç";
    fileMeta.textContent = "Dosya sadece bu oturumda okunur.";
    return;
  }

  if (file.size > MAX_PLAYLIST_BYTES) {
    fileInput.value = "";
    setResult("Dosya çok büyük", "M3U dosyası 5 MB sınırını aşıyor.", "error");
    return;
  }

  const text = await file.text();
  if (new Blob([text]).size > MAX_PLAYLIST_BYTES) {
    fileInput.value = "";
    setResult("Dosya çok büyük", "M3U dosyası UTF-8 olarak 5 MB sınırını aşıyor.", "error");
    return;
  }
  setup.file = {
    name: file.name,
    size: file.size,
    content: text,
    lineCount: text.split(/\r?\n/).length,
  };
  fileTitle.textContent = file.name;
  fileMeta.textContent = `${formatBytes(file.size)} • ${setup.file.lineCount} satır`;
});

document.querySelector("#clearButton").addEventListener("click", () => {
  form.reset();
  setMode("xtream");
  setup.file = null;
  setup.payload = null;
  fileTitle.textContent = "M3U dosyası seç";
  fileMeta.textContent = "Dosya sadece bu oturumda okunur.";
  updatePairingState();
  setResult("Hazır", "TV kodu ve kaynak bilgileri girildiğinde kurulum paketi hazırlanır.", "");
});

document.querySelector("#copyPayload").addEventListener("click", async () => {
  const payload = setup.payload || buildPayload(new FormData(form));
  await navigator.clipboard.writeText(JSON.stringify(payload, null, 2));
  setResult("Kopyalandı", "Kurulum paketi panoya kopyalandı.", "success");
});

form.addEventListener("submit", async (event) => {
  event.preventDefault();

  try {
    const payload = buildPayload(new FormData(form));
    setup.payload = payload;
    validatePayload(payload);

    if (!apiBaseUrl) {
      throw new Error("Güvenli Connected IdealPlayer API adresi yapılandırılmamış.");
    }

    setResult("Gönderiliyor...", "Kurulum paketi TV'ye gönderiliyor...", "loading");

    const code = payload.pairingCode.toUpperCase();
    const response = await fetch(`${apiBaseUrl}/api/pairings/${encodeURIComponent(code)}/playlist`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      credentials: "omit",
      cache: "no-store",
      referrerPolicy: "no-referrer",
    });
    if (!response.ok) {
      throw new Error(`Remote setup isteği başarısız (HTTP ${response.status}).`);
    }

    setResult("TV’ye gönderildi", "IdealPlayer cihazı kaynak bilgilerini alabilir.", "success");
  } catch (error) {
    setResult("Kontrol gerekli", error.message, "error");
  }
});

function setMode(mode) {
  setup.mode = mode;
  tabs.forEach((tab) => {
    const active = tab.dataset.mode === mode;
    tab.classList.toggle("active", active);
    tab.setAttribute("aria-selected", String(active));
  });
  fields.forEach((group) => {
    group.classList.toggle("active", group.dataset.fields === mode);
  });
}

function updatePairingState() {
  const ready = isPairingCodeReady(pairingCode.value);
  pairingState.textContent = ready ? "Hazır" : "Bekliyor";
  pairingState.className = "status-chip";
  if (ready) pairingState.classList.add("ready");
}

async function checkPairingStatusDb() {
  const code = normalizePairingCode(pairingCode.value);
  if (!apiBaseUrl || !isPairingCodeReady(code)) {
    updatePairingState();
    return;
  }

  try {
    const response = await fetch(`${apiBaseUrl}/api/pairings/${encodeURIComponent(code)}`, {
      method: "GET",
      credentials: "omit",
      cache: "no-store",
      referrerPolicy: "no-referrer",
    });
    if (response.ok) {
      const data = await response.json();
      if (data.status === "pending") {
        pairingState.textContent = "TV Bağlı";
        pairingState.className = "status-chip ready";
      } else if (data.status === "completed") {
        pairingState.textContent = "Gönderildi";
        pairingState.className = "status-chip ready";
      } else if (data.status === "expired" || data.status === "error") {
        pairingState.textContent = "Süre Doldu";
        pairingState.className = "status-chip error";
      }
    } else if (response.status === 404) {
      pairingState.textContent = "Kod Bulunamadı";
      pairingState.className = "status-chip error";
    } else {
      throw new Error(`HTTP ${response.status}`);
    }
  } catch (e) {
    console.error("Eşleşme kontrolü başarısız:", e);
    updatePairingState();
  }
}

function normalizeApiBaseUrl(value) {
  if (!value) return "";
  try {
    const url = new URL(value);
    const isLocalDevelopment = url.hostname === "localhost" || url.hostname === "127.0.0.1";
    if (url.protocol !== "https:" && !isLocalDevelopment) return "";
    return url.toString().replace(/\/$/, "");
  } catch (_) {
    return "";
  }
}

function buildPayload(data) {
  const name = String(data.get("name") || "").trim();
  const epgUrl = String(data.get("epgUrl") || "").trim();
  const pairing = normalizePairingCode(pairingCode.value);

  const payload = {
    version: 1,
    source: "connected-idealplayer",
    pairingCode: pairing,
    playlist: {
      name,
      type: setup.mode,
      epgUrl,
      rememberOnStart: data.get("rememberOnStart") === "on",
      epgAutoSync: data.get("epgAutoSync") === "on",
    },
  };

  if (setup.mode === "xtream") {
    payload.playlist.serverUrl = String(data.get("serverUrl") || "").trim();
    payload.playlist.username = String(data.get("username") || "").trim();
    payload.playlist.password = String(data.get("password") || "");
  }

  if (setup.mode === "m3u") {
    payload.playlist.url = String(data.get("m3uUrl") || "").trim();
  }

  if (setup.mode === "file") {
    payload.playlist.fileName = setup.file?.name || "";
    payload.playlist.fileSize = setup.file?.size || 0;
    payload.playlist.fileContent = setup.file?.content || "";
  }

  return payload;
}

function validatePayload(payload) {
  if (!isPairingCodeReady(payload.pairingCode)) {
    throw new Error("TV kodu 8 karakter olmalı.");
  }

  if (!payload.playlist.name) {
    throw new Error("Liste adı gerekli.");
  }

  if (payload.playlist.epgUrl && !isUrlLike(payload.playlist.epgUrl)) {
    throw new Error("EPG URL geçerli görünmüyor.");
  }

  if (payload.playlist.type === "xtream") {
    if (!isUrlLike(payload.playlist.serverUrl)) throw new Error("Sunucu URL gerekli.");
    if (!payload.playlist.username) throw new Error("Kullanıcı adı gerekli.");
    if (!payload.playlist.password) throw new Error("Şifre gerekli.");
  }

  if (payload.playlist.type === "m3u" && !isUrlLike(payload.playlist.url)) {
    throw new Error("M3U URL gerekli.");
  }

  if (payload.playlist.type === "file") {
    if (!payload.playlist.fileContent) {
      throw new Error("M3U dosyası seçilmeli.");
    }
    if (payload.playlist.fileSize > MAX_PLAYLIST_BYTES) {
      throw new Error("M3U dosyası 5 MB sınırını aşıyor.");
    }
  }
}

function normalizePairingCode(value) {
  return String(value || "")
    .toUpperCase()
    .split("")
    .filter((character) => PAIRING_ALPHABET.includes(character))
    .join("")
    .slice(0, PAIRING_CODE_LENGTH);
}

function isPairingCodeReady(value) {
  return normalizePairingCode(value).length === PAIRING_CODE_LENGTH;
}

function isUrlLike(value) {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
}

function setResult(title, text, state) {
  resultTitle.textContent = title;
  resultText.textContent = text;
  resultCard.className = "result-card";
  if (state) resultCard.classList.add(state);
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}
