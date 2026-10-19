import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { describe, expect, it } from "vitest";
import { THEME_INIT_SCRIPT, THEME_KEY, resolveTheme } from "@/lib/theme";

/* ------------------------------------------------------------------ helpers */

const SRC = path.resolve(__dirname, "../src");
const CSS = fs.readFileSync(path.join(SRC, "app/globals.css"), "utf8");
const stripComments = (s: string) => s.replace(/\/\*[\s\S]*?\*\//g, "").replace(/(^|[^:])\/\/.*$/gm, "$1");

function tokens(block: RegExp): Record<string, string> {
  const m = stripComments(CSS).match(block);
  if (!m) throw new Error(`theme block not found: ${block}`);
  const out: Record<string, string> = {};
  for (const [, name, value] of m[1].matchAll(/--([\w-]+)\s*:\s*([^;]+);/g)) out[name] = value.trim();
  return out;
}
const DARK = tokens(/:root,\s*:root\[data-theme="dark"\]\s*\{([^}]*)\}/);
const LIGHT = tokens(/:root\[data-theme="light"\]\s*\{([^}]*)\}/);

function luminance(hex: string): number {
  const h = hex.replace("#", "");
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255).map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function contrast(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}
const isHex = (v: string) => /^#[0-9a-f]{6}$/i.test(v);

function sourceFiles(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const p = path.join(dir, e.name);
    return e.isDirectory() ? sourceFiles(p) : /\.(css|ts|tsx)$/.test(e.name) ? [p] : [];
  });
}

/* ------------------------------------------------------------------ theme choice */

describe("resolveTheme", () => {
  it("lets an explicit choice win, otherwise follows the system", () => {
    expect(resolveTheme("light", false)).toBe("light");
    expect(resolveTheme("dark", true)).toBe("dark");
    expect(resolveTheme(null, true)).toBe("light");
    expect(resolveTheme(undefined, false)).toBe("dark");
    expect(resolveTheme("rainbow", true)).toBe("light"); // junk in storage is ignored
  });
});

describe("pre-paint init script", () => {
  function run(stored: string | null, prefersLight: boolean, storageThrows = false): string | undefined {
    const documentElement = { dataset: {} as Record<string, string> };
    const sandbox = {
      localStorage: { getItem: (k: string) => { if (storageThrows) throw new Error("denied"); return k === THEME_KEY ? stored : null; } },
      window: { matchMedia: (q: string) => ({ matches: q.includes("light") && prefersLight }) },
      document: { documentElement },
    };
    vm.runInNewContext(THEME_INIT_SCRIPT, { ...sandbox, window: { ...sandbox.window, localStorage: sandbox.localStorage } });
    return documentElement.dataset.theme;
  }

  it("agrees with resolveTheme in every combination (so React and the first paint never disagree)", () => {
    for (const stored of [null, "light", "dark", "junk"]) {
      for (const prefersLight of [true, false]) {
        expect(run(stored, prefersLight), `stored=${stored} prefersLight=${prefersLight}`).toBe(resolveTheme(stored, prefersLight));
      }
    }
  });

  it("falls back to dark when storage is blocked", () => {
    expect(run(null, true, true)).toBe("dark");
  });
});

/* ------------------------------------------------------------------ palette rules */

describe("palette: soft shades only (no pitch black, no pure white)", () => {
  it("defines exactly the same tokens for both themes", () => {
    expect(Object.keys(LIGHT).sort()).toEqual(Object.keys(DARK).sort());
    expect(Object.keys(DARK).length).toBeGreaterThan(30);
  });

  it("keeps every colour token off the extremes (not black, not white, not near either)", () => {
    for (const [theme, set] of [["dark", DARK], ["light", LIGHT]] as const) {
      for (const [name, value] of Object.entries(set)) {
        if (!isHex(value)) continue;
        const l = luminance(value);
        expect(l, `${theme} --${name} ${value} is too close to pitch black`).toBeGreaterThanOrEqual(0.005);
        expect(l, `${theme} --${name} ${value} is too close to pure white`).toBeLessThanOrEqual(0.92);
      }
    }
  });

  it("dark backgrounds are charcoal and light backgrounds are cream", () => {
    expect(luminance(DARK.bg)).toBeLessThan(0.05);
    expect(luminance(LIGHT.bg)).toBeGreaterThan(0.7);
    // cream = warm: red >= green >= blue, with visible yellow bias
    const [r, g, b] = [1, 3, 5].map((i) => parseInt(LIGHT.bg.slice(i, i + 2), 16));
    expect(r).toBeGreaterThanOrEqual(g);
    expect(g).toBeGreaterThan(b + 8);
  });

  it("no source file hard-codes pure black or pure white", () => {
    const offenders: string[] = [];
    for (const file of sourceFiles(SRC)) {
      const code = stripComments(fs.readFileSync(file, "utf8"));
      const rel = path.relative(SRC, file);
      if (/#(?:000|fff)(?![0-9a-f])|#(?:000000|ffffff)(?![0-9a-f])/i.test(code)) offenders.push(`${rel}: pure black/white hex`);
      if (/rgba?\(\s*(?:0\s*,\s*0\s*,\s*0|255\s*,\s*255\s*,\s*255)\b/i.test(code)) offenders.push(`${rel}: pure black/white rgb()`);
      if (file.endsWith(".css") && /(?<![\w-])(?:white|black)(?![\w-])/i.test(code)) offenders.push(`${rel}: white/black keyword`);
    }
    expect(offenders).toEqual([]);
  });
});

describe("palette: readable in both themes (WCAG AA, 4.5:1)", () => {
  for (const [theme, t] of [["dark", DARK], ["light", LIGHT]] as const) {
    it(`${theme}: body and secondary text on every surface`, () => {
      for (const surface of ["bg", "panel", "panel-2", "inset"]) {
        expect(contrast(t.text, t[surface]), `text on ${surface}`).toBeGreaterThanOrEqual(7);
        expect(contrast(t.muted, t[surface]), `muted on ${surface}`).toBeGreaterThanOrEqual(4.5);
      }
      for (const surface of ["panel", "panel-2"]) expect(contrast(t["text-soft"], t[surface]), `text-soft on ${surface}`).toBeGreaterThanOrEqual(4.5);
    });

    it(`${theme}: status colours on their backgrounds and on panels`, () => {
      for (const k of ["ok", "bad", "warn", "busy"]) {
        expect(contrast(t[k], t[`${k}-bg`]), `${k} on ${k}-bg`).toBeGreaterThanOrEqual(4.5);
        expect(contrast(t[k], t.panel), `${k} on panel`).toBeGreaterThanOrEqual(4.5);
      }
    });

    it(`${theme}: text on accent, pills, badges, wire-log chips and the recommended row`, () => {
      expect(contrast(t["on-accent"], t.accent)).toBeGreaterThanOrEqual(4.5);
      expect(contrast(t.muted, t["neutral-bg"])).toBeGreaterThanOrEqual(4.5);
      expect(contrast(t.text, t["neutral-bg"])).toBeGreaterThanOrEqual(4.5);
      expect(contrast(t.text, t["rec-bg"])).toBeGreaterThanOrEqual(4.5);
      for (const k of ["k-req", "k-res", "k-sse", "k-del", "k-cli"]) expect(contrast(t.text, t[k]), `text on ${k}`).toBeGreaterThanOrEqual(4.5);
    });
  }

  it("agent tag colours stay legible with dark ink in both themes", () => {
    const agents = fs.readFileSync(path.join(SRC, "lib/agents.ts"), "utf8");
    const colours = [...agents.matchAll(/color:\s*"(#[0-9a-fA-F]{6})"/g)].map((m) => m[1]);
    expect(colours.length).toBe(4);
    for (const c of colours) expect(contrast(DARK.ink, c), `ink on ${c}`).toBeGreaterThanOrEqual(4.5);
  });
});
