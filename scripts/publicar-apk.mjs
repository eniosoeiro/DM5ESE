// Compatibility entry point. The installer itself is now inspected and verified;
// neither a filename nor a renamed debug APK can authorize a production release.
import { main } from './installer-registry.mjs';
try { main(['publish', ...process.argv.slice(2)]); }
catch (error) { console.error(`Publicação interrompida: ${error.message}`); process.exitCode = 1; }
