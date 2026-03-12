#!/bin/sh
# Run this once after cloning the repo to configure Git hooks.
git config core.hooksPath .github/githooks
chmod +x .github/githooks/pre-push
echo "Git hooks configured. Pre-push checks are now active."