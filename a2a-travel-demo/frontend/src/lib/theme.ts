/**
 * Theme handling. Two themes only, both deliberately soft on the eyes:
 *   dark  = shades of charcoal (never pitch black)
 *   light = shades of cream     (never pure white)
 * The palette itself lives in app/globals.css as CSS variables; tests/theme.test.ts guards it.
 */
export type Theme = "dark" | "light";

export const THEME_KEY = "a2a.theme";

/** An explicit user choice wins; otherwise follow the operating system preference. */
export function resolveTheme(stored: string | null | undefined, prefersLight: boolean): Theme {
  if (stored === "light" || stored === "dark") return stored;
  return prefersLight ? "light" : "dark";
}

/**
 * Runs inline in <head> before the first paint, so the page never flashes the wrong theme.
 * Mirrors resolveTheme() (kept as a string because it must run before React); tests execute it to prove they agree.
 */
export const THEME_INIT_SCRIPT = `(function(){try{var s=localStorage.getItem(${JSON.stringify(THEME_KEY)});var t=(s==="light"||s==="dark")?s:(window.matchMedia("(prefers-color-scheme: light)").matches?"light":"dark");document.documentElement.dataset.theme=t}catch(e){document.documentElement.dataset.theme="dark"}})()`;
