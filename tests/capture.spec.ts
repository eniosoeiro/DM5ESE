import { test, expect } from "@playwright/test";

test("spreadsheet matrix copies full sheet and rectangular selection for Excel", async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, "clipboard", { value: { writeText: async (text: string) => { (window as any).copied = text; } } });
    Object.assign(window, { isTauri: true, __TAURI_INTERNALS__: { invoke: async (cmd: string) => {
      const connection = { port: 9, baud: 0, serial: "" };
      if (cmd === "list_ports") return [];
      if (cmd === "probe_driver") return { driver: "DM5E" };
      if (cmd === "connect_device") return { connection, files: [{ index: 0, name: "01" }] };
      if (cmd === "capture_file") return { connection, file: { name: "01" }, capturedAt: "2026-09-15T12:00:00Z", readings: [
        { label: "1A", value: 5.12, unit: "mm", state: "OK" },
        { label: "1B", value: null, unit: "mm", state: "EMPTY" },
        { label: "2A", value: 0, unit: "mm", state: "OK" },
        { label: "2B", value: 4.86, unit: "mm", state: "OK" },
      ] };
      throw cmd;
    } } });
  });
  await page.goto("/");
  await page.locator("#connect").click();
  await page.locator("#capture").click();
  await expect(page.locator("#sheet-head th")).toHaveText(["", "A", "B"]);
  await expect(page.locator("#readings tr")).toHaveCount(2);
  await expect(page.locator("#readings td")).toHaveText(["5,12", "", "0", "4,86"]);
  await page.locator("#copy-all").click();
  expect(await page.evaluate(() => (window as any).copied)).toBe("Posição\tA\tB\r\n1\t5,12\t\r\n2\t0\t4,86");
  await page.locator('#readings td[data-row="0"][data-col="0"]').click();
  await page.locator('#readings td[data-row="1"][data-col="1"]').click({ modifiers: ["Shift"] });
  await expect(page.locator("td.selected")).toHaveCount(4);
  await page.keyboard.press("Control+c");
  await expect(page.locator("#copy-status")).toContainText("Seleção copiada");
  expect(await page.evaluate(() => (window as any).copied)).toBe("5,12\t\r\n0\t4,86");
  await page.locator(".measurements").first().screenshot({ path: "research/private/spreadsheet.png" });
});

test("browser preview disables physical capture", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator("#status")).toContainText("Prévia no navegador");
  await expect(page.locator("#connect")).toBeDisabled();
  await expect(page.locator("#csv")).toBeDisabled();
});

test("directory, capture pagination, export and failed recapture preserve completed data", async ({ page }) => {
  await page.addInitScript(() => {
    let reads = 0;
    Object.assign(window, {
      isTauri: true,
      __TAURI_INTERNALS__: { invoke: async (cmd: string) => {
        const connection = { port: 3, baud: 115200, serial: "TEST" };
        if (cmd === "list_ports") return [{ name: "COM3", kind: "USB", product: "DM5E", vid: "C251", pid: "1705" }];
        if (cmd === "probe_driver") return { driver: "DM5E" };
        if (cmd === "connect_device") return { connection, files: [{ index: 0, name: "<img src=x onerror=alert(1)>" }] };
        if (cmd === "capture_file") {
          if (reads++) throw "Conexão perdida durante a captura";
          return { connection, file: { name: "ENSAIO" }, capturedAt: "2026-09-15T12:00:00Z", readings: Array.from({ length: 101 }, (_, i) => ({ index: i, label: `1.${i}`, value: i === 1 ? null : 2.5, state: i === 1 ? "EMPTY" : "OK", unit: "mm", status: i === 1 ? 32 : 0 })) };
        }
        if (cmd === "export_capture") return true;
        throw `Unexpected command: ${cmd}`;
      } },
    });
  });
  await page.goto("/");
  await expect(page.locator("#connect")).toBeEnabled();
  await page.locator("#connect").click();
  await expect(page.locator("#files option")).toHaveText("<img src=x onerror=alert(1)>");
  await expect(page.locator("#capture")).toBeEnabled();
  await page.locator("#capture").click();
  await expect(page.locator("#readings tr")).toHaveCount(100);
  await expect(page.locator("#readings tr").nth(1)).toContainText("Vazia");
  await page.locator("#next").click();
  await expect(page.locator("#readings tr")).toHaveCount(1);
  await page.locator("#csv").click();
  await expect(page.locator("#status")).toContainText("exportada em CSV");
  await page.locator("#capture").click();
  await expect(page.locator("#status")).toContainText("Conexão perdida");
  await expect(page.locator("#capture")).toBeDisabled();
  await expect(page.locator("#csv")).toBeEnabled();
  await expect(page.locator("#capture-info")).toContainText("ENSAIO");
  await page.screenshot({ path: "research/private/ui-capture.png", fullPage: true });
});
