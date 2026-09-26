#!/usr/bin/env node
// Modified by AI Hello World on 2026-09-26.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.

import { main } from '../src/main.js'

main(process.argv.slice(2)).catch((error) => {
  process.stderr.write(`[likkahub] ${error?.message || error}\n`)
  process.exit(1)
})
