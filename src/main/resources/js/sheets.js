/* Shared writes retain stable identities, optimistic versions and durable request IDs. */
(function () {
  'use strict';
  const el = (tag, text, cls) => { const n = document.createElement(tag); if (text !== undefined) n.textContent = text; if (cls) n.className = cls; return n; };
  const letters = n => { let s = ''; for (n++; n; n = Math.floor((n - 1) / 26)) s = String.fromCharCode(65 + (n - 1) % 26) + s; return s; };
  const colIndex = s => { let n = 0; for (let i = 0; i < s.length; i++) n = n * 26 + (s.charCodeAt(i) - 64); return n - 1; };
  const uuid = () => crypto.randomUUID ? crypto.randomUUID() : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => { const v = crypto.getRandomValues(new Uint8Array(1))[0] & 15; return (c === 'x' ? v : (v & 3) | 8).toString(16); });

  const ICONS = {
    undo: '<svg viewBox="0 0 16 16"><path d="M4.5 6H10a3 3 0 1 1 0 6H6v-1.5h4a1.5 1.5 0 0 0 0-3H4.5l2.2 2.2-1.1 1.1L1.8 7l3.8-3.8 1.1 1.1L4.5 6z"/></svg>',
    redo: '<svg viewBox="0 0 16 16"><path d="M11.5 6H6a3 3 0 1 0 0 6h4v-1.5H6a1.5 1.5 0 0 1 0-3h5.5L9.3 9.7l1.1 1.1L14.2 7l-3.8-3.8-1.1 1.1L11.5 6z"/></svg>',
    left: '<svg viewBox="0 0 16 16"><path d="M2 3h12v1.5H2V3zm0 3.3h8v1.5H2V6.3zm0 3.4h12v1.5H2V9.7zm0 3.3h8v1.5H2V13z"/></svg>',
    center: '<svg viewBox="0 0 16 16"><path d="M2 3h12v1.5H2V3zm2 3.3h8v1.5H4V6.3zm-2 3.4h12v1.5H2V9.7zm2 3.3h8v1.5H4V13z"/></svg>',
    right: '<svg viewBox="0 0 16 16"><path d="M2 3h12v1.5H2V3zm4 3.3h8v1.5H6V6.3zm-4 3.4h12v1.5H2V9.7zm4 3.3h8v1.5H6V13z"/></svg>',
    expand: '<svg viewBox="0 0 16 16"><path d="M2 2h4.5v1.5H3.5V6H2V2zm7.5 0H14v4h-1.5V3.5H9.5V2zM2 10h1.5v2.5H6V14H2v-4zm10.5 2.5V10H14v4H9.5v-1.5h3z"/></svg>'
  };

  const PALETTE_SWATCHES = [
    '#ffffff', '#f4f5f7', '#dfe1e6', '#172b4d', '#0747a6', '#107c41',
    '#fff2cc', '#fffae6', '#ffebe6', '#deebff', '#e3fcef', '#eae6ff',
    '#ffe380', '#ffbdad', '#b3d4ff', '#abf5d1', '#c0b6f2', '#b3bac5',
    '#ff991f', '#de350b', '#0052cc', '#006644', '#403294', '#42526e'
  ];

  // Excel quotes fields containing tabs/newlines and doubles embedded quotes.
  function parseClipboard(text) {
    const rows = []; let row = [], value = '', quoted = false, closed = false;
    for (let i = 0; i < text.length; i++) {
      const ch = text[i];
      if (quoted) { if (ch === '"') { if (text[i + 1] === '"') { value += '"'; i++; } else { quoted = false; closed = true; } } else value += ch; }
      else if (ch === '\t' || ch === '\r' || ch === '\n') { row.push(value); value = ''; closed = false; if (ch !== '\t') { rows.push(row); row = []; if (ch === '\r' && text[i + 1] === '\n') i++; } }
      else if (ch === '"' && value === '' && !closed) quoted = true;
      else { if (closed) throw new Error('Clipboard contains invalid quoted fields'); value += ch; }
    }
    if (quoted) throw new Error('Clipboard contains an unclosed quoted field');
    if (value || row.length || closed) { row.push(value); rows.push(row); }
    return rows.length ? rows : [['']];
  }
  const quote = v => /[\t\r\n"]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;

  function inferCellData(rawInput, prev = {}) {
    const str = String(rawInput ?? '');
    const trimmed = str.trim();
    if (prev.format === '@') { const next = { ...prev, value: str, type: 'text', formula: '' }; return next; }
    if (trimmed === '') {
      const next = { ...prev, value: '', type: 'text' };
      next.formula = '';
      return next;
    }
    if (trimmed.startsWith('=') && trimmed.length > 1) {
      return { ...prev, value: trimmed, formula: trimmed, type: 'text' };
    }
    if (/^(true|false)$/i.test(trimmed)) {
      const next = { ...prev, value: trimmed.toLowerCase(), type: 'boolean' };
      next.formula = '';
      return next;
    }
    if (/^-?(?:0|[1-9]\d*)(?:\.\d+)?$/.test(trimmed)) {
      const n = Number(trimmed);
      if (Number.isFinite(n) && (!Number.isInteger(n) || Number.isSafeInteger(n)) && trimmed.replace(/[^0-9]/g, '').replace(/^0+/, '').length <= 15) {
        const next = { ...prev, value: String(n), type: 'number' };
        if (next.format === '@') delete next.format;
        next.formula = '';
        return next;
      }
    }
    const next = { ...prev, value: str, type: 'text', wrap: prev.wrap || str.includes('\n') };
    next.formula = '';
    return next;
  }

  // Only the server computes formulas. This transform is for relative fill references;
  // quoted strings and absolute row/column markers retain their meaning.
  function shiftFormula(formula, dr, dc) {
    return formula.replace(/"(?:[^"]|"")*"|\$?[A-Za-z]+\$?[1-9][0-9]*/g, token => {
      if (token[0] === '"') return token;
      const m = token.match(/^(\$?)([A-Za-z]+)(\$?)([1-9][0-9]*)$/);
      const c = colIndex(m[2].toUpperCase()) + (m[1] ? 0 : dc), r = Number(m[4]) - 1 + (m[3] ? 0 : dr);
      return r < 0 || c < 0 || r >= 9999 || c >= 200 ? '#REF!' : m[1] + letters(c) + m[3] + (r + 1);
    });
  }

  class WriteQueue {
    constructor(transport, changed, newId = uuid) { this.transport = transport; this.changed = changed; this.newId = newId; this.confirmed = null; this.items = []; this.error = null; this.running = false; }
    accept(result) {
      let next = this.confirmed;
      if (Object.prototype.hasOwnProperty.call(result, 'document')) next = result.document;
      else if (result.changes) {
        if (!next) throw new Error('Missing base document');
        next = { ...next, cells: { ...next.cells } };
        for (const delta of result.changes) {
          if (delta.revision !== next.revision + 1) throw new Error('Update gap; reload required');
          Object.assign(next.cells, delta.cells); next.revision = delta.revision;
        }
      } else throw new Error('Server did not return committed changes');
      this.confirmed = next;
    }
    projected() {
      if (!this.confirmed) return null;
      const next = { ...this.confirmed, cells: { ...this.confirmed.cells } };
      for (const item of this.items) if (item.op.type === 'patch') for (const edit of item.op.edits) {
        next.cells[edit.row + ':' + edit.column] = { ...edit.cell, version: edit.expectedVersion + 1 };
      }
      return next;
    }
    enqueue(op) {
      if (this.items.length >= 1000) throw new Error('Pending queue full; retry or download pending changes first');
      if (this.items.length && (op.type !== 'patch' || this.items[0].op.type !== 'patch')) throw new Error('Wait for pending changes');
      const copy = JSON.parse(JSON.stringify(op));
      this.items.push({ id: this.newId(), op: copy, body: JSON.stringify(copy), since: null });
      this.changed(); this.send();
    }
    async send() {
      if (this.running || this.error || !this.items.length) return;
      this.running = true;
      try {
        while (this.items.length) {
          const item = this.items[0];
          if (item.since === null) item.since = this.confirmed ? this.confirmed.revision : -1;
          const result = await this.transport(item);
          this.accept(result); this.items.shift(); this.changed(result);
        }
      } catch (error) { this.error = error; this.changed(); }
      finally { this.running = false; }
    }
    retry() { if (this.running) return; this.error = null; this.changed(); this.send(); }
    discard() { if (this.running) throw new Error('Wait for the request to finish'); this.items = []; this.error = null; this.changed(); }
  }

  function formatDisplayValue(raw, fmt, type) {
    if (raw === '' || raw === null || raw === undefined) return '';
    const n = Number(raw);
    if (type === 'number' && Number.isFinite(n) && fmt && fmt !== '@' && fmt !== 'General') {
      if (fmt.includes('%')) return (n * 100).toLocaleString('en-US', { maximumFractionDigits: 2 }) + '%';
      if (fmt.includes('$')) return '$' + n.toLocaleString('en-US', { minimumFractionDigits: fmt.includes('.00') ? 2 : 0, maximumFractionDigits: 2 });
      if (fmt.includes('#,##0')) return n.toLocaleString('en-US', { minimumFractionDigits: fmt.includes('.00') ? 2 : 0, maximumFractionDigits: 2 });
    }
    return String(raw);
  }

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = { parseClipboard, quote, inferCellData, shiftFormula, WriteQueue, formatDisplayValue };
    return;
  }

  function mount(root) {
    if (root.dataset.ready) return; root.dataset.ready = 'true'; root.replaceChildren();
    const base = (window.AJS ? AJS.contextPath() : '') + '/rest/collaborative-sheets/1.0/sheets/' + root.dataset.page + '/' + root.dataset.sheet;
    let history = { undoHead: 0, redoHead: 0 };
    let doc = null, canEdit = false, pending = null, editor = null, anchor = null, extent = null, drag = false, sort = null, filters = {}, page = 0, viewRows = [], rowPositions = new Map(), pageRows = [], afterSave = null, openMenu = null;
    const titlebar = el('div', undefined, 'cs-titlebar'), ribbon = el('div', undefined, 'cs-ribbon'), formula = el('div', undefined, 'cs-formula'), notice = el('div', undefined, 'cs-notice'), grid = el('div', undefined, 'cs-grid'), footer = el('div', undefined, 'cs-footer');
    const status = el('span', 'Loading…', 'cs-status');
    const fullscreenBtn = el('button', '⛶', 'cs-fullscreen-btn');
    fullscreenBtn.type = 'button'; fullscreenBtn.title = 'Toggle fullscreen view'; fullscreenBtn.setAttribute('aria-label', 'Toggle fullscreen view');
    fullscreenBtn.onclick = () => { root.classList.toggle('cs-fullscreen'); fullscreenBtn.setAttribute('aria-pressed', String(root.classList.contains('cs-fullscreen'))); };
    titlebar.append(el('span', '▦', 'cs-workbook-icon'), el('strong', root.dataset.sheet || 'Sheet'), el('span', 'Home', 'cs-home-tab'), status, fullscreenBtn);
    root.append(titlebar, ribbon, formula, notice, grid, footer);
    grid.tabIndex = 0; grid.setAttribute('aria-label', 'Worksheet. Select a cell and type. Shift and arrows extend selection.');
    const controls = [];
    const button = (text, title, action, parent, edit = false, iconSvg = null) => {
      const b = el('button');
      if (iconSvg) { b.innerHTML = iconSvg; if (text && !['↶', '↷', '≡'].includes(text)) b.append(el('span', text)); }
      else b.textContent = text;
      b.type = 'button'; b.title = title; b.setAttribute('aria-label', title); b.onclick = action; parent.append(b); if (edit) controls.push(b); return b;
    };
    const group = name => { const box = el('div', undefined, 'cs-group'), content = el('div', undefined, 'cs-group-tools'); box.append(content, el('span', name, 'cs-group-label')); ribbon.append(box); return content; };
    function closePopovers() { if (openMenu) { openMenu.remove(); openMenu = null; } }
    function show(text, bad = false) { status.textContent = text; status.classList.toggle('cs-error', bad); }
    async function fetchJson(url, options) {
      const response = await fetch(url, { credentials: 'same-origin', cache: 'no-store', ...options });
      let body; try { body = JSON.parse(await response.text()); } catch (_) { body = { error: 'Unexpected server response (' + response.status + ')' }; }
      if (!response.ok) { const e = new Error(body.error || 'Request failed (' + response.status + ')'); e.status = response.status; throw e; }
      return body;
    }
    function message(text) { notice.replaceChildren(el('span', text)); button('×', 'Dismiss message', () => notice.replaceChildren(), notice); }
    function download(name, value) { const u = URL.createObjectURL(new Blob([value], { type: 'application/json' })), a = el('a'); a.href = u; a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(u), 1000); }
    function pendingNotice(error) {
      notice.replaceChildren(el('span', error.message + '. Your changes are still pending.'));
      if (![409, 400, 403].includes(error.status)) button('Retry', 'Retry the same save', send, notice);
      button('Download changes', 'Download pending changes', () => download('pending-sheet-changes.json', JSON.stringify(queue.items, null, 2)), notice);
      button('Discard', 'Discard pending changes', () => { if (window.confirm('Discard unsaved changes? Download them first if needed.')) { queue.discard(); notice.replaceChildren(); refresh(true); } }, notice);
    }
    const queue = new WriteQueue(item => fetchJson(base + '?since=' + item.since, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Sheet-Request': item.id }, body: item.body
    }), result => {
      pending = queue.items[0] || null; doc = queue.projected();
      if (result?.history) history = result.history;
      if (!editor) render(); else syncControls();
      if (queue.error) { pendingNotice(queue.error); show('Not saved · ' + queue.items.length + ' pending', true); }
      else { notice.replaceChildren(); show(pending ? 'Saving… · ' + queue.items.length + ' pending' : 'Saved · v' + (doc?.revision || 0)); }
    });
    function send() { queue.retry(); }
    function save(op, done) {
      if (!canEdit || editor) return;
      try { queue.enqueue(op); if (done) done(); } catch (error) { message(error.message); }
    }
    async function refresh(force = false) {
      if (!root.isConnected || pending || editor || drag) return;
      try {
        const rev = doc ? doc.revision : -1;
        const result = await fetchJson(base + (doc && !force ? '?since=' + rev : ''));
        if (pending || editor || drag || (doc ? doc.revision : -1) !== rev) return;
        history = result.history || { undoHead: 0, redoHead: 0 };
        const permissionChanged = canEdit !== result.canEdit; canEdit = result.canEdit;
        queue.accept(result); const next = queue.projected();
        const changed = !doc || !next || doc.revision !== next.revision;
        if (doc && next && doc.rows[0] !== next.rows[0]) anchor = extent = null;
        doc = next;
        if (changed || force || permissionChanged) render(); else syncControls();
        show(doc ? 'Saved · v' + doc.revision + (canEdit ? '' : ' · Read only') : 'No sheet yet');
      } catch (error) { show(error.message, true); }
    }
    function cell(r, c) {
      if (!doc || r < 0 || r >= doc.rows.length || c < 0 || c >= doc.columns.length) return { value: '', type: 'text', version: 0 };
      return doc.cells[doc.rows[r] + ':' + doc.columns[c]] || { value: '', type: 'text', version: 0 };
    }
    function computedValue(r, c) { return cell(r, c).value ?? ''; }
    function edit(r, c, changes) {
      const prev = cell(r, c);
      const next = { ...prev, ...changes };
      for (const k of ['background', 'foreground', 'align', 'format', 'formula']) { if (next[k] === '' || next[k] === null) delete next[k]; }
      return { row: doc.rows[r], column: doc.columns[c], expectedVersion: prev.version || 0, cell: next };
    }
    function bounds() { if (!anchor || !extent) return null; const a = rowPositions.get(anchor.r) ?? -1, b = rowPositions.get(extent.r) ?? -1; return { top: Math.min(a, b), bottom: Math.max(a, b), left: Math.min(anchor.c, extent.c), right: Math.max(anchor.c, extent.c) }; }
    function mergedAnchor(r, c) { for (const m of doc.merges) if (r >= m.row && r < m.row + m.height && c >= m.column && c < m.column + m.width) return { r: m.row, c: m.column }; return { r, c }; }
    function eachSelected(fn) {
      const b = bounds(); if (!b || b.top < 0) return;
      if ((b.bottom - b.top + 1) * (b.right - b.left + 1) > 10000) throw new Error('Select at most 10,000 cells for this operation');
      const seen = new Set();
      for (let y = b.top; y <= b.bottom; y++) for (let c = b.left; c <= b.right; c++) { const p = mergedAnchor(viewRows[y], c), key = p.r + ':' + p.c; if (!seen.has(key)) { seen.add(key); fn(p.r, p.c); } }
    }
    function formatCells(changes) {
      if (!anchor || editor || pending) return;
      try { const edits = []; eachSelected((r, c) => edits.push(edit(r, c, changes))); if (edits.length) save({ type: 'patch', edits }, () => grid.focus({ preventScroll: true })); } catch (e) { message(e.message); }
    }
    function select(r, c, extend = false) { if (editor) return; if (!extend || !anchor) anchor = { r, c }; extent = { r, c }; paintSelection(); }

    const aggDisplay = el('span', '', 'cs-aggregates');
    function updateAggregates() {
      aggDisplay.textContent = '';
      if (!doc || !anchor || !extent) return;
      const b = bounds(); if (!b || b.top < 0) return;
      const totalSelected = (b.bottom - b.top + 1) * (b.right - b.left + 1);
      if (totalSelected <= 1 || totalSelected > 10000) return;
      let count = 0, numCount = 0, sum = 0, min = Infinity, max = -Infinity;
      for (let y = b.top; y <= b.bottom; y++) {
        for (let c = b.left; c <= b.right; c++) {
          const val = computedValue(viewRows[y], c);
          if (val !== '' && val !== null && val !== undefined) {
            count++;
            const n = Number(val);
            if (Number.isFinite(n)) { numCount++; sum += n; if (n < min) min = n; if (n > max) max = n; }
          }
        }
      }
      if (numCount > 0) {
        const avg = sum / numCount;
        const fmt = x => Number(x.toFixed(2)).toLocaleString('en-US');
        aggDisplay.textContent = `Sum: ${fmt(sum)} · Avg: ${fmt(avg)} · Count: ${count}`;
      } else if (count > 0) {
        aggDisplay.textContent = `Count: ${count}`;
      }
    }

    function paintSelection() {
      const b = bounds();
      grid.querySelectorAll('.cs-fill-handle').forEach(h => h.remove());
      let bottomRightTd = null;
      grid.querySelectorAll('td').forEach(td => {
        const r = Number(td.dataset.row), c = Number(td.dataset.col), i = rowPositions.get(r);
        const end = c + Number(td.colSpan || 1) - 1;
        const selected = !!b && i >= b.top && i <= b.bottom && end >= b.left && c <= b.right;
        td.classList.toggle('cs-selected', selected);
        td.classList.toggle('cs-active', !!anchor && r === anchor.r && c === anchor.c);
        td.setAttribute('aria-selected', String(selected));
        if (b && i === b.bottom && end === b.right) bottomRightTd = td;
      });
      grid.querySelectorAll('thead tr:first-child th[data-col-header]').forEach(th => {
        const c = Number(th.dataset.colHeader);
        th.classList.toggle('cs-header-active', !!b && c >= b.left && c <= b.right);
      });
      grid.querySelectorAll('tbody th[data-row-header]').forEach(th => {
        const i = rowPositions.get(Number(th.dataset.rowHeader));
        th.classList.toggle('cs-header-active', !!b && i >= b.top && i <= b.bottom);
      });
      if (bottomRightTd && canEdit && !editor && !pending) {
        const handle = el('span', undefined, 'cs-fill-handle');
        handle.title = 'Drag to fill cells';
        handle.onpointerdown = e => {
          e.preventDefault(); e.stopPropagation();
          drag = true;
          const up = () => {
            document.removeEventListener('pointerup', up);
            drag = false;
            fillDownOrRight(b, bounds());
          };
          document.addEventListener('pointerup', up, { once: true });
        };
        bottomRightTd.append(handle);
      }
      updateAggregates();
      syncControls();
    }

    function fillDownOrRight(srcBounds, dstBounds) {
      if (!srcBounds || !dstBounds || !canEdit || pending) return;
      if (srcBounds.top === dstBounds.top && srcBounds.bottom === dstBounds.bottom && srcBounds.left === dstBounds.left && srcBounds.right === dstBounds.right) return;
      const edits = [];
      const srcH = srcBounds.bottom - srcBounds.top + 1, srcW = srcBounds.right - srcBounds.left + 1;
      for (let y = dstBounds.top; y <= dstBounds.bottom; y++) {
        for (let x = dstBounds.left; x <= dstBounds.right; x++) {
          if (y >= srcBounds.top && y <= srcBounds.bottom && x >= srcBounds.left && x <= srcBounds.right) continue;
          const srcY = srcBounds.top + ((y - dstBounds.top) % srcH);
          const srcX = srcBounds.left + ((x - dstBounds.left) % srcW);
          const srcCell = cell(viewRows[srcY], srcX);
          const r = viewRows[y], c = x;
          if (doc.merges.some(m => r >= m.row && r < m.row + m.height && c >= m.column && c < m.column + m.width)) continue;
          const copy = { ...srcCell }; delete copy.version; delete copy.changeId;
          copy.formula = srcCell.formula ? shiftFormula(srcCell.formula, r - viewRows[srcY], c - srcX) : '';
          edits.push(edit(r, c, copy));
        }
      }
      if (edits.length) save({ type: 'patch', edits }, () => grid.focus({ preventScroll: true }));
    }

    function historyCommand(kind) { if (editor || !canEdit) return; if (pending) { message('Wait for the current save before undoing or redoing.'); return; } const head = history[kind === 'undo' ? 'undoHead' : 'redoHead']; if (!head) return; save({ type: kind, expectedHead: head }, () => grid.focus({ preventScroll: true })); }
    function historyShortcut(e) { const mod = e.ctrlKey || e.metaKey, key = e.key.toLowerCase(); if (!mod || !(key === 'z' || key === 'y')) return false; if (editor || e.target.closest('input,textarea')) return false; e.preventDefault(); e.stopPropagation(); historyCommand(key === 'y' || e.shiftKey ? 'redo' : 'undo'); return true; }
    const historyGroup = group('History');
    const undoButton = button('↶', 'Undo my last change (Ctrl/Cmd+Z)', () => historyCommand('undo'), historyGroup, true, ICONS.undo);
    const redoButton = button('↷', 'Redo my last change (Ctrl+Y / Cmd+Shift+Z)', () => historyCommand('redo'), historyGroup, true, ICONS.redo);
    root.addEventListener('keydown', historyShortcut);
    const clipboardGroup = group('Clipboard'); button('Copy', 'Copy selected cells (Ctrl/Cmd+C)', () => copySelection(), clipboardGroup);
    button('Paste', 'Paste cells (Ctrl/Cmd+V)', async () => { try { await pasteMatrix(parseClipboard(await navigator.clipboard.readText())); } catch (e) { message('Use Ctrl/Cmd+V to paste from Excel. ' + e.message); } }, clipboardGroup, true);
    const fontGroup = group('Font'), fontSize = el('select'); fontSize.setAttribute('aria-label', 'Font size');[8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 36, 48, 72].forEach(n => { const o = el('option', String(n)); o.value = n; fontSize.append(o); }); fontSize.value = '11'; fontSize.onchange = () => formatCells({ fontSize: Number(fontSize.value) }); fontGroup.append(fontSize); controls.push(fontSize);
    const bold = button('B', 'Bold (Ctrl/Cmd+B)', () => formatCells({ bold: !activeCell().bold }), fontGroup, true); bold.classList.add('cs-bold');
    const italic = button('I', 'Italic (Ctrl/Cmd+I)', () => formatCells({ italic: !activeCell().italic }), fontGroup, true); italic.classList.add('cs-italic');
    const underline = button('U', 'Underline (Ctrl/Cmd+U)', () => formatCells({ underline: !activeCell().underline }), fontGroup, true); underline.classList.add('cs-underline');

    function colorPicker(label, field, value) {
      const holder = el('label', label, 'cs-color-label'), input = el('input');
      input.type = 'color'; input.value = value; input.title = label; input.setAttribute('aria-label', label);
      input.onchange = () => formatCells({ [field]: input.value });
      holder.append(input);
      const swatchBtn = button('▾', label + ' palette', e => {
        e.preventDefault(); e.stopPropagation(); closePopovers();
        const pop = el('div', undefined, 'cs-palette-popover');
        const gridEl = el('div', undefined, 'cs-palette-grid');
        PALETTE_SWATCHES.forEach(hex => {
          const sw = el('button', '', 'cs-palette-swatch');
          sw.type = 'button'; sw.title = hex; sw.style.backgroundColor = hex;
          sw.onclick = () => { closePopovers(); input.value = hex; formatCells({ [field]: hex }); };
          gridEl.append(sw);
        });
        const actions = el('div', undefined, 'cs-palette-actions');
        const noColor = el('button', '⊘ No color');
        noColor.type = 'button'; noColor.onclick = () => { closePopovers(); formatCells({ [field]: '' }); };
        actions.append(noColor);
        pop.append(gridEl, actions);
        const rect = holder.getBoundingClientRect();
        pop.style.left = Math.min(window.innerWidth - 205, rect.left) + 'px';
        pop.style.top = (rect.bottom + 4) + 'px';
        document.body.append(pop);
        openMenu = pop;
      }, fontGroup, true);
      swatchBtn.style.padding = '2px 4px'; swatchBtn.style.fontSize = '10px';
      fontGroup.append(holder); controls.push(input);
      return input;
    }
    const fill = colorPicker('Fill', 'background', '#fff2cc'), ink = colorPicker('Text', 'foreground', '#172b4d');

    const alignGroup = group('Alignment'); const aligns = {};
    for (const [name, icon] of [['left', '≡'], ['center', '≡'], ['right', '≡']]) {
      const b = button(icon, 'Align ' + name, () => formatCells({ align: name }), alignGroup, true, ICONS[name]);
      b.classList.add('cs-align-' + name); aligns[name] = b;
    }
    const wrap = button('↵ Wrap', 'Wrap text', () => formatCells({ wrap: !activeCell().wrap }), alignGroup, true);
    const numFormatSelect = el('select'); numFormatSelect.setAttribute('aria-label', 'Number format'); numFormatSelect.title = 'Number format';
    [['General', 'Auto'], ['#,##0', '1,234'], ['$#,##0.00', '$1,234.00'], ['0%', '100%'], ['@', 'Text']].forEach(([val, txt]) => { const o = el('option', txt); o.value = val; numFormatSelect.append(o); });
    numFormatSelect.onchange = () => formatCells({ format: numFormatSelect.value === 'General' ? '' : numFormatSelect.value });
    alignGroup.append(numFormatSelect); controls.push(numFormatSelect);

    const cellsGroup = group('Cells');
    button('+ Row', 'Append row (clears Undo/Redo history)', () => doc && save({ type: 'addRow', expectedRevision: doc.revision }), cellsGroup, true);
    button('+ Column', 'Append column (clears Undo/Redo history)', () => doc && save({ type: 'addColumn', expectedRevision: doc.revision }), cellsGroup, true);
    button('− Row', 'Delete selected row', () => doc && anchor && doc.rows.length > 1 && save({ type: 'deleteRow', id: doc.rows[anchor.r], expectedRevision: doc.revision }), cellsGroup, true);
    button('− Col', 'Delete selected column', () => doc && anchor && doc.columns.length > 1 && save({ type: 'deleteColumn', id: doc.columns[anchor.c], expectedRevision: doc.revision }), cellsGroup, true);
    const width = el('input'), height = el('input');
    for (const [n, label, min, max] of [[width, 'Column width', 40, 800], [height, 'Row height', 20, 400]]) {
      n.type = 'number'; n.min = min; n.max = max; n.setAttribute('aria-label', label); n.title = label + ' (px)';
      const l = el('label', label === 'Column width' ? 'W' : 'H', 'cs-dimension'); l.append(n); cellsGroup.append(l); controls.push(n);
    }
    width.onchange = () => resize('column', anchor?.c, Number(width.value)); height.onchange = () => resize('row', anchor?.r, Number(height.value));
    for (const n of [width, height]) n.onkeydown = e => { if (e.key === 'Enter') { e.preventDefault(); n.onchange(); } };

    const dataGroup = group('Data');
    const filterButton = button('Filter', 'Show column filters', () => { root.classList.toggle('cs-show-filters'); filterButton.setAttribute('aria-pressed', String(root.classList.contains('cs-show-filters'))); }, dataGroup);
    button('A↓Z', 'Sort selected column ascending', () => setSort(1), dataGroup);
    button('Z↓A', 'Sort selected column descending', () => setSort(-1), dataGroup);
    button('Clear', 'Clear sorting and filters', () => { filters = {}; sort = null; page = 0; render(); }, dataGroup);

    const fileGroup = group('Workbook'), input = el('input'); input.type = 'file'; input.accept = '.xlsx,.csv'; input.hidden = true; root.append(input);
    button('Import', 'Import Excel / CSV', () => input.click(), fileGroup, true);
    for (const format of ['xlsx', 'csv']) button(format.toUpperCase(), 'Export ' + format.toUpperCase(), () => {
      if (!doc || pending || editor) { message('Save pending changes before exporting.'); return; }
      if (format === 'csv' && !window.confirm('CSV contains values only, without formatting or merged cells. Continue?')) return;
      const a = el('a'); a.href = base + '/export?format=' + format; a.download = ''; a.click();
    }, fileGroup);

    input.onchange = async () => {
      if (!doc || !canEdit || pending || editor || !input.files[0]) return;
      const file = input.files[0], revision = doc.revision; input.value = '';
      if (file.size > 10000000) { message('Maximum upload size: 10 MB'); return; }
      show('Preparing import…');
      try {
        const result = await fetchJson(base + '/preview?format=' + (file.name.toLowerCase().endsWith('.csv') ? 'csv' : 'xlsx'), { method: 'POST', headers: { 'Content-Type': 'application/octet-stream' }, body: file });
        notice.replaceChildren(el('strong', 'Import: ' + file.name));
        const choose = el('select');
        result.sheets.forEach((s, i) => { const o = el('option', s.name + ' · ' + s.document.rows.length + ' × ' + s.document.columns.length); o.value = i; choose.append(o); });
        notice.append(choose);
        result.warnings.forEach(w => notice.append(el('p', w)));
        notice.append(el('p', 'Replaces this sheet and clears your Undo/Redo stack. Previous data remains in revision history.'));
        button('Replace sheet', 'Replace sheet with selected worksheet', () => save({ type: 'replace', expectedRevision: revision, document: result.sheets[Number(choose.value)].document }), notice);
        button('Cancel', 'Cancel import', () => notice.replaceChildren(), notice);
      } catch (e) { show(e.message, true); }
    };

    const address = el('span', 'A1', 'cs-address'), fx = el('span', 'fx', 'cs-fx'), formulaInput = el('textarea');
    formulaInput.rows = 1; formulaInput.setAttribute('aria-label', 'Cell value');
    formulaInput.placeholder = 'Select a cell and type · Use =SUM(A1:A5) for formulas · Alt+Enter for a new line';
    formula.append(address, fx, formulaInput);

    function activeCell() { if (!doc || !anchor) return {}; const p = mergedAnchor(anchor.r, anchor.c); return cell(p.r, p.c); }
    function syncControls() {
      controls.forEach(n => n.disabled = !canEdit || !doc || !!pending || !!editor);
      undoButton.disabled = !canEdit || !!pending || !!editor || !history.undoHead;
      redoButton.disabled = !canEdit || !!pending || !!editor || !history.redoHead;
      formulaInput.disabled = !canEdit || !doc || !anchor || !!pending;
      const v = activeCell();
      bold.setAttribute('aria-pressed', String(!!v.bold));
      italic.setAttribute('aria-pressed', String(!!v.italic));
      underline.setAttribute('aria-pressed', String(!!v.underline));
      wrap.setAttribute('aria-pressed', String(!!v.wrap));
      for (const a in aligns) aligns[a].setAttribute('aria-pressed', String((v.align || (v.type === 'number' ? 'right' : 'left')) === a));
      fontSize.value = String(v.fontSize || 11);
      fill.value = v.background || '#ffffff';
      ink.value = v.foreground || '#172b4d';
      numFormatSelect.value = ['#,##0', '$#,##0.00', '0%', '@'].includes(v.format) ? v.format : 'General';
      if (anchor && doc) {
        const b = bounds();
        address.textContent = letters(anchor.c) + (anchor.r + 1) + (b && (b.top !== b.bottom || b.left !== b.right) ? ':' + letters(extent.c) + (extent.r + 1) : '');
        width.value = doc.columnWidths?.[doc.columns[anchor.c]] || 104;
        height.value = doc.rowHeights?.[doc.rows[anchor.r]] || 24;
      }
      if (!editor) formulaInput.value = v.formula || v.value || '';
    }
    function setSort(direction) { if (!doc || !anchor) return; if (doc.merges.length) { message('Sorting and filtering are unavailable on sheets with merged cells.'); return; } sort = { c: anchor.c, direction }; page = 0; render(); }
    function resize(axis, index, size) {
      if (index === undefined || !Number.isInteger(size)) return;
      const min = axis === 'column' ? 40 : 20, max = axis === 'column' ? 800 : 400;
      if (size < min || size > max) { message('Size must be ' + min + '–' + max + ' px.'); syncControls(); return; }
      save({ type: 'resize', axis, id: doc[axis === 'column' ? 'columns' : 'rows'][index], size, expectedRevision: doc.revision });
    }
    function autoFitColumn(c) {
      if (!doc || !canEdit || pending || editor) return;
      let maxChars = 4;
      for (let r = 0; r < doc.rows.length; r++) {
        const txt = String(computedValue(r, c) || '');
        for (const line of txt.split('\n')) if (line.length > maxChars) maxChars = line.length;
      }
      const next = Math.max(60, Math.min(500, Math.round(maxChars * 8 + 24)));
      resize('column', c, next);
    }
    function resizeHandle(th, axis, index, size) {
      const handle = el('span', undefined, 'cs-resize cs-resize-' + axis);
      handle.title = 'Drag to resize ' + axis + (axis === 'column' ? ' · Double-click to auto-fit' : '');
      handle.setAttribute('aria-label', 'Resize ' + axis + ' ' + (axis === 'column' ? letters(index) : index + 1));
      th.append(handle);
      if (axis === 'column') handle.ondblclick = e => { e.preventDefault(); e.stopPropagation(); autoFitColumn(index); };
      handle.onpointerdown = e => {
        if (!canEdit || pending || editor) return;
        e.preventDefault(); e.stopPropagation(); drag = true;
        const start = axis === 'column' ? e.clientX : e.clientY, table = grid.querySelector('table'), total = parseFloat(table.style.width);
        let next = size;
        const move = ev => {
          next = Math.max(axis === 'column' ? 40 : 20, Math.min(axis === 'column' ? 800 : 400, size + (axis === 'column' ? ev.clientX : ev.clientY) - start));
          show((axis === 'column' ? 'Width: ' : 'Height: ') + Math.round(next) + ' px');
          if (axis === 'column') { grid.querySelector('col[data-column="' + index + '"]').style.width = next + 'px'; table.style.width = (total + next - size) + 'px'; }
          else th.parentNode.style.height = next + 'px';
        };
        const up = () => { document.removeEventListener('pointermove', move); document.removeEventListener('pointerup', up); drag = false; resize(axis, index, Math.round(next)); };
        document.addEventListener('pointermove', move); document.addEventListener('pointerup', up, { once: true });
      };
    }
    function moveSelection(dy, dx, extend = false) {
      if (!anchor || !doc) return;
      const from = extend ? extent : anchor, i = Math.max(0, Math.min(viewRows.length - 1, viewRows.indexOf(from.r) + dy)), c = Math.max(0, Math.min(doc.columns.length - 1, from.c + dx));
      const nextPage = Math.floor(i / 100); if (nextPage !== page) { page = nextPage; render(); }
      select(viewRows[i], c, extend); grid.focus({ preventScroll: true });
      grid.querySelector('td[data-row="' + viewRows[i] + '"][data-col="' + c + '"]')?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    }
    function beginEdit(initial, useFormula = false) {
      if (!canEdit || editor || !anchor || queue.error || queue.items.length >= 1000) return;
      if (pending && pending.op.type !== 'patch') return;
      const p = mergedAnchor(anchor.r, anchor.c), old = cell(p.r, p.c), initialText = old.formula || old.value || '';
      let box = formulaInput;
      if (!useFormula) {
        const td = grid.querySelector('td[data-row="' + anchor.r + '"][data-col="' + anchor.c + '"]');
        if (!td) { formulaInput.focus(); return; }
        box = el('textarea', undefined, 'cs-cell-editor');
        box.setAttribute('aria-label', 'Edit ' + letters(anchor.c) + (anchor.r + 1));
        box.value = initial === undefined ? initialText : initial;
        td.replaceChildren(box); td.classList.add('cs-editing');
      }
      editor = { box, p, old, rowId: doc.rows[p.r], columnId: doc.columns[p.c] }; if (useFormula) box.value = initialText;
      syncControls(); box.focus(); box.selectionStart = box.selectionEnd = box.value.length;
      box.onkeydown = e => {
        e.stopPropagation();
        if (e.key === 'Enter' && e.altKey) { e.preventDefault(); box.setRangeText('\n', box.selectionStart, box.selectionEnd, 'end'); return; }
        if (e.key === 'Escape') { e.preventDefault(); finishEdit(true); return; }
        if ((e.key === 'Enter' && !e.shiftKey) || e.key === 'Tab') {
          e.preventDefault();
          const dy = e.key === 'Enter' ? 1 : 0, dx = e.key === 'Tab' ? (e.shiftKey ? -1 : 1) : 0;
          finishEdit(false, () => moveSelection(dy, dx));
        }
      };
      box.onblur = () => finishEdit(false);
    }
    function finishEdit(cancel, done) {
      if (!editor) return;
      const { box, p, old, rowId, columnId } = editor, value = box.value;
      const oldRaw = old.formula || old.value || '';
      editor = null; box.onblur = null; box.onkeydown = null;
      if (!cancel && value !== oldRaw) {
        const nextCell = inferCellData(value, old);
        save({ type: 'patch', edits: [{ row: rowId, column: columnId, expectedVersion: old.version || 0, cell: nextCell }] }, done || (() => grid.focus({ preventScroll: true })));
      } else {
        render(); if (done) done(); else grid.focus({ preventScroll: true });
      }
    }
    formulaInput.onfocus = () => { if (!editor) beginEdit(undefined, true); };

    function styleCell(td, v) {
      td.style.backgroundColor = v.background || '';
      td.style.color = v.foreground || '';
      td.style.fontWeight = v.bold ? '700' : '400';
      td.style.fontStyle = v.italic ? 'italic' : 'normal';
      td.style.textDecoration = v.underline ? 'underline' : 'none';
      td.style.fontSize = (v.fontSize || 11) + 'pt';
      td.style.textAlign = v.align || (v.type === 'number' ? 'right' : 'left');
      td.classList.toggle('cs-wrap', !!v.wrap || String(v.value).includes('\n'));
    }
    function rowHeight(r) {
      if (doc.rowHeights?.[doc.rows[r]]) return doc.rowHeights[doc.rows[r]];
      let h = 24;
      for (let c = 0; c < doc.columns.length; c++) { const v = cell(r, c); h = Math.max(h, Math.min(160, String(v.value).split('\n').length * (v.fontSize || 11) * 1.55 + 4)); }
      return Math.round(h);
    }

    function openContextMenu(e, r, c) {
      e.preventDefault(); closePopovers();
      if (r !== undefined && c !== undefined) select(r, c, false);
      const menu = el('div', undefined, 'cs-context-menu');
      const item = (label, shortcut, action, disabled = false) => {
        const b = el('button'); b.type = 'button'; b.disabled = disabled;
        b.append(el('span', label), el('span', shortcut || ''));
        b.onclick = () => { closePopovers(); action(); };
        menu.append(b);
      };
      item('Copy', 'Ctrl+C', () => copySelection());
      item('Paste', 'Ctrl+V', async () => { try { await pasteMatrix(parseClipboard(await navigator.clipboard.readText())); } catch (err) { message(err.message); } }, !canEdit || !!pending);
      menu.append(el('hr'));
      item('Insert row above', '', () => anchor && save({ type: 'insertRow', index: anchor.r, expectedRevision: doc.revision }), !canEdit || !!pending || !anchor);
      item('Insert row below', '', () => anchor && save({ type: 'insertRow', index: anchor.r + 1, expectedRevision: doc.revision }), !canEdit || !!pending || !anchor);
      item('Delete row', '', () => anchor && doc.rows.length > 1 && save({ type: 'deleteRow', id: doc.rows[anchor.r], expectedRevision: doc.revision }), !canEdit || !!pending || !anchor || doc.rows.length <= 1);
      menu.append(el('hr'));
      item('Insert column left', '', () => anchor && save({ type: 'insertColumn', index: anchor.c, expectedRevision: doc.revision }), !canEdit || !!pending || !anchor);
      item('Insert column right', '', () => anchor && save({ type: 'insertColumn', index: anchor.c + 1, expectedRevision: doc.revision }), !canEdit || !!pending || !anchor);
      item('Delete column', '', () => anchor && doc.columns.length > 1 && save({ type: 'deleteColumn', id: doc.columns[anchor.c], expectedRevision: doc.revision }), !canEdit || !!pending || !anchor || doc.columns.length <= 1);
      menu.append(el('hr'));
      item('Clear formatting', '', () => formatCells({ bold: false, italic: false, underline: false, background: '', foreground: '', align: '', format: '' }), !canEdit || !!pending || !anchor);
      menu.style.left = Math.min(window.innerWidth - 205, e.clientX) + 'px';
      menu.style.top = Math.min(window.innerHeight - 280, e.clientY) + 'px';
      document.body.append(menu);
      openMenu = menu;
    }

    function render(focusedFilterCol = null, filterCursorPos = null) {
      const focused = document.activeElement === grid;
      grid.replaceChildren(); footer.replaceChildren();
      if (!doc) {
        const emptyBox = el('div', undefined, 'cs-empty-state');
        emptyBox.append(el('p', 'This sheet has no data yet.'));
        if (canEdit) button('Create sheet', 'Create sheet', () => save({ type: 'create' }), emptyBox);
        grid.append(emptyBox); syncControls(); return;
      }
      if (doc.merges.length) { sort = null; filters = {}; }
      viewRows = doc.rows.map((_, i) => i).filter(r => Object.entries(filters).every(([c, v]) => String(computedValue(r, Number(c))).toLocaleLowerCase().includes(v.toLocaleLowerCase())));
      if (sort) {
        viewRows.sort((a, b) => {
          const va = computedValue(a, sort.c), vb = computedValue(b, sort.c);
          const na = Number(va), nb = Number(vb);
          if (va !== '' && vb !== '' && Number.isFinite(na) && Number.isFinite(nb)) return (na - nb) * sort.direction;
          return String(va).localeCompare(String(vb), undefined, { numeric: true }) * sort.direction;
        });
      }
      rowPositions = new Map(viewRows.map((r, i) => [r, i]));
      if (extent && !rowPositions.has(extent.r)) extent = anchor ? { ...anchor } : null;
      if (!anchor || viewRows.indexOf(anchor.r) < 0 || anchor.c >= doc.columns.length) { anchor = viewRows.length ? { r: viewRows[0], c: 0 } : null; extent = anchor ? { ...anchor } : null; }
      page = Math.max(0, Math.min(page, Math.ceil(viewRows.length / 100) - 1));
      pageRows = viewRows.slice(page * 100, page * 100 + 100);
      const table = el('table'); table.setAttribute('role', 'grid'); table.setAttribute('aria-label', 'Spreadsheet'); table.setAttribute('aria-rowcount', doc.rows.length); table.setAttribute('aria-colcount', doc.columns.length);
      const cg = el('colgroup'); const cornerCol = el('col'); cornerCol.style.width = '44px'; cg.append(cornerCol);
      let totalWidth = 44;
      doc.columns.forEach((id, c) => { const col = el('col'), w = doc.columnWidths?.[id] || 104; col.style.width = w + 'px'; col.dataset.column = c; totalWidth += w; cg.append(col); });
      table.style.width = totalWidth + 'px'; table.append(cg);
      const head = el('thead'), headers = el('tr'), corner = el('th', '◩');
      corner.onclick = () => { if (viewRows.length) { anchor = { r: viewRows[0], c: 0 }; extent = { r: viewRows[viewRows.length - 1], c: doc.columns.length - 1 }; paintSelection(); grid.focus({ preventScroll: true }); } };
      headers.append(corner);
      doc.columns.forEach((id, c) => {
        const th = el('th', letters(c) + (sort?.c === c ? (sort.direction === 1 ? ' ▴' : ' ▾') : ''));
        th.scope = 'col'; th.dataset.colHeader = c;
        th.onclick = e => { if (e.target.closest('.cs-resize') || !viewRows.length) return; anchor = { r: viewRows[0], c }; extent = { r: viewRows[viewRows.length - 1], c }; paintSelection(); grid.focus({ preventScroll: true }); };
        th.oncontextmenu = e => openContextMenu(e, anchor ? anchor.r : 0, c);
        resizeHandle(th, 'column', c, doc.columnWidths?.[id] || 104); headers.append(th);
      });
      head.append(headers);
      const filterRow = el('tr', undefined, 'cs-filters'); filterRow.append(el('th'));
      doc.columns.forEach((id, c) => {
        const th = el('th'), f = el('input');
        f.placeholder = 'Filter'; f.setAttribute('aria-label', 'Filter column ' + letters(c)); f.dataset.filterCol = c;
        f.value = filters[c] || ''; f.disabled = !!doc.merges.length;
        const applyFilter = () => { filters[c] = f.value; page = 0; render(c, f.selectionStart); };
        f.oninput = applyFilter; f.onchange = applyFilter;
        th.append(f); filterRow.append(th);
      });
      head.append(filterRow); table.append(head);
      const body = el('tbody'), covered = new Set(), merges = new Map();
      if (doc.merges.length && pageRows.length) for (const m of doc.merges) {
        const first = Math.max(m.row, pageRows[0]), last = Math.min(m.row + m.height, pageRows[pageRows.length - 1] + 1);
        if (first >= last) continue;
        merges.set(first + ':' + m.column, { ...m, visibleHeight: last - first });
        for (let r = first; r < last; r++) for (let c = m.column; c < m.column + m.width; c++) if (r !== first || c !== m.column) covered.add(r + ':' + c);
      }
      pageRows.forEach(r => {
        const rh = rowHeight(r), tr = el('tr'); tr.style.height = rh + 'px';
        const rowHead = el('th', String(r + 1)); rowHead.scope = 'row'; rowHead.dataset.rowHeader = r;
        rowHead.onclick = e => { if (e.target.closest('.cs-resize')) return; anchor = { r, c: 0 }; extent = { r, c: doc.columns.length - 1 }; paintSelection(); grid.focus({ preventScroll: true }); };
        rowHead.oncontextmenu = e => openContextMenu(e, r, anchor ? anchor.c : 0);
        resizeHandle(rowHead, 'row', r, rh); tr.append(rowHead);
        doc.columns.forEach((id, c) => {
          if (covered.has(r + ':' + c)) return;
          const m = merges.get(r + ':' + c), targetRow = m ? m.row : r, v = cell(targetRow, c), td = el('td');
          td.dataset.row = r; td.dataset.col = c; td.setAttribute('role', 'gridcell');
          const rawDisplay = computedValue(targetRow, c);
          const displayStr = formatDisplayValue(rawDisplay, v.format, v.type);
          const text = el('div', displayStr, 'cs-cell-text'); text.style.maxHeight = (m ? 400 : rh - 4) + 'px'; td.append(text);
          if (v.formula) td.title = v.formula;
          if (m) { td.rowSpan = m.visibleHeight; td.colSpan = m.width; if (m.row < pageRows[0]) td.title = 'Merged cell continues from row ' + (m.row + 1); }
          styleCell(td, v);
          td.onpointerdown = e => { if (e.button !== 0 || editor || e.target.classList.contains('cs-fill-handle')) return; closePopovers(); e.preventDefault(); select(r, c, e.shiftKey); drag = true; grid.focus({ preventScroll: true }); };
          td.onpointerenter = () => { if (drag && !editor) { extent = { r, c }; paintSelection(); } };
          td.ondblclick = () => beginEdit();
          td.oncontextmenu = e => openContextMenu(e, r, c);
          tr.append(td);
        });
        body.append(tr);
      });
      table.append(body); grid.append(table);
      footer.append(el('span', '▦ ' + (root.dataset.sheet || 'Sheet'), 'cs-sheet-tab'));
      const info = el('span', viewRows.length.toLocaleString() + ' rows · ' + doc.columns.length + ' columns', 'cs-grid-info');
      footer.append(info, aggDisplay);
      button('‹', 'Previous rows', () => { if (page) { page--; render(); } }, footer);
      footer.append(el('span', (page + 1) + ' / ' + Math.max(1, Math.ceil(viewRows.length / 100))));
      button('›', 'Next rows', () => { if ((page + 1) * 100 < viewRows.length) { page++; render(); } }, footer);
      const hint = el('span', 'Click a cell, then type · Alt+Enter: new line · Right-click: row/col menu', 'cs-key-hint');
      footer.append(hint); paintSelection();
      if (focusedFilterCol !== null) {
        const fi = grid.querySelector('input[data-filter-col="' + focusedFilterCol + '"]');
        if (fi) { fi.focus(); if (filterCursorPos !== null) fi.selectionStart = fi.selectionEnd = filterCursorPos; }
      } else if (focused) grid.focus({ preventScroll: true });
    }
    function selectionData() {
      const b = bounds(); if (!b || b.top < 0) return null;
      if ((b.bottom - b.top + 1) * (b.right - b.left + 1) > 10000) throw new Error('Copy up to 10,000 cells at a time');
      const rows = [], table = el('table');
      for (let i = b.top; i <= b.bottom; i++) {
        const values = [], tr = el('tr');
        for (let c = b.left; c <= b.right; c++) {
          const v = cell(viewRows[i], c), disp = computedValue(viewRows[i], c), td = el('td', disp);
          styleCell(td, v); td.style.whiteSpace = 'pre-wrap'; tr.append(td); values.push(quote(String(disp)));
        }
        table.append(tr); rows.push(values.join('\t'));
      }
      return { text: rows.join('\r\n'), html: table.outerHTML };
    }
    async function copySelection(event) {
      if (editor || (event && event.target !== grid)) return;
      try {
        const data = selectionData(); if (!data) return;
        if (event) { event.preventDefault(); event.clipboardData.setData('text/plain', data.text); event.clipboardData.setData('text/html', data.html); }
        else if (navigator.clipboard?.write && window.ClipboardItem) await navigator.clipboard.write([new ClipboardItem({ 'text/plain': new Blob([data.text], { type: 'text/plain' }), 'text/html': new Blob([data.html], { type: 'text/html' }) })]);
        else await navigator.clipboard.writeText(data.text);
        show('Copied selection');
      } catch (e) { message(e.message + ' · Select the grid and use Ctrl/Cmd+C.'); }
    }
    function clipboardStyles(parsed, rows) {
      const table = parsed.querySelector('table'); if (!table) return null; const trs = Array.from(table.rows); if (trs.length !== rows.length) return null;
      const color = v => { if (/^#[0-9a-f]{6}$/i.test(v)) return v; const m = v.match(/^rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)$/); return m ? '#' + m.slice(1).map(n => Number(n).toString(16).padStart(2, '0')).join('') : null; };
      return trs.map(tr => Array.from(tr.cells).map(td => {
        const s = td.style, v = {};
        if (s.fontWeight) v.bold = s.fontWeight === 'bold' || Number(s.fontWeight) >= 600;
        if (s.fontStyle) v.italic = s.fontStyle === 'italic';
        if (s.textDecoration) v.underline = s.textDecoration.includes('underline');
        const size = parseFloat(s.fontSize) * (s.fontSize.endsWith('px') ? 0.75 : 1);
        if (size >= 8 && size <= 72) v.fontSize = size;
        if (['left', 'center', 'right'].includes(s.textAlign)) v.align = s.textAlign;
        for (const [key, css] of [['background', 'backgroundColor'], ['foreground', 'color']]) { const c = color(s[css]); if (c) v[key] = c; }
        return v;
      }));
    }
    async function pasteMatrix(rows, styles) {
      if (!canEdit || pending || editor || !anchor) return;
      const b = bounds(), start = b.top, first = b.left, cols = Math.max(...rows.map(r => r.length));
      if (start + rows.length > viewRows.length || first + cols > doc.columns.length) { message('Paste exceeds this sheet. Add rows or columns first. Nothing was changed.'); return; }
      if (rows.length * cols > 10000) { message('Paste up to 10,000 cells at a time. Nothing was changed.'); return; }
      const edits = [];
      for (let y = 0; y < rows.length; y++) for (let x = 0; x < cols; x++) {
        const r = viewRows[start + y], c = first + x;
        if (doc.merges.some(m => r >= m.row && r < m.row + m.height && c >= m.column && c < m.column + m.width)) { message('Paste overlaps merged cells. Paste into an unmerged area. Nothing was changed.'); return; }
        const rawVal = rows[y][x] ?? '';
        const inferred = inferCellData(rawVal, { ...cell(r, c), ...(styles?.[y]?.[x] || {}) });
        edits.push(edit(r, c, inferred));
      }
      save({ type: 'patch', edits }, () => { anchor = { r: viewRows[start], c: first }; extent = { r: viewRows[start + rows.length - 1], c: first + cols - 1 }; paintSelection(); grid.focus({ preventScroll: true }); });
    }
    grid.addEventListener('copy', copySelection);
    grid.addEventListener('paste', e => {
      if (editor || e.target !== grid) return; e.preventDefault();
      try {
        const html = e.clipboardData.getData('text/html'), rows = parseClipboard(e.clipboardData.getData('text/plain'));
        let styles = null;
        if (html) {
          const parsed = new DOMParser().parseFromString(html, 'text/html');
          if (parsed.querySelector('td[rowspan]:not([rowspan="1"]),td[colspan]:not([colspan="1"])')) { message('Clipboard contains merged cells. Use Excel import to preserve merged ranges.'); return; }
          styles = clipboardStyles(parsed, rows);
        }
        pasteMatrix(rows, styles);
      } catch (error) { message(error.message); }
    });
    grid.addEventListener('keydown', e => {
      if (editor || e.target !== grid) return;
      if (e.key === 'Escape' && root.classList.contains('cs-fullscreen')) { root.classList.remove('cs-fullscreen'); return; }
      if (historyShortcut(e)) return; e.stopPropagation();
      const mod = e.ctrlKey || e.metaKey;
      if (mod && ['b', 'i', 'u'].includes(e.key.toLowerCase())) { e.preventDefault(); const field = { b: 'bold', i: 'italic', u: 'underline' }[e.key.toLowerCase()]; formatCells({ [field]: !activeCell()[field] }); return; }
      if (mod && e.key.toLowerCase() === 'a') { e.preventDefault(); if (viewRows.length) { anchor = { r: viewRows[0], c: 0 }; extent = { r: viewRows[viewRows.length - 1], c: doc.columns.length - 1 }; paintSelection(); } return; }
      if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'Tab', 'Enter'].includes(e.key)) {
        e.preventDefault();
        moveSelection(e.key === 'ArrowUp' ? -1 : (e.key === 'ArrowDown' || e.key === 'Enter') ? 1 : 0, e.key === 'ArrowLeft' ? -1 : e.key === 'ArrowRight' ? 1 : e.key === 'Tab' ? (e.shiftKey ? -1 : 1) : 0, e.shiftKey && e.key !== 'Tab');
      } else if (e.key === 'F2') { e.preventDefault(); beginEdit(); }
      else if (e.key === 'Delete' || e.key === 'Backspace') { e.preventDefault(); formatCells({ value: '', type: 'text', formula: '' }); }
      else if (e.key.length === 1 && !mod && !e.altKey) { e.preventDefault(); beginEdit(e.key); }
    });
    const pointerUp = () => { drag = false; };
    const docClick = e => { if (openMenu && !openMenu.contains(e.target)) closePopovers(); };
    document.addEventListener('pointerup', pointerUp);
    document.addEventListener('pointerdown', docClick);
    const unload = e => { if (pending || editor) { e.preventDefault(); e.returnValue = ''; } };
    window.addEventListener('beforeunload', unload);
    refresh(true);
    const timer = setInterval(() => {
      if (!root.isConnected) { clearInterval(timer); document.removeEventListener('pointerup', pointerUp); document.removeEventListener('pointerdown', docClick); window.removeEventListener('beforeunload', unload); }
      else refresh();
    }, 5000);
  }
  function init() { document.querySelectorAll('.cs-sheet').forEach(mount); }
  if (window.AJS) AJS.toInit(init); else if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
