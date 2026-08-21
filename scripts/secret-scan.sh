#!/usr/bin/env bash
# ==============================================================================
# SimpleXray Secret Scanner (Pre-commit Hook)
# Scans staged files in git for private keys, tokens, and hardcoded secrets.
# ==============================================================================

set -eo pipefail

echo "🔍 Running pre-commit secret scan..."

# Get list of staged files (excluding deleted files)
STAGED_FILES=$(git diff --cached --name-only --diff-filter=d)

if [ -z "$STAGED_FILES" ]; then
    echo "✅ No staged files to scan."
    exit 0
fi

LEAKS_FOUND=0

# 1. Block staged binary key files (.jks, .p12, .pk8, .pem, .pfx)
for file in $STAGED_FILES; do
    if [[ "$file" =~ \.(p12|pfx|pk8|pem)$ ]]; then
        echo "❌ ERROR: Staged file '$file' appears to be a private key or certificate file."
        LEAKS_FOUND=$((LEAKS_FOUND + 1))
    fi
done

# 2. Check diffs of staged text files against high-risk regex patterns (excluding scanner script self-match)
PATTERNS=(
    "-----BEGIN (RSA|EC|DSA|OPENSSH|PGP|PRIVATE) KEY-----"
    "AKIA[0-9A-Z]{16}"
    "ghp_[0-9a-zA-Z]{36}"
    "github_pat_[0-9a-zA-Z]{82}"
    "xox[b-z]-[0-9a-zA-Z]{10,48}"
)

# Filter out scanner script itself from text diff inspection to avoid false positives on pattern definitions
STAGED_TEXT_FILES=$(echo "$STAGED_FILES" | grep -v "^scripts/secret-scan.sh$" || true)

if [ -n "$STAGED_TEXT_FILES" ]; then
    for pattern in "${PATTERNS[@]}"; do
        MATCHES=$(git diff --cached -U0 -- $STAGED_TEXT_FILES | grep -E "^\+[^\+]" | grep -E -e "$pattern" || true)
        if [ -n "$MATCHES" ]; then
            echo "❌ ERROR: High-risk secret pattern matched: '$pattern'"
            echo "$MATCHES"
            LEAKS_FOUND=$((LEAKS_FOUND + 1))
        fi
    done
fi

if [ "$LEAKS_FOUND" -gt 0 ]; then
    echo ""
    echo "⛔ Commit aborted! Found $LEAKS_FOUND potential secret leak(s)."
    echo "Please remove secrets before committing."
    exit 1
fi

echo "✅ Secret scan passed cleanly. Proceeding with commit."
exit 0
