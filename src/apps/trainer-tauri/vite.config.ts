import { defineConfig } from "vite";
import solidPlugin from "vite-plugin-solid";

export default defineConfig({
  plugins: [solidPlugin()],
  clearScreen: false,
  server: {
    port: 1430,
    strictPort: true,
    hmr: {
      overlay: false,
    },
  },
  build: {
    target: "esnext",
  },
});
