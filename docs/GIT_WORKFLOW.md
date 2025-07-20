# PeeGeeQ Git Workflow Guide

This document outlines the recommended Git workflow for the PeeGeeQ project.

## Branch Strategy

### Main Branches
- **`master`** - Production-ready code, always stable
- **`develop`** - Integration branch for features, may be unstable

### Feature Branches
- **`feature/feature-name`** - New features or enhancements
- **`bugfix/issue-description`** - Bug fixes
- **`hotfix/critical-fix`** - Critical production fixes
- **`docs/documentation-update`** - Documentation changes

## Workflow Process

### 1. Starting New Work
```bash
# Update master branch
git checkout master
git pull origin master

# Create feature branch
git checkout -b feature/new-queue-management
```

### 2. Development Process
```bash
# Make changes and commit frequently
git add .
git commit -m "feat: add queue management interface"

# Push feature branch
git push -u origin feature/new-queue-management
```

### 3. Code Review Process
1. Create Pull Request from feature branch to `master`
2. Ensure all tests pass
3. Request code review from team members
4. Address review feedback
5. Merge after approval

### 4. Merging Strategy
- Use **squash and merge** for feature branches to keep history clean
- Use **merge commit** for hotfixes to preserve context
- Delete feature branches after merging

## Commit Message Convention

Follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

### Types
- **feat**: New feature
- **fix**: Bug fix
- **docs**: Documentation changes
- **style**: Code style changes (formatting, etc.)
- **refactor**: Code refactoring
- **test**: Adding or updating tests
- **chore**: Maintenance tasks

### Examples
```bash
git commit -m "feat(api): add consumer group management endpoints"
git commit -m "fix(db): resolve connection pool leak in PgConnectionProvider"
git commit -m "docs: update installation guide with Docker requirements"
git commit -m "test(rest): add integration tests for WebSocket handlers"
```

## Release Process

### Version Tagging
```bash
# Create release tag
git tag -a v1.2.0 -m "Release version 1.2.0"
git push origin v1.2.0
```

### Hotfix Process
```bash
# Create hotfix from master
git checkout master
git checkout -b hotfix/critical-security-fix

# Make fix and test
git commit -m "fix: resolve SQL injection vulnerability"

# Merge to master and tag
git checkout master
git merge hotfix/critical-security-fix
git tag -a v1.2.1 -m "Hotfix version 1.2.1"
git push origin master --tags
```

## Best Practices

### Before Committing
- [ ] Run tests: `mvn test`
- [ ] Check code formatting
- [ ] Update documentation if needed
- [ ] Verify no sensitive data in commit

### Pull Request Checklist
- [ ] Clear, descriptive title
- [ ] Detailed description of changes
- [ ] All tests passing
- [ ] Documentation updated
- [ ] No merge conflicts
- [ ] Reviewers assigned

### Code Review Guidelines
- Focus on logic, security, and maintainability
- Check for proper error handling
- Verify test coverage
- Ensure documentation is updated
- Be constructive and respectful

## Git Hooks (Recommended)

### Pre-commit Hook
Create `.git/hooks/pre-commit`:
```bash
#!/bin/sh
# Run tests before commit
mvn test -q
if [ $? -ne 0 ]; then
    echo "Tests failed. Commit aborted."
    exit 1
fi
```

### Pre-push Hook
Create `.git/hooks/pre-push`:
```bash
#!/bin/sh
# Run full test suite before push
mvn verify -q
if [ $? -ne 0 ]; then
    echo "Build failed. Push aborted."
    exit 1
fi
```

## Troubleshooting

### Common Issues
```bash
# Undo last commit (keep changes)
git reset --soft HEAD~1

# Undo last commit (discard changes)
git reset --hard HEAD~1

# Fix commit message
git commit --amend -m "corrected commit message"

# Resolve merge conflicts
git status
# Edit conflicted files
git add .
git commit
```

### Emergency Recovery
```bash
# Find lost commits
git reflog

# Recover lost work
git checkout <commit-hash>
git checkout -b recovery-branch
```
