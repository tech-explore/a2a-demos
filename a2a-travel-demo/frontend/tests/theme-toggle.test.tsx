import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import ThemeToggle from "@/components/ThemeToggle";

describe("ThemeToggle", () => {
  it("renders on the server without touching window or localStorage, as a disabled placeholder until mounted", () => {
    const html = renderToStaticMarkup(<ThemeToggle />);
    expect(html).toContain("theme-toggle");
    expect(html).toContain("disabled");
    expect(html).toContain("Toggle theme");
  });
});
