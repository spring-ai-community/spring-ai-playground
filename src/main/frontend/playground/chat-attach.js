import { optimize, extractExif } from './chat-image-attach.js';

const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const MAX_DOCUMENT_BYTES = 20 * 1024 * 1024;
const DOCUMENT_EXTENSIONS = ['pdf', 'txt', 'md', 'markdown', 'html', 'htm', 'docx', 'pptx'];

class ChatAttach extends HTMLElement {
  connectedCallback() {
    if (this._input) return;
    this._input = document.createElement('input');
    this._input.type = 'file';
    const accept = this.getAttribute('accept-types');
    if (accept) this._input.accept = accept;
    this._input.multiple = true;
    this._input.hidden = true;
    this.appendChild(this._input);
    this._input.addEventListener('change', () => {
      const files = Array.from(this._input.files || []);
      this._input.value = '';
      files.forEach((file) => this.process(file));
    });
  }

  openPicker() {
    if (this._input) this._input.click();
  }

  bindTo(target) {
    if (!target || target.__chatAttachBound) return;
    target.__chatAttachBound = true;
    const locked = () => target.readonly || target.disabled;
    target.addEventListener('dragover', (e) => {
      if (!Array.from(e.dataTransfer?.types || []).includes('Files')) return;
      e.preventDefault();
      if (locked() && e.dataTransfer) e.dataTransfer.dropEffect = 'none';
    });
    target.addEventListener('drop', (e) => {
      if (locked()) {
        e.preventDefault();
        return;
      }
      const files = Array.from(e.dataTransfer?.files || []);
      if (!files.length) return;
      e.preventDefault();
      files.forEach((file) => this.process(file));
    });
    target.addEventListener('paste', (e) => {
      if (locked()) return;
      const files = Array.from(e.clipboardData?.items || [])
        .filter((item) => item.kind === 'file')
        .map((item) => item.getAsFile())
        .filter(Boolean);
      if (!files.length) return;
      e.preventDefault();
      files.forEach((file) => this.process(file));
    });
  }

  async process(file) {
    if (!file) return;
    if (file.type && file.type.startsWith('image/')) {
      await this.processImage(file);
      return;
    }
    await this.processDocument(file);
  }

  async processImage(file) {
    if (file.size > MAX_IMAGE_BYTES) {
      this.$server.attachFailed(`"${file.name}" is too large (max ${Math.round(MAX_IMAGE_BYTES / 1024 / 1024)}MB).`);
      return;
    }
    this.$server.imageAttachStarted();
    try {
      const exif = await extractExif(file);
      const { base64, mimeType } = await optimize(file);
      this.$server.receiveImage(file.name, base64, mimeType, exif);
    } catch (err) {
      this.$server.attachFailed((err && err.message) ? err.message : String(err));
    }
  }

  async processDocument(file) {
    const extension = (file.name.split('.').pop() || '').toLowerCase();
    if (!DOCUMENT_EXTENSIONS.includes(extension)) {
      this.$server.attachFailed(`"${file.name}" is not a supported file type.`);
      return;
    }
    if (file.size > MAX_DOCUMENT_BYTES) {
      this.$server.attachFailed(
        `"${file.name}" is too large (max ${Math.round(MAX_DOCUMENT_BYTES / 1024 / 1024)}MB).`);
      return;
    }
    try {
      const buffer = await file.arrayBuffer();
      this.$server.receiveDocument(file.name, toBase64(buffer), file.type || '');
    } catch (err) {
      this.$server.attachFailed((err && err.message) ? err.message : String(err));
    }
  }
}

function toBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary);
}

if (!customElements.get('chat-attach')) {
  customElements.define('chat-attach', ChatAttach);
}
