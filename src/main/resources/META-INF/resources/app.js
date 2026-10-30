"use strict";
const root = document.querySelector("#app"),
  modal = document.querySelector("#modal"),
  modalBody = document.querySelector("#modal-body");
let me = null,
  catalog = null,
  page = "dashboard",
  timer = null,
  serverOffset = 0,
  currentRows = [],
  overview = null,
  filterValues = defaultFilters("APPROVED"),
  tickHandle,
  project = {
    project_name: "Mein Projekt",
    preset: "MATURABALL",
    features: { teams: true, reviews: true, goals: false },
    presets: [],
  };
const statuses = {
    PENDING: "Wartet auf Prüfung",
    APPROVED: "Genehmigt",
    DENIED: "Abgelehnt",
    RUNNING: "Timer läuft",
  },
  roles = { OWNER: "Owner", ADMIN: "Admin", MEMBER: "Mitglied" };
const isAdmin = () => me && me.role !== "MEMBER";
const el = (tag, attrs = {}, ...children) => {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k.startsWith("on")) n.addEventListener(k.slice(2), v);
    else if (k === "class") n.className = v;
    else if (k === "text") n.textContent = v;
    else if (v !== null && v !== undefined) {
      if (k in n && k !== "form") n[k] = v;
      else n.setAttribute(k, v);
    }
  }
  for (const child of children.flat()) {
    if (child !== null && child !== undefined)
      n.append(
        child instanceof Node ? child : document.createTextNode(String(child)),
      );
  }
  return n;
};
const btn = (label, fn, cls = "") =>
  el("button", { type: "button", class: cls, onclick: fn }, label);
const notify = (message, error = false) => {
  const n = el(
    "div",
    {
      class: "notice" + (error ? " error" : ""),
      role: error ? "alert" : "status",
    },
    message,
  );
  const close = btn("×", () => n.remove(), "notice-close");
  close.setAttribute("aria-label", "Meldung schließen");
  n.prepend(el("strong", {}, error ? "Fehler · " : "Erfolgreich · "));
  n.append(close);
  if (modal.open) {
    let host = modal.querySelector(".modal-notices");
    if (!host) {
      host = el("div", { class: "modal-notices", "aria-live": "polite" });
      modal.prepend(host);
    }
    host.append(n);
    modal.scrollTop = 0;
  } else document.querySelector("#notices").append(n);
  if (!error) setTimeout(() => n.remove(), 9000);
};
async function api(path, options = {}) {
  const headers = { ...options.headers };
  if (options.body) {
    headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(options.body);
  }
  if (me) headers["X-CSRF-Token"] = me.csrf;
  const response = await fetch("/api" + path, { ...options, headers });
  let data;
  try {
    data = await response.json();
  } catch {
    data = { error: "Unerwartete Serverantwort." };
  }
  if (!response.ok) {
    if (response.status === 401 && me) {
      me = null;
      auth();
    }
    throw Error(data.error || "Anfrage fehlgeschlagen.");
  }
  return data;
}
async function busy(button, fn) {
  button.disabled = true;
  const old = button.textContent;
  button.textContent = "Bitte warten …";
  try {
    await fn();
  } catch (e) {
    notify(e.message, true);
  } finally {
    button.disabled = false;
    button.textContent = old;
  }
}
let controlId = 0;
const field = (label, control, help) => {
  const id = "control-" + ++controlId;
  const target =
    control.querySelector?.(".picker-trigger, input, textarea") || control;
  target.id = id;
  target.dataset.label = label;
  target.setAttribute(
    "aria-label",
    control.classList.contains("picker")
      ? label + ": " + target.textContent.replace("⌄", "").trim()
      : label,
  );
  if (help) target.setAttribute("aria-describedby", id + "-help");
  return el(
    "div",
    { class: "field" },
    el("label", { htmlFor: id }, label),
    control,
    help ? el("span", { class: "help", id: id + "-help" }, help) : null,
  );
};
const input = (name, type = "text", value = "", extra = {}) =>
  el("input", { name, type, value, ...extra });
// Keep a real native select for FormData and validation; a keyboard-operable popover is its visible UI.
const select = (name, options, value = "", extra = {}) => {
  const { selectedValues, ...nativeAttrs } = extra;
  const native = el("select", {
    name,
    ...nativeAttrs,
    class: "picker-native",
    tabIndex: -1,
    "aria-hidden": "true",
  });
  options.forEach(([v, label]) =>
    native.append(
      el(
        "option",
        {
          value: v,
          selected: extra.multiple
            ? (selectedValues || []).includes(String(v))
            : String(v) === String(value),
        },
        label,
      ),
    ),
  );
  const multiple = Boolean(extra.multiple),
    id = "options-" + ++controlId;
  const trigger = btn("", () => toggle(), "picker-trigger");
  trigger.disabled = Boolean(extra.disabled);
  trigger.setAttribute("aria-haspopup", "listbox");
  trigger.setAttribute("aria-expanded", "false");
  trigger.setAttribute("aria-controls", id);
  const panel = el("div", { class: "picker-panel", hidden: true });
  const search = input("", "search", "", {
    placeholder: "Suchen …",
    "aria-label": "Auswahl durchsuchen",
    autocomplete: "off",
  });
  const list = el("div", {
    id,
    role: "listbox",
    "aria-multiselectable": multiple ? "true" : "false",
    class: "picker-options",
  });
  const wrap = el("div", { class: "picker" }, native, trigger, panel);
  if (options.length > 5) panel.append(search);
  panel.append(list);
  const paint = () => {
    const chosen = [...native.selectedOptions].map((o) => o.textContent);
    trigger.replaceChildren(
      el(
        "span",
        { class: "picker-value" },
        multiple
          ? chosen.length
            ? chosen.join(", ")
            : "Alle Personen"
          : chosen[0] || "Bitte auswählen",
      ),
      el("span", { class: "picker-chevron", "aria-hidden": "true" }, "⌄"),
    );
    if (trigger.dataset.label)
      trigger.setAttribute(
        "aria-label",
        trigger.dataset.label +
          ": " +
          (multiple
            ? chosen.join(", ") || "Alle Personen"
            : chosen[0] || "Bitte auswählen"),
      );
    list.replaceChildren();
    [...native.options]
      .filter((o) =>
        o.textContent
          .toLocaleLowerCase()
          .includes(search.value.toLocaleLowerCase()),
      )
      .forEach((o) => {
        const option = btn(
          [
            el("span", {}, o.textContent),
            el("span", { "aria-hidden": "true" }, o.selected ? "✓" : ""),
          ],
          () => {
            if (multiple) o.selected = !o.selected;
            else native.value = o.value;
            paint();
            native.dispatchEvent(new Event("change", { bubbles: true }));
            if (!multiple) close(true);
            else
              [...list.querySelectorAll("button")]
                .find((button) => button.textContent.startsWith(o.textContent))
                ?.focus();
          },
          "picker-option" + (o.selected ? " selected" : ""),
        );
        option.setAttribute("role", "option");
        option.setAttribute("aria-selected", String(o.selected));
        list.append(option);
      });
    if (!list.childElementCount)
      list.append(
        el("p", { class: "help picker-no-results" }, "Keine passende Auswahl."),
      );
  };
  const close = (focus = false) => {
    if (panel.matches(":popover-open")) panel.hidePopover();
    panel.hidden = true;
    trigger.setAttribute("aria-expanded", "false");
    if (focus) trigger.focus();
  };
  const toggle = () => {
    if (trigger.disabled) return;
    const open = panel.hidden;
    document
      .querySelectorAll(".picker")
      .forEach((p) => p !== wrap && p.close?.());
    panel.hidden = !open;
    trigger.setAttribute("aria-expanded", String(open));
    if (open) {
      search.value = "";
      paint();
      const rect = trigger.getBoundingClientRect();
      if (wrap.classList.contains("member-inline") && panel.showPopover) {
        panel.setAttribute("popover", "manual");
        panel.style.position = "fixed";
        panel.style.margin = "0";
        panel.style.minWidth = rect.width + "px";
        panel.style.left =
          Math.max(12, Math.min(rect.left, window.innerWidth - 280)) + "px";
        panel.style.top =
          Math.min(rect.bottom + 5, window.innerHeight - 240) + "px";
        panel.showPopover();
        return;
      }
      panel.classList.toggle(
        "above",
        window.innerHeight - rect.bottom < Math.min(260, panel.scrollHeight) &&
          rect.top > 180,
      );
    }
  };
  trigger.onkeydown = (e) => {
    if (["ArrowDown", "ArrowUp"].includes(e.key)) {
      e.preventDefault();
      if (panel.hidden) toggle();
      (
        list.querySelector("button.selected") || list.querySelector("button")
      )?.focus();
    }
  };
  panel.onkeydown = (e) => {
    if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      close(true);
    }
    if (
      ["ArrowDown", "ArrowUp", "Home", "End"].includes(e.key) &&
      e.target !== search
    ) {
      e.preventDefault();
      const opts = [...list.querySelectorAll("button")],
        i = opts.indexOf(document.activeElement);
      const next =
        e.key === "Home"
          ? 0
          : e.key === "End"
            ? opts.length - 1
            : (i + (e.key === "ArrowDown" ? 1 : -1) + opts.length) %
              opts.length;
      opts[next]?.focus();
    }
  };
  search.oninput = paint;
  native.addEventListener("invalid", (e) => {
    e.preventDefault();
    trigger.focus();
    trigger.setAttribute("aria-invalid", "true");
    notify(
      "Bitte eine Auswahl für „" +
        (trigger.getAttribute("aria-label") || name) +
        "“ treffen.",
      true,
    );
  });
  native.addEventListener("change", () =>
    trigger.removeAttribute("aria-invalid"),
  );
  Object.defineProperty(wrap, "value", {
    get: () => native.value,
    set: (v) => {
      native.value = v;
      paint();
    },
  });
  Object.defineProperty(wrap, "options", { get: () => native.options });
  wrap.close = close;
  paint();
  return wrap;
};
document.addEventListener("pointerdown", (e) =>
  document.querySelectorAll(".picker").forEach((p) => {
    if (!p.contains(e.target)) p.close?.();
  }),
);
document.addEventListener("focusin", (e) =>
  document.querySelectorAll(".picker").forEach((p) => {
    if (!p.contains(e.target)) p.close?.();
  }),
);
function applyAppearance(color = "PURPLE", mode = "LIGHT") {
  if (!["PURPLE", "GREEN", "BLUE", "RED"].includes(color)) color = "PURPLE";
  if (!["LIGHT", "DARK"].includes(mode)) mode = "LIGHT";
  document.documentElement.dataset.color = color.toLowerCase();
  document.documentElement.dataset.mode = mode.toLowerCase();
  const chromeColors = {
    PURPLE: ["#f8f6fc", "#17141d"],
    GREEN: ["#f4f8f4", "#111b17"],
    BLUE: ["#f4f7fc", "#121923"],
    RED: ["#fcf6f6", "#211519"],
  };
  document.querySelector('meta[name="theme-color"]').content =
    chromeColors[color][mode === "DARK" ? 1 : 0];
  try {
    localStorage.setItem(
      "time-tracker-appearance",
      JSON.stringify({ color, mode }),
    );
  } catch {}
}
try {
  const saved = JSON.parse(
    localStorage.getItem("time-tracker-appearance") || "{}",
  );
  applyAppearance(saved.color, saved.mode);
} catch {
  applyAppearance();
}
function teamName(id = me?.team_id) {
  return (
    catalog?.teams.find((t) => t.id === id)?.name || "Noch kein Team zugewiesen"
  );
}
function statusLabel(row) {
  return row.status === "APPROVED" && Number(row.approval_required) === 0
    ? "Erfasst · ohne Freigabepflicht"
    : statuses[row.status];
}
function presetCards(presets, selected = "MATURABALL", name = "preset") {
  return el(
    "div",
    { class: "preset-grid" },
    presets.map((p) => {
      const radio = input(name, "radio", p.id, {
        required: true,
        checked: p.id === selected,
      });
      return el(
        "label",
        { class: "preset-card" },
        radio,
        el(
          "span",
          { class: "preset-icon", "aria-hidden": "true" },
          {
            MATURABALL: "✦",
            JAHRESPROJEKT: "▦",
            VEREIN: "♧",
            FREIES_PROJEKT: "◈",
          }[p.id],
        ),
        el("strong", {}, p.name),
        el("span", { class: "help" }, p.description),
        el(
          "span",
          { class: "preset-features" },
          [
            p.teams ? "Feste Teams" : "Ohne Teamzwang",
            p.reviews ? "Mit Freigaben" : "Direkt erfasst",
            p.goals ? "Wochenziel" : null,
          ]
            .filter(Boolean)
            .join(" · "),
        ),
      );
    }),
  );
}
const vals = (form) => Object.fromEntries(new FormData(form));
const hours = (ms) =>
  new Intl.NumberFormat("de-AT", {
    minimumFractionDigits: 1,
    maximumFractionDigits: 2,
  }).format(ms / 3600000) + " h";
const duration = (e) => Number(e.duration_millis || 0);
const date = (ms) =>
  new Intl.DateTimeFormat("de-AT", {
    timeZone: "Europe/Vienna",
    day: "2-digit",
    month: "short",
    year: "numeric",
  }).format(ms);
const datetime = (ms) =>
  new Intl.DateTimeFormat("de-AT", {
    timeZone: "Europe/Vienna",
    dateStyle: "short",
    timeStyle: "short",
  }).format(ms);
const time = (ms) =>
  new Intl.DateTimeFormat("de-AT", {
    timeZone: "Europe/Vienna",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  }).format(ms);
const local = (ms) => {
  const p = new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Europe/Vienna",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  }).format(ms);
  return p.replace(" ", "T");
};
const offset = (ms) => {
  const v = new Intl.DateTimeFormat("en", {
    timeZone: "Europe/Vienna",
    timeZoneName: "longOffset",
  })
    .formatToParts(ms)
    .find((p) => p.type === "timeZoneName").value;
  return v.replace("GMT", "");
};
function showDialog(content) {
  modal.querySelector(".modal-notices")?.remove();
  modalBody.replaceChildren(content);
  if (!modal.open) modal.showModal();
}
document.querySelector("#close-modal").onclick = () => modal.close();
function auth(mode = "login", token = "") {
  clearInterval(tickHandle);
  root.replaceChildren();
  const main = el(
    "main",
    { class: "auth" },
    el(
      "div",
      { class: "brand" },
      el("span", { class: "brand-mark" }, "◷"),
      "Time Tracker",
    ),
  );
  const titles = {
    login: [
      "Willkommen zurück.",
      "Deine Zeit. Dein Projekt. Gemeinsam im Überblick.",
    ],
    forgot: [
      "Passwort vergessen?",
      "Wir senden dir einen einmaligen Link an deine E-Mail-Adresse.",
    ],
    invite: [
      "Du bist eingeladen.",
      "Erstelle dein persönliches Konto für euer Projekt.",
    ],
    reset: ["Ein neuer Anfang.", "Wähle ein neues, sicheres Passwort."],
    bootstrap: [
      "Euer Projekt beginnt hier.",
      "Einmalige Einrichtung des ersten Owners. Der Einrichtungsschlüssel kommt aus der Serverkonfiguration.",
    ],
  };
  const [title, sub] = titles[mode];
  const form = el("form");
  if (["login", "forgot", "bootstrap"].includes(mode))
    form.append(
      field(
        "E-Mail-Adresse",
        input("email", "email", "", {
          required: true,
          autocomplete: "username",
          maxLength: 254,
        }),
      ),
    );
  if (["invite", "bootstrap"].includes(mode))
    form.append(
      field(
        "Anzeigename",
        input("displayName", "text", "", {
          required: true,
          minLength: 2,
          maxLength: 80,
        }),
      ),
    );
  if (["invite", "bootstrap"].includes(mode))
    form.append(
      field(
        "Schulkürzel",
        input("schoolCode", "text", "", {
          required: true,
          minLength: 2,
          maxLength: 32,
          pattern: "[a-zA-Z0-9][a-zA-Z0-9._-]{1,31}",
          autocomplete: "off",
          spellcheck: false,
          placeholder: "it/ifxxxxxx",
        }),
        "Dein persönliches Schulkürzel, nicht dein Passwort.",
      ),
    );
  if (mode === "bootstrap") {
    form.append(
      field(
        "Projektname",
        input("projectName", "text", "Maturaball", {
          required: true,
          minLength: 2,
          maxLength: 100,
        }),
      ),
    );
    const presetsArea = el(
      "section",
      { class: "setup-presets" },
      el("h2", {}, "Wie möchtet ihr arbeiten?"),
      el(
        "p",
        { class: "help" },
        "Die Vorlage aktiviert den passenden Ablauf. Owner können sie später in den Einstellungen ändern.",
      ),
    );
    form.append(presetsArea);
    api("/project/presets")
      .then((presets) => presetsArea.append(presetCards(presets)))
      .catch((e) => {
        notify(e.message, true);
        presetsArea.append(
          el(
            "p",
            { class: "danger-text" },
            "Vorlagen konnten nicht geladen werden. Bitte neu laden.",
          ),
        );
      });
  }
  if (mode === "bootstrap")
    form.append(
      field(
        "Einrichtungsschlüssel",
        input("key", "password", "", { required: true, autocomplete: "off" }),
      ),
    );
  if (mode !== "forgot")
    form.append(
      field(
        mode === "login" ? "Passwort" : "Neues Passwort",
        input("password", "password", "", {
          required: true,
          minLength: mode === "login" ? 1 : 12,
          autocomplete: mode === "login" ? "current-password" : "new-password",
        }),
        mode === "login"
          ? null
          : "Mindestens 12 Zeichen, höchstens 72 UTF-8-Bytes.",
      ),
    );
  if (mode !== "forgot")
    form.append(
      el(
        "p",
        { class: "info-box" },
        "Wichtig: Verwende ein eigenes Passwort für Time Tracker. Verwende NICHT dein Schulpasswort!",
      ),
    );
  const submit = el(
    "button",
    { type: "submit" },
    {
      login: "Anmelden →",
      forgot: "Reset-Link anfordern",
      invite: "Konto erstellen",
      reset: "Passwort speichern",
      bootstrap: "Owner erstellen",
    }[mode],
  );
  form.append(submit);
  form.onsubmit = (e) => {
    e.preventDefault();
    busy(submit, async () => {
      const b = vals(form);
      if (mode === "login") {
        await api("/auth/login", { method: "POST", body: b });
        await initialize();
        return;
      }
      if (mode === "forgot")
        await api("/auth/reset-request", { method: "POST", body: b });
      if (mode === "invite")
        await api("/auth/accept", {
          method: "POST",
          body: {
            token,
            displayName: b.displayName,
            schoolCode: b.schoolCode,
            password: b.password,
          },
        });
      if (mode === "reset")
        await api("/auth/reset", {
          method: "POST",
          body: { token, password: b.password },
        });
      if (mode === "bootstrap") {
        const key = b.key;
        delete b.key;
        await api("/auth/bootstrap", {
          method: "POST",
          body: b,
          headers: { "X-Bootstrap-Key": key },
        });
      }
      notify(
        mode === "forgot"
          ? "Falls ein aktives Konto existiert, wurde ein Link angefordert."
          : "Gespeichert. Du kannst dich jetzt anmelden.",
      );
      history.replaceState(null, "", location.pathname);
      auth();
    });
  };
  main.append(
    el(
      "p",
      { class: "eyebrow" },
      "Mehr Überblick. Mehr Zeit fürs Wesentliche.",
    ),
    el("h1", {}, title),
    el("p", { class: "subtitle" }, sub),
    el("div", { class: "card" }, form),
  );
  const links = el("div", { class: "auth-links" });
  if (mode === "login")
    links.append(
      btn("Passwort vergessen?", () => auth("forgot"), "link-button"),
      btn("Erste Einrichtung", () => auth("bootstrap"), "link-button"),
    );
  else links.append(btn("← Zur Anmeldung", () => auth(), "link-button"));
  main.append(
    links,
    el(
      "p",
      { class: "help" },
      "Zeiten werden in Europe/Vienna angezeigt. Deine Sitzung bleibt sicher auf dem Server.",
    ),
  );
  root.append(main);
}
async function initialize() {
  try {
    me = await api("/auth/me");
    catalog = await api("/catalog");
    project = catalog.project;
    applyAppearance(me.color_preset, me.color_mode);
    const t = await api("/time/timer");
    timer = t.timer.id ? t.timer : null;
    serverOffset = t.serverNow - Date.now();
    await render();
  } catch (e) {
    if (!me) auth();
    else notify(e.message, true);
  }
}
function navIcon(name) {
  const paths = {
    dashboard: [
      "M3 3h7v7H3z",
      "M14 3h7v7h-7z",
      "M3 14h7v7H3z",
      "M14 14h7v7h-7z",
    ],
    mine: ["M12 8v4l3 2", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18"],
    overview: ["M4 20V10", "M10 20V4", "M16 20v-7", "M22 20H2"],
    review: ["M9 4H5v17h14V4h-4", "M9 3h6v4H9z", "m8 14 3 3 5-6"],
    members: [
      "M9 3a3 3 0 1 0 0 6 3 3 0 0 0 0-6",
      "M2 21v-3a7 7 0 0 1 14 0v3",
      "M17 4a3 3 0 0 1 0 6",
      "M19 14a5 5 0 0 1 3 5v2",
    ],
    catalog: [
      "M9 3h6v5H9z",
      "M2 16h6v5H2z",
      "M16 16h6v5h-6z",
      "M12 8v4H5v4",
      "M12 12h7v4",
    ],
    history: ["M3 4v5h5", "M3 9a9 9 0 1 1 1 9", "M12 7v5l3 2"],
    account: [
      "M4 6h16",
      "M4 12h16",
      "M4 18h16",
      "M8 3v6",
      "M16 9v6",
      "M10 15v6",
    ],
  };
  return svgNode(
    "svg",
    {
      viewBox: "0 0 24 24",
      class: "nav-icon",
      "aria-hidden": "true",
      fill: "none",
      stroke: "currentColor",
      "stroke-width": 1.7,
      "stroke-linecap": "round",
      "stroke-linejoin": "round",
    },
    ...(paths[name] || []).map((d) => svgNode("path", { d })),
  );
}
function shell() {
  root.replaceChildren();
  const nav = el("nav", { class: "nav", "aria-label": "Hauptnavigation" });
  const links = [
    ["dashboard", "◫", "Dashboard"],
    ["mine", "◷", "Meine Zeiten"],
    ["overview", "▥", "Zeitübersicht"],
  ];
  if (isAdmin())
    links.push(
      ...(project.features.reviews || project.pendingReviews > 0
        ? [["review", "✓", "Prüfung"]]
        : []),
      ["members", "♧", "Mitglieder"],
      ["catalog", "▦", "Organisation"],
      ["history", "↺", "Historie"],
    );
  links.push(["account", "⚙", "Einstellungen"]);
  for (const [id, icon, label] of links)
    nav.append(
      btn(
        [navIcon(id), el("span", { class: "nav-text" }, label)],
        () => navigate(id),
        page === id ? "active" : "",
      ),
    );
  const side = el(
    "aside",
    { class: "sidebar" },
    el(
      "div",
      { class: "brand" },
      el("span", { class: "brand-mark" }, "◷"),
      "Time Tracker",
      el("small", {}, project.project_name),
    ),
    nav,
    el(
      "div",
      { class: "sidebar-bottom" },
      el("span", { class: "sidebar-project-type" }, project.features.name),
      el("p", {}, "Gemeinsam zählt jede Stunde."),
    ),
  );
  const content = el("main", { class: "content" });
  content.append(
    el(
      "header",
      { class: "topbar" },
      el(
        "span",
        {},
        project.project_name +
          " / " +
          {
            dashboard: "Überblick",
            mine: "Zeiterfassung",
            overview: "Auswertung",
            review: "Freigaben",
            members: "Verwaltung",
            catalog: "Verwaltung",
            account: "Einstellungen",
            history: "Änderungshistorie",
          }[page],
      ),
      el(
        "div",
        { class: "user" },
        el(
          "div",
          { class: "avatar" },
          me.display_name.slice(0, 2).toUpperCase(),
        ),
        el(
          "div",
          {},
          el("strong", {}, me.display_name),
          el(
            "span",
            { class: "user-meta" },
            roles[me.role] + " · " + teamName(),
          ),
        ),
        btn(
          "Abmelden",
          async () => {
            try {
              await api("/auth/logout", { method: "POST" });
              me = null;
              auth();
            } catch (e) {
              notify(e.message, true);
            }
          },
          "secondary small logout",
        ),
      ),
    ),
  );
  root.append(el("div", { class: "shell" }, side, content));
  return content;
}
async function navigate(next) {
  if (next === "review" && page !== "review")
    filterValues = defaultFilters("PENDING");
  if (next === "overview" && page !== "overview")
    filterValues = defaultFilters("APPROVED");
  page = next;
  await render();
}
async function render() {
  clearInterval(tickHandle);
  try {
    me = await api("/auth/me");
    catalog = await api("/catalog");
    project = catalog.project;
  } catch (e) {
    if (!me) return;
    notify(e.message, true);
    return;
  }
  const c = shell();
  const area = el("div");
  c.append(area);
  area.append(el("div", { class: "loading" }, "Wird geladen …"));
  try {
    switch (page) {
      case "dashboard":
        await dashboard(area);
        break;
      case "mine":
        await mine(area);
        break;
      case "overview":
        await overviewPage(area, false);
        break;
      case "review":
        await overviewPage(area, true);
        break;
      case "members":
        await members(area);
        break;
      case "catalog":
        catalogPage(area);
        break;
      case "account":
        account(area);
        break;
      case "history":
        await historyPage(area);
        break;
    }
    c.append(
      el(
        "footer",
        {},
        el("span", {}, "TIME TRACKER · Gemeinsam zählt jede Stunde"),
        el("span", {}, "Zeitzone Europe/Vienna"),
      ),
    );
  } catch (e) {
    area.replaceChildren(
      el(
        "div",
        { class: "card" },
        el("p", {}, e.message),
        btn("Erneut versuchen", render, "secondary"),
      ),
    );
  }
}
function title(kicker, heading, sub, action) {
  return el(
    "div",
    { class: "page-title" },
    el("p", { class: "eyebrow" }, kicker),
    el("div", { class: "heading" }, el("h1", {}, heading), action),
    el("p", { class: "subtitle" }, sub),
  );
}
function assignmentFields(form, entry = {}) {
  const known = catalog.activities.find((a) => a.id === entry.activity_id);
  const work = input(
    "workLabel",
    "text",
    entry.activity_name || known?.name || "",
    {
      required: true,
      minLength: 2,
      maxLength: 100,
      placeholder: "z. B. Social Media, Sponsoring, Recherche",
      autocomplete: "off",
    },
  );
  const suggestions = el("div", {
    class: "work-suggestions",
    role: "listbox",
    hidden: true,
  });
  const listId = "work-options-" + ++controlId;
  suggestions.id = listId;
  work.setAttribute("role", "combobox");
  work.setAttribute("aria-autocomplete", "list");
  work.setAttribute("aria-expanded", "false");
  work.setAttribute("aria-controls", listId);
  const close = () => {
    suggestions.hidden = true;
    work.setAttribute("aria-expanded", "false");
  };
  const paint = () => {
    const choices = catalog.activities
      .filter(
        (a) =>
          Number(a.active) &&
          a.name.toLocaleLowerCase().includes(work.value.toLocaleLowerCase()),
      )
      .slice(0, 8);
    suggestions.replaceChildren();
    choices.forEach((activity) => {
      const choose = () => {
        work.value = activity.name;
        // Focus first: returning from a keyboard option can run onfocus/paint.
        // Closing afterwards keeps the selected value visible and the list closed.
        work.focus({ preventScroll: true });
        close();
        work.dispatchEvent(new Event("change", { bubbles: true }));
      };
      const option = btn(activity.name, choose, "work-option");
      option.setAttribute("role", "option");
      option.tabIndex = -1;
      // Safari may blur an input without focusing a clicked button. Retain input
      // focus until click, otherwise focusout hides the suggestion before click fires.
      option.addEventListener("pointerdown", (e) => {
        if (e.button === 0) e.preventDefault();
      });
      option.addEventListener("mousedown", (e) => e.preventDefault());
      option.onkeydown = (e) => {
        if (e.key === "Escape") {
          e.preventDefault();
          e.stopPropagation();
          work.focus({ preventScroll: true });
          close();
        }
        if (e.key === "ArrowDown" || e.key === "ArrowUp") {
          e.preventDefault();
          const options = [...suggestions.querySelectorAll("button")];
          const next =
            (options.indexOf(option) +
              (e.key === "ArrowDown" ? 1 : -1) +
              options.length) %
            options.length;
          options[next]?.focus();
        }
      };
      suggestions.append(option);
    });
    if (!choices.length)
      suggestions.append(
        el(
          "p",
          { class: "help" },
          "Neue Tätigkeit – wird beim Speichern angelegt.",
        ),
      );
    suggestions.hidden = false;
    work.setAttribute("aria-expanded", "true");
  };
  work.onfocus = paint;
  work.oninput = paint;
  work.onkeydown = (e) => {
    if (e.key === "Escape") {
      e.preventDefault();
      close();
    }
    if (e.key === "ArrowDown") {
      e.preventDefault();
      paint();
      suggestions.querySelector("button")?.focus();
    }
  };
  const wrap = el("div", { class: "work-combobox" }, work, suggestions);
  wrap.addEventListener("focusout", (e) => {
    if (!wrap.contains(e.relatedTarget)) close();
  });
  form.append(
    el(
      "div",
      { class: "team-context" },
      el("span", { class: "team-context-icon", "aria-hidden": "true" }, "♧"),
      el(
        "div",
        {},
        el(
          "span",
          { class: "help" },
          entry.id ? "Team bei Erfassung" : "Dein zugewiesenes Team",
        ),
        el(
          "strong",
          {},
          entry.id ? entry.team_name || teamName(entry.team_id) : teamName(),
        ),
        el("span", { class: "help" }, "Vom Admin zugewiesen"),
      ),
    ),
    field(
      "Woran arbeitest du?",
      wrap,
      "Tätigkeit auswählen oder selbst eingeben – unabhängig von deinem Team.",
    ),
    field(
      "Was hast du gemacht?",
      input("description", "text", entry.description || "", {
        required: true,
        minLength: 3,
        maxLength: 1000,
        placeholder: "Beschreibe deinen konkreten Beitrag …",
      }),
    ),
  );
}
async function timerCard() {
  const t = await api("/time/timer");
  timer = t.timer.id ? t.timer : null;
  serverOffset = t.serverNow - Date.now();
  const clock = el(
    "div",
    { class: "clock", "aria-label": "Laufzeit" },
    "00:00:00",
  );
  const top = el(
    "div",
    { class: "timer-top" },
    el(
      "div",
      {},
      el("h2", {}, "Zeit für dein Projekt."),
      el(
        "div",
        { class: "live" },
        el("span", { class: "dot" }),
        timer ? "Dein Timer läuft" : "Bereit, wenn du es bist",
      ),
    ),
    clock,
  );
  const form = el("form", { class: "timer-fields" });
  assignmentFields(form, timer || {}, true);
  const submit = el(
    "button",
    { type: "submit" },
    timer ? "■ Timer stoppen" : "▶ Timer starten",
  );
  form.append(submit);
  let key = crypto.randomUUID();
  form.onsubmit = (e) => {
    e.preventDefault();
    busy(submit, async () => {
      const b = { ...vals(form), requestKey: key, version: timer?.version };
      if (timer)
        await api("/time/timer/" + timer.id + "/stop", {
          method: "POST",
          body: b,
        });
      else await api("/time/timer", { method: "POST", body: b });
      notify(
        timer
          ? project.features.reviews
            ? "Zeit gespeichert und zur Prüfung eingereicht."
            : "Zeit gespeichert."
          : "Timer gestartet. Er läuft auch nach dem Schließen weiter.",
      );
      await render();
    });
  };
  const update = () => {
    const s = timer
      ? Math.max(
          0,
          Math.floor((Date.now() + serverOffset - timer.started_at) / 1000),
        )
      : 0;
    clock.textContent = [Math.floor(s / 3600), Math.floor(s / 60) % 60, s % 60]
      .map((x) => String(x).padStart(2, "0"))
      .join(":");
  };
  update();
  tickHandle = setInterval(update, 1000);
  return el(
    "section",
    { class: "card timer-card" },
    top,
    form,
    el(
      "p",
      { class: "help" },
      project.features.reviews
        ? "Laufende Timer zählen noch nicht. Abgeschlossene Zeiten werden zur Prüfung eingereicht."
        : "Ohne Freigabepflicht: Abgeschlossene Zeiten werden direkt erfasst. Laufende Timer zählen noch nicht.",
    ),
  );
}
async function dashboard(area) {
  const own = await api("/time/mine"),
    stats = await api("/time/overview");
  area.replaceChildren(
    title(
      project.project_name,
      "Hallo, " + me.display_name.split(" ")[0] + ".",
      "Dein Einsatz auf einen Blick. Dein Team: " + teamName() + ".",
      btn("+ Zeit nachtragen", () => entryDialog(), "secondary"),
    ),
    await timerCard(),
  );
  const approved = (
      await api("/time/overview?person=" + me.id + "&status=APPROVED")
    ).durationMillis,
    pending = (await api("/time/overview?person=" + me.id + "&status=PENDING"))
      .durationMillis;
  const metric = (label, value, note) =>
    el(
      "div",
      { class: "card metric" },
      el("span", {}, label),
      el("strong", {}, value, el("small", {}, note)),
    );
  area.append(
    el(
      "div",
      { class: "grid" },
      metric("Deine gültige Arbeitszeit", hours(approved), "genehmigt"),
      metric("Deine offene Arbeitszeit", hours(pending), "wartet auf Prüfung"),
      metric(
        "Gemeinsam geschafft",
        hours(stats.durationMillis),
        "genehmigt · alle Mitglieder",
      ),
    ),
  );
  area.append(
    el(
      "div",
      { class: "split" },
      el(
        "section",
        { class: "card" },
        el(
          "div",
          { class: "heading" },
          el("h2", {}, "Deine letzten Einträge"),
          btn("Alle ansehen →", () => navigate("mine"), "link-button"),
        ),
        entryTable(own.slice(0, 5), { edit: true, compact: true }),
      ),
      el(
        "section",
        { class: "card" },
        el("h2", {}, "Wofür wir Zeit investieren"),
        el(
          "p",
          { class: "help" },
          "Alle Zeiträume · genehmigte Stunden · alle Mitglieder",
        ),
        chart(stats.activities),
      ),
    ),
  );
  area.append(
    el(
      "section",
      { class: "card" },
      el("h2", {}, "Gemeinsamer Fortschritt"),
      el(
        "p",
        { class: "help" },
        "Alle Zeiträume · genehmigte und direkt erfasste Stunden",
      ),
      trendChart(stats.daily),
    ),
  );
  if (project.features.goals) {
    const now = new Date(Date.now() + serverOffset);
    const vienna = new Intl.DateTimeFormat("sv-SE", {
      timeZone: "Europe/Vienna",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(now);
    const monday = new Date(vienna + "T12:00:00Z");
    monday.setUTCDate(monday.getUTCDate() - ((monday.getUTCDay() + 6) % 7));
    const week = await api(
      "/time/overview?status=APPROVED&person=" +
        me.id +
        "&from=" +
        monday.toISOString().slice(0, 10),
    );
    const goal = Number(project.weekly_goal_minutes) * 60000,
      progress = goal ? Math.min(100, (week.durationMillis / goal) * 100) : 0;
    const meter = el("progress", {
      max: 100,
      value: progress,
      "aria-label": "Wochenziel erreicht",
    });
    area.append(
      el(
        "section",
        { class: "card goal-card" },
        el("h2", {}, "Dein Wochenziel"),
        el("strong", {}, hours(week.durationMillis) + " von " + hours(goal)),
        meter,
        el(
          "p",
          { class: "help" },
          goal
            ? Math.round(progress) +
                " % erreicht · seit Montag · genehmigte / direkt erfasste Zeit"
            : "Noch kein Wochenziel festgelegt. Der Owner kann es unter Einstellungen setzen.",
        ),
      ),
    );
  }
}
function colorSwatch(color) {
  const swatch = el("span", { class: "color-swatch" }, color);
  if (/^#[a-f0-9]{6}$/i.test(color)) swatch.style.borderColor = color;
  return swatch;
}
function appearanceSettings() {
  let color = me.color_preset || "PURPLE",
    mode = me.color_mode || "LIGHT";
  const colors = [
    ["PURPLE", "Lila / Pink"],
    ["GREEN", "Grün"],
    ["BLUE", "Blau"],
    ["RED", "Rot"],
  ];
  const choices = el("div", {
    class: "theme-options",
    role: "group",
    "aria-label": "Farbwelt",
  });
  const modes = el("div", {
    class: "mode-options",
    role: "group",
    "aria-label": "Hell- oder Dunkelmodus",
  });
  const status = el("p", {
    class: "help",
    role: "status",
    "aria-live": "polite",
  });
  let saves = Promise.resolve();
  const persist = () => {
    const selectedColor = color,
      selectedMode = mode;
    status.textContent = "Darstellung wird gespeichert …";
    saves = saves.then(async () => {
      try {
        me = await api("/auth/appearance", {
          method: "PUT",
          body: {
            colorPreset: selectedColor,
            colorMode: selectedMode,
            version: me.version,
          },
        });
        if (color === selectedColor && mode === selectedMode)
          status.textContent = "Darstellung automatisch gespeichert.";
      } catch (error) {
        status.textContent = "Speichern fehlgeschlagen: " + error.message;
        notify(status.textContent, true);
      }
    });
  };
  const update = () => {
    applyAppearance(color, mode);
    choices
      .querySelectorAll("button")
      .forEach((b) =>
        b.setAttribute("aria-pressed", String(b.dataset.color === color)),
      );
    modes
      .querySelectorAll("button")
      .forEach((b) =>
        b.setAttribute("aria-pressed", String(b.dataset.mode === mode)),
      );
  };
  colors.forEach(([id, name]) => {
    const b = btn(
      [
        el("span", {
          class: "theme-orb theme-" + id.toLowerCase(),
          "aria-hidden": "true",
        }),
        el("strong", {}, name),
      ],
      () => {
        color = id;
        update();
        persist();
      },
      "theme-choice",
    );
    b.dataset.color = id;
    choices.append(b);
  });
  [
    ["LIGHT", "☀ Hell"],
    ["DARK", "☾ Dunkel"],
  ].forEach(([id, name]) => {
    const b = btn(
      name,
      () => {
        mode = id;
        update();
        persist();
      },
      "mode-choice",
    );
    b.dataset.mode = id;
    modes.append(b);
  });
  update();
  return el(
    "section",
    { class: "card appearance-card" },
    el("p", { class: "eyebrow" }, "Dein Stil. Dein Überblick."),
    el("h2", {}, "Farben & Darstellung"),
    el(
      "p",
      { class: "help" },
      "Jede Farbwelt gibt es in Hell und Dunkel. Deine Auswahl wird sofort angewendet und automatisch für dein Konto gespeichert.",
    ),
    choices,
    modes,
    status,
  );
}
function projectSettings() {
  const form = el(
    "form",
    { class: "project-settings-form" },
    field(
      "Projektname",
      input("projectName", "text", project.project_name, {
        required: true,
        minLength: 2,
        maxLength: 100,
      }),
    ),
    presetCards(project.presets, project.preset),
    field(
      "Persönliches Wochenziel in Stunden",
      input("goal", "number", project.weekly_goal_minutes / 60, {
        required: true,
        min: 0,
        max: 100,
        step: 0.25,
      }),
      "Aktiv bei Jahresprojekt und Freies Projekt. Bezieht sich auf genehmigte beziehungsweise direkt erfasste Stunden pro Person.",
    ),
  );
  const submit = el(
    "button",
    { type: "submit" },
    "Projekteinstellungen speichern",
  );
  form.append(
    el(
      "p",
      { class: "info-box" },
      "Die Vorlage steuert Team- und Freigabepflicht für neue Zeiten. Vorhandene Einträge, historische Teams und ausstehende Freigaben bleiben erhalten. Bereits geprüfte Zeiten brauchen nach Änderungen weiterhin eine neue Prüfung.",
    ),
    submit,
  );
  form.onsubmit = (e) => {
    e.preventDefault();
    busy(submit, async () => {
      const b = vals(form);
      project = await api("/project", {
        method: "PUT",
        body: {
          projectName: b.projectName,
          preset: b.preset,
          weeklyGoalMinutes: Math.round(Number(b.goal) * 60),
          version: project.version,
        },
      });
      notify("Projektvorlage gespeichert.");
      await render();
    });
  };
  return el(
    "section",
    { class: "card" },
    el("h2", {}, "Projekt & Vorlage"),
    form,
  );
}
async function mine(area) {
  const rows = await api("/time/mine");
  area.replaceChildren(
    title(
      "Dein Beitrag zählt",
      "Meine Zeiten",
      "Deine Einträge mit Status, Prüfung und Bearbeitungsmöglichkeit.",
      btn("+ Zeit nachtragen", () => entryDialog()),
    ),
    await timerCard(),
    el("section", { class: "card" }, entryTable(rows, { edit: true })),
  );
}
function entryTable(
  rows,
  { edit = false, review = false, compact = false } = {},
) {
  if (!rows.length)
    return el(
      "div",
      { class: "empty" },
      el("div", { class: "empty-symbol" }, "◷"),
      "Hier ist noch Platz für euren Einsatz.",
      el(
        "p",
        { class: "help" },
        "Für diese Auswahl gibt es noch keine Einträge.",
      ),
    );
  const table = el("table"),
    head = el("tr");
  if (review) head.append(el("th", {}, "Auswahl"));
  [
    "Person / Tätigkeit",
    "Team bei Erfassung",
    "Zeit / Dauer",
    "Status",
    "",
  ].forEach((s) => head.append(el("th", {}, s)));
  table.append(el("thead", {}, head));
  const body = el("tbody");
  for (const row of rows) {
    const tr = el("tr");
    if (review)
      tr.append(
        el(
          "td",
          {},
          input("selected", "checkbox", row.id, {
            class: "review-check",
            disabled: row.status !== "PENDING",
            "aria-label": "Eintrag von " + row.display_name + " auswählen",
          }),
        ),
      );
    tr.append(
      el(
        "td",
        {},
        el("strong", {}, row.display_name || me.display_name),
        el("span", {}, row.activity_name),
        el("small", {}, row.description),
      ),
      el("td", {}, row.team_name || "Ohne Team"),
      el(
        "td",
        {},
        el("strong", {}, row.ended_at ? hours(duration(row)) : "Läuft"),
        el("small", {}, date(row.started_at)),
        el(
          "small",
          {},
          time(row.started_at) +
            " – " +
            (row.ended_at ? datetime(row.ended_at) : "offen"),
        ),
      ),
      el(
        "td",
        {},
        el("span", { class: "badge " + row.status }, statusLabel(row)),
        row.reason ? el("small", {}, row.reason) : null,
      ),
    );
    const actions = el(
      "td",
      {},
      btn("Details", () => detailDialog(row), "secondary small"),
    );
    if (edit && (isAdmin() || row.user_id === me.id))
      actions.append(
        document.createTextNode(" "),
        btn("Ändern", () => entryDialog(row), "secondary small"),
      );
    tr.append(actions);
    body.append(tr);
  }
  table.append(body);
  return el("div", { class: "table-wrap" }, table);
}
function entryDialog(row = null) {
  const form = el("form", { class: "form-grid" });
  assignmentFields(form, row || {}, true);
  const desc = form.lastElementChild;
  desc.classList.add("full");
  const running = row?.status === "RUNNING";
  if (!running) {
    const today = local(Date.now() + serverOffset).slice(0, 10);
    for (const [name, label, value] of [
      ["start", "Beginn", row?.started_at],
      ["end", "Ende", row?.ended_at],
    ]) {
      const stamp = value ? local(value) : "";
      form.append(
        el(
          "section",
          { class: "datetime-section" },
          el("h3", {}, label),
          el(
            "div",
            { class: "datetime-pair" },
            field(
              "Datum",
              dateInput(name + "Date", stamp.slice(0, 10) || today, true),
            ),
            field(
              "Uhrzeit",
              input(name + "Time", "time", stamp.slice(11, 16), {
                required: true,
                step: 60,
                title: "HH:MM",
              }),
            ),
          ),
          el(
            "details",
            { class: "offset-details" },
            el("summary", {}, "Sommer-/Winterzeit"),
            field(
              "UTC-Offset",
              select(
                name + "Offset",
                [
                  ["", "Automatisch"],
                  ["+02:00", "+02:00 · Sommerzeit"],
                  ["+01:00", "+01:00 · Winterzeit"],
                ],
                value ? offset(value) : "",
              ),
            ),
          ),
        ),
      );
    }
  }
  const submit = el(
    "button",
    { type: "submit" },
    row ? "Änderung speichern" : "Zeit einreichen",
  );
  form.append(
    el(
      "p",
      { class: "help full" },
      "Einträge können über Mitternacht gehen. Bei der doppelten Stunde im Herbst bitte den passenden UTC-Offset wählen. Änderungen setzen geprüfte Einträge zurück auf „Wartet auf Prüfung“.",
    ),
    submit,
  );
  const key = crypto.randomUUID();
  form.onsubmit = (e) => {
    e.preventDefault();
    busy(submit, async () => {
      const values = vals(form);
      if (!running) {
        for (const name of ["start", "end"]) {
          values[name] =
            isoDate(values[name + "Date"]) + "T" + values[name + "Time"];
          // A description-only edit must retain the stored timer seconds.
          const original = row?.[name === "start" ? "started_at" : "ended_at"];
          if (original && values[name] === local(original).slice(0, 16))
            values[name] = local(original);
          delete values[name + "Date"];
          delete values[name + "Time"];
        }
      }
      await api("/time/entries" + (row ? "/" + row.id : ""), {
        method: row ? "PUT" : "POST",
        body: { ...values, requestKey: key, version: row?.version },
      });
      modal.close();
      notify("Eintrag gespeichert.");
      await render();
    });
  };
  showDialog(
    el(
      "div",
      {},
      el("h2", {}, row ? "Zeiteintrag bearbeiten" : "Zeit nachtragen"),
      form,
    ),
  );
}
function detailDialog(row) {
  const box = el(
    "div",
    {},
    el("h2", {}, "Eintrag im Detail"),
    el("span", { class: "badge " + row.status }, statusLabel(row)),
    el(
      "p",
      {},
      row.display_name +
        " · " +
        (row.team_name || "Ohne Team") +
        " · " +
        row.activity_name,
    ),
    el("p", { class: "detail" }, row.description),
    el(
      "p",
      {},
      datetime(row.started_at) +
        " bis " +
        (row.ended_at ? datetime(row.ended_at) : "läuft"),
    ),
    el(
      "p",
      {},
      row.ended_at
        ? "Dauer: " + hours(duration(row))
        : "Noch keine abgeschlossene Arbeitszeit.",
    ),
  );
  if (row.decided_at)
    box.append(
      el(
        "p",
        {},
        "Geprüft von " + row.reviewer_name + " am " + datetime(row.decided_at),
      ),
      ...(row.reason
        ? [el("p", { class: "detail" }, "Begründung: " + row.reason)]
        : []),
    );
  if (isAdmin()) {
    const history = el("section", { class: "entry-history", hidden: true });
    let loaded = false,
      loading = false;
    const toggle = btn(
      "Änderungshistorie anzeigen",
      async (e) => {
        if (loading) return;
        if (!loaded) {
          loading = true;
          await busy(e.currentTarget, async () => {
            const events = await api("/time/entries/" + row.id + "/history");
            history.replaceChildren(auditList(events));
            loaded = true;
          });
          loading = false;
          if (!loaded) return;
        }
        history.hidden = !history.hidden;
        toggle.textContent = history.hidden
          ? "Änderungshistorie anzeigen"
          : "Änderungshistorie ausblenden";
        toggle.setAttribute("aria-expanded", String(!history.hidden));
      },
      "secondary history-toggle",
    );
    toggle.setAttribute("aria-expanded", "false");
    box.append(toggle, history);
  }
  showDialog(box);
}
function chart(groups) {
  if (!groups.length)
    return el(
      "div",
      { class: "empty" },
      "Noch keine Stunden in dieser Auswahl.",
    );
  const max = Math.max(1, ...groups.map((g) => g.durationMillis));
  const wrap = el("div", {
    role: "img",
    "aria-label": groups
      .map((g) => g.name + ": " + hours(g.durationMillis))
      .join(", "),
  });
  groups.forEach((g) => {
    const bar = el("div", { class: "bar" });
    bar.style.width = (g.durationMillis / max) * 100 + "%";
    if (g.color && /^#[0-9a-f]{6}$/i.test(g.color))
      bar.style.backgroundColor = g.color;
    wrap.append(
      el(
        "div",
        { class: "bar-row" },
        el("span", {}, g.name),
        el("div", { class: "track" }, bar),
        el("strong", {}, hours(g.durationMillis)),
      ),
    );
  });
  return el(
    "div",
    {},
    wrap,
    el(
      "p",
      { class: "text-breakdown" },
      groups.map((g) => g.name + ": " + hours(g.durationMillis)).join(" · "),
    ),
  );
}
function europeanDate(value) {
  if (!value) return "";
  return value.split("-").reverse().join(".");
}
function isoDate(value) {
  if (!value.trim()) return "";
  const match = /^(\d{2})\.(\d{2})\.(\d{4})$/.exec(value.trim());
  if (!match) throw Error("Datum bitte als TT.MM.JJJJ eingeben.");
  const [, day, month, year] = match;
  const check = new Date(
    Date.UTC(Number(year), Number(month) - 1, Number(day)),
  );
  if (
    Number(year) < 1900 ||
    check.getUTCFullYear() !== Number(year) ||
    check.getUTCMonth() !== Number(month) - 1 ||
    check.getUTCDate() !== Number(day)
  )
    throw Error("Dieses Datum gibt es nicht. Bitte TT.MM.JJJJ prüfen.");
  return year + "-" + month + "-" + day;
}
function dateInput(name, isoValue, required = false) {
  const control = input(name, "text", europeanDate(isoValue), {
    required,
    placeholder: "TT.MM.JJJJ",
    inputMode: "text",
    maxLength: 10,
    autocomplete: "off",
    class: "european-date",
    "aria-label": "Datum im Format TT.MM.JJJJ",
  });
  const validate = () => {
    try {
      isoDate(control.value);
      control.setCustomValidity("");
    } catch (error) {
      control.setCustomValidity(error.message);
    }
  };
  control.addEventListener("input", validate);
  control.addEventListener("blur", () => {
    validate();
    if (control.validity.valid && control.value.trim())
      control.value = europeanDate(isoDate(control.value));
  });
  return control;
}
function defaultFilters(status) {
  const today = new Intl.DateTimeFormat("sv-SE", {
    timeZone: "Europe/Vienna",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(Date.now());
  return { status, from: today, to: today };
}
function dateFilter(name, value, label) {
  const control = dateInput(name, value || "");
  const clear = btn(
    "×",
    () => {
      control.value = "";
      control.dispatchEvent(new Event("input", { bubbles: true }));
    },
    "date-clear",
  );
  clear.setAttribute("aria-label", label + " löschen");
  return field(label, el("div", { class: "date-control" }, control, clear));
}
function filterForm(review) {
  const f = filterValues,
    form = el("form", { class: "filters" });
  form.append(
    el(
      "details",
      { class: "filter-intro full" },
      el(
        "summary",
        {},
        "Nur passende Einträge – alle Filter gelten gleichzeitig",
      ),
      el(
        "p",
        { class: "help" },
        "Zeitraum UND Personen UND Team UND Tätigkeit UND Status. Bei mehreren Personen genügt eine der ausgewählten Personen. Es werden nur passende Einträge zurückgegeben, niemals automatisch alle Zeiten einer Person.",
      ),
    ),
    el(
      "div",
      { class: "filter-dates" },
      dateFilter("from", f.from, "Von"),
      dateFilter("to", f.to, "Bis einschließlich"),
    ),
    field(
      "Status",
      select(
        "status",
        [
          ["ALL", "Alle Status"],
          ["APPROVED", "Genehmigt / direkt erfasst"],
          ["PENDING", "Wartet auf Prüfung"],
          ["DENIED", "Abgelehnt"],
          ["RUNNING", "Laufende Timer"],
        ],
        f.status || (review ? "PENDING" : "APPROVED"),
      ),
    ),
    field(
      "Team bei Erfassung",
      select(
        "team",
        [
          ["", "Alle Teams"],
          ...catalog.teams.map((x) => [
            x.id,
            x.name + (Number(x.active) ? "" : " · archiviert"),
          ]),
        ],
        f.team || "",
      ),
    ),
    field(
      "Tätigkeit / Projekt",
      select(
        "activity",
        [
          ["", "Alle Tätigkeiten"],
          ...catalog.activities.map((x) => [x.id, x.name]),
        ],
        f.activity || "",
      ),
    ),
    field(
      "Personen",
      select(
        "person",
        catalog.people.map((x) => [x.id, x.display_name]),
        "",
        { multiple: true, selectedValues: f.people || [] },
      ),
    ),
  );
  const submit = el("button", { type: "submit" }, "Filter anwenden");
  const draft = el(
    "span",
    { class: "filter-draft", role: "status" },
    "Angezeigte Ergebnisse entsprechen den angewendeten Filtern.",
  );
  form.append(
    el(
      "div",
      { class: "actions full" },
      submit,
      btn(
        "Zurücksetzen",
        () => {
          filterValues = defaultFilters(review ? "PENDING" : "APPROVED");
          render();
        },
        "secondary",
      ),
      draft,
    ),
  );
  form.addEventListener(
    "input",
    () =>
      (draft.textContent =
        "Auswahl geändert – mit „Filter anwenden“ aktualisieren."),
  );
  form.addEventListener(
    "change",
    () =>
      (draft.textContent =
        "Auswahl geändert – mit „Filter anwenden“ aktualisieren."),
  );
  form.onsubmit = (e) => {
    e.preventDefault();
    filterValues = {
      ...vals(form),
      from: isoDate(vals(form).from),
      to: isoDate(vals(form).to),
      people: new FormData(form).getAll("person"),
    };
    render();
  };
  return form;
}
function query() {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(filterValues)) {
    if (k === "people") v.forEach((id) => p.append("person", id));
    else if (v) p.set(k, v);
  }
  return p.toString();
}
function metric(label, value, note) {
  return el(
    "div",
    { class: "card metric" },
    el("span", {}, label),
    el("strong", {}, value),
    note ? el("small", { class: "help" }, note) : null,
  );
}
function statusSummary(data) {
  const wrap = el("div", { class: "status-metrics" });
  for (const item of data.statuses) {
    wrap.append(
      el(
        "div",
        { class: "status-metric " + item.id },
        el("span", { class: "status-dot", "aria-hidden": "true" }),
        el(
          "div",
          {},
          el(
            "span",
            { class: "help" },
            item.id === "APPROVED"
              ? "Genehmigt / direkt erfasst"
              : statuses[item.id],
          ),
          el(
            "strong",
            {},
            item.id === "RUNNING"
              ? item.count + " Timer"
              : hours(item.durationMillis),
          ),
          el(
            "small",
            { class: "help" },
            item.id === "RUNNING"
              ? "ohne abgeschlossene Dauer"
              : item.count + " Einträge",
          ),
        ),
      ),
    );
  }
  return wrap;
}
function svgNode(tag, attrs = {}, ...children) {
  const n = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [k, v] of Object.entries(attrs)) n.setAttribute(k, v);
  for (const c of children)
    n.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return n;
}
function trendChart(points) {
  if (!points.length)
    return el(
      "div",
      { class: "empty" },
      "Noch keine abgeschlossenen Einträge für diesen Zeitraum.",
    );
  const width =
      window.innerWidth < 760 ? Math.max(300, window.innerWidth - 80) : 640,
    height = 190,
    left = 42,
    bottom = 28,
    max = Math.max(3600000, ...points.map((p) => p.durationMillis));
  const x = (i) =>
      left + (i * (width - left - 15)) / Math.max(1, points.length - 1),
    y = (v) => height - bottom - (v / max) * (height - bottom - 18);
  const svg = svgNode("svg", {
    viewBox: `0 0 ${width} ${height}`,
    class: "trend-svg",
    role: "img",
    "aria-label":
      "Zeitverlauf in Stunden; genaue Werte stehen unter dem Diagramm.",
  });
  for (let i = 0; i <= 3; i++) {
    const value = (max * i) / 3;
    svg.append(
      svgNode("line", {
        x1: left,
        y1: y(value),
        x2: width - 10,
        y2: y(value),
        class: "chart-grid",
      }),
      svgNode(
        "text",
        {
          x: left - 7,
          y: y(value) + 4,
          "text-anchor": "end",
          class: "chart-label",
        },
        (value / 3600000).toLocaleString("de-AT", {
          maximumFractionDigits: 1,
        }) + " h",
      ),
    );
  }
  const path = points
    .map((p, i) => (i ? "L" : "M") + x(i) + "," + y(p.durationMillis))
    .join(" ");
  svg.append(
    svgNode("path", {
      d:
        path +
        ` L${x(points.length - 1)},${height - bottom} L${left},${height - bottom} Z`,
      class: "chart-area",
    }),
    svgNode("path", { d: path, class: "chart-line" }),
  );
  if (points.length < 65)
    points.forEach((p, i) =>
      svg.append(
        svgNode("circle", {
          cx: x(i),
          cy: y(p.durationMillis),
          r: 4,
          class: "chart-point",
        }),
      ),
    );
  svg.append(
    svgNode(
      "text",
      { x: left, y: height - 5, class: "chart-label" },
      points[0].name,
    ),
    svgNode(
      "text",
      {
        x: width - 10,
        y: height - 5,
        "text-anchor": "end",
        class: "chart-label",
      },
      points.at(-1).name,
    ),
  );
  const tooltip = el("output", {
    class: "chart-tooltip",
    "aria-live": "polite",
    hidden: true,
  });
  const guide = svgNode("line", {
    y1: 12,
    y2: height - bottom,
    class: "chart-cursor",
    visibility: "hidden",
  });
  const marker = svgNode("circle", {
    r: 6,
    class: "chart-point chart-selected",
    visibility: "hidden",
  });
  svg.append(guide, marker);
  let selected = 0,
    pointerInteraction = false;
  const reveal = (i) => {
    selected = Math.max(0, Math.min(points.length - 1, i));
    const p = points[selected];
    tooltip.textContent =
      europeanDate(p.name) + " · " + hours(p.durationMillis);
    tooltip.hidden = false;
    guide.setAttribute("x1", x(selected));
    guide.setAttribute("x2", x(selected));
    guide.setAttribute("visibility", "visible");
    marker.setAttribute("cx", x(selected));
    marker.setAttribute("cy", y(p.durationMillis));
    marker.setAttribute("visibility", "visible");
  };
  svg.setAttribute("tabindex", "0");
  svg.setAttribute("role", "group");
  svg.setAttribute(
    "aria-label",
    "Zeitverlauf. Mit Pfeiltasten Werte erkunden oder über das Diagramm wischen.",
  );
  const pointer = (e) => {
    pointerInteraction = true;
    const point = svg.createSVGPoint();
    point.x = e.clientX;
    point.y = e.clientY;
    const px = point.matrixTransform(svg.getScreenCTM().inverse()).x;
    reveal(
      Math.round(((px - left) / (width - left - 15)) * (points.length - 1)),
    );
    const containerBounds = container.getBoundingClientRect();
    tooltip.style.left =
      Math.max(
        0,
        Math.min(
          e.clientX - containerBounds.left + 12,
          containerBounds.width - tooltip.offsetWidth,
        ),
      ) + "px";
    tooltip.style.top =
      Math.max(0, e.clientY - containerBounds.top - tooltip.offsetHeight - 12) +
      "px";
  };
  svg.addEventListener("pointermove", pointer);
  svg.addEventListener("pointerdown", (e) => {
    svg.setPointerCapture(e.pointerId);
    pointer(e);
  });
  const hideTooltip = () => {
    pointerInteraction = false;
    tooltip.hidden = true;
    guide.setAttribute("visibility", "hidden");
    marker.setAttribute("visibility", "hidden");
  };
  svg.addEventListener("pointerleave", hideTooltip);
  svg.addEventListener("pointercancel", hideTooltip);
  svg.addEventListener("blur", hideTooltip);
  const keyboardReveal = () => {
    reveal(selected);
    tooltip.style.left = "12px";
    tooltip.style.top = "8px";
  };
  svg.addEventListener("focus", () => {
    if (!pointerInteraction) keyboardReveal();
  });
  svg.addEventListener("keydown", (e) => {
    if (["ArrowLeft", "ArrowRight", "Home", "End"].includes(e.key)) {
      e.preventDefault();
      pointerInteraction = false;
      reveal(
        e.key === "Home"
          ? 0
          : e.key === "End"
            ? points.length - 1
            : selected + (e.key === "ArrowRight" ? 1 : -1),
      );
      keyboardReveal();
    }
  });
  const container = el(
    "div",
    { class: "trend-container" },
    tooltip,
    svg,
    el(
      "details",
      { class: "text-breakdown" },
      el("summary", {}, "Textliche Aufschlüsselung nach Startdatum"),
      el(
        "p",
        {},
        points.map((p) => p.name + ": " + hours(p.durationMillis)).join(" · "),
      ),
    ),
  );
  return container;
}
function donutChart(groups) {
  const nonzero = groups.filter((g) => g.durationMillis > 0),
    total = nonzero.reduce((n, g) => n + g.durationMillis, 0);
  if (!total)
    return el(
      "div",
      { class: "empty" },
      "Noch keine Stunden in dieser Auswahl.",
    );
  const colors = [
    "#9761da",
    "#df75b0",
    "#5e96da",
    "#54a98a",
    "#de9c58",
    "#8a8fb9",
  ];
  const svg = svgNode("svg", {
    viewBox: "0 0 180 180",
    class: "donut-svg",
    role: "img",
    "aria-label": nonzero
      .map((g) => g.name + ": " + hours(g.durationMillis))
      .join(", "),
  });
  let offset = 0;
  nonzero.forEach((g, i) => {
    const share = (g.durationMillis / total) * 100;
    svg.append(
      svgNode("circle", {
        cx: 90,
        cy: 90,
        r: 66,
        fill: "none",
        stroke: g.color || colors[i % colors.length],
        "stroke-width": 22,
        pathLength: 100,
        "stroke-dasharray": `${share} ${100 - share}`,
        "stroke-dashoffset": -offset,
        transform: "rotate(-90 90 90)",
      }),
    );
    offset += share;
  });
  svg.append(
    svgNode(
      "text",
      { x: 90, y: 88, "text-anchor": "middle", class: "donut-total" },
      hours(total),
    ),
    svgNode(
      "text",
      { x: 90, y: 108, "text-anchor": "middle", class: "chart-label" },
      "Auswahl gesamt",
    ),
  );
  const legend = el("div", { class: "donut-legend" });
  for (const g of nonzero)
    legend.append(
      el(
        "div",
        {},
        el("strong", {}, g.name),
        el(
          "span",
          {},
          hours(g.durationMillis) +
            " · " +
            Math.round((g.durationMillis / total) * 100) +
            " %",
        ),
      ),
    );
  return el(
    "div",
    {},
    el("div", { class: "donut-layout" }, svg, legend),
    el(
      "p",
      { class: "text-breakdown" },
      nonzero.map((g) => g.name + ": " + hours(g.durationMillis)).join(" · "),
    ),
  );
}
function statisticsPanels(data) {
  return el(
    "div",
    { class: "statistics-grid" },
    el(
      "section",
      { class: "card chart-wide" },
      el(
        "div",
        { class: "heading" },
        el("h2", {}, "Euer Zeitverlauf"),
        el("span", { class: "pill" }, "Stunden nach Startdatum"),
      ),
      trendChart(data.daily),
      el(
        "p",
        { class: "help" },
        "Jeder Punkt fasst passende Einträge nach ihrem Wiener Startdatum zusammen. Einträge über Mitternacht zählen zum Starttag.",
      ),
    ),
    el(
      "section",
      { class: "card" },
      el("h2", {}, "Woran gearbeitet wurde"),
      donutChart(data.activities),
    ),
    el(
      "section",
      { class: "card" },
      el("h2", {}, "Beiträge nach Personen"),
      chart(data.people),
    ),
    el(
      "section",
      { class: "card" },
      el("h2", {}, "Zeit nach Team"),
      chart(data.teams),
    ),
    el(
      "section",
      { class: "card" },
      el("h2", {}, "Verteilung über die Woche"),
      chart(data.weekdays),
    ),
  );
}
async function overviewPage(area, review) {
  const data = await api("/time/overview?" + query());
  overview = { ...data, review };
  currentRows = data.entries;
  area.className = review ? "review-page" : "overview-page";
  area.replaceChildren(
    title(
      review ? "Freigabezentrale" : "Dein Projekt in Zahlen",
      review ? "Zeiten prüfen" : "Zeitübersicht",
      review
        ? "Einträge auswählen, prüfen und freigeben – alles an einem Ort."
        : "Arbeitszeit, Trends und Beiträge deines Projekts im Überblick.",
      btn(
        "↓ CSV exportieren",
        () => {
          location.href = "/api/time/export?" + query();
        },
        "secondary",
      ),
    ),
    review
      ? el(
          "details",
          { class: "card filter-card review-filters" },
          el(
            "summary",
            {},
            "Prüfliste filtern · Zeitraum, Personen und Status",
          ),
          filterForm(review),
        )
      : el("section", { class: "card filter-card" }, filterForm(review)),
  );
  const active = el(
    "div",
    { class: "active-filter-card" },
    el("strong", {}, "Angewendete Filter"),
    el(
      "div",
      { class: "status-line" },
      el(
        "span",
        { class: "pill" },
        filterValues.status === "ALL"
          ? "Alle Status"
          : filterValues.status === "APPROVED"
            ? "Genehmigt / direkt erfasst"
            : statuses[filterValues.status || "APPROVED"],
      ),
      el(
        "span",
        { class: "pill" },
        (europeanDate(filterValues.from) || "Anfang") +
          " – " +
          (europeanDate(filterValues.to) || "heute"),
      ),
      el(
        "span",
        { class: "pill" },
        "Personen: " +
          ((filterValues.people || []).length
            ? catalog.people
                .filter((p) => filterValues.people.includes(p.id))
                .map((p) => p.display_name)
                .join(", ")
            : "alle"),
      ),
      el(
        "span",
        { class: "pill" },
        "Team: " +
          (catalog.teams.find((t) => t.id === filterValues.team)?.name ||
            "alle"),
      ),
      el(
        "span",
        { class: "pill" },
        "Tätigkeit: " +
          (catalog.activities.find((a) => a.id === filterValues.activity)
            ?.name || "alle"),
      ),
    ),
  );
  area.append(active);
  if (data.status === "ALL")
    area.append(
      el(
        "p",
        { class: "info-box" },
        "Alle Status: Die erfasste Gesamtzeit enthält auch wartende und abgelehnte Einträge. Die gültigen Stunden stehen separat unter „Genehmigt / direkt erfasst“. Laufende Timer sind sichtbar, zählen aber noch nicht zur Dauer.",
      ),
    );
  if (!review)
    area.append(
      statusSummary(data),
      el(
        "div",
        { class: "grid overview-metrics" },
        metric(
          "Erfasste Zeit dieser Auswahl",
          hours(data.durationMillis),
          data.status === "ALL"
            ? "Alle abgeschlossenen Status, einschließlich abgelehnter Zeiten"
            : "Status: " +
                (data.status === "APPROVED"
                  ? "Genehmigt / direkt erfasst"
                  : statuses[data.status]),
        ),
        metric(
          "Abgeschlossene Einträge",
          data.completedCount,
          "Ø " + hours(data.averageMillis) + " pro Eintrag",
        ),
        metric(
          "Beteiligte Personen",
          data.activePeople,
          "Nur Personen mit passenden Einträgen",
        ),
      ),
      statisticsPanels(data),
    );
  const entries = el(
    "section",
    { class: "card" },
    el("h2", {}, "Passende Einträge (" + data.entries.length + ")"),
    entryTable(data.entries, { edit: isAdmin(), review }),
  );
  if (review)
    entries.append(
      el(
        "p",
        { class: "help" },
        "Nur angekreuzte wartende Einträge werden entschieden. Admins und Owner dürfen auch eigene Zeiten bestätigen. Jede Aktualisierung löscht die Auswahl.",
      ),
      el(
        "div",
        { class: "actions" },
        btn("Auswahl genehmigen", () => decisionDialog("APPROVED")),
        btn("Auswahl ablehnen", () => decisionDialog("DENIED"), "danger"),
      ),
    );
  if (review) {
    area.append(
      el(
        "div",
        { class: "review-banner" },
        el(
          "div",
          {},
          el(
            "strong",
            {},
            data.entries.filter((x) => x.status === "PENDING").length +
              " Einträge zur Prüfung",
          ),
          el("p", {}, "Nur markierte Einträge werden entschieden."),
        ),
        navIcon("review"),
      ),
    );
  }
  area.append(entries);
}
function decisionDialog(status) {
  const ids = [...document.querySelectorAll(".review-check:checked")].map(
    (x) => x.value,
  );
  if (!ids.length) {
    notify("Bitte zuerst Einträge auswählen.", true);
    return;
  }
  const form = el("form");
  form.append(
    el(
      "p",
      {},
      ids.length +
        " ausdrücklich ausgewählte Einträge " +
        (status === "APPROVED" ? "genehmigen" : "ablehnen") +
        "?",
    ),
  );
  if (status === "DENIED")
    form.append(
      field(
        "Begründung",
        el("textarea", {
          name: "reason",
          required: true,
          minLength: 3,
          maxLength: 1000,
        }),
      ),
    );
  const submit = el("button", { type: "submit" }, "Entscheidung speichern");
  form.append(el("div", { class: "actions" }, submit));
  form.onsubmit = (e) => {
    e.preventDefault();
    busy(submit, async () => {
      const versions = {};
      currentRows
        .filter((x) => ids.includes(x.id))
        .forEach((x) => (versions[x.id] = x.version));
      await api("/time/decisions", {
        method: "POST",
        body: { ids, versions, status, reason: vals(form).reason },
      });
      modal.close();
      notify("Entscheidung gespeichert.");
      await render();
    });
  };
  showDialog(el("div", {}, el("h2", {}, "Ausgewählte Einträge prüfen"), form));
}
async function members(area) {
  const rows = await api("/admin/members"),
    invites = await api("/auth/invitations");
  const table = el(
    "table",
    {},
    el(
      "thead",
      {},
      el(
        "tr",
        {},
        ...["Mitglied", "Stammteam", "Rolle", "Status", ""].map((x) =>
          el("th", {}, x),
        ),
      ),
    ),
  );
  const body = el("tbody");
  for (const m of rows)
    body.append(
      el(
        "tr",
        {},
        el(
          "td",
          {},
          el("strong", {}, m.display_name),
          el("small", {}, m.school_code || "Schulkürzel noch ergänzen"),
          el("small", {}, m.email),
        ),
        el("td", {}, memberQuickSelect(m, "teamId")),
        el("td", {}, memberQuickSelect(m, "role")),
        el("td", {}, Number(m.active) ? "Aktiv" : "Deaktiviert"),
        el(
          "td",
          {},
          btn("Bearbeiten", () => memberDialog(m), "secondary small"),
        ),
      ),
    );
  table.append(body);
  area.replaceChildren(
    title(
      "Das Team hinter dem Abend",
      "Mitglieder",
      "Neue Mitglieder starten ohne Team. Admins und Owner erstellen Teams und weisen sie hier zu. Historische Zeiteinträge behalten ihr bearbeitetes Team.",
      btn("+ Mitglied einladen", inviteDialog),
    ),
    el("section", { class: "card" }, el("div", { class: "table-wrap" }, table)),
  );
  const list = el("section", { class: "card" }, el("h2", {}, "Einladungen"));
  if (!invites.length)
    list.append(
      el("p", { class: "help" }, "Noch keine Einladungen versendet."),
    );
  for (const inv of invites) {
    const valid =
      !inv.used_at &&
      !inv.revoked_at &&
      inv.expires_at > Date.now() + serverOffset;
    list.append(
      el(
        "div",
        { class: "card" },
        el("strong", {}, inv.email),
        el(
          "p",
          { class: "help" },
          inv.used_at
            ? "Eingelöst"
            : inv.revoked_at
              ? "Widerrufen"
              : "Gültig bis " + datetime(inv.expires_at),
        ),
        el(
          "div",
          { class: "actions" },
          !inv.used_at
            ? btn(
                "Neu versenden",
                async (e) =>
                  busy(e.currentTarget, async () => {
                    await api("/auth/invite", {
                      method: "POST",
                      body: { email: inv.email },
                    });
                    notify(
                      "Neue Einladung versendet. Der alte Link ist ungültig.",
                    );
                    await render();
                  }),
                "secondary small",
              )
            : null,
          valid
            ? btn(
                "Widerrufen",
                async (e) =>
                  busy(e.currentTarget, async () => {
                    await api("/auth/invitations/" + inv.id + "/revoke", {
                      method: "POST",
                    });
                    await render();
                  }),
                "danger small",
              )
            : null,
        ),
      ),
    );
  }
  area.append(list);
}
function inviteDialog() {
  const form = el("form", { class: "form-grid" });
  form.append(
    field(
      "E-Mail-Adresse",
      input("email", "email", "", { required: true, maxLength: 254 }),
    ),
  );
  const submit = el("button", { type: "submit" }, "Einladung versenden");
  form.append(
    el(
      "p",
      { class: "help full" },
      "Der Link ist 48 Stunden gültig und kann einmal eingelöst werden. Beim erneuten Versand wird der alte Link ungültig.",
    ),
    submit,
  );
  form.onsubmit = (e) => {
    e.preventDefault();
    busy(submit, async () => {
      await api("/auth/invite", { method: "POST", body: vals(form) });
      modal.close();
      notify("Einladung versendet.");
      await render();
    });
  };
  showDialog(el("div", {}, el("h2", {}, "Ein Mitglied einladen"), form));
}
function memberQuickSelect(m, name) {
  if (name === "role" && me.role !== "OWNER")
    return el("span", {}, roles[m.role]);
  const control = select(
    name,
    name === "role"
      ? Object.entries(roles)
      : [
          ["", "Noch kein Team"],
          ...catalog.teams
            .filter((x) => Number(x.active) || x.id === m.team_id)
            .map((x) => [x.id, x.name]),
        ],
    name === "role" ? m.role : m.team_id || "",
  );
  const native = control.querySelector("select");
  control.classList.add("member-inline");
  const trigger = control.querySelector(".picker-trigger");
  trigger.setAttribute(
    "aria-label",
    (name === "role" ? "Rolle" : "Stammteam") +
      " von " +
      m.display_name +
      " ändern",
  );
  native.addEventListener("change", async () => {
    trigger.disabled = true;
    try {
      await api("/admin/members/" + m.id, {
        method: "PUT",
        body: {
          displayName: m.display_name,
          schoolCode: m.school_code,
          role: name === "role" ? control.value : m.role,
          teamId: name === "teamId" ? control.value : m.team_id || "",
          active: Boolean(Number(m.active)),
          version: m.version,
        },
      });
      notify(name === "role" ? "Rolle gespeichert." : "Stammteam gespeichert.");
      await render();
    } catch (error) {
      control.value = name === "role" ? m.role : m.team_id || "";
      notify(error.message, true);
    } finally {
      trigger.disabled = false;
    }
  });
  return control;
}
function memberDialog(m) {
  const form = el("form", { class: "form-grid" });
  form.append(
    field(
      "Anzeigename",
      input("displayName", "text", m.display_name, {
        required: true,
        minLength: 2,
        maxLength: 80,
      }),
    ),
    field(
      "Schulkürzel",
      input("schoolCode", "text", m.school_code || "", {
        required: true,
        minLength: 2,
        maxLength: 32,
        pattern: "[a-zA-Z0-9][a-zA-Z0-9._-]{1,31}",
      }),
    ),
    field(
      "Stammteam zuweisen",
      select(
        "teamId",
        [
          ["", "Noch kein Team"],
          ...catalog.teams
            .filter((x) => Number(x.active) || x.id === m.team_id)
            .map((x) => [x.id, x.name]),
        ],
        m.team_id || "",
      ),
    ),
    field(
      "Rolle",
      select("role", Object.entries(roles), m.role, {
        disabled: me.role !== "OWNER",
      }),
    ),
    field(
      "Kontostatus",
      select(
        "active",
        [
          ["true", "Aktiv"],
          ["false", "Deaktiviert"],
        ],
        String(Boolean(Number(m.active))),
      ),
    ),
  );
  const submit = el("button", { type: "submit" }, "Mitglied speichern");
  form.append(
    el(
      "p",
      { class: "help full" },
      "Rollenänderungen und Deaktivierungen beenden bestehende Sitzungen. Bei Deaktivierung wird ein laufender Timer jetzt als wartender Eintrag abgeschlossen. Der letzte aktive Owner bleibt geschützt.",
    ),
    submit,
  );
  form.onsubmit = (e) => {
    e.preventDefault();
    busy(submit, async () => {
      const b = vals(form);
      await api("/admin/members/" + m.id, {
        method: "PUT",
        body: {
          ...b,
          role: b.role || m.role,
          active: b.active === "true",
          version: m.version,
        },
      });
      modal.close();
      notify("Mitglied gespeichert.");
      await render();
    });
  };
  showDialog(el("div", {}, el("h2", {}, "Mitglied bearbeiten"), form));
}
function catalogPage(area) {
  area.replaceChildren(
    title(
      "Ordnung für euren Einsatz",
      "Teams & Tätigkeiten",
      "Admins weisen jedem Mitglied ein festes Team zu. Tätigkeiten beschreiben, woran gearbeitet wird, und können bei der Erfassung neu eingegeben werden.",
    ),
  );
  for (const [kind, label] of [
    ["teams", "Teams"],
    ["activities", "Tätigkeiten / Projekte"],
  ]) {
    const section = el(
      "section",
      { class: "card" },
      el(
        "div",
        { class: "heading" },
        el("h2", {}, label),
        btn("+ Anlegen", () => catalogDialog(kind), "secondary"),
      ),
    );
    const table = el("table");
    for (const row of catalog[kind])
      table.append(
        el(
          "tr",
          {},
          el("td", {}, el("strong", {}, row.name)),
          ...(kind === "activities"
            ? [el("td", {}, colorSwatch(row.color))]
            : []),
          el("td", {}, Number(row.active) ? "Aktiv" : "Archiviert"),
          el(
            "td",
            {},
            btn(
              "Bearbeiten",
              () => catalogDialog(kind, row),
              "secondary small",
            ),
          ),
        ),
      );
    section.append(table);
    area.append(section);
  }
}
function catalogDialog(kind, row = null) {
  const form = el("form", { class: "form-grid" });
  form.append(
    field(
      "Name",
      input("name", "text", row?.name || "", {
        required: true,
        minLength: 2,
        maxLength: 100,
      }),
    ),
  );
  if (kind === "activities")
    form.append(
      field("Farbe", input("color", "color", row?.color || "#54785b")),
    );
  if (row)
    form.append(
      field(
        "Status",
        select(
          "active",
          [
            ["true", "Aktiv"],
            ["false", "Archiviert"],
          ],
          String(Boolean(Number(row.active))),
        ),
      ),
    );
  const submit = el("button", { type: "submit" }, "Speichern");
  form.append(submit);
  form.onsubmit = (e) => {
    e.preventDefault();
    busy(submit, async () => {
      const b = vals(form);
      await api("/admin/" + kind + (row ? "/" + row.id : ""), {
        method: row ? "PUT" : "POST",
        body: { ...b, active: b.active !== "false", version: row?.version },
      });
      modal.close();
      notify("Gespeichert.");
      await render();
    });
  };
  showDialog(
    el(
      "div",
      {},
      el(
        "h2",
        {},
        (row ? "Bearbeiten: " : "Anlegen: ") +
          (kind === "teams" ? "Team" : "Tätigkeit / Projekt"),
      ),
      form,
    ),
  );
}
function account(area) {
  const form = el("form", { class: "form-grid" });
  form.append(
    field(
      "Aktuelles Passwort",
      input("currentPassword", "password", "", {
        required: true,
        autocomplete: "current-password",
      }),
    ),
    field(
      "Neues Passwort",
      input("newPassword", "password", "", {
        required: true,
        minLength: 12,
        autocomplete: "new-password",
      }),
    ),
  );
  const submit = el("button", { type: "submit" }, "Passwort ändern");
  form.append(
    el(
      "p",
      { class: "help full" },
      "Verwende NICHT dein Schulpasswort, sondern ein eigenes Time Tracker-Passwort. Mindestens 12 Zeichen und höchstens 72 UTF-8-Bytes. Danach werden alle deine Sitzungen beendet.",
    ),
    submit,
  );
  form.onsubmit = (e) => {
    e.preventDefault();
    busy(submit, async () => {
      const result = await api("/auth/password", {
        method: "POST",
        body: vals(form),
      });
      me = null;
      auth();
      notify(result.message);
    });
  };
  area.replaceChildren(
    title(
      "Dein persönlicher Zugang",
      "Einstellungen",
      "Angemeldet als " + me.display_name + " · " + roles[me.role],
    ),
    appearanceSettings(),
    ...(me.role === "OWNER" ? [projectSettings()] : []),
    el("section", { class: "card" }, el("h2", {}, "Passwort ändern"), form),
  );
}
const actionLabels = {
  PROJECT_UPDATED: "Projektvorlage geändert",
  ACTIVITY_DISCOVERED: "Tätigkeit angelegt",
  TIMER_STARTED: "Timer gestartet",
  TIMER_STOPPED: "Timer abgeschlossen",
  TIMER_STOPPED_ON_DEACTIVATION: "Timer bei Deaktivierung abgeschlossen",
  ENTRY_CREATED: "Zeit eingereicht",
  ENTRY_EDITED: "Zeit geändert",
  ENTRY_REVIEWED: "Zeit geprüft",
  MEMBER_UPDATED: "Mitglied geändert",
  CATALOG_CREATED: "Team / Aufgabe angelegt",
  CATALOG_UPDATED: "Team / Aufgabe geändert",
  INVITE_SENT: "Einladung versendet",
  INVITE_REVOKED: "Einladung widerrufen",
  INVITE_ACCEPTED: "Einladung eingelöst",
  BOOTSTRAP: "Ersteinrichtung",
  PASSWORD_CHANGED: "Passwort geändert",
  PASSWORD_RESET: "Passwort zurückgesetzt",
  RESET_SENT: "Passwort-Link angefordert",
};
function auditList(events) {
  const list = el("div");
  if (!events.length)
    list.append(el("p", { class: "empty" }, "Keine Änderungen vorhanden."));
  for (const e of events) {
    let details = e.details;
    try {
      details = JSON.stringify(JSON.parse(details), null, 2);
    } catch {}
    list.append(
      el(
        "details",
        { class: "card" },
        el(
          "summary",
          {},
          datetime(e.created_at) +
            " · " +
            (e.actor_name || "System") +
            " · " +
            (actionLabels[e.action] || e.action),
        ),
        el("pre", { class: "detail" }, details),
      ),
    );
  }
  return list;
}
async function historyPage(area) {
  area.replaceChildren(
    title(
      "Nachvollziehbar bis ins Detail",
      "Änderungshistorie",
      "Die letzten 300 Aktionen. Die vollständige Historie eines Zeiteintrags findest du in seinen Details.",
    ),
    auditList(await api("/admin/history")),
  );
}
window.addEventListener("unhandledrejection", (e) => {
  e.preventDefault();
  notify(e.reason?.message || "Ein Fehler ist aufgetreten.", true);
});
(async () => {
  const hash = location.hash.slice(1),
    [kind, token] = hash.split("=");
  if (token && ["einladung", "passwort-reset"].includes(kind)) {
    history.replaceState(null, "", location.pathname);
    auth(kind === "einladung" ? "invite" : "reset", token);
  } else await initialize();
})();
