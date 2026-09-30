// Re-crops captures that broke the screenshot rule to the region with content and
// draws the red box around it. Same pixels as the original capture; only the
// irrelevant margin (empty main panel, page chrome, old viewport-sized box) goes.
//   node fix-shots.mjs crop <file>...   content bbox (below the header, above the footer)
//   node fix-shots.mjs edge <file>...   the image already is the element (dialog/select): box on its edge
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
const [mode, ...files] = process.argv.slice(2);
const dir = path.resolve('../screenshots/run-3');
const b = await chromium.launch({ channel: 'chrome' });
const p = await b.newPage();
for (const f of files) {
  const src = `data:image/png;base64,${fs.readFileSync(path.join(dir, f)).toString('base64')}`;
  const out = await p.evaluate(async ([s, m]) => {
    const img = new Image(); img.src = s; await img.decode();
    const W = img.width, H = img.height;
    const c = document.createElement('canvas'); c.width = W; c.height = H;
    const x = c.getContext('2d'); x.drawImage(img, 0, 0);
    let box;
    if (m === 'edge') box = { x0: 4, y0: 4, x1: W - 5, y1: H - 5 };
    else {
      const d = x.getImageData(0, 0, W, H).data;
      const top = 64, bottom = H - 70, left = 12, right = W - 12;
      let x0 = W, y0 = H, x1 = 0, y1 = 0;
      for (let y = top; y < bottom; y++) for (let xx = left; xx < right; xx++) {
        const i = (y * W + xx) * 4; const r = d[i], g = d[i + 1], bl = d[i + 2];
        const isRed = r > 190 && g < 40 && bl < 40;
        const ink = !isRed && (r < 225 || g < 225 || bl < 225);
        if (ink) { if (xx < x0) x0 = xx; if (xx > x1) x1 = xx; if (y < y0) y0 = y; if (y > y1) y1 = y; }
      }
      box = { x0, y0, x1, y1 };
    }
    const pad = m === 'edge' ? 0 : 20;
    const cx0 = Math.max(0, box.x0 - pad), cy0 = Math.max(0, box.y0 - pad);
    const cx1 = Math.min(W, box.x1 + pad), cy1 = Math.min(H, box.y1 + pad);
    const o = document.createElement('canvas'); o.width = cx1 - cx0; o.height = cy1 - cy0;
    const ox = o.getContext('2d'); ox.drawImage(c, cx0, cy0, o.width, o.height, 0, 0, o.width, o.height);
    ox.strokeStyle = '#d00'; ox.lineWidth = 3;
    ox.strokeRect(box.x0 - cx0 - 6 + (m === 'edge' ? 6 : 0), box.y0 - cy0 - 6 + (m === 'edge' ? 6 : 0), (box.x1 - box.x0) + (m === 'edge' ? 0 : 12), (box.y1 - box.y0) + (m === 'edge' ? 0 : 12));
    return { data: o.toDataURL('image/png'), w: o.width, h: o.height };
  }, [src, mode]);
  fs.writeFileSync(path.join(dir, f), Buffer.from(out.data.split(',')[1], 'base64'));
  console.log(`${f} -> ${out.w}x${out.h}`);
}
await b.close();
