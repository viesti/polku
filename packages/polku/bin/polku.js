#!/usr/bin/env node
// Exists in a fresh checkout, so that npm can link the command before the build has run
import('../dist/polku.cjs').catch((err) => {
  if (err.code !== 'ERR_MODULE_NOT_FOUND') throw err;
  console.error('polku: the package is not built yet; run `npm run build` in the polku package directory');
  process.exit(1);
});
