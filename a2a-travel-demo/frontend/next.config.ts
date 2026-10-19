import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  // Cache Components: dynamic data (fetches, Date.now, ...) must stay out of the prerender, see src/app/page.tsx
  cacheComponents: true,
};

export default nextConfig;
