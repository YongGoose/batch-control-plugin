// Lists run-3 screenshots that break the rule: no red box, or a full-viewport capture.
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
const dir = path.resolve('../screenshots/run-3');
const files = fs.readdirSync(dir).filter((f) => f.endsWith('.png')).sort();
const b = await chromium.launch({ channel: 'chrome' });
const p = await b.newPage();
const bad = [];
for (const f of files) {
  const data = fs.readFileSync(path.join(dir, f)).toString('base64');
  const r = await p.evaluate(async (src) => {
    const img = new Image(); img.src = src; await img.decode();
    const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
    const x = c.getContext('2d'); x.drawImage(img, 0, 0);
    const d = x.getImageData(0, 0, img.width, img.height).data;
    let red = 0;
    for (let i = 0; i < d.length; i += 4 * 3) if (d[i] > 200 && d[i + 1] < 30 && d[i + 2] < 30) red++;
    return { w: img.width, h: img.height, red };
  }, `data:image/png;base64,${data}`);
  const fullViewport = (r.w === 1280 && r.h === 900) || (r.w === 1100 && r.h === 900);
  if (r.red < 40 || fullViewport) bad.push(`${f} ${r.w}x${r.h} red=${r.red}${fullViewport ? ' FULL-VIEWPORT' : ''}`);
}
console.log(`${files.length} screenshots, ${bad.length} breaking the rule:\n${bad.join('\n')}`);
await b.close();
