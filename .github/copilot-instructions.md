# MangaAutoScroller - Copilot Instructions

## Current Version: v1.0.0

---

# 📌 Git Tag & APK Versioning Rules

**Use Semantic Versioning for ALL releases, git tags, and APK builds. Follow this exact pattern:**

**Format:** `MAJOR.MINOR.PATCH`

---

## 1️⃣ PATCH Updates (Bug Fixes, Small UI Tweaks)

**When to use:**
- Fixed crashes or errors
- UI alignment or spacing fixes
- Performance optimizations
- Minor text corrections

**Rules:**
- Increase **PATCH** by 1
- Do NOT change MAJOR or MINOR
- Git tag format: `v1.0.0` → `v1.0.1`
- APK name format: `MangaAutoScroller-v1.0.1.apk`

**Examples:**
```
v1.0.0 → v1.0.1   (Fixed scroll speed bug)
v1.0.1 → v1.0.2   (UI alignment fix)
v1.1.3 → v1.1.4   (Performance improvement)
```

---

## 2️⃣ MINOR Updates (New Features, Enhancements)

**When to use:**
- Added new screens or features
- Enhanced existing functionality
- New user-facing capabilities
- Backward-compatible changes

**Rules:**
- Increase **MINOR** by 1
- Reset PATCH to **0**
- Git tag format: `v1.0.5` → `v1.1.0`
- APK name format: `MangaAutoScroller-v1.1.0.apk`

**Examples:**
```
v1.0.5 → v1.1.0   (Added ML bubble detection)
v1.1.4 → v1.2.0   (Added panel detection)
v1.3.7 → v1.4.0   (Added adaptive scrolling)
```

---

## 3️⃣ MAJOR Updates (Breaking Changes, Redesigns)

**When to use:**
- Complete UI/UX redesign
- Breaking API changes
- Architecture overhaul
- Removed deprecated features

**Rules:**
- Increase **MAJOR** by 1
- Reset MINOR and PATCH to **0**
- Git tag format: `v1.4.2` → `v2.0.0`
- APK name format: `MangaAutoScroller-v2.0.0.apk`

**Examples:**
```
v1.4.2 → v2.0.0   (Complete UI redesign)
v2.3.9 → v3.0.0   (New ML model architecture)
```

---

## 📋 Version Progression Template

**Starting version:**
```
v1.0.0
```

**PATCH increments (fixes/tweaks):**
```
v1.0.1
v1.0.2
v1.0.3
...
```

**MINOR increments (new features):**
```
v1.1.0
v1.1.1   ← patches to v1.1.0
v1.2.0   ← new feature release
...
```

**MAJOR increments (breaking changes):**
```
v2.0.0
v2.0.1   ← patches to v2.0.0
v2.1.0   ← new features in v2
...
```

---

## 🚀 Release Workflow (Agent Instructions)

### Step 1: Determine Version Type

**Ask yourself:**
- Is this a bug fix or small tweak? → **PATCH**
- Is this a new feature (backward-compatible)? → **MINOR**
- Is this a breaking change or major redesign? → **MAJOR**

### Step 2: Update Version Number

**Follow the rules:**
- PATCH: Increment last digit (`v1.0.0` → `v1.0.1`)
- MINOR: Increment middle digit, reset last (`v1.0.5` → `v1.1.0`)
- MAJOR: Increment first digit, reset others (`v1.4.9` → `v2.0.0`)

### Step 3: Update build.gradle.kts

Update both `versionCode` and `versionName`:
- `versionCode` = MAJOR*100 + MINOR*10 + PATCH
- `versionName` = "MAJOR.MINOR.PATCH"

### Step 4: Commit Changes

```bash
git add .
git commit -m "feat: [Brief description]

- [Change 1]
- [Change 2]
- [Change 3]"
```

### Step 5: Create Git Tag

```bash
git tag -a v1.0.1 -m "Version 1.0.1 - [Update Type]

[Category]:
- [Change 1]
- [Change 2]
- [Change 3]"
```

### Step 6: Push to GitHub

```bash
git push origin master2
git push origin v1.0.1
```

GitHub Actions will automatically build and release the APK!

---

## 📐 Versioning Rules Summary

| Rule                       | Description                                         |
| -------------------------- | --------------------------------------------------- |
| ✅ **Always increment**    | Never decrease or reuse version numbers             |
| ✅ **Match git tag & APK** | Tag `v1.0.1` = APK `MangaAutoScroller-v1.0.1.apk`   |
| ✅ **Use `v` prefix**      | All git tags start with lowercase `v`               |
| ✅ **Document changes**    | Include clear commit messages and tag descriptions  |
| ❌ **Never go backward**   | `v1.0.1` can never become `v1.0.0`                  |
| ❌ **No arbitrary jumps**  | Don't jump from `v1.0.1` to `v1.3.0` without reason |

---

## 🎯 Quick Reference

| Change Type     | Version Change      | Example                        |
| --------------- | ------------------- | ------------------------------ |
| Bug fix         | `v1.0.0` → `v1.0.1` | Fixed scroll speed bug         |
| New feature     | `v1.0.1` → `v1.1.0` | Added ML bubble detection      |
| Breaking change | `v1.9.9` → `v2.0.0` | Complete UI redesign           |

---

**Last Updated:** January 1, 2026
**Maintained by:** GitHub Copilot Agent
