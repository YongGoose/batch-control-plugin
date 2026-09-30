// Re-append the latest row of an id with some cells replaced: node amend.mjs <id> '<json patch>'
import fs from 'node:fs';
import { row } from './rec.mjs';
import { OUT } from '../lib.mjs';
const [id, patch] = process.argv.slice(2);
const rows = fs.readFileSync(OUT + '/audit.jsonl', 'utf8').trim().split('\n').map((l) => JSON.parse(l)).filter((r) => r.id === id);
if (!rows.length) throw new Error(`no row ${id}`);
const last = rows[rows.length - 1];
const p = JSON.parse(patch);
const merged = { ...last, ...p };
for (const k of ['G', 'V', 'R', 'C', 'E', 'note']) if (p[`+${k}`]) merged[k] = `${last[k]}; ${p[`+${k}`]}`;
delete merged.verdict; if (p.verdict) merged.verdict = p.verdict;
row(id, merged);
