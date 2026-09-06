# PeeGeeQ Contributor Environment and CI Guide

**Status:** CURRENT CATEGORY GUIDE

This guide is the maintained entry point for contributor workstations, build prerequisites, local
platform startup, Jenkins CI, and environment-specific access procedures.

## Current detailed sources

- [Development Environment Setup](PEEGEEQ_DEVELOPMENT_ENVIRONMENT_SETUP.md)
- [Jenkins on ESXi](../docs-design/dev/PEEGEEQ_JENKINS_ESXI_CI_SETUP.md)
- [WSL password-free SSH](../docs-design/dev/PEEGEEQ_WSL_PASSWORDLESS_SSH_SETUP.md)
- [Coding principles](../docs-design/dev/pgq-coding-principles.md)
- [Testing Guide](PEEGEEQ_TESTING_GUIDE.md)

Environment-specific instructions remain explicitly labelled. They do not define portable product
runtime behavior.

## Required working sequence

1. Read the coding principles and testing standards before implementation.
2. Read every file to be changed and the relevant tests in full.
3. Check touched files for prohibited patterns before editing.
4. Make one bounded change.
5. Rebuild the affected reactor slice before targeted verification.
6. Run the smallest relevant test scope with its required profile.
7. Inspect and report the test counts and saved output rather than relying on a build banner alone.

## CI environment

The Jenkins-on-ESXi guide records the current Ubuntu VM, Jenkins, JDK, Maven, Node, browser, Docker,
and Testcontainers setup used by this project. It is environment-specific operating documentation,
not a requirement that every PeeGeeQ deployment use Jenkins or ESXi.

The WSL SSH guide is retained as an appendix for administering that VM from Windows.

## Source authority

The repository build files own dependency and plugin versions. The coding principles and testing
standards own contributor rules. Historical migration guides and completed remediation plans are
evidence, not additional active standards.
