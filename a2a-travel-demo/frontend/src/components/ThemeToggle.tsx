"use client";

import { useEffect, useState } from "react";
import { THEME_KEY, Theme, resolveTheme } from "@/lib/theme";

function SunIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
  );
}

function MoonIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
    </svg>
  );
}

/** Dark / light switch. The initial theme is set before paint by THEME_INIT_SCRIPT; this component only reads and flips it. */
export default function ThemeToggle() {
  const [theme, setTheme] = useState<Theme | null>(null); // null until mounted, so server and client markup match

  useEffect(() => {
    setTheme((document.documentElement.dataset.theme as Theme) ?? "dark");

    // until the user picks a theme explicitly, follow the operating system
    const query = window.matchMedia("(prefers-color-scheme: light)");
    const onSystemChange = () => {
      let stored: string | null = null;
      try {
        stored = localStorage.getItem(THEME_KEY);
      } catch {
        /* storage unavailable */
      }
      if (stored) return;
      const next = resolveTheme(null, query.matches);
      document.documentElement.dataset.theme = next;
      setTheme(next);
    };
    query.addEventListener("change", onSystemChange);
    return () => query.removeEventListener("change", onSystemChange);
  }, []);

  const toggle = () => {
    const next: Theme = theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    setTheme(next);
    try {
      localStorage.setItem(THEME_KEY, next);
    } catch {
      /* storage unavailable: the choice just will not persist */
    }
  };

  const target = theme === "dark" ? "light" : "dark";

  return (
    <button
      type="button"
      className="btn theme-toggle"
      onClick={toggle}
      disabled={theme === null}
      aria-label={theme ? `Switch to ${target} theme` : "Toggle theme"}
      title={theme ? `Switch to ${target} theme` : "Toggle theme"}
    >
      {theme === "dark" ? <SunIcon /> : <MoonIcon />}
      <span>{theme === "dark" ? "Light" : theme === "light" ? "Dark" : "Theme"}</span>
    </button>
  );
}
