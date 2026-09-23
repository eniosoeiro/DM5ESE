import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { constants, copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync, utimesSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';

// Public identifiers only. Never reads a keystore or a signing password.
export const PACKAGES = Object.freeze({
  'conector-android': 'br.com.gestaonr13.instrumentos',
  'es-medicao': 'com.dm5ese.usbprobe',
});
export const sha256 = file => createHash('sha256').update(readFileSync(file)).digest('hex');
export function readJson(file) {
  try { return JSON.parse(readFileSync(file, 'utf8')); }
  catch (error) { if (error.code === 'ENOENT') return null; throw error; }
}
export function atomicJson(file, data) {
  mkdirSync(dirname(file), { recursive: true });
  const temporary = `${file}.${randomUUID()}.tmp`;
  try { writeFileSync(temporary, JSON.stringify(data, null, 2) + '\n', { flag: 'wx' }); renameSync(temporary, file); }
  finally { rmSync(temporary, { force: true }); }
}
function run(command, args) {
  return execFileSync(command, args, { encoding: 'utf8', timeout: 60000, maxBuffer: 4 * 1024 * 1024, windowsHide: true });
}
export function parseApkInfo(badging, certificate, id) {
  if (!PACKAGES[id]) throw new Error('Aplicativo não registrado. Configure seu applicationId antes de distribuir.');
  const fields = badging.match(/package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'/);
  const minSdk = Number(badging.match(/(?:minSdkVersion|sdkVersion):'?(\d+)'?/i)?.[1]);
  const digests = [...certificate.matchAll(/Signer #\d+ certificate SHA-256 digest:\s*([a-f0-9]{64})/gi)];
  if (!fields || fields[1] !== PACKAGES[id] || digests.length !== 1 || !minSdk) throw new Error('Pacote, SDK mínimo ou certificado incompatível.');
  if (!/^[0-9]+(?:\.[0-9]+){1,3}(?:-[a-zA-Z0-9.-]+)?$/.test(fields[3])) throw new Error('Nome de versão inválido para distribuição.');
  const versionCode = Number(fields[2]);
  if (!Number.isSafeInteger(versionCode) || versionCode < 1) throw new Error('versionCode inválido.');
  const development = /application-debuggable|Android Debug/i.test(badging + '\n' + certificate);
  return { id, pacote: fields[1], versao: fields[3], versionCode, minSdk, certificadoSha256: digests[0][1].toLowerCase(), canal: development ? 'homologacao' : 'producao' };
}
export function inspectApk({ id, apk, sdk, java }) {
  if (!apk || !apk.toLowerCase().endsWith('.apk') || !statSync(apk).isFile()) throw new Error('Informe um APK existente.');
  const sdkRoot = sdk || process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdkRoot) throw new Error('Configure ANDROID_HOME ou informe --sdk para verificar o APK.');
  const tools = join(sdkRoot, 'build-tools');
  const version = readdirSync(tools).filter(v => /^\d+\.\d+\.\d+$/.test(v)).sort((a, b) => b.localeCompare(a, undefined, { numeric: true })).find(v => existsSync(join(tools, v, 'lib/apksigner.jar')));
  if (!version) throw new Error('Android Build Tools com apksigner ausente.');
  const aapt = join(tools, version, process.platform === 'win32' ? 'aapt2.exe' : 'aapt2');
  const javaBin = java || (process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java');
  const certificate = run(javaBin, ['-jar', join(tools, version, 'lib/apksigner.jar'), 'verify', '--print-certs', apk]);
  const info = parseApkInfo(run(aapt, ['dump', 'badging', apk]), certificate, id);
  return { ...info, tamanhoBytes: statSync(apk).size, geradoEm: statSync(apk).mtime.toISOString(), sha256: sha256(apk) };
}
export function assertNewer(previous, next) {
  if (!previous) return;
  if (previous.pacote !== next.pacote || previous.certificadoSha256 !== next.certificadoSha256) throw new Error('Mudança de pacote/assinatura recusada; preserve a possibilidade de atualização.');
  if (previous.sha256 === next.sha256) return; // Idempotent repetition, not a new build.
  if (previous.canal === 'producao' && next.versionCode === previous.versionCode) throw new Error('Uma nova versão de produção deve incrementar versionCode.');
  if (next.versionCode < previous.versionCode || (next.versionCode === previous.versionCode && Date.parse(next.geradoEm) < Date.parse(previous.geradoEm))) throw new Error('Versão anterior não pode substituir a última compilação.');
}
function withLock(root, id, operation) {
  const lock = join(root, `.installer-${id}.lock`);
  mkdirSync(root, { recursive: true });
  try { mkdirSync(lock); } catch { throw new Error('Outra operação está atualizando este aplicativo. Tente novamente após concluí-la.'); }
  try { return operation(); } finally { rmSync(lock, { recursive: true }); }
}
function storeVerified(apk, info, output) {
  const directory = join(output, info.id, info.canal);
  const latestFile = join(directory, 'latest.json');
  const previous = readJson(latestFile);
  assertNewer(previous, info);
  const filename = `${info.id}-v${info.versao}-c${info.versionCode}-${info.sha256.slice(0, 12)}.apk`;
  mkdirSync(directory, { recursive: true });
  const destination = join(directory, filename);
  if (!existsSync(destination)) { copyFileSync(apk, destination, constants.COPYFILE_EXCL); utimesSync(destination, new Date(), new Date(info.geradoEm)); }
  if (sha256(destination) !== info.sha256) throw new Error('Hash do arquivo arquivado não confere. O ponteiro latest foi preservado.');
  const manifest = { ...info, arquivo: filename };
  atomicJson(destination + '.json', manifest);
  atomicJson(latestFile, manifest);
  return { directory, destination, manifest };
}
export function archiveApk(options) {
  const info = inspectApk(options);
  return withLock(resolve(options.out), info.id, () => storeVerified(options.apk, info, resolve(options.out)));
}
export function assertPublishable(info, channel, acceptTest, expectedCertificate) {
  if (channel !== info.canal) throw new Error('O canal solicitado não corresponde à assinatura e ao tipo real do APK.');
  if (channel === 'homologacao' && !acceptTest) throw new Error('APK de desenvolvimento: use --accept-test somente para autorizar distribuição em homologação.');
  if (channel === 'producao' && (!expectedCertificate || expectedCertificate.replaceAll(':', '').toLowerCase() !== info.certificadoSha256)) throw new Error('Produção requer o fingerprint do certificado previamente aprovado pelo proprietário.');
}
export function publishApk(options) {
  const info = inspectApk(options);
  const certificate = process.env[info.id === 'conector-android' ? 'NR13_ANDROID_CERT_SHA256' : 'ES_MEDICAO_CERT_SHA256'];
  assertPublishable(info, options.channel, options.acceptTest, certificate);
  // Static Pages assets have a 25 MiB limit; larger packages must use the file CDN.
  if (info.tamanhoBytes > 25 * 1024 * 1024) throw new Error('APK excede 25 MiB. Publique no CDN de arquivos, não no Pages.');
  const catalogFile = resolve(options.catalog);
  return withLock(dirname(catalogFile), 'catalog', () => {
    const catalog = readJson(catalogFile);
    const matches = catalog?.aplicativos?.filter(app => app.id === info.id) || [];
    if (matches.length !== 1) throw new Error('Aplicativo ausente ou duplicado no catálogo.');
    const app = matches[0];
    if (info.canal === 'homologacao') assertNewer(app.instaladorTeste, info);
    const stored = storeVerified(options.apk, info, join(dirname(catalogFile), 'instaladores'));
    const url = `/aplicativos/instaladores/${info.id}/${info.canal}/${stored.manifest.arquivo}`;
    const { id: _id, canal: _canal, ...metadata } = info;
    if (info.canal === 'homologacao') {
      app.instaladorTeste = { ...metadata, arquivoUrl: url };
      // A test must never replace an existing production version.
      if (app.status !== 'publicado') {
        app.versao = info.versao;
        app.tamanho = `${(info.tamanhoBytes / 1000000).toFixed(1).replace('.', ',')} MB`;
      }
    } else {
      Object.assign(app, { versao: info.versao, versionCode: info.versionCode, sha256: info.sha256, certificadoSha256: info.certificadoSha256, downloadUrl: url, status: 'publicado', atualizadoEm: info.geradoEm.slice(0, 10), tamanho: `${(info.tamanhoBytes / 1000000).toFixed(1).replace('.', ',')} MB` });
    }
    atomicJson(catalogFile, catalog);
    return stored;
  });
}
export function main(args = process.argv.slice(2)) {
  const { values, positionals } = parseArgs({ args, allowPositionals: true, options: {
    id: { type: 'string' }, apk: { type: 'string' }, out: { type: 'string' }, sdk: { type: 'string' }, java: { type: 'string' },
    catalog: { type: 'string' }, channel: { type: 'string', default: 'producao' }, 'accept-test': { type: 'boolean', default: false },
  } });
  const operation = positionals[0];
  if (positionals.length !== 1 || !['archive', 'publish'].includes(operation) || !values.id || !values.apk) throw new Error('Uso: node installer-registry.mjs archive|publish --id <app> --apk <arquivo> --sdk <SDK> [--out <registro>] [--catalog <catalogo.json>] [--channel homologacao --accept-test]');
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const options = { ...values, apk: resolve(values.apk), acceptTest: values['accept-test'], out: values.out || process.env.ES_INSTALLER_DIR || join(root, 'artifacts/installers'), catalog: values.catalog || join(root, 'public/aplicativos/catalogo.json') };
  const result = operation === 'archive' ? archiveApk(options) : publishApk(options);
  console.log(JSON.stringify({ operacao: operation, aplicativo: values.id, canal: result.manifest.canal, versao: result.manifest.versao, versionCode: result.manifest.versionCode, sha256: result.manifest.sha256, arquivo: result.destination, latest: join(result.directory, 'latest.json') }));
  return result;
}
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try { main(); } catch (error) { console.error(`Distribuição interrompida: ${error.message}`); process.exitCode = 1; }
}
