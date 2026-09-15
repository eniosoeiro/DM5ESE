// Local native smoke check. No simulated Tauri responses are installed.
import { spawn } from "node:child_process";
import { chromium } from "playwright";
import assert from "node:assert/strict";
import path from "node:path";
const app = spawn(path.resolve("src-tauri/target/debug/dm5ese.exe"), [], {
  windowsHide: true,
  env: { ...process.env, WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: "--remote-debugging-port=19325" },
  stdio: "ignore",
});
let browser;
try {
  let endpoint;
  for (let i = 0; i < 60; i++) {
    try { const r = await fetch("http://127.0.0.1:19325/json/version"); endpoint = (await r.json()).webSocketDebuggerUrl; if (endpoint) break; } catch {}
    await new Promise(r => setTimeout(r, 250));
  }
  assert.ok(endpoint, "WebView2 debug endpoint did not open");
  browser = await chromium.connectOverCDP(endpoint);
  let page;
  for (let i = 0; i < 40; i++) {
    page = browser.contexts().flatMap(c => c.pages()).find(p => /tauri|localhost/.test(p.url()));
    if (page) break;
    await new Promise(r => setTimeout(r, 250));
  }
  assert.ok(page, "Desktop page was not created");
  await page.locator("#driver-status").filter({ hasText: "Componente GE disponível" }).waitFor({ timeout: 20000 });
  assert.ok(await page.locator("#connect").isEnabled());
  assert.ok(await page.locator("#csv").isDisabled());
  console.log(JSON.stringify({ url: page.url(), status: await page.locator("#status").innerText(), driver: await page.locator("#driver-status").innerText(), ports: await page.locator("#ports").innerText() }));
  await page.screenshot({ path: "research/private/desktop-native.png", fullPage: true });
} finally {
  if (browser) await browser.close();
  app.kill();
}
