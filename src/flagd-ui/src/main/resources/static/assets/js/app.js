// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

// Client behavior of the flagd UI. It replaces what Phoenix LiveView did over its websocket:
// the pages are rendered by the server and these small fetch() calls save changes and refresh the
// parts of a page that other sessions or timers can change.
(() => {
  "use strict";

  const rootMeta = document.querySelector("meta[name='root-path']");
  const rootPath = (rootMeta ? rootMeta.getAttribute("content") : "") || "";
  const base = rootPath.replace(/\/+$/, "");

  const url = (path) => `${base}${path}`;

  const request = async (method, path, body) => {
    const response = await fetch(url(path), {
      method,
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (!response.ok) {
      throw new Error(`${method} ${path} failed with ${response.status}`);
    }
    return response;
  };

  // ---------------------------------------------------------------------------------------------
  // Flash messages: toasts that stay until they are clicked or replaced, like LiveView flashes.

  const FLASH_ICONS = {
    info: "hero-information-circle-mini",
    error: "hero-exclamation-circle-mini",
  };

  const FLASH_CLASSES = {
    info: "alert-info",
    error: "alert-error",
  };

  const clearFlashKind = (kind) => {
    const existing = document.getElementById(`flash-${kind}`);
    if (existing) existing.remove();
  };

  const clearFlash = () => {
    clearFlashKind("info");
    clearFlashKind("error");
  };

  const showFlash = (kind, message) => {
    clearFlashKind(kind);
    const toast = document.createElement("div");
    toast.id = `flash-${kind}`;
    toast.setAttribute("role", "alert");
    toast.className = "toast toast-top toast-end z-50";

    const alert = document.createElement("div");
    alert.className = `alert w-80 sm:w-96 max-w-80 sm:max-w-96 text-wrap ${FLASH_CLASSES[kind]}`;

    const icon = document.createElement("span");
    icon.className = `${FLASH_ICONS[kind]} size-5 shrink-0`;
    alert.appendChild(icon);

    const text = document.createElement("div");
    const paragraph = document.createElement("p");
    paragraph.textContent = message;
    text.appendChild(paragraph);
    alert.appendChild(text);

    const spacer = document.createElement("div");
    spacer.className = "flex-1";
    alert.appendChild(spacer);

    const close = document.createElement("button");
    close.type = "button";
    close.className = "group self-start cursor-pointer";
    close.setAttribute("aria-label", "close");
    const closeIcon = document.createElement("span");
    closeIcon.className = "hero-x-mark-solid size-5 opacity-40 group-hover:opacity-70";
    close.appendChild(closeIcon);
    alert.appendChild(close);

    toast.appendChild(alert);
    toast.addEventListener("click", () => toast.remove());
    document.body.appendChild(toast);
  };

  // ---------------------------------------------------------------------------------------------
  // Basic page: change a flag's variant, and pick up changes made elsewhere.

  const initDashboard = () => {
    const form = document.getElementById("flags-form");
    if (!form) return;

    form.addEventListener("change", async (event) => {
      const select = event.target.closest("select[data-flag-select]");
      if (!select) return;
      try {
        const response = await request("POST", "/ui/flags", {
          flag: select.name,
          variant: select.value,
        });
        const result = await response.json();
        showFlash("info", result.message);
      } catch (error) {
        console.error(error);
        showFlash("error", "Could not save the flag");
      }
    });

    let version = document.getElementById("flags-grid")?.dataset.version;
    let busy = false;

    const poll = async () => {
      if (busy || document.hidden) return;
      const active = document.activeElement;
      if (active && active.matches && active.matches("select[data-flag-select]")) return;
      busy = true;
      try {
        const current = await (await request("GET", "/ui/flags/version")).json();
        if (String(current.version) !== String(version)) {
          const html = await (await request("GET", "/ui/flags/grid")).text();
          const doc = new DOMParser().parseFromString(html, "text/html");
          const fresh = doc.getElementById("flags-grid");
          const grid = document.getElementById("flags-grid");
          if (fresh && grid) {
            grid.replaceWith(fresh);
            version = fresh.dataset.version;
          }
        }
      } catch (error) {
        console.error(error);
      } finally {
        busy = false;
      }
    };

    setInterval(poll, 2000);
  };

  // ---------------------------------------------------------------------------------------------
  // Advanced page: edit the whole configuration as JSON.

  const initAdvanced = () => {
    const content = document.getElementById("advanced-content");
    const save = document.getElementById("advanced-save");
    const unsaved = document.getElementById("advanced-unsaved");
    if (!content || !save || !unsaved) return;

    content.addEventListener("input", () => {
      unsaved.hidden = false;
    });

    save.addEventListener("click", async () => {
      try {
        const response = await request("POST", "/ui/advanced/save", { content: content.value });
        const result = await response.json();
        if (result.ok) {
          content.value = result.content;
          unsaved.hidden = true;
          clearFlash();
          showFlash("info", result.message);
        } else {
          showFlash("error", result.message);
        }
      } catch (error) {
        console.error(error);
        showFlash("error", "Could not save the configuration");
      }
    });
  };

  // ---------------------------------------------------------------------------------------------
  // Scheduler page.

  const initScheduler = () => {
    const form = document.getElementById("scheduler-form");
    if (!form) return;

    const errorBox = document.getElementById("scheduler-error");
    const setError = (message) => {
      errorBox.textContent = message || "";
      errorBox.hidden = !message;
    };

    const checkboxes = () => Array.from(form.querySelectorAll("input[type=checkbox]"));
    const flagOf = (checkbox) => {
      const match = /^variants\[(.*)\]\[\]$/.exec(checkbox.name);
      return match ? match[1] : null;
    };

    const collect = () => {
      const params = {};
      for (const name of [
        "interval_seconds",
        "min_duration_seconds",
        "max_duration_seconds",
        "concurrency",
        "seed",
      ]) {
        params[name] = form.elements[name].value;
      }
      const variants = {};
      for (const checkbox of checkboxes()) {
        if (!checkbox.checked) continue;
        const flag = flagOf(checkbox);
        if (flag === null) continue;
        (variants[flag] = variants[flag] || []).push(checkbox.value);
      }
      params.variants = variants;
      return params;
    };

    const refresh = async () => {
      try {
        const html = await (await request("GET", "/ui/scheduler/status")).text();
        const doc = new DOMParser().parseFromString(html, "text/html");
        for (const id of ["scheduler-status", "scheduler-actions", "scheduler-history"]) {
          const fresh = doc.getElementById(id);
          const current = document.getElementById(id);
          if (fresh && current && fresh.outerHTML !== current.outerHTML) {
            current.replaceWith(fresh);
          }
        }
      } catch (error) {
        console.error(error);
      }
    };

    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      try {
        const result = await (await request("POST", "/ui/scheduler/start", collect())).json();
        if (result.ok) {
          setError(null);
          clearFlash();
          showFlash("info", result.message);
        } else {
          setError(result.error);
        }
        await refresh();
      } catch (error) {
        console.error(error);
        setError("Could not reach the server");
      }
    });

    form.addEventListener("click", async (event) => {
      const button = event.target.closest("[data-action]");
      if (!button) return;
      switch (button.dataset.action) {
        case "preset":
          form.elements["interval_seconds"].value = button.dataset.interval;
          form.elements["min_duration_seconds"].value = button.dataset.min;
          form.elements["max_duration_seconds"].value = button.dataset.max;
          break;
        case "select_all":
          checkboxes().forEach((checkbox) => (checkbox.checked = true));
          break;
        case "select_none":
          checkboxes().forEach((checkbox) => (checkbox.checked = false));
          break;
        case "toggle_flag": {
          const own = checkboxes().filter((checkbox) => flagOf(checkbox) === button.dataset.flag);
          const anySelected = own.some((checkbox) => checkbox.checked);
          own.forEach((checkbox) => (checkbox.checked = !anySelected));
          break;
        }
        case "stop":
          try {
            await request("POST", "/ui/scheduler/stop");
            setError(null);
            clearFlash();
            showFlash("info", "Scheduler stopped");
            await refresh();
          } catch (error) {
            console.error(error);
            setError("Could not reach the server");
          }
          break;
        default:
          break;
      }
    });

    // The scheduler changes state on its own timers, and "time left" counts down every second.
    setInterval(() => {
      if (!document.hidden) refresh();
    }, 1000);
  };

  const init = () => {
    initDashboard();
    initAdvanced();
    initScheduler();
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();
