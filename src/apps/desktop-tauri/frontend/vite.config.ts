import { defineConfig } from "vite";
import solidPlugin from "vite-plugin-solid";

export default defineConfig({
  plugins: [solidPlugin()],
  clearScreen: false,
  optimizeDeps: {
    // lucide-solid exports thousands of icon modules; skipping prebundle avoids
    // occasional esbuild service crashes (EPIPE) observed in desktop dev mode.
    exclude: ["lucide-solid"],
  },
  server: {
    port: 1420,
    strictPort: true,
    hmr: {
      // Keep desktop dev usable even when esbuild child process is restarted.
      overlay: false,
    },
  },
  build: {
    target: "esnext",
  },
});
