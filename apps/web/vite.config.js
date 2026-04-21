import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev server proxies /api and /ws to the relay. Override the target
// port with RELAY_PORT=... if your relay doesn't sit on :8080.
const relayPort = process.env.RELAY_PORT || '8080';
const relayHttp = `http://127.0.0.1:${relayPort}`;
const relayWs = `ws://127.0.0.1:${relayPort}`;

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      '/api': { target: relayHttp, changeOrigin: true },
      '/ws': { target: relayWs, ws: true, changeOrigin: true },
    },
  },
});
