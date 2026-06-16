"use strict";

// Live Transcribe (web) — real-time, speaker-tagged English transcription using
// the browser Web Speech API. Same UX as the desktop/Android tools: tap who is
// about to speak (buttons or number keys 0–4), consecutive same-speaker speech
// merges into one paragraph, a speaker change starts a new one. Pause/Resume,
// Save, Clear. NOTE: in Chrome the Web Speech API streams audio to Google — it
// is not offline. Use the Android app for on-device recognition.

const SPK_NAMES = ["Me", "Speaker 1", "Speaker 2", "Speaker 3", "Speaker 4"];
const SPK_COLORS = ["#444444", "#1565C0", "#2E7D32", "#C62828", "#6A1B9A"];

const STATES = {
  idle:         ["#E0E0E0", "#444444", "Idle — press Start to begin"],
  listening:    ["#C8E6C9", "#1B5E20", "●  LISTENING — speak anytime"],
  hearing:      ["#FFCDD2", "#B71C1C", "●  HEARING YOU…"],
  paused:       ["#FFF9C4", "#827717", "❚❚  PAUSED — press Resume"],
  stopped:      ["#E0E0E0", "#444444", "Stopped — press Start to resume"],
};

const $ = (id) => document.getElementById(id);
const startBtn = $("startBtn");
const pauseBtn = $("pauseBtn");
const clearBtn = $("clearBtn");
const saveBtn = $("saveBtn");
const speakerRow = $("speakerRow");
const banner = $("banner");
const preview = $("preview");
const transcriptEl = $("transcript");

let recognition = null;
let running = false;
let paused = false;
let currentSpeaker = 0;
// paragraphs: { speaker, text, time }
const paras = [];

// ---- speaker buttons ----
const spkButtons = [];
SPK_NAMES.forEach((name, i) => {
  const b = document.createElement("button");
  b.className = "spk";
  b.textContent = `${name} [${i}]`;
  b.addEventListener("click", () => setSpeaker(i));
  speakerRow.appendChild(b);
  spkButtons.push(b);
});

function setSpeaker(i) {
  currentSpeaker = i;
  spkButtons.forEach((b, idx) => {
    if (idx === i) {
      b.classList.add("active");
      b.style.background = SPK_COLORS[i];
    } else {
      b.classList.remove("active");
      b.style.background = "";
    }
  });
}
setSpeaker(0);

// ---- banner ----
function setState(name) {
  const [bg, fg, cap] = STATES[name] || STATES.idle;
  banner.style.background = bg;
  banner.style.color = fg;
  banner.textContent = cap;
}

// ---- recognition ----
function getRecognition() {
  const Rec = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!Rec) return null;
  const r = new Rec();
  r.lang = "en-US";
  r.continuous = true;
  r.interimResults = true;
  r.maxAlternatives = 1;
  r.onstart = () => { if (!paused) setState("listening"); };
  r.onspeechstart = () => setState("hearing");
  r.onresult = onResult;
  r.onerror = (e) => {
    // "no-speech" / "aborted" are normal during continuous use.
    if (e.error === "not-allowed" || e.error === "service-not-allowed") {
      alert("Microphone access was blocked. Allow it and press Start again.");
      stop();
    }
  };
  r.onend = () => {
    preview.textContent = "";
    if (running && !paused) {
      try { r.start(); } catch (_) { setTimeout(() => { try { r.start(); } catch (_) {} }, 250); }
    }
  };
  return r;
}

function onResult(event) {
  let interim = "";
  for (let i = event.resultIndex; i < event.results.length; i++) {
    const res = event.results[i];
    const txt = res[0].transcript;
    if (res.isFinal) {
      appendFinal(currentSpeaker, txt);
    } else {
      interim += txt;
    }
  }
  preview.textContent = interim;
  if (running && !paused && interim) setState("hearing");
}

function start() {
  recognition = getRecognition();
  if (!recognition) {
    alert("This browser has no Web Speech API. Use Chrome, or the Android app.");
    return;
  }
  running = true;
  paused = false;
  startBtn.innerHTML = "■&nbsp;Stop";
  pauseBtn.disabled = false;
  pauseBtn.innerHTML = "❚❚&nbsp;Pause";
  setState("listening");
  try { recognition.start(); } catch (_) {}
}

function stop() {
  running = false;
  paused = false;
  if (recognition) {
    try { recognition.onend = null; recognition.stop(); } catch (_) {}
  }
  recognition = null;
  startBtn.innerHTML = "●&nbsp;Start";
  pauseBtn.disabled = true;
  pauseBtn.innerHTML = "❚❚&nbsp;Pause";
  preview.textContent = "";
  setState("stopped");
}

function togglePause() {
  if (!running) return;
  if (!paused) {
    paused = true;
    if (recognition) { try { recognition.stop(); } catch (_) {} }
    pauseBtn.innerHTML = "▶&nbsp;Resume";
    preview.textContent = "";
    setState("paused");
  } else {
    paused = false;
    pauseBtn.innerHTML = "❚❚&nbsp;Pause";
    setState("listening");
    if (recognition) { try { recognition.start(); } catch (_) {} }
  }
}

// ---- transcript ----
function timeNow() {
  return new Date().toTimeString().slice(0, 8);
}

function appendFinal(spk, raw) {
  const line = raw.trim();
  if (!line) return;
  const last = paras[paras.length - 1];
  if (last && last.speaker === spk) {
    last.text += " " + line;
  } else {
    paras.push({ speaker: spk, text: line, time: timeNow() });
  }
  render();
}

function render() {
  transcriptEl.innerHTML = "";
  for (const p of paras) {
    const para = document.createElement("p");
    const ts = document.createElement("span");
    ts.className = "ts";
    ts.textContent = `[${p.time}] `;
    const name = document.createElement("span");
    name.className = "name";
    name.style.color = SPK_COLORS[p.speaker];
    name.textContent = `${SPK_NAMES[p.speaker]}: `;
    para.appendChild(ts);
    para.appendChild(name);
    para.appendChild(document.createTextNode(p.text));
    transcriptEl.appendChild(para);
  }
  transcriptEl.scrollTop = transcriptEl.scrollHeight;
}

function clearAll() {
  paras.length = 0;
  render();
}

function save() {
  if (!paras.length) { alert("Nothing to save yet."); return; }
  const lines = paras.map((p) => `[${p.time}] ${SPK_NAMES[p.speaker]}: ${p.text}`);
  const blob = new Blob([lines.join("\n\n") + "\n"], { type: "text/plain" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  const stamp = new Date().toISOString().replace(/[-:T]/g, "").slice(0, 15);
  a.href = url;
  a.download = `transcript_${stamp}.txt`;
  a.click();
  URL.revokeObjectURL(url);
}

// ---- wiring ----
startBtn.addEventListener("click", () => (running ? stop() : start()));
pauseBtn.addEventListener("click", togglePause);
clearBtn.addEventListener("click", clearAll);
saveBtn.addEventListener("click", save);

document.addEventListener("keydown", (e) => {
  if (e.target.tagName === "INPUT" || e.target.tagName === "TEXTAREA") return;
  if (e.key >= "0" && e.key <= "4") { setSpeaker(parseInt(e.key, 10)); e.preventDefault(); }
  else if (e.key === "m" || e.key === "M") { setSpeaker(0); }
  else if (e.code === "Space") { togglePause(); e.preventDefault(); }
});

setState("idle");

// register service worker so the page can be installed as an app on Chromebook
if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("sw.js").catch(() => {});
  });
}
