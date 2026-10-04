# Contributing

Thanks for improving Escooter Control.

## Before opening a pull request

1. Keep changes focused and explain the user-visible reason for them.
2. Run `./gradlew test` and, when possible, `./gradlew assembleDebug`.
3. Do not commit `local.properties`, signing material, build output, captures containing device identifiers, or private keys.
4. Add or update tests when changing frame construction, parsing, scaling, or state interpretation.
5. Update `docs/PROTOCOL.md` when a protocol conclusion changes.

## Protocol evidence

Use precise language. Mark findings as source-confirmed, device/capture-confirmed, or inferred. Include enough sanitized evidence for another contributor to reproduce the conclusion. Do not invent support for hardware you have not tested.

## Code style

Prefer small functions with descriptive names over explanatory comments. Comments should explain *why* a non-obvious protocol or Android BLE workaround exists, not restate the code. Keep UI state hoisted where practical and use Material theme tokens rather than one-off colors.

## Attribution

This project is MIT licensed. Keep the copyright and license notice when redistributing copies or substantial portions of the project.
