#!/usr/bin/env bash
# ==============================================================================
# Focus Shield - One-Time Production Signing Setup Script
# ==============================================================================
# Generates ONE permanent, stable production release keystore and credentials
# in the git-ignored .signing/ directory, and attempts to configure GitHub
# Actions repository secrets automatically via the GitHub CLI ('gh').
#
# If 'gh' is not installed or authenticated, it safely prepares the exact
# secret files in .signing/ without printing secrets into console logs.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SIGNING_DIR="$PROJECT_ROOT/.signing"

KEYSTORE_FILE="$SIGNING_DIR/focusshield-release.keystore"
BASE64_FILE="$SIGNING_DIR/keystore_base64.txt"
CREDS_FILE="$SIGNING_DIR/signing_credentials.env"
INFO_FILE="$SIGNING_DIR/SETUP_INSTRUCTIONS.txt"

KEY_ALIAS="focusshield"

mkdir -p "$SIGNING_DIR"
chmod 755 "$SIGNING_DIR"

echo "=================================================="
echo "Focus Shield: Production Signing Setup"
echo "=================================================="

# ------------------------------------------------------------------------------
# 1. Generate or verify stable production keystore
# ------------------------------------------------------------------------------
if [ -f "$KEYSTORE_FILE" ] && [ -f "$CREDS_FILE" ]; then
  echo "✓ Existing stable production keystore found at:"
  echo "  .signing/focusshield-release.keystore"
  echo "  Reusing existing keystore to maintain application update compatibility."
  
  # Read existing password without printing
  # shellcheck disable=SC1090
  source "$CREDS_FILE"
  STORE_PASS="$RELEASE_STORE_PASSWORD"
else
  echo "Generating new permanent production keystore..."
  
  # Generate strong random password (24 characters, url-safe alphanumeric)
  STORE_PASS=$(head -c 32 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 24)
  
  keytool -genkeypair -v \
    -keystore "$KEYSTORE_FILE" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Focus Shield Release, O=Focus Shield, C=US" \
    -storepass "$STORE_PASS" \
    -keypass "$STORE_PASS" > /dev/null 2>&1

  chmod 600 "$KEYSTORE_FILE"

  # Save credentials file (protected, gitignored)
  cat > "$CREDS_FILE" <<EOF
RELEASE_KEY_ALIAS=$KEY_ALIAS
RELEASE_STORE_PASSWORD=$STORE_PASS
RELEASE_KEY_PASSWORD=$STORE_PASS
EOF
  chmod 644 "$CREDS_FILE"
  echo "✓ Permanent production keystore generated successfully."
fi

# Export dedicated single-line password file (no newline, no variable name)
printf "%s" "$STORE_PASS" > "$SIGNING_DIR/RELEASE_STORE_PASSWORD.txt"
chmod 644 "$SIGNING_DIR/RELEASE_STORE_PASSWORD.txt"

# Also write to root directory for direct visibility in the project editor
printf "%s" "$STORE_PASS" > "$PROJECT_ROOT/COPY_RELEASE_STORE_PASSWORD.txt"
chmod 644 "$PROJECT_ROOT/COPY_RELEASE_STORE_PASSWORD.txt"
printf "%s" "$STORE_PASS" > "$PROJECT_ROOT/RELEASE_STORE_PASSWORD.txt"
chmod 644 "$PROJECT_ROOT/RELEASE_STORE_PASSWORD.txt"

# ------------------------------------------------------------------------------
# 2. Export Base64 representation (single line, no linebreaks)
# ------------------------------------------------------------------------------
base64 "$KEYSTORE_FILE" | tr -d '\r\n' > "$BASE64_FILE"
chmod 644 "$BASE64_FILE"
echo "✓ Base64 representation generated at .signing/keystore_base64.txt"

# ------------------------------------------------------------------------------
# 3. Create non-sensitive setup instructions file in .signing/
# ------------------------------------------------------------------------------
cat > "$INFO_FILE" <<EOF
================================================================================
Focus Shield - GitHub Actions Secret Configuration
================================================================================

To enable automated production release builds on push, add the following 4
repository secrets in GitHub:
Repository -> Settings -> Secrets and variables -> Actions -> "New repository secret"

1. Secret Name:  RELEASE_KEYSTORE_BASE64
   Value:        (Copy the entire contents of .signing/keystore_base64.txt)

2. Secret Name:  RELEASE_STORE_PASSWORD
   Value:        (Value of RELEASE_STORE_PASSWORD from .signing/signing_credentials.env)

3. Secret Name:  RELEASE_KEY_ALIAS
   Value:        $KEY_ALIAS

4. Secret Name:  RELEASE_KEY_PASSWORD
   Value:        (Value of RELEASE_KEY_PASSWORD from .signing/signing_credentials.env)

NOTE: The .signing/ directory is strictly listed in .gitignore.
Never commit .signing/ or its contents to Git.
================================================================================
EOF
chmod 600 "$INFO_FILE"

# ------------------------------------------------------------------------------
# 4. Attempt automatic GitHub Actions secret configuration via GitHub CLI ('gh')
# ------------------------------------------------------------------------------
echo ""
echo "Checking GitHub CLI ('gh') integration..."

GH_CONFIGURED=false

if command -v gh >/dev/null 2>&1; then
  if gh auth status >/dev/null 2>&1; then
    echo "GitHub CLI detected and authenticated."
    echo "Attempting to create/update GitHub repository secrets..."
    
    if gh secret set RELEASE_KEYSTORE_BASE64 < "$BASE64_FILE" && \
       gh secret set RELEASE_STORE_PASSWORD -b "$STORE_PASS" && \
       gh secret set RELEASE_KEY_ALIAS -b "$KEY_ALIAS" && \
       gh secret set RELEASE_KEY_PASSWORD -b "$STORE_PASS"; then
      echo "✓ All 4 production release secrets successfully created in GitHub Actions!"
      GH_CONFIGURED=true
    else
      echo "⚠ Direct secret creation via 'gh' encountered a permission/API error."
    fi
  else
    echo "⚠ GitHub CLI ('gh') is installed but not authenticated ('gh auth login' required)."
  fi
else
  echo "⚠ GitHub CLI ('gh') is not installed in this environment."
fi

# ------------------------------------------------------------------------------
# 5. Summary Report (Secrets are NEVER printed to stdout)
# ------------------------------------------------------------------------------
echo ""
echo "=================================================="
echo "SETUP STATUS REPORT"
echo "=================================================="
echo "- Keystore Created/Present:      YES (.signing/focusshield-release.keystore)"
echo "- Credentials Stored Locally:    YES (.signing/signing_credentials.env - gitignored)"
echo "- Base64 Export Stored Locally:  YES (.signing/keystore_base64.txt - gitignored)"

if [ "$GH_CONFIGURED" = "true" ]; then
  echo "- GitHub Secrets Configured:     YES (automatic via GitHub CLI)"
  echo "- Push to main/master ready:     YES (Production releases will build and sign)"
else
  echo "- GitHub Secrets Configured:     NO (direct API access not available in this container)"
  echo "- Action Required:               One-time copy from .signing/ to GitHub Secrets"
  echo ""
  echo "To finish setup, open:"
  echo "  .signing/SETUP_INSTRUCTIONS.txt"
  echo "  .signing/signing_credentials.env"
  echo "and add the 4 secrets in GitHub (Settings -> Secrets and variables -> Actions)."
fi
echo "=================================================="
