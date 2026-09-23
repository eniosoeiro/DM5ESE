import { resolve, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { main, PACKAGES, readJson, sha256 } from './installer-registry.mjs';

try {
  const { values } = parseArgs({ options: {
    id: { type: 'string' }, channel: { type: 'string', default: 'producao' },
    registry: { type: 'string' }, catalog: { type: 'string' }, sdk: { type: 'string' }, java: { type: 'string' },
    'accept-test': { type: 'boolean', default: false },
  } });
  if (!PACKAGES[values.id] || !['producao', 'homologacao'].includes(values.channel)) throw new Error('Informe --id e um canal válido.');
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const directory = join(values.registry || process.env.ES_INSTALLER_DIR || join(root, 'artifacts/installers'), values.id, values.channel);
  const latest = readJson(join(directory, 'latest.json'));
  if (!latest || latest.id !== values.id || latest.canal !== values.channel || !/^[a-z0-9.-]+\.apk$/i.test(latest.arquivo)) throw new Error('Não há manifesto latest válido para este aplicativo/canal.');
  const apk = join(directory, latest.arquivo);
  if (sha256(apk) !== latest.sha256) throw new Error('APK arquivado foi alterado; publicação recusada.');
  const args = ['publish', '--id', values.id, '--apk', apk, '--channel', values.channel];
  for (const name of ['catalog', 'sdk', 'java']) if (values[name]) args.push(`--${name}`, values[name]);
  if (values['accept-test']) args.push('--accept-test');
  main(args);
} catch (error) { console.error(`Publicação interrompida: ${error.message}`); process.exitCode = 1; }
