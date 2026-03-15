#!/usr/bin/env pwsh
# Convert Thread.sleep, CountDownLatch to Vert.x 5.x test facilities
# Scope: peegeeq-db/src/test ONLY

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$testRoot = "peegeeq-db\src\test\java"
$totalChanges = 0
$filesChanged = @{}

function Get-VertxAccessor($content) {
    if ($content -match 'extends BaseIntegrationTest') { return 'manager.getVertx()' }
    if ($content -match 'private\s+Vertx\s+vertx\s*[;=]') { return 'vertx' }
    if ($content -match 'Vertx\s+vertx\s*=\s*Vertx\.vertx\(\)') { return 'vertx' }
    if ($content -match 'manager\.getVertx\(\)') { return 'manager.getVertx()' }
    return $null
}

function Count-Changes($original, $content) {
    $origLines = $original -split "`n"
    $newLines = $content -split "`n"
    $diff = 0
    $maxLen = [Math]::Max($origLines.Count, $newLines.Count)
    for ($i = 0; $i -lt $maxLen; $i++) {
        $a = if ($i -lt $origLines.Count) { $origLines[$i] } else { '' }
        $b = if ($i -lt $newLines.Count) { $newLines[$i] } else { '' }
        if ($a -ne $b) { $diff++ }
    }
    return $diff
}

# ============================================================================
# PHASE 1: Thread.sleep(N) → vertx.timer(N)...join()
# ============================================================================
Write-Host "`n=== PHASE 1: Thread.sleep → vertx.timer ==="

$sleepFiles = Get-ChildItem -Recurse -Include "*.java" -Path $testRoot | 
    Select-String "Thread\.sleep" | Group-Object Path | ForEach-Object { $_.Name }

foreach ($file in $sleepFiles) {
    $content = Get-Content $file -Raw
    $original = $content
    $shortName = Split-Path $file -Leaf
    
    $va = Get-VertxAccessor $content
    if ($null -eq $va) {
        Write-Host "  SKIP (no vertx accessor): $shortName"
        continue
    }
    
    # Replace Thread.sleep(number_literal) 
    $content = [regex]::Replace($content, 'Thread\.sleep\((\d+)\)', "${va}.timer(`$1).toCompletionStage().toCompletableFuture().join()")
    
    # Replace Thread.sleep(variable_name)
    $content = [regex]::Replace($content, 'Thread\.sleep\(([a-zA-Z_]\w*)\)', "${va}.timer(`$1).toCompletionStage().toCompletableFuture().join()")
    
    # Clean up: remove now-unnecessary catch(InterruptedException) blocks around the replaced sleep
    # Pattern: try { <timer_call>; } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    # This is a common wrapper that's no longer needed since timer().join() doesn't throw InterruptedException
    $pattern = '(?s)try\s*\{\s*\r?\n(\s*)(' + [regex]::Escape($va) + '\.timer\(\d+\)\.toCompletionStage\(\)\.toCompletableFuture\(\)\.join\(\);[^\r\n]*\r?\n)\s*\}\s*catch\s*\(InterruptedException\s+\w+\)\s*\{\s*\r?\n\s*Thread\.currentThread\(\)\.interrupt\(\);\s*\r?\n\s*\}'
    $content = [regex]::Replace($content, $pattern, '$1$2')
    
    # Also handle: try { timer; } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
    $pattern2 = '(?s)try\s*\{\s*\r?\n(\s*)(' + [regex]::Escape($va) + '\.timer\(\d+\)\.toCompletionStage\(\)\.toCompletableFuture\(\)\.join\(\);[^\r\n]*\r?\n)\s*\}\s*catch\s*\(InterruptedException\s+\w+\)\s*\{\s*\r?\n\s*Thread\.currentThread\(\)\.interrupt\(\);\s*\r?\n\s*break;\s*\r?\n\s*\}'
    $content = [regex]::Replace($content, $pattern2, '$1$2')
    
    if ($content -ne $original) {
        Set-Content $file -Value $content -NoNewline
        $changes = Count-Changes $original $content
        $script:totalChanges += $changes
        $filesChanged[$shortName] = $true
        Write-Host "  $shortName - $changes line changes (vertx=$va)"
    }
}

# ============================================================================
# PHASE 2: CountDownLatch(1) named 'latch' → VertxTestContext
# ============================================================================
Write-Host "`n=== PHASE 2: CountDownLatch(1) → VertxTestContext ==="

$cdlFiles = Get-ChildItem -Recurse -Include "*.java" -Path $testRoot |
    Select-String "new CountDownLatch\(1\)" | Group-Object Path | ForEach-Object { $_.Name }

foreach ($file in $cdlFiles) {
    $content = Get-Content $file -Raw
    $original = $content
    $shortName = Split-Path $file -Leaf
    
    # --- Add imports ---
    if ($content -notmatch 'import io\.vertx\.junit5\.VertxTestContext;') {
        # Find a good insertion point - after last existing import
        $content = [regex]::Replace($content, 
            '(import static org\.junit\.jupiter\.api\.Assertions\.\*;)',
            "import io.vertx.junit5.VertxTestContext;`n`n`$1")
    }
    
    # --- Remove CountDownLatch import ---
    $content = $content -replace "import java\.util\.concurrent\.CountDownLatch;\r?\n", ''
    
    # --- Remove "CountDownLatch latch = new CountDownLatch(1);" lines ---
    $content = [regex]::Replace($content, '(?m)^\s*CountDownLatch latch = new CountDownLatch\(1\);\s*\r?\n', '')
    
    # --- Add testContext parameter to test methods ---
    # Find @Test methods that don't already have VertxTestContext parameter
    # Pattern: void testMethodName(...) throws ... {
    # We need to add VertxTestContext testContext parameter
    $content = [regex]::Replace($content, 
        '(?m)(void\s+\w+)\(\)\s*(throws\s+\w+(?:\s*,\s*\w+)*)?\s*\{',
        { 
            param($m)
            $methodSig = $m.Groups[1].Value
            $throws = $m.Groups[2].Value
            if ($throws) {
                "$methodSig(VertxTestContext testContext) $throws {"
            } else {
                "$methodSig(VertxTestContext testContext) {"
            }
        })
    
    # --- Replace latch.countDown() → testContext.completeNow() ---
    $content = $content -replace 'latch\.countDown\(\)', 'testContext.completeNow()'
    
    # --- Replace .onFailure patterns that used latch ---
    # Pattern: .onFailure(error -> { logger.error("Test failed", error); testContext.completeNow(); fail("..."); })
    # → .onFailure(testContext::failNow)
    $content = [regex]::Replace($content,
        '(?s)\.onFailure\(error -> \{\s*\r?\n\s*logger\.error\("Test failed", error\);\s*\r?\n\s*testContext\.completeNow\(\);\s*\r?\n\s*fail\("Test failed: " \+ error\.getMessage\(\)\);\s*\r?\n\s*\}\)',
        '.onFailure(testContext::failNow)')
    
    # Also handle reverse order: fail then completeNow
    $content = [regex]::Replace($content,
        '(?s)\.onFailure\(error -> \{\s*\r?\n\s*logger\.error\("Test failed", error\);\s*\r?\n\s*fail\("Test failed: " \+ error\.getMessage\(\)\);\s*\r?\n\s*testContext\.completeNow\(\);\s*\r?\n\s*\}\)',
        '.onFailure(testContext::failNow)')
    
    # --- Replace assertTrue(latch.await(N, TimeUnit.SECONDS), "msg") ---
    $content = [regex]::Replace($content,
        'assertTrue\(latch\.await\((\d+), TimeUnit\.SECONDS\),\s*"[^"]*"\)',
        'assertTrue(testContext.awaitCompletion($1, TimeUnit.SECONDS))')
    
    # Also handle without message: assertTrue(latch.await(N, TimeUnit.SECONDS))
    $content = [regex]::Replace($content,
        'assertTrue\(latch\.await\((\d+), TimeUnit\.SECONDS\)\)',
        'assertTrue(testContext.awaitCompletion($1, TimeUnit.SECONDS))')
    
    if ($content -ne $original) {
        Set-Content $file -Value $content -NoNewline
        $changes = Count-Changes $original $content
        $script:totalChanges += $changes
        $filesChanged[$shortName] = $true
        Write-Host "  $shortName - $changes line changes"
    }
}

# ============================================================================
# PHASE 3: CountDownLatch(N>1) and variable-N → Checkpoint
# ============================================================================
Write-Host "`n=== PHASE 3: CountDownLatch(N>1/variable) → Checkpoint ==="

$cdlNFiles = Get-ChildItem -Recurse -Include "*.java" -Path $testRoot |
    Select-String "CountDownLatch" | Group-Object Path | ForEach-Object { $_.Name }

foreach ($file in $cdlNFiles) {
    $content = Get-Content $file -Raw
    $original = $content
    $shortName = Split-Path $file -Leaf

    # --- Add imports if not present ---
    if ($content -notmatch 'import io\.vertx\.junit5\.VertxTestContext;') {
        $content = [regex]::Replace($content, 
            '(import static org\.junit\.jupiter\.api\.Assertions\.\*;)',
            "import io.vertx.junit5.VertxTestContext;`n`n`$1")
    }
    if ($content -notmatch 'import io\.vertx\.junit5\.Checkpoint;') {
        $content = [regex]::Replace($content,
            '(import io\.vertx\.junit5\.VertxTestContext;)',
            "import io.vertx.junit5.Checkpoint;`n`$1")
    }
    
    # --- Remove CountDownLatch import ---
    $content = $content -replace "import java\.util\.concurrent\.CountDownLatch;\r?\n", ''
    
    # --- Replace CountDownLatch <name> = new CountDownLatch(N) → Checkpoint <name> = testContext.checkpoint(N) ---
    $content = [regex]::Replace($content,
        'CountDownLatch\s+(\w+)\s*=\s*new\s+CountDownLatch\(([^)]+)\)',
        'Checkpoint $1 = testContext.checkpoint($2)')
    
    # --- Replace <name>.countDown() → <name>.flag() ---
    # Find all checkpoint variable names first
    $checkpointNames = [regex]::Matches($content, 'Checkpoint\s+(\w+)\s*=') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
    foreach ($cpName in $checkpointNames) {
        $content = $content -replace "$cpName\.countDown\(\)", "$cpName.flag()"
        $content = $content -replace "$cpName\.await\((\d+),\s*TimeUnit\.SECONDS\)", 'testContext.awaitCompletion($1, TimeUnit.SECONDS)'
    }
    
    # Also handle remaining latch.countDown() → latch.flag() for any name
    $content = [regex]::Replace($content, '(\w+)\.countDown\(\)', '$1.flag()')
    
    if ($content -ne $original) {
        Set-Content $file -Value $content -NoNewline
        $changes = Count-Changes $original $content
        $script:totalChanges += $changes
        $filesChanged[$shortName] = $true
        Write-Host "  $shortName - $changes line changes"
    }
}

# ============================================================================
# PHASE 4: Remove import java.util.concurrent.TimeUnit if only used for sleep
# ============================================================================
Write-Host "`n=== PHASE 4: Cleanup unused TimeUnit imports ==="

$tuFiles = Get-ChildItem -Recurse -Include "*.java" -Path $testRoot |
    Select-String "import java\.util\.concurrent\.TimeUnit;" | ForEach-Object { $_.Path } | Sort-Object -Unique

foreach ($file in $tuFiles) {
    $content = Get-Content $file -Raw
    $original = $content
    $shortName = Split-Path $file -Leaf
    
    # Count TimeUnit usages excluding the import line
    $usageCount = ([regex]::Matches($content, '(?<!import java\.util\.concurrent\.)TimeUnit\.')).Count
    
    if ($usageCount -eq 0) {
        # TimeUnit is not used anywhere else, remove import
        $content = $content -replace "import java\.util\.concurrent\.TimeUnit;\r?\n", ''
        if ($content -ne $original) {
            Set-Content $file -Value $content -NoNewline
            $changes = 1
            $script:totalChanges += $changes
            $filesChanged[$shortName] = $true
            Write-Host "  $shortName - removed unused TimeUnit import"
        }
    }
}

# ============================================================================
# Summary
# ============================================================================
Write-Host "`n=== SUMMARY ==="
Write-Host "Files changed: $($filesChanged.Count)"
Write-Host "Total line changes: $totalChanges"
Write-Host ""
Write-Host "Changed files:"
$filesChanged.Keys | Sort-Object | ForEach-Object { Write-Host "  $_" }

# Verify remaining hits
Write-Host "`n=== REMAINING PATTERNS ==="
$remaining = Get-ChildItem -Recurse -Include "*.java" -Path $testRoot | 
    Select-String "Thread\.sleep|TimeUnit\.\w+\.sleep|CountDownLatch" |
    ForEach-Object { "$(Split-Path $_.Path -Leaf):$($_.LineNumber): $($_.Line.Trim())" }
if ($remaining) {
    Write-Host "Still $($remaining.Count) hits:"
    $remaining | ForEach-Object { Write-Host "  $_" }
} else {
    Write-Host "ZERO HITS - all patterns converted!"
}
