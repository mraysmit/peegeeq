# Playwright, Ant Design, and Enterprise UI Testing
*A Practical Engineering Discussion*

## Context
This document captures a technical discussion around **Playwright E2E testing**, **performance**, and **UI behavior** in an **enterprise React application** built using **Ant Design (AntD)**.

The conversation began with Playwright performance concerns and gradually uncovered deeper architectural realities around UI frameworks, test determinism, and frontend/backend responsibility boundaries.

---

## 1. Initial Problem: Playwright Tests Appearing to Pause

### Observation
Tests appeared to “pause” before messages referencing:
- `trace.zip`
- screenshots
- videos

### Explanation
This pause was **not Playwright execution** but **artifact finalization**, including:
- Trace compression
- Screenshot/video writing
- CI artifact uploads

On slower filesystems (Docker volumes, CI runners), this can dominate runtime.

### Key Insight
Most “Playwright performance problems” are **self-inflicted** via:
- Always-on tracing
- Video recording on green runs
- Heavy artifact uploads

### Correct Baseline Configuration
```ts
use: {
  trace: 'on-first-retry',
  screenshot: 'only-on-failure',
  video: 'on-first-retry',
}
```

---

## 2. Tests Running Twice

### Root Causes Identified
Tests commonly “run twice” due to configuration, not bugs:
1. Multiple Playwright projects
2. Retries enabled
3. `repeatEach` set
4. CI invoking tests more than once
5. Duplicate test discovery
6. CLI flags like `--repeat-each`

### Lesson
Always inspect:
- `projects`
- `retries`
- `repeatEach`
- `testDir` / `testMatch`

---

## 3. Dropdown Failures After Removing `slowMo`

### What Changed
- `slowMo: 1000` removed
- Artificial delays removed

### Result
Ant Design dropdowns started failing due to **strict mode violations**.

### Why
AntD dropdowns animate on close and remain in the DOM briefly, causing multiple matches.

### Conclusion
**SlowMo masked real UI behavior.**

---

## 4. Correct Fix: Selector Discipline

### Bad Pattern
```css
.ant-select-dropdown:visible
```

### Correct Strategy
Filter out leaving dropdowns or scope via `aria-controls`.

---

## 5. Ant Design Warnings in Tests

### Warning
```
[antd: Tabs] `Tabs.TabPane` is deprecated. Please use `items` instead.
```

### Fix
Migrate to `items` API or filter known warnings in tests.

---

## 6. What Is Ant Design

Ant Design is a React UI framework and enterprise design system optimized for data-heavy internal applications.

---

## 7. Testing Reality with Ant Design

Challenges include:
- Portals
- Animations
- Delayed unmounting
- Heavy DOM

### Survival Rules
1. Avoid `:visible`
2. Scope selectors
3. Disable motion in E2E
4. Centralize helpers
5. Use `data-testid`

---

## 8. Giant Tables and Virtualization

Without virtualization:
- All rows render
- Performance degrades
- Tests flake

Ant tables do not virtualize by default.

---

## 9. Best Architecture with Server-Side Filtering

### Recommended
- Server-side filtering
- Server-side pagination (50–200 rows)

### Alternatives
- Cursor pagination for event streams
- Virtualization for massive grids

---

## 10. Final Takeaways

- Ant is reasonable for enterprise
- Requires discipline
- E2E flakiness often reveals design debt

> Treat Ant as a platform dependency. Hide it behind helpers.
