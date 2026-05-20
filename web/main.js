/**
 * main.js — AirFerry Web Encoder front-end
 *
 * Depends on cimbar_js.js (libcimbar Emscripten-generated, USE_WASM=2 single-file build).
 *
 * C API used (encoder-only, exported by libcimbar cimbar_js.cpp):
 *   cimbare_configure(mode_val, compression)  → int
 *   cimbare_init_encode(fnPtr, fnLen, encId)  → int
 *   cimbare_encode_bufsize()                  → int
 *   cimbare_encode(bufPtr, size)              → int  (0=ready, 1=more, <0=err)
 *   cimbare_next_frame(colorBalance)          → int  (frame count, <0=err)
 *   cimbare_get_frame_buff(ptrToPtr)          → int  (byte length, <0=err)
 *   cimbare_get_aspect_ratio()                → float
 */

'use strict';

// ─── Patch GLFW passive event listeners ──────────────────────────────────────
// Emscripten's GLFW implementation registers touchstart/touchmove/wheel/
// mousewheel without { passive: true }, triggering Chrome [Violation] warnings
// and hurting scroll performance. Since cimbar_js.js is a libcimbar compiled artifact we
// cannot edit it directly; instead we patch EventTarget.addEventListener before
// the WASM module loads so that these specific scroll-blocking events are always
// registered as passive when the listener does not explicitly call
// preventDefault() (GLFW's handlers never do for these events).
(function patchPassiveListeners() {
  const _orig = EventTarget.prototype.addEventListener;
  const PASSIVE_EVENTS = new Set(['touchstart', 'touchmove', 'wheel', 'mousewheel']);
  EventTarget.prototype.addEventListener = function (type, listener, options) {
    if (PASSIVE_EVENTS.has(type)) {
      if (typeof options === 'object' && options !== null) {
        // Only force passive if the caller hasn't explicitly set passive: false
        if (options.passive !== false) {
          options = Object.assign({}, options, { passive: true });
        }
      } else {
        // options is boolean (capture) or undefined — wrap into object
        options = { capture: !!options, passive: true };
      }
    }
    return _orig.call(this, type, listener, options);
  };
})();

// ─── State ────────────────────────────────────────────────────────────────────
// NOTE: Do NOT use let/const for Module — cimbar_js.js declares `var Module`
// at the top level and the two declarations must be compatible (both var).
var Module = null;   // Emscripten module (populated by onRuntimeInitialized)
let _file  = null;   // File object selected by user
let _running = false;
let _rafId   = null;
let _canvas  = null;
let _ctx     = null;
let _frameCount = 0;
let _totalFrames = 0;  // blocks_required from C layer (= real 100% mark)
let _cycleFrames = 0;  // last frameRes, used to detect cycle wrap-around
let _lastFrameTime = 0;

// ─── DOM refs ─────────────────────────────────────────────────────────────────
const elLoading     = document.getElementById('loading');
const elDropzone    = document.getElementById('dropzone');
const elDropText    = document.getElementById('dropzone-text');
const elFileInput   = document.getElementById('file-input');
const elModeSelect  = document.getElementById('mode-select');
const elCompRange   = document.getElementById('compression-range');
const elCompVal     = document.getElementById('comp-val');
const elFpsRange    = document.getElementById('fps-range');
const elFpsVal      = document.getElementById('fps-val');
const elCanvasWrap  = document.getElementById('canvas-wrap');
const elPlaceholder = document.getElementById('canvas-placeholder');
const elFrameInfo   = document.getElementById('frame-info');
const elProgressWrap= document.getElementById('progress-wrap');
const elProgressBar = document.getElementById('progress-bar');
const elProgressLbl = document.getElementById('progress-label');
const elStatus      = document.getElementById('status');
const elBtnEncode   = document.getElementById('btn-encode');
const elBtnStop     = document.getElementById('btn-stop');

// ─── Helpers ──────────────────────────────────────────────────────────────────
function setStatus(msg, cls = '') {
  elStatus.textContent = msg;
  elStatus.className = cls;
}

function setProgress(current, total) {
  const pct = total > 0 ? Math.round((current / total) * 100) : 0;
  elProgressBar.style.width = pct + '%';
  elProgressLbl.textContent = `${current} / ${total > 0 ? total : '?'} 帧`;
  elProgressWrap.setAttribute('aria-valuenow', pct);
}

function ensureCanvas(w, h) {
  if (!_canvas) {
    _canvas = document.createElement('canvas');
    _canvas.setAttribute('role', 'img');
    _canvas.setAttribute('aria-label', 'AirFerry 编码帧预览');
    // Hide placeholder and append canvas
    if (elPlaceholder) elPlaceholder.style.display = 'none';
    elCanvasWrap.appendChild(_canvas);
    _ctx = _canvas.getContext('2d');
  }
  if (_canvas.width !== w || _canvas.height !== h) {
    _canvas.width  = w;
    _canvas.height = h;
  }
}

// ─── WASM module ready ────────────────────────────────────────────────────────
function onModuleReady(mod) {
  Module = mod;
  elLoading.classList.add('hidden');
  setStatus('WASM 模块已就绪', 'ok');
  console.log('[airferry] WASM module ready');
  // Fire event for header badge
  document.dispatchEvent(new Event('airferry:ready'));
  // Enable encode button if a file is already selected
  if (_file) elBtnEncode.disabled = false;
}

// main.js loads BEFORE cimbar_js.js (libcimbar compiled output, see index.html script order).
// cimbar_js.js (from libcimbar) does: var Module = typeof Module != "undefined" ? Module : {}
// So we pre-populate the global Module object with our callbacks here.
// We must NOT reassign Module (window.Module = {...}) because that would
// create a new object; instead we set properties on the existing var.
Module = {
  onRuntimeInitialized: function () {
    onModuleReady(Module);
  },
  print:    function (s) { console.debug('[airferry]', s); },
  printErr: function (s) { console.warn('[airferry]', s); },
  // GLFW requires Module.canvas to exist before glfwInit().
  // We provide a hidden offscreen canvas; actual frame rendering is done
  // by reading raw pixel data via libcimbar's cimbare_get_frame_buff() into our own canvas.
  canvas: (function () {
    const c = document.createElement('canvas');
    c.id = '_cimbar_glfw_canvas';
    c.width  = 1040;
    c.height = 1040;
    c.style.display = 'none';
    document.body.appendChild(c);
    return c;
  })(),
};

// ─── File selection ───────────────────────────────────────────────────────────
elDropzone.addEventListener('click', () => elFileInput.click());
elDropzone.addEventListener('keydown', e => {
  if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); elFileInput.click(); }
});
elDropzone.addEventListener('dragover', e => {
  e.preventDefault();
  elDropzone.classList.add('dragover');
});
elDropzone.addEventListener('dragleave', () => elDropzone.classList.remove('dragover'));
elDropzone.addEventListener('drop', e => {
  e.preventDefault();
  elDropzone.classList.remove('dragover');
  const f = e.dataTransfer.files[0];
  if (f) selectFile(f);
});
elFileInput.addEventListener('change', () => {
  if (elFileInput.files[0]) selectFile(elFileInput.files[0]);
});

function selectFile(f) {
  _file = f;
  const dz = document.getElementById('dropzone');
  dz.classList.add('has-file');
  elDropText.innerHTML = `<strong>${f.name}</strong><br>${formatBytes(f.size)}`;
  elBtnEncode.disabled = !Module;
  setStatus('');
  stopAnimation();
}

function formatBytes(n) {
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / (1024 * 1024)).toFixed(2) + ' MB';
}

// ─── Settings ─────────────────────────────────────────────────────────────────
elCompRange.addEventListener('input', () => {
  elCompVal.textContent = elCompRange.value;
  elCompRange.setAttribute('aria-valuenow', elCompRange.value);
});
elFpsRange.addEventListener('input', () => {
  elFpsVal.textContent = elFpsRange.value + ' fps';
  elFpsRange.setAttribute('aria-valuenow', elFpsRange.value);
});

// ─── Encode ───────────────────────────────────────────────────────────────────
elBtnEncode.addEventListener('click', startEncode);
elBtnStop.addEventListener('click', stopAnimation);

async function startEncode() {
  if (!Module || !_file) return;

  stopAnimation();
  setStatus('正在读取文件…', 'info');
  elBtnEncode.disabled = true;
  elBtnStop.disabled   = false;

  const modeVal     = parseInt(elModeSelect.value, 10);
  const compression = parseInt(elCompRange.value, 10);

  // 1. Configure
  // cimbare_configure() also tries to create a GLFW/WebGL window for rendering.
  // In the encoder-only (USE_WASM=2) build we use libcimbar's cimbare_get_frame_buff() to
  // read raw pixel data directly, so a window is not required.  We tolerate a
  // negative return value here (window creation may fail in headless contexts).
  const cfgRes = Module._cimbare_configure(modeVal, compression);
  if (cfgRes < 0) {
    console.warn('[airferry] configure returned', cfgRes,
      '— window creation may have failed; continuing without WebGL window.');
  }

  // 2. Read file into ArrayBuffer
  let fileData;
  try {
    fileData = await _file.arrayBuffer();
  } catch (e) {
    setStatus('读取文件失败：' + e.message, 'error');
    elBtnEncode.disabled = false;
    elBtnStop.disabled   = true;
    return;
  }

  // 3. init_encode — pass filename
  const fnBytes  = new TextEncoder().encode(_file.name);
  const fnPtr    = Module._malloc(fnBytes.length + 1);
  Module.HEAPU8.set(fnBytes, fnPtr);
  Module.HEAPU8[fnPtr + fnBytes.length] = 0;

  const initRes = Module._cimbare_init_encode(fnPtr, fnBytes.length, -1);
  Module._free(fnPtr);

  if (initRes < 0) {
    setStatus(`init_encode 失败 (${initRes})`, 'error');
    elBtnEncode.disabled = false;
    elBtnStop.disabled   = true;
    return;
  }

  // 4. Feed file data in chunks
  const chunkSize = Module._cimbare_encode_bufsize();
  const src       = new Uint8Array(fileData);
  const bufPtr    = Module._malloc(chunkSize);

  setStatus('正在压缩并编码…', 'info');

  let offset = 0;
  let encRes = 1;
  while (encRes === 1 && offset < src.length) {
    const slice = src.subarray(offset, offset + chunkSize);
    Module.HEAPU8.set(slice, bufPtr);
    encRes = Module._cimbare_encode(bufPtr, slice.length);
    offset += slice.length;
  }

  // Final flush (size < chunkSize signals end-of-data)
  if (encRes === 1) {
    // file was an exact multiple of chunkSize — send a zero-length flush
    encRes = Module._cimbare_encode(bufPtr, 0);
  }

  Module._free(bufPtr);

  if (encRes < 0) {
    setStatus(`encode 失败 (${encRes})`, 'error');
    elBtnEncode.disabled = false;
    elBtnStop.disabled   = true;
    return;
  }

  // 5. Start animation loop
  _frameCount  = 0;
  _totalFrames = 0;
  _cycleFrames = 0;
  _running     = true;
  elProgressWrap.style.display = 'block';

  // Query the minimum blocks the decoder needs — this is the real 100% mark.
  // The encoder cycles for 8× this count before restarting, but the decoder
  // only needs ~1× to reconstruct the file.
  const blocksReq = Module._cimbare_blocks_required ? Module._cimbare_blocks_required() : 0;
  _totalFrames = blocksReq > 0 ? blocksReq : 0;

  setProgress(0, _totalFrames);
  setStatus('编码中…', 'info');

  scheduleNextFrame();
}

// ─── Animation loop ───────────────────────────────────────────────────────────
function scheduleNextFrame() {
  if (!_running) return;
  const fps      = parseInt(elFpsRange.value, 10);
  const interval = 1000 / fps;
  const now      = performance.now();
  const delay    = Math.max(0, interval - (now - _lastFrameTime));
  _rafId = setTimeout(renderFrame, delay);
}

function renderFrame() {
  if (!_running || !Module) return;

  const frameRes = Module._cimbare_next_frame(false);
  if (frameRes < 0) {
    setStatus(`next_frame 失败 (${frameRes})`, 'error');
    stopAnimation();
    return;
  }

  // Get raw pixel buffer from the generated cv::Mat
  // libcimbar's cimbare_get_frame_buff writes the pointer into a HEAPU32 slot
  const ptrSlot = Module._malloc(4);
  const byteLen = Module._cimbare_get_frame_buff(ptrSlot);
  const imgPtr  = Module.HEAPU32[ptrSlot >> 2];
  Module._free(ptrSlot);

  if (byteLen > 0 && imgPtr) {
    // Determine canvas dimensions from aspect ratio + byte count
    // The frame is RGB (3 channels). byteLen = w * h * 3
    const aspect = Module._cimbare_get_aspect_ratio();
    // w/h = aspect, w*h = byteLen/3  →  w = sqrt(byteLen/3 * aspect)
    const w = Math.round(Math.sqrt((byteLen / 3) * aspect));
    const h = Math.round(byteLen / 3 / w);

    ensureCanvas(w, h);

    // Convert RGB → RGBA for ImageData
    const rgb  = Module.HEAPU8.subarray(imgPtr, imgPtr + byteLen);
    const rgba = new Uint8ClampedArray(w * h * 4);
    for (let i = 0, j = 0; i < byteLen; i += 3, j += 4) {
      rgba[j]     = rgb[i];
      rgba[j + 1] = rgb[i + 1];
      rgba[j + 2] = rgb[i + 2];
      rgba[j + 3] = 255;
    }
    _ctx.putImageData(new ImageData(rgba, w, h), 0, 0);
  }

  _frameCount++;
  _lastFrameTime = performance.now();

  // frameRes is the per-cycle block index (resets to 0 every 8×blocks_required frames).
  // _totalFrames = blocks_required (fetched once after encode, the real 100% mark).
  // If the C function wasn't available at encode time, fall back to wrap detection.
  if (_totalFrames === 0) {
    if (_cycleFrames > 0 && frameRes <= _cycleFrames) {
      // Detected wrap-around of the 8× cycle — use 1/8 of that as the real total
      _totalFrames = Math.round((_frameCount - 1) / 8);
    }
    _cycleFrames = frameRes;
  }

  // Progress: clamp to 100% at blocks_required, keep cycling visually after that
  const cyclePos = _totalFrames > 0
    ? Math.min(frameRes + 1, _totalFrames)
    : frameRes + 1;
  setProgress(cyclePos, _totalFrames);

  // Show which pass we're on (each pass = blocks_required frames)
  const pass = _totalFrames > 0 ? Math.floor(frameRes / _totalFrames) + 1 : 1;
  elFrameInfo.textContent = _totalFrames > 0
    ? `第 ${cyclePos}/${_totalFrames} 块  ·  第 ${pass} 轮  ·  总帧 #${_frameCount}`
    : `帧 #${_frameCount}`;
  elFrameInfo.classList.add('visible');

  scheduleNextFrame();
}

function stopAnimation() {
  _running = false;
  if (_rafId !== null) {
    clearTimeout(_rafId);
    _rafId = null;
  }
  elBtnEncode.disabled = !(_file && Module);
  elBtnStop.disabled   = true;
  if (_frameCount > 0)
    setStatus(`已停止（共渲染 ${_frameCount} 帧）`);
  elFrameInfo.classList.remove('visible');
}
