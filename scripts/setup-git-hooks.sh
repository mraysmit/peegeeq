#!/bin/bash

# PeeGeeQ Git Hooks Setup Script
# This script sets up recommended Git hooks for the project

set -e

HOOKS_DIR=".git/hooks"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Setting up Git hooks for PeeGeeQ..."

# Create hooks directory if it doesn't exist
mkdir -p "$HOOKS_DIR"

# Pre-commit hook
cat > "$HOOKS_DIR/pre-commit" << 'EOF'
#!/bin/bash

echo "Running pre-commit checks..."

# Check for merge conflict markers
if grep -r "<<<<<<< \|======= \|>>>>>>> " --include="*.java" --include="*.xml" --include="*.md" --include="*.json" --include="*.yml" --include="*.ts" --include="*.tsx" --include="*.js" --include="*.jsx" .; then
    echo "❌ Merge conflict markers found. Please resolve conflicts before committing."
    exit 1
fi

# Check for TODO/FIXME comments in staged files
staged_files=$(git diff --cached --name-only --diff-filter=ACM | grep -E '\.(java|ts|tsx|js|jsx)$' || true)
if [ -n "$staged_files" ]; then
    if echo "$staged_files" | xargs grep -l "TODO\|FIXME" 2>/dev/null; then
        echo "⚠️  Warning: TODO/FIXME comments found in staged files."
        echo "Consider addressing these before committing."
        read -p "Continue anyway? (y/N): " -n 1 -r
        echo
        if [[ ! $REPLY =~ ^[Yy]$ ]]; then
            exit 1
        fi
    fi
fi

# Check for sensitive data patterns
if git diff --cached --name-only | xargs grep -l "password\|secret\|key\|token" --include="*.java" --include="*.properties" --include="*.yml" --include="*.json" 2>/dev/null; then
    echo "ERROR: Potential sensitive data found in staged files."
    echo "Please review and remove any hardcoded secrets."
    exit 1
fi

# Run Java code formatting check (if checkstyle is available)
if command -v mvn >/dev/null 2>&1; then
    echo "Checking Java code style..."
    if ! mvn checkstyle:check -q 2>/dev/null; then
        echo "WARNING: Code style issues found. Run 'mvn checkstyle:check' for details."
    fi
fi

# Run quick tests on changed modules
if command -v mvn >/dev/null 2>&1; then
    echo "Running quick tests..."
    
    # Determine which modules have changes
    changed_modules=""
    if git diff --cached --name-only | grep -q "peegeeq-api/"; then
        changed_modules="$changed_modules peegeeq-api"
    fi
    if git diff --cached --name-only | grep -q "peegeeq-db/"; then
        changed_modules="$changed_modules peegeeq-db"
    fi
    if git diff --cached --name-only | grep -q "peegeeq-rest/"; then
        changed_modules="$changed_modules peegeeq-rest"
    fi
    
    # Run tests for changed modules only
    if [ -n "$changed_modules" ]; then
        for module in $changed_modules; do
            echo "Testing $module..."
            if ! mvn test -pl "$module" -q -Dtest=*UnitTest,*SimpleTest 2>/dev/null; then
                echo "❌ Unit tests failed in $module. Please fix before committing."
                exit 1
            fi
        done
    fi
fi

echo "✅ Pre-commit checks passed!"
EOF

# Pre-push hook
cat > "$HOOKS_DIR/pre-push" << 'EOF'
#!/bin/bash

echo "Running pre-push checks..."

# Get the remote and branch being pushed to
remote="$1"
url="$2"

# Run full test suite before pushing to main branches
current_branch=$(git rev-parse --abbrev-ref HEAD)
if [[ "$current_branch" == "master" || "$current_branch" == "main" || "$current_branch" == "develop" ]]; then
    echo "Pushing to protected branch '$current_branch'. Running full test suite..."
    
    if command -v mvn >/dev/null 2>&1; then
        if ! mvn test -q; then
            echo "❌ Tests failed. Push aborted."
            exit 1
        fi
        
        # Run integration tests if available
        if ! mvn verify -q -Dtest.profile=integration 2>/dev/null; then
            echo "⚠️  Integration tests failed or not available."
        fi
    fi
fi

# Check that we're not pushing directly to master from a feature branch
if [[ "$current_branch" =~ ^(feature|bugfix|hotfix)/ ]]; then
    echo "⚠️  Pushing feature branch '$current_branch' directly."
    echo "Consider creating a Pull Request instead."
    read -p "Continue anyway? (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        exit 1
    fi
fi

echo "✅ Pre-push checks passed!"
EOF

# Commit message hook
cat > "$HOOKS_DIR/commit-msg" << 'EOF'
#!/bin/bash

# Check commit message format (Conventional Commits)
commit_regex='^(feat|fix|docs|style|refactor|test|chore)(\(.+\))?: .{1,50}'

if ! grep -qE "$commit_regex" "$1"; then
    echo "❌ Invalid commit message format!"
    echo ""
    echo "Commit messages should follow the Conventional Commits specification:"
    echo "  <type>[optional scope]: <description>"
    echo ""
    echo "Examples:"
    echo "  feat(api): add consumer group management"
    echo "  fix(db): resolve connection pool leak"
    echo "  docs: update installation guide"
    echo ""
    echo "Types: feat, fix, docs, style, refactor, test, chore"
    exit 1
fi

# Check commit message length
if [ $(head -n1 "$1" | wc -c) -gt 72 ]; then
    echo "❌ Commit message too long (max 72 characters for first line)"
    exit 1
fi
EOF

# Make hooks executable
chmod +x "$HOOKS_DIR/pre-commit"
chmod +x "$HOOKS_DIR/pre-push"
chmod +x "$HOOKS_DIR/commit-msg"

echo "✅ Git hooks installed successfully!"
echo ""
echo "Installed hooks:"
echo "  - pre-commit: Runs code style checks and quick tests"
echo "  - pre-push: Runs full test suite for protected branches"
echo "  - commit-msg: Validates commit message format"
echo ""
echo "To disable hooks temporarily, use: git commit --no-verify"
echo "To remove hooks, delete files in .git/hooks/"
