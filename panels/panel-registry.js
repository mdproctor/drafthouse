// panels/panel-registry.js
// PanelRegistry — component type catalogue + factory.
// First draft of what @casehub/ui's component type registry will be.

export class PanelRegistry {
  #panels = new Map();

  register(metadata) {
    if (!metadata.type || !metadata.component) {
      throw new Error('PanelRegistry.register() requires type and component');
    }
    if (this.#panels.has(metadata.type)) {
      throw new Error(`Panel type '${metadata.type}' already registered`);
    }
    customElements.define(metadata.type, metadata.component);
    this.#panels.set(metadata.type, {
      type: metadata.type,
      label: metadata.label || metadata.type,
      icon: metadata.icon || '',
      propsSchema: metadata.propsSchema || {},
    });
  }

  get(type) {
    const meta = this.#panels.get(type);
    if (!meta) throw new Error(`Unknown panel type: '${type}'`);
    return meta;
  }

  create(type, props) {
    if (!this.#panels.has(type)) {
      throw new Error(`Unknown panel type: '${type}'`);
    }
    const el = document.createElement(type);
    if (props) el.configure(props);
    return el;
  }

  types() {
    return [...this.#panels.keys()];
  }
}

export const registry = new PanelRegistry();
