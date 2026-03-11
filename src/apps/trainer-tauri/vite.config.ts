import { defineConfig } from "vite";
import solidPlugin from "vite-plugin-solid";

export default defineConfig({
  plugins: [solidPlugin()],
  clearScreen: false,
  server: {
    port: 1430,
    strictPort: true,
    proxy: {
      "/v1": {
        target: "http://127.0.0.1:5001",
        changeOrigin: true,
      },
    },
    hmr: {
      overlay: false,
    },
  },
  build: {
    target: "esnext",
  },
});
