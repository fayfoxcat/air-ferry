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
let _prevFrameRes = -1; // previous frameRes, used to detect fountain stream restart

// ── Temporal dithering (sub-pixel jitter) ─────────────────────────────────
// Cycles a 4-frame pattern: center → top-left → center → bottom-right.
// Each step shifts by ~8px at reference 1080p, scaled to actual image size.
// This dither helps phone cameras by sampling barcode cells from slightly
// different sensor pixel positions across frames, enabling super-resolution
// reconstruction and reducing color aliasing / moiré artifacts.
let _offscreen    = null;
let _offscreenCtx = null;
let _shakeIdx     = 0;
const SHAKE_DIRS  = [[0,0], [-1,-1], [0,0], [1,1]];

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
    // Recover exact image dimensions from byteLen + approximate aspect ratio.
    // cimbare_get_aspect_ratio() may diverge from the actual cv::Mat
    // because cimbare_init_window() rounds dimensions up to multiples of 4.
    const totalPx      = byteLen / 3;
    const approxAspect = Module._cimbare_get_aspect_ratio();
    const initW        = Math.round(Math.sqrt(totalPx * approxAspect));
    let imgW = initW;
    let imgH;

    // Search nearby widths for one that evenly divides totalPx
    for (let dw = 0; dw <= 12; ++dw) {
      for (const w of [initW - dw, initW + dw]) {
        if (w <= 0) continue;
        const h = totalPx / w;
        if (h === Math.floor(h) && Math.abs(w / h - approxAspect) < 0.05) {
          imgW = w;
          imgH = h;
          dw = 999;
          break;
        }
      }
    }
    if (!imgH) {
      imgH = Math.round(totalPx / imgW);
      imgW = Math.round(totalPx / imgH);
    }

    // ── Temporal dithering ──────────────────────────────────────────
    const shakePx = 8.0 * Math.min(imgW, imgH) / 1080.0;
    const pad     = Math.ceil(shakePx);
    const [dx, dy] = SHAKE_DIRS[_shakeIdx];
    _shakeIdx = (_shakeIdx + 1) % 4;
    const offX = dx * shakePx;
    const offY = dy * shakePx;

    // Canvas is padded so the shake offset never exposes black edges
    const cw = imgW + pad * 2;
    const ch = imgH + pad * 2;
    ensureCanvas(cw, ch);

    // Convert RGB → RGBA for ImageData
    const rgb  = Module.HEAPU8.subarray(imgPtr, imgPtr + byteLen);
    const rgba = new Uint8ClampedArray(imgW * imgH * 4);
    for (let i = 0, j = 0; i < byteLen; i += 3, j += 4) {
      rgba[j]     = rgb[i];
      rgba[j + 1] = rgb[i + 1];
      rgba[j + 2] = rgb[i + 2];
      rgba[j + 3] = 255;
    }

    // Stage to offscreen canvas (putImageData ignores transforms)
    if (!_offscreen || _offscreen.width !== imgW || _offscreen.height !== imgH) {
      _offscreen = document.createElement('canvas');
      _offscreen.width  = imgW;
      _offscreen.height = imgH;
      _offscreenCtx = _offscreen.getContext('2d', { willReadFrequently: false });
    }
    _offscreenCtx.putImageData(new ImageData(rgba, imgW, imgH), 0, 0);

    // Draw with sub-pixel jitter offset; padding prevents edge clipping
    _ctx.fillStyle = '#000';
    _ctx.fillRect(0, 0, cw, ch);
    _ctx.imageSmoothingEnabled = true;
    _ctx.drawImage(_offscreen, pad + offX, pad + offY, imgW, imgH);
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

  // Reset temporal dithering phase when the fountain stream restarts
  // (frameRes jumps from a high value back to near-zero).
  if (_prevFrameRes > frameRes) {
    _shakeIdx = 0;
  }
  _prevFrameRes = frameRes;

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
